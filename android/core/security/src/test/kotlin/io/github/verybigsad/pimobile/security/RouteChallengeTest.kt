package io.github.verybigsad.pimobile.security

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class RouteChallengeTest {
    private val now = Instant.parse("2030-01-01T00:00:00Z")

    @Test
    fun canonicalizesOnlyExactDeviceDataChallenge() {
        val challenge = RouteChallenge.parse(
            """{"routeId":"route-1","nonce":"${Base64Url.encode(ByteArray(32) { 7 })}","expiresAt":"2030-01-01T00:00:30Z","rendezvousId":"rv-1","keyId":"device-key-1","audience":"device-data"}""",
            now,
        )
        assertThat(challenge.canonicalSignedPayload().toString(Charsets.UTF_8)).isEqualTo(
            """{"audience":"device-data","expiresAt":"2030-01-01T00:00:30Z","keyId":"device-key-1","nonce":"${Base64Url.encode(ByteArray(32) { 7 })}","rendezvousId":"rv-1","routeId":"route-1"}""",
        )
    }

    @Test
    fun rejectsWrongAudienceBoundsDuplicatesAndUnknownFields() {
        val valid = """{"audience":"device-data","routeId":"route-1","keyId":"device-key-1","rendezvousId":"rv-1","nonce":"${Base64Url.encode(ByteArray(32))}","expiresAt":"2030-01-01T00:00:30Z"}"""
        assertFails { RouteChallenge.parse(valid.replace("device-data", "control"), now) }
        assertFails { RouteChallenge.parse(valid.replace("00:00:30Z", "00:00:31Z"), now) }
        assertFails { RouteChallenge.parse(valid.replace("\"routeId\":", "\"routeId\":\"duplicate\",\"routeId\":"), now) }
        assertFails { RouteChallenge.parse(valid.dropLast(1) + ",\"extra\":true}", now) }
        assertFails { RouteChallenge.parse("{" + " ".repeat(16 * 1024) + "}", now) }
    }

    private fun assertFails(block: () -> Unit) {
        assertThat(runCatching(block).isFailure).isTrue()
    }
}
