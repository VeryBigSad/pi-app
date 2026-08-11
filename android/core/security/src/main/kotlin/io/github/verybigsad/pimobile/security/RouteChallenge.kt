package io.github.verybigsad.pimobile.security

import io.github.verybigsad.pimobile.protocol.canonicalizeJson
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RouteChallenge private constructor(
    val routeId: String,
    val keyId: String,
    val rendezvousId: String,
    val expiresAt: Instant,
    private val nonceValue: ByteArray,
    private val signedValue: JsonObject,
    private val canonicalValue: ByteArray,
) {
    val audience: String = "device-data"

    fun nonce(): ByteArray = nonceValue.copyOf()

    fun signedPayload(): JsonObject = JsonObject(signedValue.toMap())

    fun canonicalSignedPayload(): ByteArray = canonicalValue.copyOf()

    companion object {
        fun parse(signedJson: String, now: Instant = Instant.now()): RouteChallenge {
            val signed = StrictJson.objectValue(
                signedJson,
                maxBytes = 16 * 1024,
                maxDepth = 3,
                maxObjectMembers = 8,
                maxArrayItems = 1,
            )
            signed.requireExactKeys(setOf("audience", "routeId", "keyId", "rendezvousId", "nonce", "expiresAt"))
            require(signed.requireString("audience", 16) == "device-data")
            val routeId = signed.requireString("routeId", 128).also { require(opaqueIdPattern.matches(it)) }
            val keyId = signed.requireString("keyId", 128).also { require(opaqueIdPattern.matches(it)) }
            val rendezvousId = signed.requireString("rendezvousId", 128).also { require(opaqueIdPattern.matches(it)) }
            val nonce = Base64Url.decode(signed.requireString("nonce", 43), maxBytes = 32, exactBytes = 32)
            val expiresText = signed.requireString("expiresAt", 35)
            val expiresAt = runCatching { Instant.parse(expiresText) }.getOrElse {
                throw IllegalArgumentException("invalid route challenge expiry")
            }
            val remaining = Duration.between(now, expiresAt)
            require(!remaining.isNegative && !remaining.isZero && remaining <= Duration.ofSeconds(30))
            val canonical = canonicalizeJson(signed).encodeToByteArray()
            require(canonical.size <= 16 * 1024)
            return RouteChallenge(routeId, keyId, rendezvousId, expiresAt, nonce, signed, canonical)
        }
    }
}

class RouteProof internal constructor(
    val challenge: RouteChallenge,
    signatureDer: ByteArray,
) {
    private val signatureValue = EcdsaDer.requireP256Signature(signatureDer)

    fun signatureDer(): ByteArray = signatureValue.copyOf()

    fun toJsonObject(): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("route.proof"),
            "signed" to challenge.signedPayload(),
            "signature" to JsonPrimitive(Base64Url.encode(signatureValue)),
        ),
    )
}
