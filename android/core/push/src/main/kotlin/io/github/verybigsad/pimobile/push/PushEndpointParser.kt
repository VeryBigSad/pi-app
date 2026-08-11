package io.github.verybigsad.pimobile.push

import java.net.URI
import java.net.URISyntaxException
import org.unifiedpush.android.connector.data.PushEndpoint

internal sealed interface EndpointParseResult {
    data class Valid(val endpoint: UnifiedPushEndpoint) : EndpointParseResult
    data class Invalid(val reason: EndpointInvalidReason) : EndpointParseResult
}

internal object PushEndpointParser {
    const val MAX_ENDPOINT_CHARS = 4_096
    private const val PUBLIC_KEY_CHARS = 87
    private const val AUTH_SECRET_CHARS = 22

    fun parse(endpoint: PushEndpoint, instance: String): EndpointParseResult {
        if (instance != UnifiedPushClient.PUSH_INSTANCE) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.WRONG_INSTANCE)
        }
        val url = endpoint.url
        if (url.isEmpty()) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.URL_EMPTY)
        }
        if (url.length > MAX_ENDPOINT_CHARS) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.URL_TOO_LARGE)
        }
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.URL_INVALID)
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.URL_NOT_HTTPS)
        }
        if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.URL_INVALID)
        }

        val publicKey = endpoint.pubKeySet?.pubKey
        val authSecret = endpoint.pubKeySet?.auth
        if (!isValidKeySet(publicKey, authSecret)) {
            return EndpointParseResult.Invalid(EndpointInvalidReason.INVALID_KEY_SET)
        }
        return EndpointParseResult.Valid(
            UnifiedPushEndpoint(
                url = url,
                instance = instance,
                temporary = endpoint.temporary,
                publicKey = publicKey,
                authSecret = authSecret,
            ),
        )
    }

    private fun isValidKeySet(publicKey: String?, authSecret: String?): Boolean {
        if (publicKey == null && authSecret == null) {
            return true
        }
        return publicKey != null &&
            authSecret != null &&
            publicKey.length == PUBLIC_KEY_CHARS &&
            authSecret.length == AUTH_SECRET_CHARS &&
            publicKey.all { it.isAsciiBase64Url() } &&
            authSecret.all { it.isAsciiBase64Url() }
    }

    private fun Char.isAsciiBase64Url(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
}
