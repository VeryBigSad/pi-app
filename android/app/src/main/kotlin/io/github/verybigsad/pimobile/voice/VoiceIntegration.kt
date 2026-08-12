package io.github.verybigsad.pimobile.voice

import io.github.verybigsad.pimobile.wire.HostConnector
import io.github.verybigsad.pimobile.wire.WireMessages
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GatewayMacVoiceTransport(
    private val connector: () -> HostConnector?,
) : MacVoiceTransport {
    private class StreamCounters {
        val sendLock = Mutex()
        var frameSequence = 0L
        var streamOffset = 0uL
        var chunkSequence = 0L
        var packetSequence = 0
        var chunkOffset = 0
        var finalSent = false
    }

    private val counters = ConcurrentHashMap<String, StreamCounters>()
    @Volatile
    private var sink: VoiceTranscriptSink? = null

    override suspend fun startSession(descriptor: VoiceSessionDescriptor) {
        val host = connector() ?: throw VoiceTransportUnavailable()
        if (counters.isNotEmpty()) throw VoiceTransportUnavailable()
        val state = StreamCounters()
        if (counters.putIfAbsent(descriptor.sessionId, state) != null) throw VoiceTransportUnavailable()
        try {
            host.send("voice.start", WireMessages.voiceStart(descriptor.sessionId))
        } catch (error: Exception) {
            counters.remove(descriptor.sessionId, state)
            throw error
        }
    }

    override suspend fun sendPcm(packet: VoicePcmPacket) {
        val state = counters[packet.sessionId] ?: throw VoiceTransportUnavailable()
        state.sendLock.withLock {
            val host = connector() ?: throw VoiceTransportUnavailable()
            if (
                state.finalSent || packet.chunkSequence != state.chunkSequence ||
                packet.packetSequence != state.packetSequence || packet.byteOffset != state.chunkOffset ||
                state.frameSequence > UINT32_MAX || ULong.MAX_VALUE - state.streamOffset < packet.sizeBytes.toULong()
            ) throw VoiceTransportUnavailable()
            host.sendVoicePcm(packet.sessionId, state.frameSequence, state.streamOffset, packet.copyPcm16Le())
            state.frameSequence += 1
            state.streamOffset += packet.sizeBytes.toULong()
            state.packetSequence += 1
            state.chunkOffset += packet.sizeBytes
            if (packet.endOfChunk) {
                host.send(
                    "voice.audio",
                    WireMessages.voiceAudio(packet.sessionId, packet.chunkSequence, packet.finalChunk),
                )
                state.chunkSequence += 1
                state.packetSequence = 0
                state.chunkOffset = 0
                state.finalSent = packet.finalChunk
            }
        }
    }

    override suspend fun finishSession(sessionId: String) {
        val state = counters[sessionId] ?: throw VoiceTransportUnavailable()
        state.sendLock.withLock {
            if (!state.finalSent) {
                val host = connector() ?: throw VoiceTransportUnavailable()
                host.send("voice.audio", WireMessages.voiceAudio(sessionId, state.chunkSequence, final = true))
                state.chunkSequence += 1
                state.finalSent = true
            }
        }
        counters.remove(sessionId, state)
    }

    override suspend fun cancelSession(sessionId: String) {
        val state = counters.remove(sessionId) ?: return
        state.sendLock.withLock {
            if (!state.finalSent) {
                connector()?.send("voice.cancel", WireMessages.voiceCancel(sessionId, "client_cancel"))
                state.finalSent = true
            }
        }
    }

    override fun attachTranscriptSink(sink: VoiceTranscriptSink?) {
        this.sink = sink
    }

    fun transcriptSink(): VoiceTranscriptSink? = sink

    class VoiceTransportUnavailable : Exception("voice transport requires one READY host stream")

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
    }
}
