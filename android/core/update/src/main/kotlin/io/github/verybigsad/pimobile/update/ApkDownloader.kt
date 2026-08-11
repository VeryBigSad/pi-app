package io.github.verybigsad.pimobile.update

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class DownloadOutcome(
    val file: File,
    val bytesWritten: Long,
    val sha256Hex: String,
    val etag: String?,
)

/**
 * Resumable APK download. Resume protocol: send Range bytes=N- with If-Range carrying the stored
 * etag (or Last-Modified). 206 appends, 200 restarts from zero, 416 means the partial file is
 * already complete (hash decides). Final size and SHA-256 must match the metadata exactly.
 */
class ApkDownloader(
    private val client: OkHttpClient = OkHttpClient(),
    private val freeSpaceProvider: (File) -> Long = { it.usableSpace },
) {
    suspend fun download(
        candidate: UpdateCandidate,
        targetFile: File,
        partialFile: File,
        onProgress: (Long) -> Unit = {},
        onCall: (okhttp3.Call) -> Unit = {},
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val resumeFrom = if (partialFile.isFile) partialFile.length() else 0L
        val free = freeSpaceProvider(targetFile.parentFile ?: targetFile)
        // Existing partial bytes are already on disk; credit them against the preflight.
        val effectiveFree = if (free > Long.MAX_VALUE - resumeFrom) Long.MAX_VALUE else free + resumeFrom
        if (effectiveFree < candidate.apkSizeBytes * UpdateConfig.DOWNLOAD_PREFLIGHT_FACTOR) {
            throw UpdateException(
                UpdateError.DOWNLOAD_INSUFFICIENT_SPACE,
                "need ${candidate.apkSizeBytes * UpdateConfig.DOWNLOAD_PREFLIGHT_FACTOR}, have $effectiveFree",
            )
        }
        val requestBuilder = Request.Builder().url(candidate.apkUrl)
        if (resumeFrom > 0L) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
            candidate.etag?.let { requestBuilder.header("If-Range", it) }
        }
        val call = client.newCall(requestBuilder.build())
        onCall(call)
        val response = try {
            call.execute()
        } catch (error: Exception) {
            throw UpdateException(UpdateError.DOWNLOAD_FAILED, "connect failed", error)
        }
        response.use { resp ->
            val etag = resp.header("ETag") ?: resp.header("Last-Modified") ?: candidate.etag
            val append = when {
                resp.code == 206 && resumeFrom > 0L -> {
                    validateContentRange(resp.header("Content-Range"), resumeFrom, candidate, partialFile)
                    true
                }
                resp.code == 416 -> {
                    // Server says range unsatisfiable: accept only if partial already matches size.
                    if (resumeFrom != candidate.apkSizeBytes) {
                        partialFile.delete()
                        throw UpdateException(UpdateError.DOWNLOAD_SIZE_MISMATCH, "416 at $resumeFrom bytes")
                    }
                    return@withContext finalizeDownload(partialFile, targetFile, candidate, resumeFrom, etag)
                }
                resp.isSuccessful -> false
                else -> throw UpdateException(UpdateError.DOWNLOAD_FAILED, "HTTP ${resp.code}")
            }
            val body = resp.body
            if (!append && partialFile.isFile) partialFile.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = if (append) resumeFrom else 0L
            // Re-hash existing prefix when appending so the final digest covers the whole file.
            if (append) hashPrefix(partialFile, digest)
            RandomAccessFile(partialFile, "rw").use { output ->
                output.seek(if (append) resumeFrom else 0L)
                val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = body.byteStream().read(chunk)
                    if (read < 0) break
                    output.write(chunk, 0, read)
                    digest.update(chunk, 0, read)
                    written += read
                    if (written > candidate.apkSizeBytes) {
                        throw UpdateException(UpdateError.DOWNLOAD_SIZE_MISMATCH, "stream exceeds sizeBytes")
                    }
                    onProgress(written)
                }
                output.fd.sync()
            }
            finalizeDownload(partialFile, targetFile, candidate, written, etag, digest.digest())
        }
    }

    private fun validateContentRange(
        header: String?,
        resumeFrom: Long,
        candidate: UpdateCandidate,
        partialFile: File,
    ) {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.orEmpty().trim())
        val start = match?.groupValues?.get(1)?.toLongOrNull()
        val total = match?.groupValues?.get(3)?.toLongOrNull()
        if (start == null || start != resumeFrom || (total != null && total != candidate.apkSizeBytes)) {
            partialFile.delete()
            throw UpdateException(UpdateError.DOWNLOAD_FAILED, "bad Content-Range: $header")
        }
    }

    private fun hashPrefix(file: File, digest: MessageDigest) {
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                digest.update(chunk, 0, read)
            }
        }
    }

    private fun finalizeDownload(
        partialFile: File,
        targetFile: File,
        candidate: UpdateCandidate,
        written: Long,
        etag: String?,
        digestBytes: ByteArray? = null,
    ): DownloadOutcome {
        if (written != candidate.apkSizeBytes) {
            throw UpdateException(UpdateError.DOWNLOAD_SIZE_MISMATCH, "$written != ${candidate.apkSizeBytes}")
        }
        val digest = digestBytes ?: MessageDigest.getInstance("SHA-256").also { hashPrefix(partialFile, it) }.digest()
        val hex = digest.joinToString("") { "%02x".format(it) }
        if (!hex.equals(candidate.apkSha256, ignoreCase = true)) {
            partialFile.delete()
            throw UpdateException(UpdateError.DOWNLOAD_HASH_MISMATCH, "sha256 $hex")
        }
        if (targetFile.isFile) targetFile.delete()
        if (!partialFile.renameTo(targetFile)) {
            throw UpdateException(UpdateError.DOWNLOAD_FAILED, "commit rename failed")
        }
        syncDir(targetFile.parentFile)
        return DownloadOutcome(targetFile, written, hex, etag)
    }

    companion object {
        private val CONTENT_RANGE_REGEX = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    }
}
