package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.wire.HostConnector
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class VoiceIntegrationTest {
    @Test
    fun sendsAudioPcmWithGlobalContinuityThenCanonicalBoundary() = runBlocking {
        val connector = RecordingConnector()
        val transport = GatewayMacVoiceTransport { connector }
        val chunker = VadVoiceChunker(VoiceActivityDetector { true })
        chunker.push(ShortArray(VoiceAudioSpec.SAMPLES_PER_FRAME) { 1 })
        val chunk = chunker.finish().single()

        transport.startSession(VoiceSessionDescriptor(STREAM_ID))
        for (packet in VoicePacketizer(maximumPacketBytes = 320).packetize(STREAM_ID, chunk)) transport.sendPcm(packet)
        transport.finishSession(STREAM_ID)

        assertThat(connector.messages.map(RecordingMessage::type)).containsExactly("voice.start", "voice.audio").inOrder()
        assertThat(connector.frames.map(RecordingFrame::sequence)).containsExactly(0L, 1L).inOrder()
        assertThat(connector.frames.map(RecordingFrame::offset)).containsExactly(0uL, 320uL).inOrder()
        assertThat(connector.frames.all { it.bytes.size == 320 }).isTrue()
        val marker = connector.messages.last().body
        assertThat(marker.getValue("sessionId").jsonPrimitive.content).isEqualTo(STREAM_ID)
        assertThat(marker.getValue("chunkSequence").jsonPrimitive.content).isEqualTo("0")
        assertThat(marker.getValue("chunkSequence").jsonPrimitive.isString).isTrue()
        assertThat(marker.getValue("final").jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun finishWithoutSpeechSendsEmptyFinalBoundaryAndCancelUsesReason() = runBlocking {
        val connector = RecordingConnector()
        val transport = GatewayMacVoiceTransport { connector }

        transport.startSession(VoiceSessionDescriptor(STREAM_ID))
        transport.finishSession(STREAM_ID)
        assertThat(connector.frames).isEmpty()
        assertThat(connector.messages.last().type).isEqualTo("voice.audio")
        assertThat(connector.messages.last().body.getValue("chunkSequence").jsonPrimitive.content).isEqualTo("0")
        assertThat(connector.messages.last().body.getValue("final").jsonPrimitive.content).isEqualTo("true")

        transport.startSession(VoiceSessionDescriptor(OTHER_STREAM_ID))
        transport.cancelSession(OTHER_STREAM_ID)
        assertThat(connector.messages.last().type).isEqualTo("voice.cancel")
        assertThat(connector.messages.last().body.getValue("streamId").jsonPrimitive.content).isEqualTo(OTHER_STREAM_ID)
        assertThat(connector.messages.last().body.getValue("reason").jsonPrimitive.content).isEqualTo("client_cancel")
    }

    private data class RecordingMessage(val type: String, val body: JsonObject)
    private data class RecordingFrame(val streamId: String, val sequence: Long, val offset: ULong, val bytes: ByteArray)

    private class RecordingConnector : HostConnector {
        override val path = TransportPath.DIRECT
        val messages = mutableListOf<RecordingMessage>()
        val frames = mutableListOf<RecordingFrame>()

        override suspend fun send(type: String, body: JsonObject, replyTo: String?) {
            messages += RecordingMessage(type, body)
        }

        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = Unit

        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) {
            frames += RecordingFrame(streamId, sequence, offset, bytes.copyOf())
        }

        override suspend fun close() = Unit
    }

    private companion object {
        const val STREAM_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_STREAM_ID = "123e4567-e89b-42d3-a456-426614174001"
    }
}
