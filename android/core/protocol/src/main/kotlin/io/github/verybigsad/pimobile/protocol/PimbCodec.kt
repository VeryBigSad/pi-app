package io.github.verybigsad.pimobile.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonObject

data class PimbFrame(
    val kind: FrameKind,
    val payload: ByteArray,
)

data class StreamPayload(
    val streamId: String,
    val sequence: Long,
    val offset: ULong,
    val data: ByteArray,
)

data class TerminalPayload(
    val terminalGeneration: ULong,
    val sequence: ULong,
    val data: ByteArray,
)

object PimbCodec {
    fun encode(kind: FrameKind, payload: ByteArray): ByteArray {
        assertPayload(kind, payload)
        return ByteBuffer.allocate(ProtocolConstants.headerBytes + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ProtocolConstants.magic)
            .put(ProtocolConstants.major.toByte())
            .put(kind.code.toByte())
            .putShort(0)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun decode(frame: ByteArray): PimbFrame {
        if (frame.size < ProtocolConstants.headerBytes) protocolViolation("Truncated PIMB header")
        val header = parseHeader(frame.copyOfRange(0, ProtocolConstants.headerBytes))
        if (frame.size != ProtocolConstants.headerBytes + header.length) protocolViolation("PIMB frame length does not match its header")
        val payload = frame.copyOfRange(ProtocolConstants.headerBytes, frame.size)
        assertPayload(header.kind, payload)
        return PimbFrame(header.kind, payload)
    }

    fun decodeJsonPayload(payload: ByteArray): JsonObject {
        if (payload.size > ProtocolConstants.maxJsonPayloadBytes) frameTooLarge("JSON payload exceeds its bound")
        return parseJsonObject(decodeUtf8Strict(payload))
    }

    fun encodeStreamPayload(value: StreamPayload): ByteArray {
        if (value.sequence !in 0..0xffff_ffffL || value.data.size > ProtocolConstants.maxBinaryDataBytes) {
            protocolViolation("Stream prefix is invalid")
        }
        return ByteBuffer.allocate(ProtocolConstants.streamPrefixBytes + value.data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(uuidToBytes(value.streamId))
            .putInt(value.sequence.toInt())
            .put(writeUint64BigEndian(value.offset))
            .put(value.data)
            .array()
    }

    fun decodeStreamPayload(payload: ByteArray): StreamPayload {
        if (payload.size < ProtocolConstants.streamPrefixBytes || payload.size - ProtocolConstants.streamPrefixBytes > ProtocolConstants.maxBinaryDataBytes) {
            protocolViolation("Stream payload is out of bounds")
        }
        val view = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val uuidBytes = ByteArray(16).also(view::get)
        return StreamPayload(
            bytesToUuid(uuidBytes),
            view.int.toLong() and 0xffff_ffffL,
            view.long.toULong(),
            payload.copyOfRange(ProtocolConstants.streamPrefixBytes, payload.size),
        )
    }

    fun encodeTerminalPayload(value: TerminalPayload): ByteArray {
        if (value.data.size > ProtocolConstants.maxBinaryDataBytes) protocolViolation("Terminal prefix is invalid")
        return ByteBuffer.allocate(ProtocolConstants.terminalPrefixBytes + value.data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(writeUint64BigEndian(value.terminalGeneration))
            .put(writeUint64BigEndian(value.sequence))
            .put(value.data)
            .array()
    }

    fun decodeTerminalPayload(payload: ByteArray): TerminalPayload {
        if (payload.size < ProtocolConstants.terminalPrefixBytes || payload.size - ProtocolConstants.terminalPrefixBytes > ProtocolConstants.maxBinaryDataBytes) {
            protocolViolation("Terminal payload is out of bounds")
        }
        val view = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return TerminalPayload(
            view.long.toULong(),
            view.long.toULong(),
            payload.copyOfRange(ProtocolConstants.terminalPrefixBytes, payload.size),
        )
    }

    internal fun assertPayload(kind: FrameKind, payload: ByteArray) {
        if (payload.size > ProtocolConstants.maxFramePayloadBytes) frameTooLarge("PIMB payload exceeds its bound")
        when (kind) {
            FrameKind.Json -> if (payload.size > ProtocolConstants.maxJsonPayloadBytes) frameTooLarge("JSON payload exceeds its bound")
            FrameKind.BlobChunk, FrameKind.AudioPcm -> if (payload.size < ProtocolConstants.streamPrefixBytes || payload.size - ProtocolConstants.streamPrefixBytes > ProtocolConstants.maxBinaryDataBytes) protocolViolation("Stream payload is out of bounds")
            FrameKind.TerminalBytes -> if (payload.size < ProtocolConstants.terminalPrefixBytes || payload.size - ProtocolConstants.terminalPrefixBytes > ProtocolConstants.maxBinaryDataBytes) protocolViolation("Terminal payload is out of bounds")
        }
    }

    internal fun parseHeader(header: ByteArray): Header {
        if (!ProtocolConstants.magic.indices.all { header[it] == ProtocolConstants.magic[it] }) protocolViolation("PIMB magic is invalid")
        if (header[4].toInt() and 0xff != ProtocolConstants.major) throw ProtocolException(ProtocolErrorCode.UNSUPPORTED_VERSION, "PIMB major version is unsupported")
        val kind = FrameKind.fromCode(header[5].toInt() and 0xff)
        val view = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        if (view.getShort(6).toInt() != 0) protocolViolation("PIMB flags are invalid")
        val length = view.getInt(8)
        if (length < 0 || length > ProtocolConstants.maxFramePayloadBytes) frameTooLarge("PIMB payload exceeds its bound")
        when (kind) {
            FrameKind.Json -> if (length > ProtocolConstants.maxJsonPayloadBytes) frameTooLarge("JSON payload exceeds its bound")
            FrameKind.BlobChunk, FrameKind.AudioPcm -> if (length < ProtocolConstants.streamPrefixBytes || length - ProtocolConstants.streamPrefixBytes > ProtocolConstants.maxBinaryDataBytes) protocolViolation("Stream payload length is out of bounds")
            FrameKind.TerminalBytes -> if (length < ProtocolConstants.terminalPrefixBytes || length - ProtocolConstants.terminalPrefixBytes > ProtocolConstants.maxBinaryDataBytes) protocolViolation("Terminal payload length is out of bounds")
        }
        return Header(kind, length)
    }

    internal data class Header(val kind: FrameKind, val length: Int)
}

class PimbFrameDecoder {
    private val headerBytes = ByteArray(ProtocolConstants.headerBytes)
    private var headerCount = 0
    private var header: PimbCodec.Header? = null
    private var payload: ByteArray? = null
    private var payloadCount = 0

    fun push(chunk: ByteArray): List<PimbFrame> {
        val frames = mutableListOf<PimbFrame>()
        var offset = 0
        while (offset < chunk.size) {
            if (header == null) {
                val count = minOf(ProtocolConstants.headerBytes - headerCount, chunk.size - offset)
                chunk.copyInto(headerBytes, headerCount, offset, offset + count)
                headerCount += count
                offset += count
                if (headerCount < ProtocolConstants.headerBytes) break
                header = PimbCodec.parseHeader(headerBytes)
                payload = ByteArray(requireNotNull(header).length)
            }

            val activeHeader = requireNotNull(header)
            val activePayload = requireNotNull(payload)
            val count = minOf(activeHeader.length - payloadCount, chunk.size - offset)
            chunk.copyInto(activePayload, payloadCount, offset, offset + count)
            payloadCount += count
            offset += count
            if (payloadCount != activeHeader.length) continue

            PimbCodec.assertPayload(activeHeader.kind, activePayload)
            frames += PimbFrame(activeHeader.kind, activePayload)
            headerCount = 0
            header = null
            payload = null
            payloadCount = 0
        }
        return frames
    }

    fun finish() {
        if (headerCount != 0 || header != null) protocolViolation("Truncated PIMB frame")
    }

    fun bufferedBytes(): Int = headerCount + payloadCount
}
