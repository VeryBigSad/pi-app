package io.github.verybigsad.pimobile.voice

import io.github.verybigsad.pimobile.wire.HostConnector
import io.github.verybigsad.pimobile.wire.WireMessages
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MacVoiceTransport over the live protocol connection.
 *
 * Adapter gap: PIMB AUDIO_PCM frames carry a stream-global (sequence, offset) pair with no
 * VAD chunk-boundary markers, while VoicePcmPacket metadata is chunk-relative (integration
 * plan §10). This adapter maps packets onto a stream-global counter and flattens the
 * endOfChunk/finalChunk flags, which the Mac chunking contract still needs; the host also
 * currently rejects AudioPcm, so failures surface through voice.error/transport failure
 * rather than fabricated transcripts. Chunk boundaries must be added to the wire contract.
 */
class GatewayMacVoiceTransport(
    private val connector: () -> HostConnector?,
) : MacVoiceTransport {
    private class StreamCounters {
        val sequence = AtomicLong(0)
        val offset = java.util.concurrent.atomic.AtomicReference(0uL)
    }

    private val counters = ConcurrentHashMap<String, StreamCounters>()
    @Volatile
    private var sink: VoiceTranscriptSink? = null

    override suspend fun startSession(descriptor: VoiceSessionDescriptor) {
        val host = connector() ?: throw VoiceTransportUnavailable()
        host.send("voice.start", WireMessages.voiceStart(descriptor.sessionId))
        counters[descriptor.sessionId] = StreamCounters()
    }

    override suspend fun sendPcm(packet: VoicePcmPacket) {
        val host = connector() ?: throw VoiceTransportUnavailable()
        val counter = counters[packet.sessionId] ?: throw VoiceTransportUnavailable()
        val sequence = counter.sequence.getAndIncrement()
        val offset = counter.offset.getAndUpdate { it + packet.sizeBytes.toULong() }
        host.sendVoicePcm(packet.sessionId, sequence, offset, packet.copyPcm16Le())
    }

    override suspend fun finishSession(sessionId: String) {
        counters.remove(sessionId)
        connector()?.send("voice.finish", WireMessages.streamControl("voice.finish", sessionId))
            ?: throw VoiceTransportUnavailable()
    }

    override suspend fun cancelSession(sessionId: String) {
        counters.remove(sessionId)
        connector()?.send("voice.cancel", WireMessages.streamControl("voice.cancel", sessionId))
    }

    override fun attachTranscriptSink(sink: VoiceTranscriptSink?) {
        this.sink = sink
    }

    fun transcriptSink(): VoiceTranscriptSink? = sink

    class VoiceTransportUnavailable : Exception("voice transport requires a READY host connection")
}
