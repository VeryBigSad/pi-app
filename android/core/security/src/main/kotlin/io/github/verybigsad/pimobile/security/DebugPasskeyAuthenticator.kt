package io.github.verybigsad.pimobile.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/**
 * Debug-only local passkey authenticator. Performs real WebAuthn ceremonies (P-256 keys,
 * structurally valid attestation/assertion responses, genuine ECDSA signatures) without a
 * platform passkey provider, so the pairing ceremony is completable on emulators with an
 * old Play services. Every public entry point refuses to run in release builds, and the
 * produced responses still pass the full [PasskeyPolicy] validation. Credentials live in
 * process memory only.
 */
class DebugPasskeyAuthenticator(private val originProvider: () -> String) : PasskeyExecutor {
    private val random = SecureRandom()
    private val credentials = mutableMapOf<String, StoredCredential>()

    private class StoredCredential(
        val id: ByteArray,
        val idBase64Url: String,
        val privateKey: PrivateKey,
        val publicKey: ECPublicKey,
        val userHandle: ByteArray,
        var signCount: Long,
    )

    override suspend fun createCredential(requestJson: String): String {
        check(BuildConfig.DEBUG) { "debug passkey authenticator is unavailable in release builds" }
        val options = StrictJson.objectValue(requestJson, MaxOptionsBytes, maxDepth = 16, maxArrayItems = 64)
        val challenge = options.requireString("challenge", 1024)
        val user = options.requireObject("user")
        val userHandle = Base64Url.decode(user.requireString("id", 1024), maxBytes = 128)
        val keyPair = generateKeyPair()
        val credentialId = ByteArray(32).also(random::nextBytes)
        val credentialIdBase64Url = Base64Url.encode(credentialId)
        val publicKey = keyPair.public as ECPublicKey
        synchronized(credentials) {
            credentials[credentialIdBase64Url] = StoredCredential(
                credentialId,
                credentialIdBase64Url,
                keyPair.private,
                publicKey,
                userHandle,
                signCount = 0,
            )
        }
        val authData = registrationAuthData(credentialId, publicKey)
        val clientData = clientDataJson(PasskeyCeremonyType.REGISTRATION.clientDataType, challenge)
        val attestationObject = Cbor.map(
            Cbor.text("fmt") to Cbor.text("none"),
            Cbor.text("attStmt") to Cbor.map(),
            Cbor.text("authData") to Cbor.byteString(authData),
        )
        return credentialResponse(
            idBase64Url = credentialIdBase64Url,
            responseFields = """
                "clientDataJSON":"${Base64Url.encode(clientData)}",
                "attestationObject":"${Base64Url.encode(attestationObject)}"
            """,
        )
    }

    override suspend fun getCredential(requestJson: String): String {
        check(BuildConfig.DEBUG) { "debug passkey authenticator is unavailable in release builds" }
        val options = StrictJson.objectValue(requestJson, MaxOptionsBytes, maxDepth = 16, maxArrayItems = 64)
        val challenge = options.requireString("challenge", 1024)
        val credential = selectCredential(options)
            ?: throw NoSuchElementException("no debug passkey credential matches the request")
        val signCount = synchronized(credentials) { ++credential.signCount }
        val authData = assertionAuthData(signCount)
        val clientData = clientDataJson(PasskeyCeremonyType.ASSERTION.clientDataType, challenge)
        val signature = sign(credential.privateKey, authData + sha256(clientData))
        return credentialResponse(
            idBase64Url = credential.idBase64Url,
            responseFields = """
                "clientDataJSON":"${Base64Url.encode(clientData)}",
                "authenticatorData":"${Base64Url.encode(authData)}",
                "signature":"${Base64Url.encode(EcdsaDer.requireP256Signature(signature))}",
                "userHandle":"${Base64Url.encode(credential.userHandle)}"
            """,
        )
    }

    /** Test hook: public key of a stored credential, null when unknown. */
    internal fun publicKeyFor(credentialIdBase64Url: String): ECPublicKey? =
        synchronized(credentials) { credentials[credentialIdBase64Url]?.publicKey }

    /** Test hook: current signature counter of a stored credential, null when unknown. */
    internal fun signCountFor(credentialIdBase64Url: String): Long? =
        synchronized(credentials) { credentials[credentialIdBase64Url]?.signCount }

