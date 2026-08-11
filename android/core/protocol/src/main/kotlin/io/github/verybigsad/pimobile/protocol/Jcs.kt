package io.github.verybigsad.pimobile.protocol

import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun canonicalizeJson(value: JsonElement): String {
    assertJsonValue(value)
    return canonical(value)
}

fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

fun commandPayloadHash(
    sessionId: String,
    operation: String,
    payload: JsonObject,
    expectedLeafId: String? = null,
    includeExpectedLeafId: Boolean = false,
): String {
    if (includeExpectedLeafId && expectedLeafId != null && !Regex("^[0-9a-f]{8}$").matches(expectedLeafId)) {
        protocolViolation("expectedLeafId must be absent, null, or eight lowercase hex characters")
    }
    val values = linkedMapOf<String, JsonElement>(
        "sessionId" to JsonPrimitive(sessionId),
        "operation" to JsonPrimitive(operation),
        "payload" to payload,
    )
    if (includeExpectedLeafId) values["expectedLeafId"] = expectedLeafId?.let(::JsonPrimitive) ?: JsonNull
    return sha256Hex(canonicalizeJson(JsonObject(values)).encodeToByteArray())
}

private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.keys.sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { key -> "${quote(key)}:${canonical(value.getValue(key))}" }
    is JsonArray -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonical(it) }
    is JsonPrimitive -> when {
        value is JsonNull -> "null"
        value.isString -> quote(value.content)
        value.content == "true" || value.content == "false" -> value.content
        else -> canonicalNumber(value.content)
    }
}

private fun canonicalNumber(value: String): String {
    val number = value.toDoubleOrNull() ?: protocolViolation("JSON number must be finite")
    return ecmaScriptNumberToString(number)
}

/**
 * RFC 8785 number serialization: ECMAScript Number::toString with the shortest
 * significand that round-trips, ties on the significand resolved to even digits.
 */
internal fun ecmaScriptNumberToString(value: Double): String {
    if (!value.isFinite()) protocolViolation("JSON number must be finite")
    if (value == 0.0) return "0"
    val sign = if (value < 0) "-" else ""
    val magnitude = kotlin.math.abs(value)
    val exact = java.math.BigDecimal(magnitude)
    var digits = ""
    var point = 0
    for (precision in 1..17) {
        val lower = exact.round(java.math.MathContext(precision, java.math.RoundingMode.FLOOR))
        val upper = exact.round(java.math.MathContext(precision, java.math.RoundingMode.CEILING))
        val candidates = when (exact.subtract(lower).compareTo(upper.subtract(exact))) {
            -1 -> listOf(lower, upper)
            1 -> listOf(upper, lower)
            else -> if (lower == upper) listOf(lower) else {
                val lowerEven = lower.stripTrailingZeros().unscaledValue().mod(BigIntegerTwo).signum() == 0
                if (lowerEven) listOf(lower, upper) else listOf(upper, lower)
            }
        }
        for (candidate in candidates) {
            val shortest = candidate.stripTrailingZeros()
            if (shortest.toString().toDouble() != magnitude) continue
            digits = shortest.unscaledValue().abs().toString()
            point = digits.length - shortest.scale()
            break
        }
        if (digits.isNotEmpty()) break
    }
    if (digits.isEmpty()) protocolViolation("JSON number must be finite")
    val length = digits.length
    val body = when {
        point in length..21 -> digits + "0".repeat(point - length)
        point in 1..21 -> digits.substring(0, point) + "." + digits.substring(point)
        point in -5..0 -> "0." + "0".repeat(-point) + digits
        length == 1 -> digits + "e" + exponentSign(point) + kotlin.math.abs(point - 1)
        else -> digits.substring(0, 1) + "." + digits.substring(1) + "e" + exponentSign(point) + kotlin.math.abs(point - 1)
    }
    return sign + body
}

private val BigIntegerTwo = BigInteger.valueOf(2)

private fun exponentSign(point: Int): String = if (point - 1 >= 0) "+" else "-"

private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { character -> when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000c' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
    } }
    append('"')
}
