package io.github.verybigsad.pimobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val uuidV4Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val uint64Pattern = Regex("^(0|[1-9][0-9]{0,19})$")
private val leafIdPattern = Regex("^[0-9a-f]{8}$")

fun parseUint64(value: String): ULong {
    if (!uint64Pattern.matches(value)) protocolViolation("uint64 must be canonical decimal text")
    return value.toULongOrNull() ?: protocolViolation("uint64 exceeds its maximum")
}

fun formatUint64(value: ULong): String = value.toString()

fun assertLeafId(value: String?) {
    if (value != null && !leafIdPattern.matches(value)) protocolViolation("Leaf ID must be null or eight lowercase hex characters")
}

fun uuidToBytes(uuid: String): ByteArray {
    if (!uuidPattern.matches(uuid)) protocolViolation("UUID must be lowercase canonical text")
    return uuid.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun uuidV4ToBytes(uuid: String): ByteArray {
    if (!uuidV4Pattern.matches(uuid)) protocolViolation("UUID must be a lowercase UUIDv4")
    return uuidToBytes(uuid)
}

fun bytesToUuid(bytes: ByteArray): String {
    if (bytes.size != 16) protocolViolation("UUID prefix must contain exactly 16 bytes")
    val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

fun readUint64BigEndian(bytes: ByteArray, offset: Int = 0): ULong {
    if (offset < 0 || offset + 8 > bytes.size) protocolViolation("Truncated uint64")
    return ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN).long.toULong()
}

fun writeUint64BigEndian(value: ULong): ByteArray = ByteBuffer.allocate(8)
    .order(ByteOrder.BIG_ENDIAN)
    .putLong(value.toLong())
    .array()
