package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class VoiceCaptureControllerTest {
    @Test
    fun startReflectsForegroundAndAllPermissionStates() = runBlocking {
        val permission = MutablePermission(MicrophonePermissionState.REQUEST_REQUIRED)
        val source = FakeAudioSource()
        val harness = harness(permission, source)

        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.NOT_FOREGROUND)
        harness.controller.setForeground(true)
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.PERMISSION_REQUIRED)
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.PERMISSION_REQUIRED)

        permission.value = MicrophonePermissionState.REQUEST_REQUIRED
        harness.controller.setPermission(MicrophonePermissionState.DENIED)
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.PERMISSION_DENIED)
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.PERMISSION_DENIED)
        assertThat(source.started.get()).isFalse()

        permission.value = MicrophonePermissionState.GRANTED
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.STARTED)
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.ALREADY_ACTIVE)
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CAPTURING)

        harness.close()
    }

    @Test
    fun captureAssemblesPartialReadsAndGracefulStopFlushesOnlyToMac() = runBlocking {
        val source = FakeAudioSource(maximumReadSamples = 37)
        val transport = FakeMacTransport()
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source, transport)
        harness.controller.setForeground(true)

        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.STARTED)
        repeat(15) { source.offer(frame(0)) }
        repeat(10) { source.offer(frame(1_000)) }
        await { source.framesConsumed.get() == 25 }

        assertThat(harness.controller.stop()).isTrue()

        assertThat(transport.started).hasSize(1)
        val descriptor = transport.started.single()
        assertThat(descriptor.sampleRateHz).isEqualTo(16_000)
        assertThat(descriptor.channelCount).isEqualTo(1)
        assertThat(descriptor.bitsPerSample).isEqualTo(16)
        assertThat(descriptor.frameDurationMilliseconds).isEqualTo(20)
        assertThat(transport.sent.sumOf(VoicePcmPacket::sizeBytes)).isEqualTo(25 * VoiceAudioSpec.BYTES_PER_FRAME)
        assertThat(transport.sent.last().finalChunk).isTrue()
        assertThat(transport.finished).containsExactly(SESSION_ID)
        assertThat(transport.canceled).isEmpty()
        assertThat(source.released.get()).isTrue()
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.COMPLETED)

        harness.close()
    }

    @Test
    fun cancelDropsUnflushedAudioAndCancelsMacSession() = runBlocking {
        val source = FakeAudioSource()
        val transport = FakeMacTransport()
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source, transport)
        harness.controller.setForeground(true)
        harness.controller.start()
        repeat(100) { source.offer(frame(1_000)) }
        await { source.framesConsumed.get() == 100 }

        assertThat(harness.controller.cancel()).isTrue()

        assertThat(transport.sent).isEmpty()
        assertThat(transport.finished).isEmpty()
        assertThat(transport.canceled).containsExactly(SESSION_ID)
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CANCELED)
        assertThat(harness.controller.state.value.cancellationReason).isEqualTo(VoiceCancellationReason.USER)
        harness.close()
    }

    @Test
    fun backgroundAndPermissionRevocationCancelActiveCapture() = runBlocking {
        val permission = MutablePermission(MicrophonePermissionState.GRANTED)
        val first = harness(permission, FakeAudioSource())
        first.controller.setForeground(true)
        first.controller.start()

        first.controller.setForeground(false)

        assertThat(first.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CANCELED)
        assertThat(first.controller.state.value.cancellationReason).isEqualTo(VoiceCancellationReason.BACKGROUND)
        assertThat(first.transport.canceled).containsExactly(SESSION_ID)
        first.close()

        val second = harness(permission, FakeAudioSource())
        second.controller.setForeground(true)
        second.controller.start()

        second.controller.setPermission(MicrophonePermissionState.DENIED)

        assertThat(second.controller.state.value.permission).isEqualTo(MicrophonePermissionState.DENIED)
        assertThat(second.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CANCELED)
        assertThat(second.controller.state.value.cancellationReason).isEqualTo(VoiceCancellationReason.PERMISSION_REVOKED)
        assertThat(second.transport.canceled).containsExactly(SESSION_ID)
        second.close()
    }

    @Test
    fun audioReadFailureStopsAndReportsStableFrontendCode() = runBlocking {
        val source = FakeAudioSource()
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source)
        harness.controller.setForeground(true)
        harness.controller.start()

        source.failRead()
        await { harness.controller.state.value.phase == VoiceCapturePhase.FAILED }
        await { harness.transport.canceled.isNotEmpty() }

        assertThat(harness.controller.state.value.frontendError).isEqualTo(VoiceFrontendErrorCode.AUDIO_READ)
        assertThat(harness.transport.canceled).containsExactly(SESSION_ID)
        assertThat(source.released.get()).isTrue()
        harness.close()
    }

    @Test
    fun permissionLossDuringReadBecomesPermissionCancellation() = runBlocking {
        val permission = MutablePermission(MicrophonePermissionState.GRANTED)
        val source = FakeAudioSource()
        val harness = harness(permission, source)
        harness.controller.setForeground(true)
        harness.controller.start()

        permission.value = MicrophonePermissionState.REQUEST_REQUIRED
        source.failRead()
        await { harness.controller.state.value.phase == VoiceCapturePhase.CANCELED }

        assertThat(harness.controller.state.value.permission).isEqualTo(MicrophonePermissionState.REQUEST_REQUIRED)
        assertThat(harness.controller.state.value.cancellationReason)
            .isEqualTo(VoiceCancellationReason.PERMISSION_REVOKED)
        assertThat(harness.controller.state.value.frontendError).isNull()
        await { harness.transport.canceled.isNotEmpty() }
        harness.close()
    }

    @Test
    fun transportAndAudioStartFailuresAreDistinct() = runBlocking {
        val transportFailure = FakeMacTransport(failStart = true)
        val firstSource = FakeAudioSource()
        val first = harness(
            MutablePermission(MicrophonePermissionState.GRANTED),
            firstSource,
            transportFailure,
        )
        first.controller.setForeground(true)

        assertThat(first.controller.start()).isEqualTo(VoiceStartResult.FAILED)
        assertThat(first.controller.state.value.frontendError).isEqualTo(VoiceFrontendErrorCode.MAC_TRANSPORT)
        assertThat(firstSource.started.get()).isFalse()
        assertThat(firstSource.released.get()).isTrue()
        first.close()

        val secondSource = FakeAudioSource(failStart = true)
        val second = harness(MutablePermission(MicrophonePermissionState.GRANTED), secondSource)
        second.controller.setForeground(true)

        assertThat(second.controller.start()).isEqualTo(VoiceStartResult.FAILED)
        assertThat(second.controller.state.value.frontendError).isEqualTo(VoiceFrontendErrorCode.AUDIO_START)
        assertThat(second.transport.canceled).containsExactly(SESSION_ID)
        assertThat(secondSource.released.get()).isTrue()
        second.close()
    }

    @Test
    fun macQuotaAndRetryErrorsAreExplicitAndStaleErrorsAreIgnored() = runBlocking {
        val first = harness(MutablePermission(MicrophonePermissionState.GRANTED), FakeAudioSource())
        first.controller.setForeground(true)
        first.controller.start()
        val quota = MacVoiceError.fromWire(
            code = "VOICE_QUOTA",
            detailCode = "VOICE_ASD_LIMIT",
            resetAtEpochMilliseconds = 9_000,
        )

        assertThat(first.controller.onMacError("stale", quota)).isFalse()
        assertThat(first.controller.onMacError(SESSION_ID, quota)).isTrue()
        assertThat(first.controller.state.value.phase).isEqualTo(VoiceCapturePhase.HOST_ERROR)
        assertThat(first.controller.state.value.hostError).isEqualTo(quota)
        assertThat(first.controller.state.value.hostError?.quotaWindow)
            .isEqualTo(MacVoiceQuotaWindow.AUDIO_SECONDS_PER_DAY)
        first.close()

        val second = harness(MutablePermission(MicrophonePermissionState.GRANTED), FakeAudioSource())
        second.controller.setForeground(true)
        second.controller.start()
        val retry = MacVoiceError.fromWire(
            code = "VOICE_RETRY_AFTER_LONG",
            resetAtEpochMilliseconds = 50_000,
            retryAfterMilliseconds = 121_000,
        )

        second.controller.onMacError(SESSION_ID, retry)

        assertThat(second.controller.state.value.phase).isEqualTo(VoiceCapturePhase.HOST_ERROR)
        assertThat(second.controller.state.value.hostError?.code).isEqualTo(MacVoiceErrorCode.RETRY_AFTER_LONG)
        assertThat(second.controller.state.value.hostError?.retryAfterMilliseconds).isEqualTo(121_000)
        second.close()
    }

    @Test
    fun slowMacStopsCaptureBeforeBacklogExceedsThirtySeconds() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = FakeAudioSource()
        val transport = FakeMacTransport(sendGate = gate)
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source, transport)
        harness.controller.setForeground(true)
        harness.controller.start()

        repeat(1_750) { source.offer(frame(1_000)) }
        await(timeoutMilliseconds = 5_000) { harness.controller.state.value.phase == VoiceCapturePhase.FAILED }
        await { transport.canceled.isNotEmpty() }

        assertThat(harness.controller.state.value.frontendError).isEqualTo(VoiceFrontendErrorCode.MAC_BACKLOG_LIMIT)
        assertThat(harness.controller.state.value.queueDepth).isEqualTo(0)
        assertThat(transport.canceled).containsExactly(SESSION_ID)
        gate.complete(Unit)
        harness.close()
    }

    @Test
    fun gracefulStopWaitsForActiveMacSendBeforeFinish() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = FakeAudioSource()
        val transport = FakeMacTransport(sendGate = gate)
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source, transport)
        harness.controller.setForeground(true)
        harness.controller.start()
        repeat(600) { source.offer(frame(1_000)) }
        await { transport.sent.isNotEmpty() }

        val stop = async { harness.controller.stop() }
        await { harness.controller.state.value.phase == VoiceCapturePhase.STOPPING }
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.ALREADY_ACTIVE)
        assertThat(stop.isCompleted).isFalse()
        assertThat(transport.finished).isEmpty()

        gate.complete(Unit)
        assertThat(withTimeout(2_000) { stop.await() }).isTrue()
        assertThat(transport.finished).containsExactly(SESSION_ID)
        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.COMPLETED)
        harness.close()
    }

    @Test
    fun cancelingGracefulStopStillCleansAudioAndMacSession() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = FakeAudioSource()
        val transport = FakeMacTransport(sendGate = gate)
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source, transport)
        harness.controller.setForeground(true)
        harness.controller.start()
        repeat(600) { source.offer(frame(1_000)) }
        await { transport.sent.isNotEmpty() }

        val stopping = launch { harness.controller.stop() }
        await { harness.controller.state.value.phase == VoiceCapturePhase.STOPPING }
        stopping.cancelAndJoin()
        await { transport.canceled.isNotEmpty() }
        await { harness.controller.state.value.phase != VoiceCapturePhase.STOPPING }

        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CANCELED)
        assertThat(source.released.get()).isTrue()
        assertThat(transport.finished).isEmpty()
        harness.close()
    }

    @Test
    fun parentScopeCancellationClosesAndCleansActiveCapture() = runBlocking {
        val source = FakeAudioSource()
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), source)
        harness.controller.setForeground(true)
        harness.controller.start()

        harness.scope.cancel()
        await { harness.controller.state.value.phase == VoiceCapturePhase.CLOSED }

        assertThat(source.released.get()).isTrue()
        assertThat(harness.transport.canceled).containsExactly(SESSION_ID)
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.CLOSED)
    }

    @Test
    fun closeIsIdempotentAndRejectsFutureStarts() = runBlocking {
        val harness = harness(MutablePermission(MicrophonePermissionState.GRANTED), FakeAudioSource())
        harness.controller.setForeground(true)
        harness.controller.start()

        harness.controller.close()
        harness.controller.close()

        assertThat(harness.controller.state.value.phase).isEqualTo(VoiceCapturePhase.CLOSED)
        assertThat(harness.controller.start()).isEqualTo(VoiceStartResult.CLOSED)
        assertThat(harness.transport.canceled).containsExactly(SESSION_ID)
        harness.scope.cancel()
    }

    private fun harness(
        permission: MutablePermission,
        source: FakeAudioSource,
        transport: FakeMacTransport = FakeMacTransport(),
    ): Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = VoiceCaptureController(
            permissionSource = permission,
            audioSourceFactory = Pcm16AudioSourceFactory { source },
            transport = transport,
            parentScope = scope,
            detectorFactory = { VoiceActivityDetector { frame -> frame.any { it.toInt() != 0 } } },
            sessionIdFactory = { SESSION_ID },
        )
        return Harness(controller, source, transport, scope)
    }

    private class Harness(
        val controller: VoiceCaptureController,
        val source: FakeAudioSource,
        val transport: FakeMacTransport,
        val scope: CoroutineScope,
    ) {
        suspend fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private class MutablePermission(
        @Volatile var value: MicrophonePermissionState,
    ) : MicrophonePermissionSource {
        override fun current(): MicrophonePermissionState = value
    }

    private class FakeAudioSource(
        private val maximumReadSamples: Int = VoiceAudioSpec.SAMPLES_PER_FRAME,
        private val failStart: Boolean = false,
    ) : Pcm16AudioSource {
        private sealed interface ReadEvent {
            data class Samples(val value: ShortArray) : ReadEvent
            data object Stop : ReadEvent
            data object Failure : ReadEvent
        }

        private val events = LinkedBlockingQueue<ReadEvent>()
        private var current: ShortArray? = null
        private var currentOffset = 0
        val started = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val framesConsumed = AtomicInteger(0)

        override fun start() {
            if (failStart) throw IllegalStateException("audio start detail")
            started.set(true)
        }

        override fun read(destination: ShortArray, offset: Int, length: Int): Int {
            while (true) {
                val samples = current
                if (samples == null) {
                    when (val event = events.take()) {
                        is ReadEvent.Samples -> {
                            current = event.value
                            currentOffset = 0
                        }
                        ReadEvent.Stop -> return -3
                        ReadEvent.Failure -> throw IllegalStateException("audio read detail")
                    }
                    continue
                }
                val count = minOf(length, maximumReadSamples, samples.size - currentOffset)
                samples.copyInto(destination, offset, currentOffset, currentOffset + count)
                currentOffset += count
                if (currentOffset == samples.size) {
                    current = null
                    currentOffset = 0
                    framesConsumed.incrementAndGet()
                }
                return count
            }
        }

        override fun stop() {
            events.offer(ReadEvent.Stop)
        }

        override fun release() {
            released.set(true)
            events.offer(ReadEvent.Stop)
        }

        fun offer(frame: ShortArray) {
            events.put(ReadEvent.Samples(frame.copyOf()))
        }

        fun failRead() {
            events.put(ReadEvent.Failure)
        }
    }

    private class FakeMacTransport(
        private val failStart: Boolean = false,
        private val sendGate: CompletableDeferred<Unit>? = null,
    ) : MacVoiceTransport {
        val started = CopyOnWriteArrayList<VoiceSessionDescriptor>()
        val sent = CopyOnWriteArrayList<VoicePcmPacket>()
        val finished = CopyOnWriteArrayList<String>()
        val canceled = CopyOnWriteArrayList<String>()

        override suspend fun startSession(descriptor: VoiceSessionDescriptor) {
            if (failStart) throw IllegalStateException("transport start detail")
            started += descriptor
        }

        override suspend fun sendPcm(packet: VoicePcmPacket) {
            sent += packet
            sendGate?.await()
        }

        override suspend fun finishSession(sessionId: String) {
            finished += sessionId
        }

        override suspend fun cancelSession(sessionId: String) {
            canceled += sessionId
        }

        override fun attachTranscriptSink(sink: VoiceTranscriptSink?) = Unit
    }

    private companion object {
        const val SESSION_ID = "123e4567-e89b-42d3-a456-426614174000"

        fun frame(sample: Int): ShortArray = ShortArray(VoiceAudioSpec.SAMPLES_PER_FRAME) { sample.toShort() }

        suspend fun await(
            timeoutMilliseconds: Long = 2_000,
            predicate: () -> Boolean,
        ) {
            withTimeout(timeoutMilliseconds) {
                while (!predicate()) kotlinx.coroutines.yield()
            }
        }
    }
}
