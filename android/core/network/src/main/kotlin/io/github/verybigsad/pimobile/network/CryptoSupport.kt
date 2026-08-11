package io.github.verybigsad.pimobile.network

import java.security.AlgorithmParameters
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal val opaqueIdPattern = Regex("^[A-Za-z0-9._-]{1,128}$")

internal fun requireP256(key: PublicKey) {
    val ec = key as? ECPublicKey
        ?: throw NetworkException(NetworkError.INVALID_SIGNATURE, "Public key is not EC")
    if (!isP256(ec.params)) throw NetworkException(NetworkError.INVALID_SIGNATURE, "Public key is not P-256")
}

internal fun isP256(parameters: ECParameterSpec): Boolean {
    val expected = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    return parameters.curve == expected.curve &&
        parameters.generator == expected.generator &&
        parameters.order == expected.order &&
        parameters.cofactor == expected.cofactor
}

internal fun decodeBase64Url(value: String, maxDecodedBytes: Int, exactBytes: Int? = null): ByteArray {
    if (value.isEmpty() || value.length > encodedLength(maxDecodedBytes) || !base64UrlPattern.matches(value)) {
        throw NetworkException(NetworkError.MALFORMED_JSON, "base64url value is invalid")
    }
    val decoded = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        throw NetworkException(NetworkError.MALFORMED_JSON, "base64url value is invalid")
    }
    if (decoded.size > maxDecodedBytes || exactBytes != null && decoded.size != exactBytes || encodeBase64Url(decoded) != value) {
        throw NetworkException(NetworkError.MALFORMED_JSON, "base64url value is not canonical")
    }
    return decoded
}

internal fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

internal fun parseInstant(value: String, error: NetworkError): Instant = try {
    Instant.parse(value)
} catch (_: Exception) {
    throw NetworkException(error, "Timestamp is invalid")
}

internal fun requireUuid(value: String): UUID {
    val parsed = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw NetworkException(NetworkError.INVITATION_INVALID, "Invitation ID is invalid")
    }
    if (parsed.toString() != value) {
        throw NetworkException(NetworkError.INVITATION_INVALID, "Invitation ID is not canonical")
    }
    return parsed
}

internal fun equalBytes(first: ByteArray, second: ByteArray): Boolean = MessageDigest.isEqual(first, second)

private val base64UrlPattern = Regex("^[A-Za-z0-9_-]+$")

private fun encodedLength(bytes: Int): Int = (bytes * 4 + 2) / 3
