package io.github.verybigsad.pimobile.pairing

import android.annotation.SuppressLint
import io.github.verybigsad.pimobile.network.CertificateIdentity
import io.github.verybigsad.pimobile.network.CertificateProfileValidator
import io.github.verybigsad.pimobile.network.CertificateRole
import io.github.verybigsad.pimobile.network.NetworkError
import io.github.verybigsad.pimobile.network.NetworkException
import io.github.verybigsad.pimobile.network.StreamByteChannel
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side provisional (pinned, server-auth-only) TLS adapter.
 *
 * Adapter gap: the v1 pairing invitation carries the pinned leaf fingerprint but NOT the Mac
 * instanceId, and network's TlsClientContext has an internal constructor, so
 * TlsContexts.provisional cannot be driven with the SAN identity the invitation never
 * provided (integration plan unresolved Q1). This adapter pins the leaf exactly like the
 * network PinnedLeafTrustManager, forbids client certificates, then learns the Mac SAN
 * identity from the pinned certificate itself (TOFU within the one-use invitation) and
 * validates the full certificate profile against it. Contract fix: add macId to the
 * invitation and delete this adapter.
 */
object ProvisionalTls {
    private const val CONNECT_TIMEOUT_MILLIS = 10_000

    class PinnedPeer(
        val macId: String,
        val leaf: X509Certificate,
    )

    suspend fun connect(
        host: String,
        port: Int,
        expectedLeafSha256: ByteArray,
    ): Pair<StreamByteChannel, PinnedPeer> = withContext(Dispatchers.IO) {
        require(expectedLeafSha256.size == 32)
        val context = try {
            SSLContext.getInstance("TLSv1.3").also {
                it.init(
                    arrayOf<KeyManager>(RejectingClientKeyManager),
                    arrayOf<TrustManager>(PinOnlyTrustManager(expectedLeafSha256)),
                    null,
                )
            }
        } catch (error: Exception) {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "Provisional TLS context creation failed", error)
        }
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            val tls = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = CONNECT_TIMEOUT_MILLIS
            tls.useClientMode = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.sslParameters = tls.sslParameters.also { it.protocols = arrayOf("TLSv1.3") }
            try {
                tls.startHandshake()
            } catch (error: Exception) {
                throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "Provisional TLS 1.3 handshake failed", error)
            } finally {
                tls.soTimeout = 0
            }
            if (tls.session.protocol != "TLSv1.3") {
                throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "Provisional TLS did not negotiate TLS 1.3")
            }
            val leaf = tls.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw NetworkException(NetworkError.CERTIFICATE_PIN_MISMATCH, "Provisional peer certificate is missing")
            val macId = extractMacIdentity(leaf)
            try {
                CertificateProfileValidator(CertificateIdentity(CertificateRole.MAC_SERVER, macId)).validate(leaf)
            } catch (error: CertificateException) {
                throw NetworkException(NetworkError.CERTIFICATE_PROFILE_INVALID, "Provisional peer certificate profile is invalid", error)
            }
            StreamByteChannel.fromConnectedSocket(tls) to PinnedPeer(macId, leaf)
        } catch (error: NetworkException) {
            runCatching { plain.close() }
            throw error
        } catch (error: Exception) {
            runCatching { plain.close() }
            throw NetworkException(NetworkError.TRANSPORT_CLOSED, "Provisional connection failed", error)
        }
    }

    fun extractMacIdentity(certificate: X509Certificate): String {
        val sans = certificate.subjectAlternativeNames.orEmpty()
            .mapNotNull { entry -> if (entry.size >= 2 && entry[0] == 6) entry[1] as? String else null }
        val mac = sans.filter { it.startsWith("urn:pimobile:mac:") }
        if (mac.size != 1) {
            throw NetworkException(NetworkError.CERTIFICATE_PROFILE_INVALID, "Peer certificate has no single Mac identity")
        }
        return mac.single().removePrefix("urn:pimobile:mac:")
    }

    @SuppressLint("CustomX509TrustManager")
    private class PinOnlyTrustManager(
        private val expectedPin: ByteArray,
    ) : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = checkPin(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = checkPin(chain)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = checkPin(chain)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = rejectClient()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = rejectClient()
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        private fun checkPin(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            if (!MessageDigest.isEqual(expectedPin, actual)) throw CertificateException("Server certificate pin mismatch")
        }

        private fun rejectClient(): Nothing = throw CertificateException("Client certificates are forbidden in provisional TLS")
    }

    private object RejectingClientKeyManager : javax.net.ssl.X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = reject()
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = null
        override fun getPrivateKey(alias: String?): PrivateKey? = null

        private fun reject(): Nothing = throw IllegalStateException("Client certificate request is forbidden in provisional TLS")
    }
}
