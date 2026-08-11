package io.github.verybigsad.pimobile.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val typePattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

data class Envelope(
    val value: JsonObject,
) {
    val type: String get() = (value.getValue("type") as JsonPrimitive).content
}

fun parseEnvelope(text: String): Envelope {
    val value = parseJsonObject(text)
    val version = value["v"] as? JsonObject
    val major = (version?.get("major") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    val minor = (version?.get("minor") as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    if (major != ProtocolConstants.major.toDouble() || minor == null || minor % 1.0 != 0.0 || minor !in 0.0..255.0) {
        throw ProtocolException(ProtocolErrorCode.UNSUPPORTED_VERSION, "Envelope version is unsupported")
    }
    val type = (value["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type == null || type.length !in 1..64 || !typePattern.matches(type)) protocolViolation("Envelope type is invalid")
    val messageId = (value["messageId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: protocolViolation("Envelope messageId is invalid")
    uuidV4ToBytes(messageId)
    val replyTo = value["replyTo"]
    if (replyTo !is JsonPrimitive || (replyTo.content != "null" && !replyTo.isString)) protocolViolation("Envelope replyTo is invalid")
    if (replyTo.isString) uuidV4ToBytes(replyTo.content)
    if (value["body"] !is JsonObject) protocolViolation("Envelope body must be an object")
    return Envelope(value)
}
