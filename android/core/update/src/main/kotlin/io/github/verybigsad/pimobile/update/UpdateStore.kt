package io.github.verybigsad.pimobile.update

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PersistedUpdateSnapshot(
    @SerialName("highWaterMark") val highWaterMark: Long = 0L,
    @SerialName("candidate") val candidate: PersistedCandidate? = null,
    @SerialName("authorizedVersionCode") val authorizedVersionCode: Long? = null,
    @SerialName("sessionId") val sessionId: Int? = null,
    @SerialName("lastCheckAtMillis") val lastCheckAtMillis: Long = 0L,
)

@Serializable
data class PersistedCandidate(
    @SerialName("versionCode") val versionCode: Long,
    @SerialName("versionName") val versionName: String,
    @SerialName("publishedAt") val publishedAt: String,
    @SerialName("releasePageUrl") val releasePageUrl: String,
    @SerialName("apkUrl") val apkUrl: String,
    @SerialName("apkSizeBytes") val apkSizeBytes: Long,
    @SerialName("apkSha256") val apkSha256: String,
    @SerialName("etag") val etag: String? = null,
    @SerialName("downloadedBytes") val downloadedBytes: Long = 0L,
    @SerialName("verified") val verified: Boolean = false,
    @SerialName("createdAtMillis") val createdAtMillis: Long = 0L,
)

fun UpdateCandidate.toPersisted(createdAtMillis: Long): PersistedCandidate = PersistedCandidate(
    versionCode = versionCode,
    versionName = versionName,
    publishedAt = publishedAt,
    releasePageUrl = releasePageUrl,
    apkUrl = apkUrl,
    apkSizeBytes = apkSizeBytes,
    apkSha256 = apkSha256,
    etag = etag,
    downloadedBytes = downloadedBytes,
    verified = verified,
    createdAtMillis = createdAtMillis,
)

fun PersistedCandidate.toDomain(): UpdateCandidate = UpdateCandidate(
    versionCode = versionCode,
    versionName = versionName,
    publishedAt = publishedAt,
    releasePageUrl = releasePageUrl,
    apkUrl = apkUrl,
    apkSizeBytes = apkSizeBytes,
    apkSha256 = apkSha256,
    etag = etag,
    downloadedBytes = downloadedBytes,
    verified = verified,
)

/** Best-effort directory fsync so a rename survives a crash. */
internal fun syncDir(dir: File?) {
    if (dir == null) return
    runCatching {
        java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ)
            .use { it.force(true) }
    }
}

/**
 * Atomic JSON store rooted at noBackupFilesDir/updates. Writes go through tmp+rename under an
 * exclusive file lock. Corruption fails closed: the broken file is quarantined and the store resets.
 */
class UpdateStore(private val rootDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stateFile = File(rootDir, "state.json")
    private val lockFile = File(rootDir, "state.lock")

    init {
        rootDir.mkdirs()
    }

    fun candidateFile(versionCode: Long): File = File(rootDir, "candidate-$versionCode.apk")

    fun partialFile(versionCode: Long): File = File(rootDir, "candidate-$versionCode.apk.part")

    @Synchronized
    fun read(): PersistedUpdateSnapshot = withLock { readUnlocked() }

    private fun readUnlocked(): PersistedUpdateSnapshot {
        if (!stateFile.isFile) return PersistedUpdateSnapshot()
        val raw = try {
            stateFile.readBytes()
        } catch (_: Exception) {
            quarantine()
            return PersistedUpdateSnapshot()
        }
        return try {
            json.decodeFromString<PersistedUpdateSnapshot>(raw.decodeToString())
        } catch (_: Exception) {
            quarantine()
            PersistedUpdateSnapshot()
        }
    }

    @Synchronized
    fun write(snapshot: PersistedUpdateSnapshot) = withLock { writeUnlocked(snapshot) }

    private fun writeUnlocked(snapshot: PersistedUpdateSnapshot) {
        val tmp = File(rootDir, "state.json.tmp")
        tmp.writeBytes(json.encodeToString(snapshot).encodeToByteArray())
        RandomAccessFile(tmp, "rw").use { it.fd.sync() }
        if (!tmp.renameTo(stateFile)) {
            tmp.delete()
            throw UpdateException(UpdateError.STAGING_FAILED, "state commit rename failed")
        }
        syncDir(rootDir)
    }

    @Synchronized
    fun mutate(block: (PersistedUpdateSnapshot) -> PersistedUpdateSnapshot): PersistedUpdateSnapshot = withLock {
        val next = block(readUnlocked())
        writeUnlocked(next)
        next
    }

    private fun quarantine() {
        val quarantined = File(rootDir, "state.json.corrupt-${System.currentTimeMillis()}")
        stateFile.renameTo(quarantined)
    }

    private fun <T> withLock(block: () -> T): T {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.lock().use { return block() }
        }
    }
}
