package io.github.verybigsad.pimobile.update

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fetches the static metadata document, hard-bounded at [UpdateConfig.METADATA_MAX_BYTES]. */
class MetadataFetcher(
    private val client: OkHttpClient = OkHttpClient(),
    private val metadataUrl: String = UpdateConfig.METADATA_URL,
) {
    suspend fun fetch(): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(metadataUrl).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateException(UpdateError.METADATA_FETCH_FAILED, "HTTP ${response.code}")
            }
            val body = response.body
            val declared = body.contentLength()
            if (declared > UpdateConfig.METADATA_MAX_BYTES) {
                throw UpdateException(UpdateError.METADATA_TOO_LARGE, "content-length $declared")
            }
            val source = body.byteStream()
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            try {
                while (true) {
                    val read = source.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > UpdateConfig.METADATA_MAX_BYTES) {
                        throw UpdateException(UpdateError.METADATA_TOO_LARGE, "stream exceeded bound")
                    }
                    buffer.write(chunk, 0, read)
                }
            } catch (error: IOException) {
                throw UpdateException(UpdateError.METADATA_FETCH_FAILED, "read failed", error)
            }
            buffer.toByteArray()
        }
    }
}
