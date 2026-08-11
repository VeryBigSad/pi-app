package io.github.verybigsad.pimobile.security

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.protocol.canonicalizeJson
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class PairingInvitationTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")

    @Test
    fun parsesCanonicalBoundedInvitationAndExposesDefensiveCryptographicData() {
        val fixture = invitation()
        val parsed = PairingInvitation.parse(fixture.uri, now)

        assertThat(parsed.version).isEqualTo(1)
        assertThat(parsed.routeId).isEqualTo("route-1")
        assertThat(parsed.routeKeyId).isEqualTo("mac-key-1")
        assertThat(parsed.macInstanceId.toString()).isEqualTo("550e8400-e29b-41d4-a716-446655440042")
        assertThat(parsed.relayUrl.toString()).isEqualTo("wss://relay.example.test/pair")
        assertThat(parsed.directCandidates).containsExactly(
            DirectCandidate("mac.local", 443),
            DirectCandidate("2001:db8::1", 8443),
        ).inOrder()
        assertThat(parsed.verifyRouteSignature(fixture.keyPair.public)).isTrue()
        assertThat(parsed.serverCertificateSha256).isEqualTo("02".repeat(32))
        assertThat(parsed.serverCertificateFingerprint).isEqualTo(CertificateFingerprint.fromHex("02".repeat(32)))
        assertThat(parsed.serverCertificateFingerprint.bytes()).isEqualTo(ByteArray(32) { 2 })

        val nonce = parsed.nonce()
        nonce.fill(0)
        assertThat(parsed.nonce()).isEqualTo(ByteArray(32) { 1 })
        val signature = parsed.signatureDer()
        signature.fill(0)
        assertThat(parsed.verifyRouteSignature(fixture.keyPair.public)).isTrue()
    }

    @Test
    fun rejectsExpiredTooLongNoncanonicalAndUnknownInvitationData() {
        val expired = invitation(expiresAt = "2029-12-31T23:59:59Z")
        assertFails { PairingInvitation.parse(expired.uri, now) }
        val tooLong = invitation(expiresAt = "2030-01-01T00:05:01Z")
        assertFails { PairingInvitation.parse(tooLong.uri, now) }

        val fixture = invitation()
        val decoded = Base64Url.decode(fixture.uri.substringAfter("&d="), 2048).toString(Charsets.UTF_8)
        val noncanonical = decoded.replaceFirst("{", "{ ")
        assertFails { PairingInvitation.parse(uri(noncanonical), now) }
        val unknown = decoded.replaceFirst("\"signature\":", "\"unknown\":1,\"signature\":")
        assertFails { PairingInvitation.parse(uri(unknown), now) }
        val duplicate = decoded.replaceFirst("\"signature\":", "\"signature\":\"AQ\",\"signature\":")
        assertFails { PairingInvitation.parse(uri(duplicate), now) }
    }

    @Test
    fun rejectsMalformedUriFingerprintAndSignature() {
        val fixture = invitation()
        assertFails { PairingInvitation.parse(fixture.uri + "&v=1", now) }
        assertFails { PairingInvitation.parse(fixture.uri.replace("pimobile://", "https://"), now) }

        val decoded = Base64Url.decode(fixture.uri.substringAfter("&d="), 2048).toString(Charsets.UTF_8)
        val badFingerprint = decoded.replace("02".repeat(32), "02".repeat(31))
        assertFails { PairingInvitation.parse(uri(badFingerprint), now) }
        val signatureText = Regex("\"signature\":\"([^\"]+)\"").find(decoded)!!.groupValues[1]
        val badSignature = decoded.replace(signatureText, Base64Url.encode(byteArrayOf(0x30, 0x00)))
        assertFails { PairingInvitation.parse(uri(badSignature), now) }
    }

    @Test
    fun rejectsMissingMalformedOrNonV4MacInstanceId() {
        val without = invitation(macInstanceId = null)
        assertFails { PairingInvitation.parse(without.uri, now) }
        val malformed = invitation(macInstanceId = "not-a-uuid")
        assertFails { PairingInvitation.parse(malformed.uri, now) }
        val nonV4 = invitation(macInstanceId = "550e8400-e29b-11d4-a716-446655440042")
        assertFails { PairingInvitation.parse(nonV4.uri, now) }
    }

    private fun invitation(
        expiresAt: String = "2030-01-01T00:05:00Z",
        macInstanceId: String? = "550e8400-e29b-41d4-a716-446655440042",
    ): InvitationFixture {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val signed = JsonObject(
            buildMap {
                put("version", JsonPrimitive(1))
                put("relayUrl", JsonPrimitive("wss://relay.example.test/pair"))
                put("routeId", JsonPrimitive("route-1"))
                put("routeKeyId", JsonPrimitive("mac-key-1"))
                put("invitationId", JsonPrimitive("550e8400-e29b-41d4-a716-446655440000"))
                macInstanceId?.let { put("macInstanceId", JsonPrimitive(it)) }
                put("expiresAt", JsonPrimitive(expiresAt))
                put("nonce", JsonPrimitive(Base64Url.encode(ByteArray(32) { 1 })))
                put("serverCertificateSha256", JsonPrimitive("02".repeat(32)))
                put(
                    "directCandidates",
                    JsonArray(
                        listOf(
                            JsonObject(mapOf("host" to JsonPrimitive("mac.local"), "port" to JsonPrimitive(443))),
                            JsonObject(mapOf("host" to JsonPrimitive("2001:db8::1"), "port" to JsonPrimitive(8443))),
                        ),
                    ),
                )
            },
        )
        val canonical = canonicalizeJson(signed).encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(canonical)
            sign()
        }
        val envelope = JsonObject(
            mapOf(
                "signed" to signed,
                "signature" to JsonPrimitive(Base64Url.encode(signature)),
            ),
        )
        return InvitationFixture(uri(canonicalizeJson(envelope)), keyPair)
    }

    private fun uri(envelope: String): String = "pimobile://pair?v=1&d=${Base64Url.encode(envelope.encodeToByteArray())}"

    private fun assertFails(block: () -> Unit) {
        assertThat(runCatching(block).isFailure).isTrue()
    }
}

private data class InvitationFixture(
    val uri: String,
    val keyPair: java.security.KeyPair,
)
