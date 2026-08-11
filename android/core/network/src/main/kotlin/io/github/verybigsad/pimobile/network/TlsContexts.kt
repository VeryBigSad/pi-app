package io.github.verybigsad.pimobile.network

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Clock
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

private const val SERVER_AUTH_EKU = "1.3.6.1.5.5.7.3.1"
private const val CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2"
private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"

enum class CertificateRole {
    MAC_SERVER,
    ANDROID_DEVICE,
}

data class CertificateIdentity(
    val role: CertificateRole,
    val id: String,
) {
    val uri: String = when (role) {
        CertificateRole.MAC_SERVER -> "urn:pimobile:mac:$id"
        CertificateRole.ANDROID_DEVICE -> "urn:pimobile:device:$id"
    }

    init {
        if (!opaqueIdPattern.matches(id)) {
            throw NetworkException(NetworkError.CERTIFICATE_PROFILE_INVALID, "Certificate identity is invalid")
        }
    }
}

fun interface CertificateRevocationChecker {
    fun isRevoked(certificate: X509Certificate): Boolean
}

class CertificateProfileValidator(
    private val identity: CertificateIdentity,
    private val clock: Clock = Clock.systemUTC(),
    private val revocationChecker: CertificateRevocationChecker = CertificateRevocationChecker { false },
) {
    @Throws(CertificateException::class)
    fun validate(certificate: X509Certificate) {
        try {
            certificate.checkValidity(Date.from(clock.instant()))
            requireP256(certificate.publicKey)
            if (certificate.basicConstraints != -1 || BASIC_CONSTRAINTS_OID !in certificate.criticalExtensionOIDs.orEmpty()) invalid()
            val usage = certificate.keyUsage ?: invalid()
            if (usage.isEmpty() || !usage[0] || usage.indices.any { it != 0 && usage[it] }) invalid()
            val expectedEku = when (identity.role) {
                CertificateRole.MAC_SERVER -> SERVER_AUTH_EKU
                CertificateRole.ANDROID_DEVICE -> CLIENT_AUTH_EKU
            }
            if (certificate.extendedKeyUsage != listOf(expectedEku)) invalid()
            val sans = certificate.subjectAlternativeNames ?: invalid()
            val uriSans = sans.mapNotNull { entry ->
                if (entry.size >= 2 && entry[0] == 6) entry[1] as? String else null
            }
            if (uriSans.count { it == identity.uri } != 1 || uriSans.any { it.startsWith("urn:pimobile:") && it != identity.uri }) invalid()
            if (identity.role == CertificateRole.ANDROID_DEVICE && sans.any { it.size >= 2 && (it[0] == 2 || it[0] == 7) }) invalid()
            if (revocationChecker.isRevoked(certificate)) {
                throw CertificateException("Certificate is revoked")
            }
        } catch (error: NetworkException) {
            throw CertificateException("Certificate profile is invalid", error)
        }
    }

    fun validate(session: SSLSession) {
        val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw NetworkException(NetworkError.CERTIFICATE_PROFILE_INVALID, "TLS peer certificate is missing")
        try {
            validate(certificate)
        } catch (error: CertificateException) {
            throw NetworkException(NetworkError.CERTIFICATE_PROFILE_INVALID, "TLS peer certificate profile is invalid", error)
        }
    }

    private fun invalid(): Nothing = throw CertificateException("Certificate profile is invalid")
}

class TlsClientContext internal constructor(
    val sslContext: SSLContext,
    private val profileValidator: CertificateProfileValidator,
) {
    fun newEngine(peerHost: String, peerPort: Int): SSLEngine = sslContext.createSSLEngine(peerHost, peerPort).also { engine ->
        engine.useClientMode = true
        engine.enabledProtocols = arrayOf("TLSv1.3")
        engine.sslParameters = engine.sslParameters.also { parameters ->
            parameters.protocols = arrayOf("TLSv1.3")
        }
    }

    fun authenticate(session: SSLSession) {
        if (session.protocol != "TLSv1.3") {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS peer did not negotiate TLS 1.3")
        }
        profileValidator.validate(session)
    }
}

