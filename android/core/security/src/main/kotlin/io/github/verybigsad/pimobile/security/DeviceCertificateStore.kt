package io.github.verybigsad.pimobile.security

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

private const val ClientAuthOid = "1.3.6.1.5.5.7.3.2"
private const val BasicConstraintsOid = "2.5.29.19"
private const val MaxCertificateBytes = 8 * 1024
private const val MaxChainBytes = 32 * 1024
private const val MaxLeafValidityMillis = 31L * 24 * 60 * 60 * 1000
private val identityLock = Any()

class InstalledClientIdentity constructor(
    val deviceId: String,
    val leafFingerprint: CertificateFingerprint,
    val issuerFingerprint: CertificateFingerprint,
    val notBefore: Instant,
    val notAfter: Instant,
)

internal object DeviceCertificateStore {
    fun install(deviceId: String, certificateChainDer: List<ByteArray>): InstalledClientIdentity = synchronized(identityLock) {
        require(deviceIdPattern.matches(deviceId))
        val store = androidKeyStore()
        val existing = store.getEntry(TlsAlias, null) as? KeyStore.PrivateKeyEntry
            ?: error("TLS key is unavailable")
        val chain = ClientCertificateChain.parseAndValidate(
            certificateChainDer,
            existing.certificate.publicKey,
            deviceId,
            Date(),
        )
        val oldChain = existing.certificateChain.copyOf()
        try {
            store.setKeyEntry(TlsAlias, existing.privateKey, null, chain.toTypedArray())
            val installed = androidKeyStore().getEntry(TlsAlias, null) as? KeyStore.PrivateKeyEntry
                ?: error("installed TLS identity is unavailable")
            val installedChain = installed.certificateChain.map { it as X509Certificate }
            ClientCertificateChain.validate(installedChain, existing.certificate.publicKey, deviceId, Date())
            require(installed.privateKey.encoded == null)
            identity(installedChain, deviceId)
        } catch (error: Exception) {
            runCatching { store.setKeyEntry(TlsAlias, existing.privateKey, null, oldChain) }
            throw error
        }
    }

    fun keyManager(deviceId: String): X509ExtendedKeyManager = synchronized(identityLock) {
        require(deviceIdPattern.matches(deviceId))
        val entry = androidKeyStore().getEntry(TlsAlias, null) as? KeyStore.PrivateKeyEntry
            ?: error("TLS key is unavailable")
        val chain = entry.certificateChain.map { it as X509Certificate }
        ClientCertificateChain.validate(chain, entry.certificate.publicKey, deviceId, Date())
        RestrictedTlsKeyManager(entry.privateKey, chain)
    }

    private fun identity(chain: List<X509Certificate>, deviceId: String): InstalledClientIdentity = InstalledClientIdentity(
        deviceId = deviceId,
        leafFingerprint = CertificateFingerprint.fromCertificate(chain.first().encoded),
        issuerFingerprint = CertificateFingerprint.fromCertificate(chain.last().encoded),
        notBefore = chain.first().notBefore.toInstant(),
        notAfter = chain.first().notAfter.toInstant(),
    )
}

internal object ClientCertificateChain {
    fun parseAndValidate(
        encodedChain: List<ByteArray>,
        expectedPublicKey: PublicKey,
        deviceId: String,
        now: Date,
    ): List<X509Certificate> {
        require(encodedChain.size in 2..4)
        require(encodedChain.sumOf(ByteArray::size) <= MaxChainBytes)
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = encodedChain.map { encoded ->
            require(encoded.size in 1..MaxCertificateBytes)
            val input = ByteArrayInputStream(encoded)
            val certificate = factory.generateCertificate(input) as X509Certificate
            require(input.available() == 0)
            require(certificate.encoded.contentEquals(encoded))
            certificate
        }
        validate(certificates, expectedPublicKey, deviceId, now)
        return certificates
    }

    fun validate(
        certificates: List<X509Certificate>,
        expectedPublicKey: PublicKey,
        deviceId: String,
        now: Date,
    ) {
        require(certificates.size in 2..4)
        requireP256(expectedPublicKey)
        require(certificates.map { Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(it.encoded)) }.distinct().size == certificates.size)
        certificates.forEach { certificate ->
            requireP256(certificate.publicKey)
            require(certificate.sigAlgOID == "1.2.840.10045.4.3.2")
            certificate.checkValidity(now)
        }
        val leaf = certificates.first()
        require(MessageDigest.isEqual(leaf.publicKey.encoded, expectedPublicKey.encoded))
        require(leaf.version == 3 && leaf.basicConstraints == -1)
        require(BasicConstraintsOid in leaf.criticalExtensionOIDs.orEmpty())
        require(leaf.notAfter.time - leaf.notBefore.time <= MaxLeafValidityMillis)
        val leafKeyUsage = requireNotNull(leaf.keyUsage)
        require(leafKeyUsage.getOrElse(0) { false })
        require(leafKeyUsage.indices.drop(1).none { leafKeyUsage[it] })
        require(leaf.extendedKeyUsage == listOf(ClientAuthOid))
        requireDeviceSan(leaf, deviceId)

        certificates.zipWithNext().forEach { (child, issuer) ->
            require(child.issuerX500Principal == issuer.subjectX500Principal)
            child.verify(issuer.publicKey)
            require(issuer.basicConstraints >= 0)
            require(BasicConstraintsOid in issuer.criticalExtensionOIDs.orEmpty())
            val issuerKeyUsage = requireNotNull(issuer.keyUsage)
            require(issuerKeyUsage.getOrElse(5) { false })
        }
        val root = certificates.last()
        require(root.subjectX500Principal == root.issuerX500Principal)
        root.verify(root.publicKey)

        val factory = CertificateFactory.getInstance("X.509")
        val path = factory.generateCertPath(certificates.dropLast(1))
        val parameters = PKIXParameters(setOf(TrustAnchor(root, null))).apply {
            date = Date(now.time)
            isRevocationEnabled = false
        }
        CertPathValidator.getInstance("PKIX").validate(path, parameters)
    }

    private fun requireDeviceSan(certificate: X509Certificate, deviceId: String) {
        val names = certificate.subjectAlternativeNames.orEmpty()
        require(names.size == 1)
        val name = names.single()
        require(name.size >= 2 && name[0] == 6)
        require(name[1] == "urn:pimobile:device:$deviceId")
    }
}

private class RestrictedTlsKeyManager(
    private val privateKey: PrivateKey,
    certificateChain: List<X509Certificate>,
) : X509ExtendedKeyManager() {
    private val chain = certificateChain.toTypedArray()

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (compatible(keyType, issuers)) arrayOf(TlsAlias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType.orEmpty().any { compatible(it, issuers) }) TlsAlias else null

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (keyType.orEmpty().any { compatible(it, issuers) }) TlsAlias else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == TlsAlias) chain.copyOf() else null

    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == TlsAlias) privateKey else null

    private fun compatible(keyType: String?, issuers: Array<out Principal>?): Boolean {
        if (runCatching { chain.first().checkValidity() }.isFailure) return false
        if (keyType != "EC" && keyType != "ECDSA") return false
        if (issuers.isNullOrEmpty()) return true
        val subjects = chain.map(X509Certificate::getSubjectX500Principal).toSet()
        return issuers.any(subjects::contains)
    }
}

internal fun androidKeyStore(): KeyStore = KeyStore.getInstance(KeyStoreProvider).apply { load(null) }