    private fun selectCredential(options: kotlinx.serialization.json.JsonObject): StoredCredential? {
        val allowed = options["allowCredentials"] as? kotlinx.serialization.json.JsonArray
        synchronized(credentials) {
            if (allowed == null) return credentials.values.firstOrNull()
            val ids = allowed.mapNotNull { entry ->
                (entry as? kotlinx.serialization.json.JsonObject)?.let {
                    runCatching { it.requireString("id", 1024) }.getOrNull()
                }
            }
            return ids.firstNotNullOfOrNull { credentials[it] }
        }
    }

    private fun generateKeyPair(): KeyPair {
        val hardwareBacked = runCatching {
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    "pimobile-debug-passkey-${UUID.randomUUID()}",
                    android.security.keystore.KeyProperties.PURPOSE_SIGN,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKeyPair()
        }
        return hardwareBacked.getOrElse {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            generator.generateKeyPair()
        }
    }

    private fun registrationAuthData(credentialId: ByteArray, publicKey: ECPublicKey): ByteArray {
        val attestedCredentialData = ByteArray(16) + // AAGUID (zero: unattested debug authenticator)
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) +
            credentialId +
            coseP256Key(publicKey)
        return rpIdHash() + byteArrayOf((FLAG_UP_I or FLAG_UV_I or FLAG_AT_I).toByte()) + counterBytes(0) + attestedCredentialData
    }

    private fun assertionAuthData(signCount: Long): ByteArray =
        rpIdHash() + byteArrayOf((FLAG_UP_I or FLAG_UV_I).toByte()) + counterBytes(signCount)

    private fun clientDataJson(type: String, challenge: String): ByteArray {
        val origin = originProvider()
        require(origin in PasskeyOrigins.allowedAndroidOrigins()) { "debug origin is not an allowed Android origin" }
        return """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}"""
            .encodeToByteArray()
    }

    private fun credentialResponse(idBase64Url: String, responseFields: String): String = """
        {
          "id":"$idBase64Url",
          "rawId":"$idBase64Url",
          "type":"public-key",
          "authenticatorAttachment":"platform",
          "response":{${responseFields.trimIndent().replace("\n", "")}},
          "clientExtensionResults":{}
        }
    """.trimIndent()

    private fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }

    private companion object {
        private const val MaxOptionsBytes = 64 * 1024
        private const val FLAG_UP_I = 0x01
        private const val FLAG_UV_I = 0x04
        private const val FLAG_AT_I = 0x40

        private fun rpIdHash(): ByteArray = sha256(PasskeyIdentity.RpId.encodeToByteArray())

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun counterBytes(value: Long): ByteArray = byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte(),
        )

        private fun coseP256Key(publicKey: ECPublicKey): ByteArray = Cbor.map(
            Cbor.integer(1) to Cbor.integer(2), // kty: EC2
            Cbor.integer(3) to Cbor.integer(-7), // alg: ES256
            Cbor.integer(-1) to Cbor.integer(1), // crv: P-256
            Cbor.integer(-2) to Cbor.byteString(fixed32(publicKey.w.affineX.toByteArray())),
            Cbor.integer(-3) to Cbor.byteString(fixed32(publicKey.w.affineY.toByteArray())),
        )

        private fun fixed32(value: ByteArray): ByteArray {
            val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
            require(stripped.size <= 32)
            return ByteArray(32 - stripped.size) + stripped
        }
    }
}

private object Cbor {
    fun integer(value: Int): ByteArray =
        if (value >= 0) head(0x00, value) else head(0x20, -1 - value)

    fun text(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return head(0x60, bytes.size) + bytes
    }

    fun byteString(value: ByteArray): ByteArray = head(0x40, value.size) + value

    fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        head(0xA0, entries.size) + entries.fold(ByteArray(0)) { acc, (key, value) -> acc + key + value }

    private fun head(major: Int, size: Int): ByteArray = when {
        size < 24 -> byteArrayOf((major or size).toByte())
        size < 256 -> byteArrayOf((major or 24).toByte(), size.toByte())
        else -> byteArrayOf((major or 25).toByte(), (size shr 8).toByte(), size.toByte())
    }
}
