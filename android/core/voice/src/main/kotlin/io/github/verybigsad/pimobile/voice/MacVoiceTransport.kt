package io.github.verybigsad.pimobile.voice

interface MacVoiceTransport {
    suspend fun startSession(descriptor: VoiceSessionDescriptor)

    suspend fun sendPcm(packet: VoicePcmPacket)

    suspend fun finishSession(sessionId: String)

    suspend fun cancelSession(sessionId: String)

    /**
     * Installs the editable-draft surface receiving ordered, deduplicated host
     * transcripts (`voice.partial` / `voice.finish`). Null detaches; drafts for
     * sessions without a sink are discarded. Cancelling a session suppresses
     * every later transcript for it.
     */
    fun attachTranscriptSink(sink: VoiceTranscriptSink?)
}

class VoicePcmPacket internal constructor(
    val sessionId: String,
    val chunkSequence: Long,
    val packetSequence: Int,
    val byteOffset: Int,
    val endOfChunk: Boolean,
    val finalChunk: Boolean,
    pcm16Le: ByteArray,
) {
    private val bytes = pcm16Le.copyOf()

    val sizeBytes: Int
        get() = bytes.size

    init {
        require(isVoiceSessionId(sessionId))
        require(chunkSequence >= 0)
        require(packetSequence >= 0)
        require(byteOffset >= 0 && byteOffset % VoiceAudioSpec.BYTES_PER_SAMPLE == 0)
        require(bytes.isNotEmpty() && bytes.size <= VoiceAudioSpec.MAX_PACKET_BYTES)
        require(bytes.size % VoiceAudioSpec.BYTES_PER_SAMPLE == 0)
        require(!finalChunk || endOfChunk)
    }

    fun copyPcm16Le(): ByteArray = bytes.copyOf()
}

class VoicePacketizer(
    private val maximumPacketBytes: Int = VoiceAudioSpec.MAX_PACKET_BYTES,
) {
    init {
        require(maximumPacketBytes in VoiceAudioSpec.BYTES_PER_SAMPLE..VoiceAudioSpec.MAX_PACKET_BYTES)
        require(maximumPacketBytes % VoiceAudioSpec.BYTES_PER_SAMPLE == 0)
    }

    fun packetize(sessionId: String, chunk: VoiceAudioChunk): List<VoicePcmPacket> {
        val bytes = chunk.copyPcm16Le()
        val packets = ArrayList<VoicePcmPacket>((bytes.size + maximumPacketBytes - 1) / maximumPacketBytes)
        var offset = 0
        var packetSequence = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + maximumPacketBytes)
            val endOfChunk = end == bytes.size
            packets += VoicePcmPacket(
                sessionId = sessionId,
                chunkSequence = chunk.sequence,
                packetSequence = packetSequence++,
                byteOffset = offset,
                endOfChunk = endOfChunk,
                finalChunk = chunk.final && endOfChunk,
                pcm16Le = bytes.copyOfRange(offset, end),
            )
            offset = end
        }
        return packets
    }
}
