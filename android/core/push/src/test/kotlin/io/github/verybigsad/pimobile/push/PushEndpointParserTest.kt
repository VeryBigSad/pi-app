package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.data.PushEndpoint

class PushEndpointParserTest {
    @Test
    fun acceptsHttpsEndpointWithoutWebPushKeys() {
        val result = parse(PushEndpoint("https://push.example/up/opaque-token?up=1", null, false))

        assertThat(result).isInstanceOf(EndpointParseResult.Valid::class.java)
        val endpoint = (result as EndpointParseResult.Valid).endpoint
        assertThat(endpoint.url).isEqualTo("https://push.example/up/opaque-token?up=1")
        assertThat(endpoint.instance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(endpoint.temporary).isFalse()
        assertThat(endpoint.publicKey).isNull()
        assertThat(endpoint.authSecret).isNull()
    }

    @Test
    fun acceptsExactWebPushKeyShapes() {
        val result = parse(
            PushEndpoint(
                "https://push.example/up/token",
                PublicKeySet("A".repeat(87), "b".repeat(22)),
                true,
            ),
        )

        val endpoint = (result as EndpointParseResult.Valid).endpoint
        assertThat(endpoint.temporary).isTrue()
        assertThat(endpoint.publicKey).hasLength(87)
        assertThat(endpoint.authSecret).hasLength(22)
    }

    @Test
    fun rejectsWrongInstanceBeforeEndpointUse() {
        val result = PushEndpointParser.parse(
            PushEndpoint("https://push.example/up/token", null, false),
            "another-instance",
        )

        assertThat(result).isEqualTo(EndpointParseResult.Invalid(EndpointInvalidReason.WRONG_INSTANCE))
    }

    @Test
    fun rejectsEmptyAndOversizedUrls() {
        assertInvalid("", EndpointInvalidReason.URL_EMPTY)
        assertInvalid(
            "https://push.example/" + "a".repeat(PushEndpointParser.MAX_ENDPOINT_CHARS),
            EndpointInvalidReason.URL_TOO_LARGE,
        )
    }

    @Test
    fun rejectsNonHttpsEndpoints() {
        listOf(
            "http://push.example/up/token",
            "ws://push.example/up/token",
            "file:///tmp/token",
            "push.example/up/token",
        ).forEach { url -> assertInvalid(url, EndpointInvalidReason.URL_NOT_HTTPS) }
    }

    @Test
    fun rejectsMalformedAuthorityAndFragments() {
        listOf(
            "https://user:password@push.example/up/token",
            "https://push.example/up/token#fragment",
            "https:///up/token",
            "https://exa mple/up/token",
        ).forEach { url -> assertInvalid(url, EndpointInvalidReason.URL_INVALID) }
    }

    @Test
    fun rejectsMalformedWebPushKeys() {
        listOf(
            PublicKeySet("A".repeat(86), "b".repeat(22)),
            PublicKeySet("A".repeat(88), "b".repeat(22)),
            PublicKeySet("A".repeat(87), "b".repeat(21)),
            PublicKeySet("A".repeat(87), "b".repeat(23)),
            PublicKeySet("+".repeat(87), "b".repeat(22)),
            PublicKeySet("A".repeat(87), "=".repeat(22)),
        ).forEach { keys ->
            val result = parse(PushEndpoint("https://push.example/up/token", keys, false))
            assertThat(result).isEqualTo(EndpointParseResult.Invalid(EndpointInvalidReason.INVALID_KEY_SET))
        }
    }

    @Test
    fun endpointStringRepresentationNeverContainsEndpointOrKeys() {
        val endpoint = (parse(
            PushEndpoint(
                "https://push.example/up/private-token",
                PublicKeySet("A".repeat(87), "b".repeat(22)),
                false,
            ),
        ) as EndpointParseResult.Valid).endpoint

        assertThat(endpoint.toString()).doesNotContain("push.example")
        assertThat(endpoint.toString()).doesNotContain("private-token")
        assertThat(endpoint.toString()).doesNotContain("A".repeat(20))
        assertThat(endpoint.toString()).doesNotContain("b".repeat(20))
    }

    private fun parse(endpoint: PushEndpoint): EndpointParseResult =
        PushEndpointParser.parse(endpoint, UnifiedPushClient.PUSH_INSTANCE)

    private fun assertInvalid(url: String, reason: EndpointInvalidReason) {
        assertThat(parse(PushEndpoint(url, null, false))).isEqualTo(EndpointParseResult.Invalid(reason))
    }
}
