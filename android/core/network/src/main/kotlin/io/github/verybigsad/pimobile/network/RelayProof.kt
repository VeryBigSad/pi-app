package io.github.verybigsad.pimobile.network

import io.github.verybigsad.pimobile.security.RoutePayloadSigner
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val MAX_RELAY_CONTROL_BYTES = 16 * 1_024
const val RELAY_NONCE_BYTES = 32
val MAX_RELAY_CHALLENGE_LIFETIME: Duration = Duration.ofSeconds(30)
val RELAY_REPLAY_RETENTION: Duration = Duration.ofMinutes(2)

/** Caller-created proofs stay strictly inside the relay challenge lifetime window. */
val SelfIssuedProofLifetime: Duration = Duration.ofSeconds(25)

enum class RelayAudience(val wireValue: String) {
    CONTROL("control"),
    ROUTE_ADMIN("route-admin"),
    DEVICE_DATA("device-data"),
    MAC_DATA("mac-data"),
    ;

    companion object {
        fun fromWire(value: String): RelayAudience = entries.firstOrNull { it.wireValue == value }
            ?: throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay audience is invalid")
    }
}

/**
 * Signs the canonical relay challenge payload without exposing private key material.
 * Production backs this with a non-exportable Android Keystore route key.
 */
fun interface RelayProofSigner {
    fun sign(payload: ByteArray): ByteArray
}

/** Adapts the core/security raw-payload route signer to the relay proof signer boundary. */
fun RoutePayloadSigner.asRelayProofSigner(): RelayProofSigner = RelayProofSigner { payload -> sign(payload) }

class RelayChallenge internal constructor(
    val audience: RelayAudience,
    val routeId: String,
    val keyId: String,
    nonce: ByteArray,
    val expiresAt: Instant,
    val rendezvousId: String?,
    private val expiresAtWire: String,
) {
    private val nonceValue = nonce.copyOf()
    val nonce: ByteArray get() = nonceValue.copyOf()
    internal fun signedJson(): JsonObject {
        val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "audience" to JsonPrimitive(audience.wireValue),
            "routeId" to JsonPrimitive(routeId),
            "keyId" to JsonPrimitive(keyId),
            "nonce" to JsonPrimitive(encodeBase64Url(nonceValue)),
            "expiresAt" to JsonPrimitive(expiresAtWire),
        )
        if (rendezvousId != null) values["rendezvousId"] = JsonPrimitive(rendezvousId)
        return JsonObject(values)
    }
}

data class ExpectedRelayChallenge(
    val audience: RelayAudience,
    val routeId: String,
    val keyId: String,
    val rendezvousId: String? = null,
)

