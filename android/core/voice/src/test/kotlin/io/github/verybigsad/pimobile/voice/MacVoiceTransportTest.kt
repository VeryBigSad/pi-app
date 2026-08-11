package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class MacVoiceTransportTest {
    @Test
    fun packetizerBoundsPacketsAndReconstructsMaximumChunk() {
        val payload = ByteArray(VoiceAudioSpec.MAX_CHUNK_BYTES) { index -> index.toByte() }
        val chunk = VoiceAudioChunk(sequence = 7, final = true, pcm16Le = payload)

        val packets = VoicePacketizer().packetize(SESSION_ID, chunk)

        assertThat(packets).hasSize(6)
        assertThat(packets.all { it.sizeBytes in 1..VoiceAudioSpec.MAX_PACKET_BYTES }).isTrue()
        assertThat(packets.map(VoicePcmPacket::packetSequence)).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
        assertThat(packets.map(VoicePcmPacket::byteOffset))
            .containsExactly(0, 65_536, 131_072, 196_608, 262_144, 327_680)
            .inOrder()
        assertThat(packets.dropLast(1).none(VoicePcmPacket::endOfChunk)).isTrue()
        assertThat(packets.dropLast(1).none(VoicePcmPacket::finalChunk)).isTrue()
        assertThat(packets.last().endOfChunk).isTrue()
        assertThat(packets.last().finalChunk).isTrue()
        assertThat(join(packets)).isEqualTo(payload)
    }

    @Test
    fun packetsOwnTheirPayload() {
        val chunk = audioChunk(sequence = 0, durationMilliseconds = 20)
        val packet = VoicePacketizer().packetize(SESSION_ID, chunk).single()
        val copy = packet.copyPcm16Le()

        copy.fill(99)

        assertThat(packet.copyPcm16Le()).isEqualTo(ByteArray(VoiceAudioSpec.BYTES_PER_FRAME))
    }

    @Test
    fun queueKeepsOneActiveAndOnlyTwoPendingInOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gates = List(3) { CompletableDeferred<Unit>() }
        val started = CopyOnWriteArrayList<Long>()
        val transport = FakeTransport { packet ->
            if (packet.packetSequence == 0) {
                started += packet.chunkSequence
                gates[packet.chunkSequence.toInt()].await()
            }
        }
        val queue = BoundedMacVoiceQueue(SESSION_ID, transport, scope)

        assertThat(queue.submit(audioChunk(0))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        await { started == listOf(0L) }
        assertThat(queue.submit(audioChunk(1))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        assertThat(queue.submit(audioChunk(2))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        assertThat(queue.metrics().depth).isEqualTo(3)
        assertThat(queue.submit(audioChunk(3))).isEqualTo(VoiceQueueSubmitResult.QUEUE_FULL)

        gates[0].complete(Unit)
        await { started == listOf(0L, 1L) }
        gates[1].complete(Unit)
        await { started == listOf(0L, 1L, 2L) }
        gates[2].complete(Unit)
        assertThat(withTimeout(2_000) { queue.closeAndDrain() }).isEqualTo(VoiceQueueCloseResult.DRAINED)
        assertThat(queue.metrics()).isEqualTo(VoiceQueueMetrics(0, active = false, queuedAudioMilliseconds = 0))
        assertThat(transport.sent.map(VoicePcmPacket::chunkSequence)).containsExactly(0L, 1L, 2L).inOrder()
        scope.cancel()
    }

    @Test
    fun queueStopsBeforeThirtySecondBacklog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { gate.await() }
        val queue = BoundedMacVoiceQueue(SESSION_ID, transport, scope)

        assertThat(queue.submit(audioChunk(0, 12_000))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        await { queue.metrics().active }
        assertThat(queue.submit(audioChunk(1, 12_000))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        assertThat(queue.submit(audioChunk(2, 12_000))).isEqualTo(VoiceQueueSubmitResult.BACKLOG_LIMIT)
        assertThat(queue.metrics().queuedAudioMilliseconds).isEqualTo(24_000)

        gate.complete(Unit)
        assertThat(withTimeout(2_000) { queue.closeAndDrain() }).isEqualTo(VoiceQueueCloseResult.DRAINED)
        scope.cancel()
    }

    @Test
    fun transportFailureClearsQueueAndReportsStableCode() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failures = CopyOnWriteArrayList<VoiceFrontendErrorCode>()
        val transport = FakeTransport { throw IllegalStateException("sensitive transport detail") }
        val queue = BoundedMacVoiceQueue(
            sessionId = SESSION_ID,
            transport = transport,
            scope = scope,
            onFailure = failures::add,
        )

        assertThat(queue.submit(audioChunk(0))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)
        assertThat(withTimeout(2_000) { queue.closeAndDrain() })
            .isEqualTo(VoiceQueueCloseResult.FAILED(VoiceFrontendErrorCode.MAC_TRANSPORT))
        assertThat(failures).containsExactly(VoiceFrontendErrorCode.MAC_TRANSPORT)
        assertThat(queue.metrics().depth).isEqualTo(0)
        assertThat(queue.submit(audioChunk(1))).isEqualTo(VoiceQueueSubmitResult.CLOSED)
        scope.cancel()
    }

    @Test
    fun stalledMacSendTimesOutAndClosesQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val failures = CopyOnWriteArrayList<VoiceFrontendErrorCode>()
        val queue = BoundedMacVoiceQueue(
            sessionId = SESSION_ID,
            transport = FakeTransport { gate.await() },
            scope = scope,
            sendTimeoutMilliseconds = 20,
            onFailure = failures::add,
        )

        assertThat(queue.submit(audioChunk(0))).isEqualTo(VoiceQueueSubmitResult.ACCEPTED)

        assertThat(withTimeout(2_000) { queue.closeAndDrain() })
            .isEqualTo(VoiceQueueCloseResult.FAILED(VoiceFrontendErrorCode.MAC_TRANSPORT))
        assertThat(failures).containsExactly(VoiceFrontendErrorCode.MAC_TRANSPORT)
        scope.cancel()
    }

    @Test
    fun cancelAbortsActiveAndDropsPendingWithoutSendingThem() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { gate.await() }
        val queue = BoundedMacVoiceQueue(SESSION_ID, transport, scope)

        queue.submit(audioChunk(0))
        queue.submit(audioChunk(1))
        await { queue.metrics().active }

        withTimeout(2_000) { queue.cancel() }

        assertThat(queue.metrics()).isEqualTo(VoiceQueueMetrics(0, active = false, queuedAudioMilliseconds = 0))
        assertThat(queue.closeAndDrain()).isEqualTo(VoiceQueueCloseResult.CANCELED)
        assertThat(transport.sent.map(VoicePcmPacket::chunkSequence)).containsExactly(0L)
        scope.cancel()
    }

    private class FakeTransport(
        private val sender: suspend (VoicePcmPacket) -> Unit = {},
    ) : MacVoiceTransport {
        val sent = CopyOnWriteArrayList<VoicePcmPacket>()

        override suspend fun startSession(descriptor: VoiceSessionDescriptor) = Unit

        override suspend fun sendPcm(packet: VoicePcmPacket) {
            sent += packet
            sender(packet)
        }

        override suspend fun finishSession(sessionId: String) = Unit

        override suspend fun cancelSession(sessionId: String) = Unit

        override fun attachTranscriptSink(sink: VoiceTranscriptSink?) = Unit
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"

        fun audioChunk(
            sequence: Long,
            durationMilliseconds: Int = 20,
        ): VoiceAudioChunk = VoiceAudioChunk(
            sequence = sequence,
            final = false,
            pcm16Le = ByteArray(durationMilliseconds / VoiceAudioSpec.FRAME_DURATION_MS * VoiceAudioSpec.BYTES_PER_FRAME),
        )

        fun join(packets: List<VoicePcmPacket>): ByteArray {
            val output = ByteArray(packets.sumOf(VoicePcmPacket::sizeBytes))
            var offset = 0
            for (packet in packets) {
                val bytes = packet.copyPcm16Le()
                bytes.copyInto(output, offset)
                offset += bytes.size
            }
            return output
        }

        suspend fun await(predicate: () -> Boolean) {
            withTimeout(2_000) {
                while (!predicate()) kotlinx.coroutines.yield()
            }
        }
    }
}
