package io.github.verybigsad.pimobile.push

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Base64

internal enum class EndpointUploadState {
    PENDING,
    UPLOADED,
}

internal data class PushRegistrationSnapshot(
    val endpoint: UnifiedPushEndpoint?,
    val uploadState: EndpointUploadState,
    val pendingRemoval: String?,
)

internal interface PushRegistrationPersistence {
    fun read(): ByteArray?

    fun write(bytes: ByteArray)

    fun clear()
}

private class AtomicFilePushRegistrationPersistence(context: Context) : PushRegistrationPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    override fun read(): ByteArray? {
        if (!file.baseFile.exists()) {
            return null
        }
        return runCatching { file.readFully() }.getOrNull()
    }

    override fun write(bytes: ByteArray) {
        val output = try {
            file.startWrite()
        } catch (e: IOException) {
            throw IllegalStateException("push registration store unavailable", e)
        }
        try {
            output.write(bytes)
            output.flush()
            file.finishWrite(output)
        } catch (e: IOException) {
            file.failWrite(output)
            throw IllegalStateException("push registration store write failed", e)
        }
    }

    override fun clear() {
        file.delete()
    }

    companion object {
        const val FILE_NAME = "pi_push_registration_v1"
    }
}

internal class PushRegistrationStore private constructor(
    private val persistence: PushRegistrationPersistence,
) {
    constructor(context: Context) : this(AtomicFilePushRegistrationPersistence(context.applicationContext))

    fun load(): PushRegistrationSnapshot? = synchronized(lock) {
        decode(persistence.read())
    }

    fun saveEndpoint(endpoint: UnifiedPushEndpoint): PushRegistrationSnapshot = synchronized(lock) {
        val updated = (decode(persistence.read()) ?: EMPTY).copy(
            endpoint = endpoint,
            uploadState = EndpointUploadState.PENDING,
            pendingRemoval = null,
        )
        persistence.write(encode(updated))
        updated
    }

    fun markUploaded(): PushRegistrationSnapshot? = synchronized(lock) {
        val current = decode(persistence.read()) ?: return null
        if (current.endpoint == null) {
            return current
        }
        val updated = current.copy(uploadState = EndpointUploadState.UPLOADED)
        persistence.write(encode(updated))
        updated
    }

    fun saveRemoval(instance: String): PushRegistrationSnapshot = synchronized(lock) {
        val updated = (decode(persistence.read()) ?: EMPTY).copy(
            endpoint = null,
            uploadState = EndpointUploadState.UPLOADED,
            pendingRemoval = instance,
        )
        persistence.write(encode(updated))
        updated
    }

    fun clearPendingRemoval(): PushRegistrationSnapshot? = synchronized(lock) {
        val current = decode(persistence.read()) ?: return null
        val updated = current.copy(pendingRemoval = null)
        persistence.write(encode(updated))
        updated
    }

    fun clearEndpoint(): PushRegistrationSnapshot? = synchronized(lock) {
        val current = decode(persistence.read()) ?: return null
        val updated = current.copy(endpoint = null, uploadState = EndpointUploadState.UPLOADED)
        persistence.write(encode(updated))
        updated
    }

    fun clear() = synchronized(lock) {
        persistence.clear()
    }

    private fun encode(snapshot: PushRegistrationSnapshot): ByteArray {
        val builder = StringBuilder()
        builder.append(KEY_VERSION).append('=').append(FORMAT_VERSION).append('\n')
        snapshot.endpoint?.let { endpoint ->
            builder.appendField(KEY_URL, endpoint.url)
            builder.appendField(KEY_INSTANCE, endpoint.instance)
            builder.append(KEY_TEMPORARY).append('=').append(if (endpoint.temporary) "1" else "0").append('\n')
            builder.appendNullableField(KEY_PUBLIC_KEY, endpoint.publicKey)
            builder.appendNullableField(KEY_AUTH_SECRET, endpoint.authSecret)
        }
        builder.append(KEY_UPLOAD_STATE).append('=').append(snapshot.uploadState.name).append('\n')
        builder.appendNullableField(KEY_PENDING_REMOVAL, snapshot.pendingRemoval)
        return builder.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun StringBuilder.appendField(key: String, value: String): StringBuilder =
        append(key).append('=').append(encodeField(value)).append('\n')

    private fun StringBuilder.appendNullableField(key: String, value: String?): StringBuilder =
        append(key).append('=').append(value?.let(::encodeField) ?: ABSENT_FIELD).append('\n')

    private fun encodeField(value: String): String =
        encoder.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(bytes: ByteArray?): PushRegistrationSnapshot? {
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_SERIALIZED_BYTES) {
            return null
        }
        val fields = LinkedHashMap<String, String>()
        for (line in String(bytes, Charsets.US_ASCII).split('\n')) {
            if (line.isEmpty()) {
                continue
            }
            val separator = line.indexOf('=')
            if (separator <= 0) {
                return null
            }
            fields[line.substring(0, separator)] = line.substring(separator + 1)
        }
        if (fields[KEY_VERSION] != FORMAT_VERSION) {
            return null
        }
        val uploadState = EndpointUploadState.entries.firstOrNull { it.name == fields[KEY_UPLOAD_STATE] }
            ?: return null
        val pendingRemoval = decodeNullableField(fields[KEY_PENDING_REMOVAL], MAX_INSTANCE_CHARS) ?: return null
        val endpoint = decodeEndpoint(fields) ?: return null
        return PushRegistrationSnapshot(
            endpoint = endpoint.endpoint,
            uploadState = uploadState,
            pendingRemoval = pendingRemoval.takeIf { it.isNotEmpty() },
        )
    }

    private fun decodeEndpoint(fields: Map<String, String>): DecodeEndpointResult? {
        val present = listOf(KEY_URL, KEY_INSTANCE, KEY_TEMPORARY).any { fields.containsKey(it) }
        if (!present) {
            return DecodeEndpointResult(null)
        }
        val url = decodeNullableField(fields[KEY_URL], PushEndpointParser.MAX_ENDPOINT_CHARS) ?: return null
        val instance = decodeNullableField(fields[KEY_INSTANCE], MAX_INSTANCE_CHARS) ?: return null
        val temporary = when (fields[KEY_TEMPORARY]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        if (url.isEmpty() || instance.isEmpty()) {
            return null
        }
        val publicKey = decodeNullableField(fields[KEY_PUBLIC_KEY], MAX_KEY_CHARS)
        val authSecret = decodeNullableField(fields[KEY_AUTH_SECRET], MAX_KEY_CHARS)
        if (publicKey == null || authSecret == null) {
            return null
        }
        return DecodeEndpointResult(
            UnifiedPushEndpoint(
                url = url,
                instance = instance,
                temporary = temporary,
                publicKey = publicKey.takeIf { it.isNotEmpty() },
                authSecret = authSecret.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /** Returns null on malformed input; empty string maps an absent optional field. */
    private fun decodeNullableField(encoded: String?, maxChars: Int): String? {
        if (encoded == null || encoded == ABSENT_FIELD) {
            return ""
        }
        val decoded = runCatching { String(decoder.decode(encoded), Charsets.UTF_8) }.getOrNull()
            ?: return null
        return decoded.takeIf { it.length <= maxChars }
    }

    private class DecodeEndpointResult(val endpoint: UnifiedPushEndpoint?)

    internal companion object {
        const val MAX_SERIALIZED_BYTES = 16 * 1024
        const val MAX_INSTANCE_CHARS = 128
        const val MAX_KEY_CHARS = 128
        const val STORE_FILE_NAME = AtomicFilePushRegistrationPersistence.FILE_NAME
        private const val FORMAT_VERSION = "1"
        private const val ABSENT_FIELD = "-"
        private const val KEY_VERSION = "version"
        private const val KEY_URL = "url"
        private const val KEY_INSTANCE = "instance"
        private const val KEY_TEMPORARY = "temporary"
        private const val KEY_PUBLIC_KEY = "pubkey"
        private const val KEY_AUTH_SECRET = "auth"
        private const val KEY_UPLOAD_STATE = "upload"
        private const val KEY_PENDING_REMOVAL = "removal"
        private val EMPTY = PushRegistrationSnapshot(
            endpoint = null,
            uploadState = EndpointUploadState.UPLOADED,
            pendingRemoval = null,
        )
        private val lock = Any()
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun forTest(persistence: PushRegistrationPersistence): PushRegistrationStore =
            PushRegistrationStore(persistence)
    }
}