object TlsContexts {
    fun provisional(
        expectedLeafSha256: ByteArray,
        expectedIdentity: CertificateIdentity,
        clock: Clock = Clock.systemUTC(),
        secureRandom: SecureRandom? = null,
    ): TlsClientContext {
        if (expectedIdentity.role != CertificateRole.MAC_SERVER || expectedLeafSha256.size != 32) {
            throw NetworkException(NetworkError.CERTIFICATE_PIN_MISMATCH, "Provisional server pin configuration is invalid")
        }
        val profile = CertificateProfileValidator(expectedIdentity, clock)
        val trust = PinnedLeafTrustManager(expectedLeafSha256.copyOf(), profile)
        return TlsClientContext(newContext(arrayOf(RejectingClientKeyManager), arrayOf(trust), secureRandom), profile)
    }

    fun mutual(
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        expectedServerIdentity: CertificateIdentity,
        clock: Clock = Clock.systemUTC(),
        revocationChecker: CertificateRevocationChecker = CertificateRevocationChecker { false },
        secureRandom: SecureRandom? = null,
    ): TlsClientContext {
        if (keyManagers.isEmpty() || expectedServerIdentity.role != CertificateRole.MAC_SERVER) {
            throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "mTLS key managers or server identity are missing")
        }
        val delegate = trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "Exactly one X.509 trust manager is required")
        val profile = CertificateProfileValidator(expectedServerIdentity, clock, revocationChecker)
        val trust = ProfileTrustManager(delegate, profile)
        return TlsClientContext(newContext(keyManagers.copyOf(), arrayOf(trust), secureRandom), profile)
    }

    private fun newContext(
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        secureRandom: SecureRandom?,
    ): SSLContext = try {
        SSLContext.getInstance("TLSv1.3").also { it.init(keyManagers, trustManagers, secureRandom) }
    } catch (error: Exception) {
        throw NetworkException(NetworkError.TLS_HANDSHAKE_FAILED, "TLS 1.3 context creation failed", error)
    }
}

private object RejectingClientKeyManager : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = reject()
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = reject()
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = reject()
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = null
    override fun getPrivateKey(alias: String?): PrivateKey? = null

    private fun reject(): Nothing = throw IllegalStateException("Client certificate request is forbidden in provisional TLS")
}

@SuppressLint("CustomX509TrustManager")
private class PinnedLeafTrustManager(
    private val expectedPin: ByteArray,
    private val profileValidator: CertificateProfileValidator,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = rejectClient()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = rejectClient()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = rejectClient()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Server certificate is missing")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!MessageDigest.isEqual(expectedPin, actual)) throw CertificateException("Server certificate pin mismatch")
        profileValidator.validate(leaf)
    }

    private fun rejectClient(): Nothing = throw CertificateException("Client certificates are forbidden in provisional TLS")
}

@SuppressLint("CustomX509TrustManager")
private class ProfileTrustManager(
    private val delegate: X509TrustManager,
    private val profileValidator: CertificateProfileValidator,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        delegate.checkServerTrusted(required(chain), authType)
        profileValidator.validate(required(chain).first())
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val checked = required(chain)
        if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(checked, authType, socket) else delegate.checkServerTrusted(checked, authType)
        profileValidator.validate(checked.first())
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val checked = required(chain)
        if (delegate is X509ExtendedTrustManager) delegate.checkServerTrusted(checked, authType, engine) else delegate.checkServerTrusted(checked, authType)
        profileValidator.validate(checked.first())
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = delegate.checkClientTrusted(required(chain), authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
        val checked = required(chain)
        if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(checked, authType, socket) else delegate.checkClientTrusted(checked, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
        val checked = required(chain)
        if (delegate is X509ExtendedTrustManager) delegate.checkClientTrusted(checked, authType, engine) else delegate.checkClientTrusted(checked, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    private fun required(chain: Array<out X509Certificate>?): Array<out X509Certificate> = chain?.takeIf { it.isNotEmpty() }
        ?: throw CertificateException("Certificate chain is missing")
}
