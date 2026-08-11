package io.github.verybigsad.pimobile.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

internal const val KeyStoreProvider = "AndroidKeyStore"
internal const val TlsAlias = "pimobile-device-tls-v1"
internal const val RouteAlias = "pimobile-route-auth-v1"
internal const val MaxRoutePayloadBytes = 16 * 1_024
internal val deviceIdPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

data class DevicePublicKeys(
    val tlsPublicKeySpki: ByteArray,
    val routePublicKeySpki: ByteArray,
    val csrDer: ByteArray,
)

data class DeviceKeySecurity(
    val tlsInsideSecureHardware: Boolean,
    val routeInsideSecureHardware: Boolean,
)

/** Keystore-backed device identity surface consumed by pairing orchestration. */
interface DeviceIdentityStore {
    fun getOrCreate(deviceId: String): DevicePublicKeys

    fun installClientCertificateChain(deviceId: String, certificateChainDer: List<ByteArray>): InstalledClientIdentity

    fun tlsKeyManager(deviceId: String): X509ExtendedKeyManager

    /**
     * Raw-payload route signer boundary: signs an arbitrary bounded canonical (RFC 8785/JCS)
     * payload with the device route key. [RouteChallenge]-bound signing stays available via
     * [DeviceKeys.signRouteChallenge]; this API exists for caller-created relay proofs
     * (audience `device-data`/`route-admin`) that carry no relay-issued challenge.
     */
    fun routePayloadSigner(): RoutePayloadSigner
}

/** Signs bounded canonical JCS payloads with the non-exportable route key. */
fun interface RoutePayloadSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

class DeviceKeys : DeviceIdentityStore {
    override fun getOrCreate(deviceId: String): DevicePublicKeys {
        require(deviceIdPattern.matches(deviceId))
        val tls = keyPair(TlsAlias, allowNoneDigest = true)
        val route = keyPair(RouteAlias, allowNoneDigest = false)
        val csr = Pkcs10.encode(tls.public, deviceId) { requestInfo ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(tls.private)
                update(requestInfo)
                sign()
            }
        }
        return DevicePublicKeys(
            tlsPublicKeySpki = tls.public.encoded.copyOf(),
            routePublicKeySpki = route.public.encoded.copyOf(),
            csrDer = csr,
        )
    }

    override fun routePayloadSigner(): RoutePayloadSigner = RoutePayloadSigner(::signRoutePayload)

    fun signRoutePayload(canonicalPayload: ByteArray): ByteArray {
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= MaxRoutePayloadBytes) {
            "route payload is outside its bound"
        }
        val entry = androidKeyStore().getEntry(RouteAlias, null) as? KeyStore.PrivateKeyEntry
            ?: error("route key is unavailable")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(canonicalPayload.copyOf())
            sign()
        }
        return EcdsaDer.requireP256Signature(signature)
    }

    fun signRouteChallenge(challenge: RouteChallenge): RouteProof {
        val entry = androidKeyStore().getEntry(RouteAlias, null) as? KeyStore.PrivateKeyEntry
            ?: error("route key is unavailable")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(challenge.canonicalSignedPayload())
            sign()
        }
        return RouteProof(challenge, signature)
    }

    override fun installClientCertificateChain(
        deviceId: String,
        certificateChainDer: List<ByteArray>,
    ): InstalledClientIdentity = DeviceCertificateStore.install(deviceId, certificateChainDer.map(ByteArray::copyOf))

    override fun tlsKeyManager(deviceId: String): X509ExtendedKeyManager = DeviceCertificateStore.keyManager(deviceId)

    fun securityReport(): DeviceKeySecurity = DeviceKeySecurity(
        tlsInsideSecureHardware = keyInfo(TlsAlias).isInsideSecureHardwareCompat(),
        routeInsideSecureHardware = keyInfo(RouteAlias).isInsideSecureHardwareCompat(),
    )

    fun deleteAll() {
        androidKeyStore().run {
            deleteEntry(TlsAlias)
            deleteEntry(RouteAlias)
        }
    }

    private fun keyPair(alias: String, allowNoneDigest: Boolean): KeyPair {
        val store = androidKeyStore()
        val existing = store.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) {
            require(existing.privateKey.encoded == null)
            requireP256(existing.certificate.publicKey)
            return KeyPair(existing.certificate.publicKey, existing.privateKey)
        }

        val now = System.currentTimeMillis()
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setCertificateSubject(X500Principal("CN=$alias"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 60_000))
            .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
            .setUserAuthenticationRequired(false)
        if (allowNoneDigest) {
            builder.setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
        } else {
            builder.setDigests(KeyProperties.DIGEST_SHA256)
        }
        return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KeyStoreProvider).run {
            initialize(builder.build())
            generateKeyPair().also {
                require(it.private.encoded == null)
                requireP256(it.public)
            }
        }
    }

    private fun keyInfo(alias: String): KeyInfo {
        val entry = androidKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: error("device key is unavailable")
        return KeyFactory.getInstance(entry.privateKey.algorithm, KeyStoreProvider)
            .getKeySpec(entry.privateKey, KeyInfo::class.java)
    }

    @Suppress("DEPRECATION")
    private fun KeyInfo.isInsideSecureHardwareCompat(): Boolean = isInsideSecureHardware
}
