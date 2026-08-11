package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class RelayProofTest {
    private val now = Instant.parse("2026-08-09T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(256)
        generateKeyPair()
    }
    private val expected = ExpectedRelayChallenge(RelayAudience.DEVICE_DATA, "route-1", "device-1")

    @Test
    fun validatesChallengeEncodesJcsDerProofAndVerifiesIt() {
        val codec = RelayProofCodec(clock)
        val rawChallenge = challenge(now.plusSeconds(30))

        val parsed = codec.parseChallenge(rawChallenge, expected)
        val proof = codec.encodeProof(parsed, RelayProofSigner { payload ->
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        })

        val proofJson = StrictJson.parseObject(proof, JsonBounds(MAX_RELAY_CONTROL_BYTES))
        assertThat(proof).isEqualTo(StrictJson.canonicalize(proofJson))
        val verified = RelayProofCodec(clock).parseAndVerifyProof(proof, expected, keyPair.public)
        assertThat(verified.routeId).isEqualTo("route-1")
        assertThat(verified.nonce).hasLength(32)
    }

    @Test
    fun rejectsMismatchExpiryReplayAndDuplicateFields() {
        val codec = RelayProofCodec(clock)
        val valid = challenge(now.plusSeconds(20))
        codec.parseChallenge(valid, expected)
        val replay = runCatching { codec.parseChallenge(valid, expected) }.exceptionOrNull() as NetworkException
        assertThat(replay.code).isEqualTo(NetworkError.CHALLENGE_REPLAYED)

        val mismatch = runCatching {
            RelayProofCodec(clock).parseChallenge(valid, expected.copy(routeId = "route-2"))
        }.exceptionOrNull() as NetworkException
        assertThat(mismatch.code).isEqualTo(NetworkError.CHALLENGE_INVALID)

        val future = runCatching {
            RelayProofCodec(clock).parseChallenge(challenge(now.plusSeconds(31)), expected)
        }.exceptionOrNull() as NetworkException
        assertThat(future.code).isEqualTo(NetworkError.CHALLENGE_EXPIRED)

        val duplicate = valid.toString(Charsets.UTF_8).replace("\"routeId\":", "\"routeId\":\"other\",\"routeId\":")
        val duplicateError = runCatching {
            RelayProofCodec(clock).parseChallenge(duplicate.encodeToByteArray(), expected)
        }.exceptionOrNull() as NetworkException
        assertThat(duplicateError.code).isEqualTo(NetworkError.MALFORMED_JSON)
    }

    @Test
    fun bindsMacDataProofToRendezvous() {
        val macExpected = ExpectedRelayChallenge(RelayAudience.MAC_DATA, "route-1", "key-1", "rv-1")
        val raw = challenge(now.plusSeconds(20), RelayAudience.MAC_DATA, "key-1", "rv-1")
        val parsed = RelayProofCodec(clock).parseChallenge(raw, macExpected)
        val proof = RelayProofCodec(clock).encodeProof(parsed, RelayProofSigner { payload ->
            java.security.Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(payload)
                sign()
            }
        })

        val wrong = runCatching {
            RelayProofCodec(clock).parseAndVerifyProof(proof, macExpected.copy(rendezvousId = "rv-2"), keyPair.public)
        }.exceptionOrNull() as NetworkException
        assertThat(wrong.code).isEqualTo(NetworkError.CHALLENGE_INVALID)
    }

    @Test
    fun selfIssuedDeviceDataProofRoundTripsAndIsVerifiableByRelay() {
        val codec = RelayProofCodec(clock)
        val proof = codec.encodeSelfIssuedProof(
            RelayAudience.DEVICE_DATA,
            "route-1",
            "device-1",
            RelayProofSigner { payload ->
                java.security.Signature.getInstance("SHA256withECDSA").run {
                    initSign(keyPair.private)
                    update(payload)
                    sign()
                }
            },
            nonce = ByteArray(32) { 9 },
        )

        val verified = RelayProofCodec(clock).parseAndVerifyProof(proof, expected, keyPair.public)
        assertThat(verified.audience).isEqualTo(RelayAudience.DEVICE_DATA)
        assertThat(verified.rendezvousId).isNull()
        assertThat(verified.expiresAt).isEqualTo(now.plusSeconds(25))
    }

    @Test
    fun selfIssuedProofRejectsChallengeBoundAudiencesAndBadFields() {
        val codec = RelayProofCodec(clock)
        val signer = RelayProofSigner { it }
        for (audience in listOf(RelayAudience.CONTROL, RelayAudience.MAC_DATA)) {
            val error = runCatching {
                codec.encodeSelfIssuedProof(audience, "route-1", "device-1", signer)
            }.exceptionOrNull() as NetworkException
            assertThat(error.code).isEqualTo(NetworkError.CHALLENGE_INVALID)
        }
        assertThat(
            runCatching { codec.encodeSelfIssuedProof(RelayAudience.DEVICE_DATA, "route 1", "device-1", signer) }.isFailure,
        ).isTrue()
        assertThat(
            runCatching {
                codec.encodeSelfIssuedProof(RelayAudience.DEVICE_DATA, "route-1", "device-1", signer, nonce = ByteArray(16))
            }.isFailure,
        ).isTrue()
    }

    private fun challenge(
        expiry: Instant,
        audience: RelayAudience = RelayAudience.DEVICE_DATA,
        keyId: String = "device-1",
        rendezvousId: String? = null,
    ): ByteArray {
        val values = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "audience" to JsonPrimitive(audience.wireValue),
            "routeId" to JsonPrimitive("route-1"),
            "keyId" to JsonPrimitive(keyId),
            "nonce" to JsonPrimitive(encodeBase64Url(ByteArray(32) { 3 })),
            "expiresAt" to JsonPrimitive(expiry.toString()),
        )
        if (rendezvousId != null) values["rendezvousId"] = JsonPrimitive(rendezvousId)
        return StrictJson.canonicalize(
            JsonObject(mapOf("type" to JsonPrimitive("route.challenge"), "signed" to JsonObject(values))),
        )
    }
}
