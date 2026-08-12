package io.github.verybigsad.pimobile.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

sealed interface VoiceQueueSubmitResult {
    data object ACCEPTED : VoiceQueueSubmitResult
    data object QUEUE_FULL : VoiceQueueSubmitResult
    data object BACKLOG_LIMIT : VoiceQueueSubmitResult
    data object CLOSED : VoiceQueueSubmitResult
}

sealed interface VoiceQueueCloseResult {
    data object DRAINED : VoiceQueueCloseResult
    data object CANCELED : VoiceQueueCloseResult
    data class FAILED(val code: VoiceFrontendErrorCode) : VoiceQueueCloseResult
}

data class VoiceQueueMetrics(
    val depth: Int,
    val active: Boolean,
    val queuedAudioMilliseconds: Int,
)

class BoundedMacVoiceQueue(
    private val sessionId: String,
    private val transport: MacVoiceTransport,
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val packetizer: VoicePacketizer = VoicePacketizer(),
    private val sendTimeoutMilliseconds: Long = MAXIMUM_MAC_SEND_TIMEOUT_MILLISECONDS,
    private val onMetrics: (VoiceQueueMetrics) -> Unit = {},
    private val onFailure: (VoiceFrontendErrorCode) -> Unit = {},
) {
    init {
        require(sendTimeoutMilliseconds in 1..MAXIMUM_MAC_SEND_TIMEOUT_MILLISECONDS)
    }

    private val lock = Any()
    private val pending = ArrayDeque<VoiceAudioChunk>(2)
    private var workerRunning = false
    private var activeChunk = false
    private var accepting = true
    private var canceled = false
    private var bufferedAudioMilliseconds = 0
    private var failure: VoiceFrontendErrorCode? = null
    private var worker: Job? = null
    private var idle = completedSignal()

    fun submit(chunk: VoiceAudioChunk): VoiceQueueSubmitResult {
        var jobToStart: Job? = null
        val result: VoiceQueueSubmitResult
        val metrics: VoiceQueueMetrics?
        synchronized(lock) {
            val depth = pending.size + if (activeChunk) 1 else 0
            result = when {
                !accepting -> VoiceQueueSubmitResult.CLOSED
                depth >= MAXIMUM_OCCUPIED_CHUNKS -> VoiceQueueSubmitResult.QUEUE_FULL
                bufferedAudioMilliseconds + chunk.encodedDurationMilliseconds > VoiceAudioSpec.MAX_BACKLOG_MS -> {
                    VoiceQueueSubmitResult.BACKLOG_LIMIT
                }
                else -> {
                    pending.addLast(chunk)
                    bufferedAudioMilliseconds += chunk.encodedDurationMilliseconds
                    if (!workerRunning) {
                        workerRunning = true
                        idle = CompletableDeferred()
                        worker = scope.launch(workerDispatcher, start = CoroutineStart.LAZY) { drain() }
                        jobToStart = worker
                    }
                    VoiceQueueSubmitResult.ACCEPTED
                }
            }
            metrics = if (result == VoiceQueueSubmitResult.ACCEPTED) metricsLocked() else null
        }
        metrics?.let(onMetrics)
        jobToStart?.start()
        return result
    }

    fun metrics(): VoiceQueueMetrics = synchronized(lock) { metricsLocked() }

    suspend fun closeAndDrain(): VoiceQueueCloseResult {
        val signal: CompletableDeferred<Unit>
        synchronized(lock) {
            accepting = false
            signal = idle
        }
        signal.await()
        return synchronized(lock) {
            when {
                canceled -> VoiceQueueCloseResult.CANCELED
                failure != null -> VoiceQueueCloseResult.FAILED(checkNotNull(failure))
                else -> VoiceQueueCloseResult.DRAINED
            }
        }
    }

    suspend fun cancel() {
        val job: Job?
        synchronized(lock) {
            accepting = false
            canceled = true
            pending.clear()
            bufferedAudioMilliseconds = 0
            job = worker
        }
        job?.cancelAndJoin()
        val metrics: VoiceQueueMetrics
        synchronized(lock) {
            activeChunk = false
            workerRunning = false
            worker = null
            idle.complete(Unit)
            metrics = metricsLocked()
        }
        onMetrics(metrics)
    }

    private suspend fun drain() {
        while (true) {
            var chunk: VoiceAudioChunk? = null
            val metrics: VoiceQueueMetrics
            synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    activeChunk = false
                    workerRunning = false
                    worker = null
                    idle.complete(Unit)
                } else {
                    chunk = next
                    activeChunk = true
                }
                metrics = metricsLocked()
            }
            onMetrics(metrics)
            val current = chunk ?: return
            try {
                withTimeout(sendTimeoutMilliseconds) {
                    for (packet in packetizer.packetize(sessionId, current)) {
                        transport.sendPcm(packet)
                    }
                }
            } catch (error: CancellationException) {
                val shouldReport = synchronized(lock) { !canceled }
                if (shouldReport) markFailure(VoiceFrontendErrorCode.MAC_TRANSPORT)
                throw error
            } catch (_: Exception) {
                markFailure(VoiceFrontendErrorCode.MAC_TRANSPORT)
                return
            }

            val completedMetrics: VoiceQueueMetrics
            synchronized(lock) {
                activeChunk = false
                bufferedAudioMilliseconds -= current.encodedDurationMilliseconds
                completedMetrics = metricsLocked()
            }
            onMetrics(completedMetrics)
        }
    }

    private fun markFailure(code: VoiceFrontendErrorCode) {
        val metrics: VoiceQueueMetrics
        val completion: CompletableDeferred<Unit>
        synchronized(lock) {
            if (failure != null || canceled) return
            failure = code
            accepting = false
            pending.clear()
            activeChunk = false
            bufferedAudioMilliseconds = 0
            workerRunning = false
            worker = null
            completion = idle
            metrics = metricsLocked()
        }
        onMetrics(metrics)
        onFailure(code)
        completion.complete(Unit)
    }

    private fun metricsLocked(): VoiceQueueMetrics = VoiceQueueMetrics(
        depth = pending.size + if (activeChunk) 1 else 0,
        active = activeChunk,
        queuedAudioMilliseconds = bufferedAudioMilliseconds,
    )

    private companion object {
        const val MAXIMUM_OCCUPIED_CHUNKS = 3
        const val MAXIMUM_MAC_SEND_TIMEOUT_MILLISECONDS = 10_000L

        fun completedSignal(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { complete(Unit) }
    }
}
