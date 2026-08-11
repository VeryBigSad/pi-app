package io.github.verybigsad.pimobile.voice

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

class VoiceCaptureController(
    private val permissionSource: MicrophonePermissionSource,
    private val audioSourceFactory: Pcm16AudioSourceFactory,
    private val transport: MacVoiceTransport,
    parentScope: CoroutineScope,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val detectorFactory: () -> VoiceActivityDetector = { RmsVoiceActivityDetector() },
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(
        VoiceFrontendState(
            permission = permissionSource.current(),
            foreground = false,
            phase = VoiceCapturePhase.IDLE,
        ),
    )
    private var runtime: CaptureRuntime? = null
    private var terminating = false
    private var closed = false
    private val scopeCancellationWatcher = scope.launch {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { closeController() }
        }
    }

    val state: StateFlow<VoiceFrontendState> = mutableState.asStateFlow()

    suspend fun setForeground(foreground: Boolean) {
        val detached = operations.withLock {
            if (closed) return@withLock null
            val current = runtime
            if (foreground || current == null) {
                mutableState.value = mutableState.value.copy(foreground = foreground)
                null
            } else {
                runtime = null
                terminating = true
                mutableState.value = mutableState.value.copy(
                    foreground = false,
                    phase = VoiceCapturePhase.CANCELING,
                    queueDepth = 0,
                    queuedAudioMilliseconds = 0,
                    cancellationReason = VoiceCancellationReason.BACKGROUND,
                    frontendError = null,
                    hostError = null,
                )
                current
            }
        }
        if (detached != null) {
            cleanupCanceled(detached)
            completeCancellation(detached, VoiceCancellationReason.BACKGROUND)
        }
    }

    suspend fun refreshPermission(): MicrophonePermissionState {
        val permission = permissionSource.current()
        setPermission(permission)
        return permission
    }

    suspend fun setPermission(permission: MicrophonePermissionState) {
        var detached: CaptureRuntime? = null
        operations.withLock {
            if (closed) return
            val current = runtime
            if (permission != MicrophonePermissionState.GRANTED && current != null) {
                runtime = null
                terminating = true
                detached = current
                mutableState.value = mutableState.value.copy(
                    permission = permission,
                    phase = VoiceCapturePhase.CANCELING,
                    queueDepth = 0,
                    queuedAudioMilliseconds = 0,
                    cancellationReason = VoiceCancellationReason.PERMISSION_REVOKED,
                    frontendError = null,
                    hostError = null,
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    permission = permission,
                    phase = permission.idlePhase(mutableState.value.phase, current != null),
                    cancellationReason = null,
                    frontendError = null,
                    hostError = null,
                )
            }
        }
        detached?.let {
            cleanupCanceled(it)
            completeCancellation(it, VoiceCancellationReason.PERMISSION_REVOKED)
        }
    }

    suspend fun start(): VoiceStartResult = operations.withLock {
        if (closed) return VoiceStartResult.CLOSED
        if (runtime != null || terminating) return VoiceStartResult.ALREADY_ACTIVE
        if (!mutableState.value.foreground) return VoiceStartResult.NOT_FOREGROUND

        val permission = observedPermission()
        if (permission != MicrophonePermissionState.GRANTED) {
            mutableState.value = mutableState.value.copy(
                permission = permission,
                phase = permission.requiredPhase(),
                sessionId = null,
                cancellationReason = null,
                frontendError = null,
                hostError = null,
            )
            return if (permission == MicrophonePermissionState.REQUEST_REQUIRED) {
                VoiceStartResult.PERMISSION_REQUIRED
            } else {
                VoiceStartResult.PERMISSION_DENIED
            }
        }

        val sessionId = sessionIdFactory()
        val descriptor = VoiceSessionDescriptor(sessionId)
        mutableState.value = mutableState.value.copy(
            permission = permission,
            phase = VoiceCapturePhase.STARTING,
            sessionId = sessionId,
            queueDepth = 0,
            queuedAudioMilliseconds = 0,
            cancellationReason = null,
            frontendError = null,
            hostError = null,
        )

        val source = try {
            withContext(captureDispatcher) { audioSourceFactory.create() }
        } catch (error: Exception) {
            if (error is CancellationException && !currentCoroutineContext().isActive) {
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCapturePhase.CANCELED,
                    cancellationReason = VoiceCancellationReason.USER,
                )
                throw error
            }
            val permissionAfterFailure = observedPermission()
            if (permissionAfterFailure != MicrophonePermissionState.GRANTED) {
                mutableState.value = mutableState.value.copy(
                    permission = permissionAfterFailure,
                    phase = permissionAfterFailure.requiredPhase(),
                    frontendError = null,
                )
                return permissionAfterFailure.startResult()
            }
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.FAILED,
                frontendError = VoiceFrontendErrorCode.AUDIO_INITIALIZATION,
            )
            return VoiceStartResult.FAILED
        }
        val chunker = VadVoiceChunker(detectorFactory())
        lateinit var createdRuntime: CaptureRuntime
        val queue = BoundedMacVoiceQueue(
            sessionId = sessionId,
            transport = transport,
            scope = scope,
            workerDispatcher = captureDispatcher,
            onMetrics = { metrics -> publishMetrics(sessionId, metrics) },
            onFailure = { code -> scheduleFailure(createdRuntime, code) },
        )
        createdRuntime = CaptureRuntime(
            sessionId = sessionId,
            source = source,
            chunker = chunker,
            queue = queue,
        )

        var transportStarted = false
        try {
            withContext(captureDispatcher) {
                withTimeout(MAC_OPERATION_TIMEOUT_MILLISECONDS) { transport.startSession(descriptor) }
                transportStarted = true
                source.start()
            }
        } catch (error: Exception) {
            withContext(NonCancellable + captureDispatcher) {
                safeStop(source)
                safeRelease(source)
                if (transportStarted) safeCancelTransport(sessionId)
            }
            if (error is CancellationException && !currentCoroutineContext().isActive) {
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCapturePhase.CANCELED,
                    cancellationReason = VoiceCancellationReason.USER,
                )
                throw error
            }
            val permissionAfterFailure = observedPermission()
            if (permissionAfterFailure != MicrophonePermissionState.GRANTED) {
                mutableState.value = mutableState.value.copy(
                    permission = permissionAfterFailure,
                    phase = permissionAfterFailure.requiredPhase(),
                    frontendError = null,
                )
                return permissionAfterFailure.startResult()
            }
            val code = if (transportStarted) VoiceFrontendErrorCode.AUDIO_START else VoiceFrontendErrorCode.MAC_TRANSPORT
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.FAILED,
                frontendError = code,
            )
            return VoiceStartResult.FAILED
        }

        val captureJob = scope.launch(captureDispatcher, start = CoroutineStart.LAZY) {
            capture(createdRuntime)
        }
        createdRuntime.captureJob = captureJob
        runtime = createdRuntime
        mutableState.value = mutableState.value.copy(phase = VoiceCapturePhase.CAPTURING)
        captureJob.start()
        VoiceStartResult.STARTED
    }

    suspend fun stop(): Boolean {
        val detached = operations.withLock {
            val current = runtime ?: return@withLock null
            runtime = null
            terminating = true
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.STOPPING,
                cancellationReason = null,
                frontendError = null,
                hostError = null,
            )
            current
        } ?: return false

        try {
            withContext(captureDispatcher) {
                safeStop(detached.source)
                safeRelease(detached.source)
            }
            detached.captureJob?.cancelAndJoin()
            val finalChunks = detached.chunker.finish()
            for (chunk in finalChunks) {
                when (detached.queue.submit(chunk)) {
                    VoiceQueueSubmitResult.ACCEPTED -> Unit
                    VoiceQueueSubmitResult.QUEUE_FULL -> {
                        failDuringStop(detached, VoiceFrontendErrorCode.MAC_QUEUE_FULL)
                        return true
                    }
                    VoiceQueueSubmitResult.BACKLOG_LIMIT -> {
                        failDuringStop(detached, VoiceFrontendErrorCode.MAC_BACKLOG_LIMIT)
                        return true
                    }
                    VoiceQueueSubmitResult.CLOSED -> {
                        failDuringStop(detached, VoiceFrontendErrorCode.MAC_TRANSPORT)
                        return true
                    }
                }
            }

            val closeResult = detached.queue.closeAndDrain()
            val failure = when (closeResult) {
                VoiceQueueCloseResult.DRAINED -> null
                VoiceQueueCloseResult.CANCELED -> VoiceFrontendErrorCode.MAC_TRANSPORT
                is VoiceQueueCloseResult.FAILED -> closeResult.code
            }
            if (failure != null) {
                failDuringStop(detached, failure)
                return true
            }

            val transportFinished = withContext(captureDispatcher) {
                try {
                    withTimeout(MAC_OPERATION_TIMEOUT_MILLISECONDS) { transport.finishSession(detached.sessionId) }
                    true
                } catch (error: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    safeCancelTransport(detached.sessionId)
                    false
                } catch (_: Exception) {
                    safeCancelTransport(detached.sessionId)
                    false
                } finally {
                    safeRelease(detached.source)
                }
            }
            operations.withLock {
                terminating = false
                if (!closed && mutableState.value.sessionId == detached.sessionId) {
                    mutableState.value = mutableState.value.copy(
                        phase = if (transportFinished) VoiceCapturePhase.COMPLETED else VoiceCapturePhase.FAILED,
                        queueDepth = 0,
                        queuedAudioMilliseconds = 0,
                        frontendError = if (transportFinished) null else VoiceFrontendErrorCode.MAC_TRANSPORT,
                    )
                }
            }
            return true
        } catch (error: CancellationException) {
            mutableState.update { state ->
                if (!closed && state.sessionId == detached.sessionId) {
                    state.copy(
                        phase = VoiceCapturePhase.CANCELED,
                        queueDepth = 0,
                        queuedAudioMilliseconds = 0,
                        cancellationReason = VoiceCancellationReason.USER,
                    )
                } else {
                    state
                }
            }
            cleanupCanceled(detached)
            withContext(NonCancellable) {
                operations.withLock { terminating = false }
            }
            throw error
        }
    }

    suspend fun cancel(reason: VoiceCancellationReason = VoiceCancellationReason.USER): Boolean {
        val detached = operations.withLock {
            val current = runtime ?: return@withLock null
            runtime = null
            terminating = true
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.CANCELING,
                queueDepth = 0,
                queuedAudioMilliseconds = 0,
                cancellationReason = reason,
                frontendError = null,
                hostError = null,
            )
            current
        } ?: return false
        cleanupCanceled(detached)
        completeCancellation(detached, reason)
        return true
    }

    suspend fun onMacError(sessionId: String, error: MacVoiceError): Boolean {
        val detached = operations.withLock {
            val current = runtime
            if (current == null || current.sessionId != sessionId) return@withLock null
            runtime = null
            terminating = true
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.HOST_ERROR,
                queueDepth = 0,
                queuedAudioMilliseconds = 0,
                cancellationReason = VoiceCancellationReason.HOST_ERROR,
                frontendError = null,
                hostError = error,
            )
            current
        } ?: return false
        cleanupCanceled(detached)
        withContext(NonCancellable) { operations.withLock { terminating = false } }
        return true
    }

    suspend fun close() {
        closeController()
        scopeCancellationWatcher.cancel()
        controllerJob.cancel()
    }

    private suspend fun capture(current: CaptureRuntime) {
        val frame = ShortArray(VoiceAudioSpec.SAMPLES_PER_FRAME)
        var filled = 0
        try {
            while (currentCoroutineContext().isActive) {
                val count = current.source.read(frame, filled, frame.size - filled)
                when {
                    count > 0 -> filled += count
                    count == 0 -> {
                        yield()
                        continue
                    }
                    else -> {
                        scheduleAudioFailure(current)
                        return
                    }
                }
                if (filled != frame.size) continue
                filled = 0
                for (chunk in current.chunker.push(frame)) {
                    when (current.queue.submit(chunk)) {
                        VoiceQueueSubmitResult.ACCEPTED -> Unit
                        VoiceQueueSubmitResult.QUEUE_FULL -> {
                            scheduleFailure(current, VoiceFrontendErrorCode.MAC_QUEUE_FULL)
                            return
                        }
                        VoiceQueueSubmitResult.BACKLOG_LIMIT -> {
                            scheduleFailure(current, VoiceFrontendErrorCode.MAC_BACKLOG_LIMIT)
                            return
                        }
                        VoiceQueueSubmitResult.CLOSED -> {
                            scheduleFailure(current, VoiceFrontendErrorCode.MAC_TRANSPORT)
                            return
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            scheduleAudioFailure(current)
        }
    }

    private fun scheduleAudioFailure(current: CaptureRuntime) {
        scope.launch {
            val permission = observedPermission()
            if (permission == MicrophonePermissionState.GRANTED) {
                failRuntime(current, VoiceFrontendErrorCode.AUDIO_READ)
            } else {
                setPermission(permission)
            }
        }
    }

    private fun scheduleFailure(current: CaptureRuntime, code: VoiceFrontendErrorCode) {
        scope.launch { failRuntime(current, code) }
    }

    private suspend fun failRuntime(current: CaptureRuntime, code: VoiceFrontendErrorCode) {
        val detached = operations.withLock {
            if (runtime !== current) return@withLock false
            runtime = null
            terminating = true
            mutableState.value = mutableState.value.copy(
                phase = VoiceCapturePhase.FAILED,
                queueDepth = 0,
                queuedAudioMilliseconds = 0,
                cancellationReason = null,
                frontendError = code,
                hostError = null,
            )
            true
        }
        if (detached) {
            cleanupCanceled(current)
            withContext(NonCancellable) { operations.withLock { terminating = false } }
        }
    }

    private suspend fun failDuringStop(current: CaptureRuntime, code: VoiceFrontendErrorCode) {
        current.queue.cancel()
        withContext(captureDispatcher) {
            safeCancelTransport(current.sessionId)
            safeRelease(current.source)
        }
        operations.withLock {
            terminating = false
            if (!closed && mutableState.value.sessionId == current.sessionId) {
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCapturePhase.FAILED,
                    queueDepth = 0,
                    queuedAudioMilliseconds = 0,
                    frontendError = code,
                )
            }
        }
    }

    private suspend fun cleanupCanceled(current: CaptureRuntime) {
        withContext(NonCancellable + captureDispatcher) {
            safeStop(current.source)
            safeRelease(current.source)
            current.captureJob?.cancelAndJoin()
            current.chunker.cancel()
            current.queue.cancel()
            safeCancelTransport(current.sessionId)
            safeRelease(current.source)
        }
    }

    private suspend fun completeCancellation(current: CaptureRuntime, reason: VoiceCancellationReason) {
        withContext(NonCancellable) {
            operations.withLock {
                terminating = false
                if (!closed && mutableState.value.sessionId == current.sessionId && mutableState.value.phase == VoiceCapturePhase.CANCELING) {
                    mutableState.value = mutableState.value.copy(
                        phase = VoiceCapturePhase.CANCELED,
                        queueDepth = 0,
                        queuedAudioMilliseconds = 0,
                        cancellationReason = reason,
                    )
                }
            }
        }
    }

    private fun publishMetrics(sessionId: String, metrics: VoiceQueueMetrics) {
        mutableState.update { state ->
            if (state.sessionId == sessionId && state.phase in METRIC_PHASES) {
                state.copy(
                    queueDepth = metrics.depth,
                    queuedAudioMilliseconds = metrics.queuedAudioMilliseconds,
                )
            } else {
                state
            }
        }
    }

    private fun safeStop(source: Pcm16AudioSource) {
        try {
            source.stop()
        } catch (_: Exception) {
        }
    }

    private fun safeRelease(source: Pcm16AudioSource) {
        try {
            source.release()
        } catch (_: Exception) {
        }
    }

    private suspend fun safeCancelTransport(sessionId: String) {
        try {
            withTimeoutOrNull(MAC_CANCEL_TIMEOUT_MILLISECONDS) { transport.cancelSession(sessionId) }
        } catch (_: Exception) {
        }
    }

    private suspend fun closeController() {
        val detached = operations.withLock {
            if (closed) return
            closed = true
            val current = runtime
            runtime = null
            terminating = current != null
            mutableState.value = mutableState.value.copy(
                phase = if (current == null) VoiceCapturePhase.CLOSED else VoiceCapturePhase.CANCELING,
                queueDepth = 0,
                queuedAudioMilliseconds = 0,
                cancellationReason = VoiceCancellationReason.CLOSED,
                frontendError = null,
                hostError = null,
            )
            current
        }
        if (detached != null) cleanupCanceled(detached)
        withContext(NonCancellable) {
            operations.withLock {
                terminating = false
                mutableState.value = mutableState.value.copy(
                    phase = VoiceCapturePhase.CLOSED,
                    queueDepth = 0,
                    queuedAudioMilliseconds = 0,
                    cancellationReason = VoiceCancellationReason.CLOSED,
                )
            }
        }
    }

    private fun observedPermission(): MicrophonePermissionState {
        val observed = permissionSource.current()
        return if (observed != MicrophonePermissionState.GRANTED && mutableState.value.permission == MicrophonePermissionState.DENIED) {
            MicrophonePermissionState.DENIED
        } else {
            observed
        }
    }

    private class CaptureRuntime(
        val sessionId: String,
        val source: Pcm16AudioSource,
        val chunker: VadVoiceChunker,
        val queue: BoundedMacVoiceQueue,
    ) {
        var captureJob: Job? = null
    }

    private companion object {
        const val MAC_OPERATION_TIMEOUT_MILLISECONDS = 10_000L
        const val MAC_CANCEL_TIMEOUT_MILLISECONDS = 5_000L

        val METRIC_PHASES = setOf(
            VoiceCapturePhase.CAPTURING,
            VoiceCapturePhase.STOPPING,
        )
    }
}

private fun MicrophonePermissionState.requiredPhase(): VoiceCapturePhase = when (this) {
    MicrophonePermissionState.GRANTED -> VoiceCapturePhase.IDLE
    MicrophonePermissionState.REQUEST_REQUIRED -> VoiceCapturePhase.PERMISSION_REQUIRED
    MicrophonePermissionState.DENIED -> VoiceCapturePhase.PERMISSION_DENIED
}

private fun MicrophonePermissionState.startResult(): VoiceStartResult = when (this) {
    MicrophonePermissionState.GRANTED -> VoiceStartResult.STARTED
    MicrophonePermissionState.REQUEST_REQUIRED -> VoiceStartResult.PERMISSION_REQUIRED
    MicrophonePermissionState.DENIED -> VoiceStartResult.PERMISSION_DENIED
}

private fun MicrophonePermissionState.idlePhase(
    current: VoiceCapturePhase,
    active: Boolean,
): VoiceCapturePhase = when {
    active -> current
    this == MicrophonePermissionState.REQUEST_REQUIRED -> VoiceCapturePhase.PERMISSION_REQUIRED
    this == MicrophonePermissionState.DENIED -> VoiceCapturePhase.PERMISSION_DENIED
    current == VoiceCapturePhase.PERMISSION_REQUIRED || current == VoiceCapturePhase.PERMISSION_DENIED -> VoiceCapturePhase.IDLE
    else -> current
}
