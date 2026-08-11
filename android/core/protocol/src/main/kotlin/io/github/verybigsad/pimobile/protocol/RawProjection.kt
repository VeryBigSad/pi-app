package io.github.verybigsad.pimobile.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val projectedKeys = listOf(
    "type",
    "assistantMessageEvent",
    "message",
    "toolCallId",
    "requestId",
    "willRetry",
    "attempt",
    "reason",
)

data class InlineRawRecord(
    val rawJson: String,
    val rawSize: String,
    val rawSha256: String,
    val projection: JsonObject,
)

fun projectPiRecord(record: JsonObject): JsonObject {
    val projection = buildJsonObject {
        projectedKeys.forEach { key -> record[key]?.let { put(key, it) } }
    }
    if (projection.toString().encodeToByteArray().size > ProtocolConstants.maxInlineRawBytes) frameTooLarge("Pi reducer projection exceeds its bound")
    return projection
}

fun projectRawPiJson(rawBytes: ByteArray): InlineRawRecord {
    if (rawBytes.size > ProtocolConstants.maxInlineRawBytes) frameTooLarge("Inline Pi record exceeds its bound")
    val rawJson = decodeUtf8Strict(rawBytes)
    val parsed = parseJsonObject(rawJson)
    return InlineRawRecord(rawJson, rawBytes.size.toString(), sha256Hex(rawBytes), projectPiRecord(parsed))
}

fun verifyInlineRawRecord(record: InlineRawRecord) {
    val rawBytes = record.rawJson.encodeToByteArray()
    if (rawBytes.size > ProtocolConstants.maxInlineRawBytes || record.rawSize != rawBytes.size.toString() || record.rawSha256 != sha256Hex(rawBytes)) {
        protocolViolation("Inline Pi record metadata is invalid")
    }
    if (projectPiRecord(parseJsonObject(record.rawJson)) != record.projection) protocolViolation("Inline Pi record projection is invalid")
}
