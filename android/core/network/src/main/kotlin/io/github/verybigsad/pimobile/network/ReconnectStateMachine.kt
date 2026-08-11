package io.github.verybigsad.pimobile.network

import kotlin.math.min
import kotlin.random.Random

sealed interface ReconnectState {
    data object Idle : ReconnectState
    data class Connecting(val generation: Long, val failureCount: Int) : ReconnectState
    data class Connected(val generation: Long, val path: TransportPathKind) : ReconnectState
    data class Waiting(val failureCount: Int, val delayMillis: Long) : ReconnectState
    data object Stopped : ReconnectState
}

sealed interface ReconnectEvent {
    data object Start : ReconnectEvent
    data class Authenticated(val generation: Long, val path: TransportPathKind) : ReconnectEvent
    data class Failed(val generation: Long) : ReconnectEvent
    data class Disconnected(val generation: Long) : ReconnectEvent
    data object DelayElapsed : ReconnectEvent
    data object Stop : ReconnectEvent
}

class BackoffPolicy(
    private val initialMillis: Long = 500,
    private val maximumMillis: Long = 30_000,
    private val jitter: (Long) -> Long = { ceiling -> Random.Default.nextLong(ceiling + 1) },
) {
    init {
        require(initialMillis > 0 && maximumMillis >= initialMillis && maximumMillis <= 300_000)
    }

    fun delayMillis(failureCount: Int): Long {
        require(failureCount > 0)
        var ceiling = initialMillis
        repeat(min(failureCount - 1, 62)) {
            ceiling = if (ceiling > maximumMillis / 2) maximumMillis else ceiling * 2
        }
        ceiling = min(ceiling, maximumMillis)
        return jitter(ceiling).also { require(it in 0..ceiling) }
    }
}

class ReconnectStateMachine(
    private val backoff: BackoffPolicy = BackoffPolicy(),
) {
    @Volatile
    var state: ReconnectState = ReconnectState.Idle
        private set
    private var lastGeneration = 0L

    @Synchronized
    fun transition(event: ReconnectEvent): ReconnectState {
        state = when (event) {
            ReconnectEvent.Stop -> ReconnectState.Stopped
            ReconnectEvent.Start -> when (state) {
                ReconnectState.Idle -> connecting(0)
                else -> invalid()
            }
            is ReconnectEvent.Authenticated -> when (val current = state) {
                is ReconnectState.Connecting -> if (current.generation == event.generation) {
                    ReconnectState.Connected(event.generation, event.path)
                } else {
                    invalid()
                }
                else -> invalid()
            }
            is ReconnectEvent.Failed -> when (val current = state) {
                is ReconnectState.Connecting -> if (current.generation == event.generation) waiting(current.failureCount + 1) else invalid()
                else -> invalid()
            }
            is ReconnectEvent.Disconnected -> when (val current = state) {
                is ReconnectState.Connected -> if (current.generation == event.generation) waiting(1) else invalid()
                else -> invalid()
            }
            ReconnectEvent.DelayElapsed -> when (val current = state) {
                is ReconnectState.Waiting -> connecting(current.failureCount)
                else -> invalid()
            }
        }
        return state
    }

    private fun connecting(failureCount: Int): ReconnectState.Connecting {
        if (lastGeneration == Long.MAX_VALUE) invalid()
        lastGeneration += 1
        return ReconnectState.Connecting(lastGeneration, failureCount)
    }

    private fun waiting(failureCount: Int): ReconnectState.Waiting = ReconnectState.Waiting(
        failureCount,
        backoff.delayMillis(failureCount),
    )

    private fun invalid(): Nothing = throw NetworkException(NetworkError.STATE_TRANSITION_INVALID, "Reconnect transition is invalid")
}
