package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.terminal.TerminalInput
import io.github.verybigsad.pimobile.terminal.TerminalRechunkException
import io.github.verybigsad.pimobile.terminal.TerminalRechunkRejection
import io.github.verybigsad.pimobile.terminal.TerminalRechunker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal enum class TerminalInputWriteError {
    CLOSED,
    GENERATION_CHANGED,
    QUEUE_EXHAUSTED,
    SEQUENCE_EXHAUSTED,
    TOO_LARGE,
    UNCERTAIN_DELIVERY,
}

internal class TerminalInputWriteException(
    val code: TerminalInputWriteError,
    cause: Throwable? = null,
) : IllegalStateException(code.name, cause)

/** Serializes and rechunks every logical terminal input owned by one host connection. */
internal class TerminalInputActor(
    scope: CoroutineScope,
    private val rechunker: TerminalRechunker = TerminalRechunker(),
    private val sendFrame: suspend (TerminalInput) -> Unit,
) {
    private sealed interface Request {
        data class Activate(val generation: ULong) : Request
        data class Deactivate(val generation: ULong) : Request
        data class Input(
            val generation: ULong,
            val bytes: ByteArray,
            val reservedFrames: Int,
            val completion: CompletableDeferred<Unit>,
        ) : Request
    }

    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val lock = Any()
    private var desiredGeneration: ULong? = null
    private var queuedFrames = 0
    private var queuedBytes = 0
    private var closed = false
    private var terminalFailure: TerminalInputWriteException? = null
    private val job: Job = scope.launch { consume() }

    fun activate(generation: ULong) {
        synchronized(lock) {
            ensureAvailable()
            if (desiredGeneration == generation) return
            desiredGeneration = generation
            check(requests.trySend(Request.Activate(generation)).isSuccess)
        }
    }

    fun deactivate(generation: ULong) {
        synchronized(lock) {
            if (desiredGeneration != generation) return
            desiredGeneration = null
            requests.trySend(Request.Deactivate(generation))
        }
    }

    fun submit(generation: ULong, bytes: ByteArray): Deferred<Unit> {
        val completion = CompletableDeferred<Unit>()
        val reservedFrames = maximumFrameCount(bytes.size)
        synchronized(lock) {
            val failure = terminalFailure
            when {
                failure != null -> completion.completeExceptionally(failure)
                closed -> completion.completeExceptionally(TerminalInputWriteException(TerminalInputWriteError.CLOSED))
                desiredGeneration != generation -> completion.completeExceptionally(
                    TerminalInputWriteException(TerminalInputWriteError.GENERATION_CHANGED),
                )
                bytes.size > MAX_LOGICAL_INPUT_BYTES -> completion.completeExceptionally(
                    TerminalInputWriteException(TerminalInputWriteError.TOO_LARGE),
                )
                queuedFrames > MAX_QUEUED_FRAMES - reservedFrames || queuedBytes > MAX_QUEUED_BYTES - bytes.size ->
                    completion.completeExceptionally(TerminalInputWriteException(TerminalInputWriteError.QUEUE_EXHAUSTED))
                else -> {
                    queuedFrames += reservedFrames
                    queuedBytes += bytes.size
                    if (!requests.trySend(Request.Input(generation, bytes.copyOf(), reservedFrames, completion)).isSuccess) {
                        queuedFrames -= reservedFrames
                        queuedBytes -= bytes.size
                        completion.completeExceptionally(TerminalInputWriteException(TerminalInputWriteError.CLOSED))
                    }
                }
            }
        }
        return completion
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            desiredGeneration = null
            requests.close()
        }
        job.cancel()
    }

    private suspend fun consume() {
        try {
            for (request in requests) {
                when (request) {
                    is Request.Activate -> if (isDesired(request.generation)) rechunker.reset(request.generation)
                    is Request.Deactivate -> rechunker.deactivate(request.generation)
                    is Request.Input -> process(request)
                }
            }
        } catch (_: Throwable) {
        } finally {
            val failure = synchronized(lock) {
                closed = true
                desiredGeneration = null
                terminalFailure ?: TerminalInputWriteException(TerminalInputWriteError.CLOSED)
            }
            while (true) {
                val request = requests.tryReceive().getOrNull() ?: break
                if (request is Request.Input) {
                    release(request)
                    request.completion.completeExceptionally(failure)
                }
            }
        }
    }

    private suspend fun process(request: Request.Input) {
        try {
            terminalFailure?.let {
                request.completion.completeExceptionally(it)
                return
            }
            if (!isDesired(request.generation)) {
                request.completion.completeExceptionally(TerminalInputWriteException(TerminalInputWriteError.GENERATION_CHANGED))
                return
            }
            val frames = try {
                rechunker.split(request.generation, request.bytes)
            } catch (error: TerminalRechunkException) {
                val code = when (error.reason) {
                    TerminalRechunkRejection.GENERATION_CHANGE -> TerminalInputWriteError.GENERATION_CHANGED
                    TerminalRechunkRejection.LOGICAL_INPUT_TOO_LARGE -> TerminalInputWriteError.TOO_LARGE
                    TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED -> TerminalInputWriteError.SEQUENCE_EXHAUSTED
                }
                request.completion.completeExceptionally(TerminalInputWriteException(code, error))
                return
            }
            for (frame in frames) {
                if (!isDesired(request.generation)) {
                    request.completion.completeExceptionally(TerminalInputWriteException(TerminalInputWriteError.GENERATION_CHANGED))
                    return
                }
                try {
                    sendFrame(frame)
                } catch (error: Throwable) {
                    val failure = TerminalInputWriteException(TerminalInputWriteError.UNCERTAIN_DELIVERY, error)
                    poison(failure)
                    request.completion.completeExceptionally(failure)
                    return
                }
            }
            request.completion.complete(Unit)
        } finally {
            release(request)
        }
    }

    private fun poison(failure: TerminalInputWriteException) {
        synchronized(lock) {
            terminalFailure = failure
            desiredGeneration = null
            requests.close(failure)
        }
    }

    private fun release(request: Request.Input) {
        synchronized(lock) {
            queuedFrames -= request.reservedFrames
            queuedBytes -= request.bytes.size
        }
    }

    private fun isDesired(generation: ULong): Boolean = synchronized(lock) {
        !closed && terminalFailure == null && desiredGeneration == generation
    }

    private fun ensureAvailable() {
        terminalFailure?.let { throw it }
        if (closed) throw TerminalInputWriteException(TerminalInputWriteError.CLOSED)
    }

    private fun maximumFrameCount(bytes: Int): Int = maxOf(1, (bytes + MINIMUM_UTF8_SAFE_FRAME_BYTES - 1) / MINIMUM_UTF8_SAFE_FRAME_BYTES)

    private companion object {
        const val MAX_LOGICAL_INPUT_BYTES = 1_048_560
        const val MAX_QUEUED_FRAMES = 512
        const val MAX_QUEUED_BYTES = 8 * 1_024 * 1_024
        const val MINIMUM_UTF8_SAFE_FRAME_BYTES = 65_533
    }
}
