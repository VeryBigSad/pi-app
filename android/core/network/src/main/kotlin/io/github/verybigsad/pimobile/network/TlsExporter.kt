package io.github.verybigsad.pimobile.network

import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket

/**
 * RFC 5705/8446 TLS keying-material exporter via the platform Conscrypt provider.
 * The unified exporter contract is label `EXPORTER-Pi-Mobile-Pairing-v1` with an
 * empty context and 32 bytes; both peers of a TLS 1.3 session derive identical bytes.
 * Conscrypt is the only Android TLS provider exposing exporters. On JVMs or platforms
 * without it, export fails with [NetworkError.EXPORTER_UNSUPPORTED]. The exporter is
 * optional: pairing binds to the in-channel `pairingToken` instead, so this failure
 * never blocks pairing.
 * On Android API 29 the platform provider class com.android.org.conscrypt.Conscrypt
 * hides exportKeyingMaterial behind the non-SDK interface lists, so the exporter is
 * genuinely unavailable there; provisioning requires a platform with reflective access
 * to the Conscrypt exporter (or a bundled conscrypt-android provider).
 */
object TlsExporter {
    private const val MAX_EXPORTER_BYTES = 64
    private const val MAX_CONTEXT_BYTES = 255

    fun export(
        engine: SSLEngine,
        label: String = TlsExporterLabel,
        context: ByteArray = ByteArray(0),
        length: Int = TlsExporterBytes,
    ): ByteArray = exportChecked(engine, label, context, length)

    fun export(
        socket: SSLSocket,
        label: String = TlsExporterLabel,
        context: ByteArray = ByteArray(0),
        length: Int = TlsExporterBytes,
    ): ByteArray = exportChecked(socket, label, context, length)

    /** True when this runtime exposes a Conscrypt exporter for [peerType]. */
    fun isSupported(peerType: Class<*>): Boolean = try {
        conscryptExport(peerType)
        true
    } catch (error: NetworkException) {
        false
    }

    private fun exportChecked(peer: Any, label: String, context: ByteArray, length: Int): ByteArray {
        if (label.isEmpty() || label.encodeToByteArray().size > 255) {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS exporter label is invalid")
        }
        if (length !in 1..MAX_EXPORTER_BYTES || context.size > MAX_CONTEXT_BYTES) {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS exporter parameters exceed their bound")
        }
        val method = when (peer) {
            is SSLEngine -> conscryptExport(SSLEngine::class.java)
            is SSLSocket -> conscryptExport(SSLSocket::class.java)
            else -> throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS exporter peer is unsupported")
        }
        return try {
            method.invoke(null, peer, label, context.copyOf(), length) as ByteArray
        } catch (error: Exception) {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS exporter derivation failed", error)
        }
    }

    private fun conscryptExport(peerType: Class<*>): java.lang.reflect.Method {
        var failure: Exception? = null
        // Bundled `conscrypt-android` exposes org.conscrypt.Conscrypt; the platform
        // provider on API 29+ exposes com.android.org.conscrypt.Conscrypt.
        for (provider in listOf("org.conscrypt.Conscrypt", "com.android.org.conscrypt.Conscrypt")) {
            try {
                return Class.forName(provider).getMethod(
                    "exportKeyingMaterial",
                    peerType,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
            } catch (error: Exception) {
                failure = error
            }
        }
        throw NetworkException(NetworkError.EXPORTER_UNSUPPORTED, "TLS exporter is unavailable on this TLS provider", failure)
    }
}
