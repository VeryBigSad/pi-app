package io.github.verybigsad.pimobile.protocol

data class VoicePcmChunk(
    val sequence: ULong,
    val final: Boolean,
    val pcm16Le: ByteArray,
)

class VoicePcmStream(
    val streamId: String,
) {
    private var nextFrameSequence = 0L
    private var nextOffset = 0uL
    private var nextChunkSequence = 0uL
    private val parts = ArrayList<ByteArray>()
    private var chunkBytes = 0
    private var ended = false

    init {
        runCatching { uuidV4ToBytes(streamId) }.getOrElse { fail() }
    }

    fun accept(streamId: String, sequence: Long, offset: ULong, data: ByteArray) {
        if (
            ended || streamId != this.streamId || sequence != nextFrameSequence || offset != nextOffset ||
            sequence !in 0..0xffff_ffffL || data.isEmpty() || data.size > ProtocolConstants.maxBinaryDataBytes ||
            data.size % 2 != 0 || chunkBytes + data.size > ProtocolConstants.maxVoiceChunkBytes ||
            ULong.MAX_VALUE - nextOffset < data.size.toULong()
        ) fail()
        parts += data.copyOf()
        chunkBytes += data.size
        nextFrameSequence += 1
        nextOffset += data.size.toULong()
    }

    fun boundary(streamId: String, chunkSequence: String, final: Boolean): VoicePcmChunk {
        val sequence = runCatching { parseUint64(chunkSequence) }.getOrElse { fail() }
        if (
            ended || streamId != this.streamId || sequence != nextChunkSequence ||
            chunkBytes == 0 && !final || sequence == ULong.MAX_VALUE && !final
        ) fail()
        val pcm = ByteArray(chunkBytes)
        var offset = 0
        for (part in parts) {
            part.copyInto(pcm, offset)
            offset += part.size
        }
        parts.clear()
        chunkBytes = 0
        if (final) ended = true else nextChunkSequence += 1u
        return VoicePcmChunk(sequence, final, pcm)
    }

    fun cancel() {
        ended = true
        parts.clear()
        chunkBytes = 0
    }

    fun offset(): ULong = nextOffset

    private fun fail(): Nothing {
        cancel()
        throw ProtocolException(ProtocolErrorCode.STREAM_INVALID, "Voice PCM ordering, format, or bounds are invalid")
    }
}