class RelayProofCodec(
    private val clock: Clock = Clock.systemUTC(),
    private val replays: ReplayWindow = ReplayWindow(RELAY_REPLAY_RETENTION, 10_000),
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Caller-created proof for relay HTTP/WebSocket endpoints that authenticate a
     * self-issued audience (`device-data`, `route-admin`) instead of a relay-issued
     * challenge. `control` and `mac-data` require relay-issued challenges/notices and
     * are rejected here; [RouteChallenge]-bound signing also rejects missing rendezvous
     * identity, so device-data cannot be minted through the challenge path.
     */
    fun encodeSelfIssuedProof(
        audience: RelayAudience,
        routeId: String,
        keyId: String,
        signer: RelayProofSigner,
        lifetime: Duration = SelfIssuedProofLifetime,
        nonce: ByteArray = ByteArray(RELAY_NONCE_BYTES).also(random::nextBytes),
    ): ByteArray {
        if (audience != RelayAudience.DEVICE_DATA && audience != RelayAudience.ROUTE_ADMIN) {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay audience requires a relay-issued challenge")
        }
        if (lifetime.isNegative || lifetime.isZero || lifetime > MAX_RELAY_CHALLENGE_LIFETIME) {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay proof lifetime exceeds its bound")
        }
        if (nonce.size != RELAY_NONCE_BYTES || !opaqueIdPattern.matches(routeId) || !opaqueIdPattern.matches(keyId)) {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay proof fields are invalid")
        }
        val expiresAt = clock.instant().plus(lifetime)
        val challenge = RelayChallenge(audience, routeId, keyId, nonce, expiresAt, null, expiresAt.toString())
        return encodeProof(challenge, signer)
    }

    fun parseChallenge(raw: ByteArray, expected: ExpectedRelayChallenge): RelayChallenge {
        val outer = StrictJson.parseObject(raw, JsonBounds(MAX_RELAY_CONTROL_BYTES))
        requireKeys(outer, setOf("type", "signed"), NetworkError.CHALLENGE_INVALID)
        if (outer.stringValue("type", NetworkError.CHALLENGE_INVALID) != "route.challenge") {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay message is not a challenge")
        }
        val challenge = parseSigned(outer.objectValue("signed", NetworkError.CHALLENGE_INVALID))
        validate(challenge, expected, consume = true)
        return challenge
    }

    fun encodeProof(challenge: RelayChallenge, signer: RelayProofSigner): ByteArray {
        val signed = challenge.signedJson()
        val signature = try {
            signer.sign(StrictJson.canonicalize(signed))
        } catch (error: Exception) {
            throw NetworkException(NetworkError.INVALID_SIGNATURE, "Relay proof signing failed", error)
        }
        if (signature.size > 80) throw NetworkException(NetworkError.INVALID_SIGNATURE, "Relay proof signature is invalid")
        val proof = JsonObject(
            mapOf(
                "type" to JsonPrimitive("route.proof"),
                "signed" to signed,
                "signature" to JsonPrimitive(encodeBase64Url(signature)),
            ),
        )
        val encoded = StrictJson.canonicalize(proof)
        if (encoded.size > MAX_RELAY_CONTROL_BYTES) throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay proof exceeds its bound")
        return encoded
    }

    fun parseAndVerifyProof(raw: ByteArray, expected: ExpectedRelayChallenge, publicKey: PublicKey): RelayChallenge {
        requireP256(publicKey)
        val proof = StrictJson.parseObject(raw, JsonBounds(MAX_RELAY_CONTROL_BYTES))
        requireKeys(proof, setOf("type", "signed", "signature"), NetworkError.CHALLENGE_INVALID)
        if (proof.stringValue("type", NetworkError.CHALLENGE_INVALID) != "route.proof") {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay message is not a proof")
        }
        val signed = proof.objectValue("signed", NetworkError.CHALLENGE_INVALID)
        val challenge = parseSigned(signed)
        val signatureBytes = decodeBase64Url(proof.stringValue("signature", NetworkError.CHALLENGE_INVALID), 80)
        val valid = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(StrictJson.canonicalize(signed))
                verify(signatureBytes)
            }
        } catch (_: Exception) {
            false
        }
        if (!valid) throw NetworkException(NetworkError.INVALID_SIGNATURE, "Relay proof signature is invalid")
        validate(challenge, expected, consume = true)
        return challenge
    }

    private fun parseSigned(value: JsonObject): RelayChallenge {
        val required = setOf("audience", "routeId", "keyId", "nonce", "expiresAt")
        if (value.keys != required && value.keys != required + "rendezvousId") {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay challenge fields are invalid")
        }
        val audience = RelayAudience.fromWire(value.stringValue("audience", NetworkError.CHALLENGE_INVALID))
        val routeId = value.opaqueId("routeId", NetworkError.CHALLENGE_INVALID)
        val keyId = value.opaqueId("keyId", NetworkError.CHALLENGE_INVALID)
        val nonce = decodeBase64Url(value.stringValue("nonce", NetworkError.CHALLENGE_INVALID), RELAY_NONCE_BYTES, RELAY_NONCE_BYTES)
        val expiresAtWire = value.stringValue("expiresAt", NetworkError.CHALLENGE_INVALID)
        val expiresAt = parseInstant(expiresAtWire, NetworkError.CHALLENGE_INVALID)
        val rendezvousId = value["rendezvousId"]?.let {
            value.opaqueId("rendezvousId", NetworkError.CHALLENGE_INVALID)
        }
        return RelayChallenge(audience, routeId, keyId, nonce, expiresAt, rendezvousId, expiresAtWire)
    }

    private fun validate(challenge: RelayChallenge, expected: ExpectedRelayChallenge, consume: Boolean) {
        if (
            challenge.audience != expected.audience ||
            challenge.routeId != expected.routeId ||
            challenge.keyId != expected.keyId ||
            challenge.rendezvousId != expected.rendezvousId
        ) {
            throw NetworkException(NetworkError.CHALLENGE_INVALID, "Relay challenge does not match the requested route")
        }
        val now = clock.instant()
        if (!challenge.expiresAt.isAfter(now) || challenge.expiresAt.isAfter(now.plus(MAX_RELAY_CHALLENGE_LIFETIME))) {
            throw NetworkException(NetworkError.CHALLENGE_EXPIRED, "Relay challenge is expired or exceeds its lifetime")
        }
        if (consume) {
            replays.consume(
                listOf(
                    challenge.audience.wireValue,
                    challenge.routeId,
                    challenge.keyId,
                    challenge.rendezvousId.orEmpty(),
                    encodeBase64Url(challenge.nonce),
                ).joinToString("\u0000"),
                now,
                NetworkError.CHALLENGE_REPLAYED,
            )
        }
    }
}
