package io.github.verybigsad.pimobile.network

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PathAndReconnectTest {
    @Test
    fun acceptsFirstFullyAuthenticatedGenerationAndClosesLoser() = runBlocking {
        val direct = TestPath(TransportPathKind.DIRECT, 80)
        val relay = TestPath(TransportPathKind.RELAY, 10)
        val coordinator = PathRaceCoordinator()

        val winner = coordinator.connect(
            listOf(
                PathAttempt { generation -> direct.withGeneration(generation) },
                PathAttempt { generation -> relay.withGeneration(generation) },
            ),
        )

        assertThat(winner.kind).isEqualTo(TransportPathKind.RELAY)
        assertThat(winner.generation).isEqualTo(1)
        assertThat(relay.closed.get()).isFalse()
        assertThat(direct.closed.get()).isTrue()
        winner.close()
    }

    @Test
    fun timesOutStalledPathsAndClosesBoth() = runBlocking {
        val direct = TestPath(TransportPathKind.DIRECT, 500)
        val relay = TestPath(TransportPathKind.RELAY, 500)
        val coordinator = PathRaceCoordinator(pathTimeoutMillis = 20)

        val error = runCatching {
            coordinator.connect(
                listOf(
                    PathAttempt { generation -> direct.withGeneration(generation) },
                    PathAttempt { generation -> relay.withGeneration(generation) },
                ),
            )
        }.exceptionOrNull() as NetworkException

        assertThat(error.code).isEqualTo(NetworkError.PATHS_FAILED)
        assertThat(direct.closed.get()).isTrue()
        assertThat(relay.closed.get()).isTrue()
    }

    @Test
    fun reconnectBackoffUsesNewGenerationsAndRejectsStaleEvents() {
        val machine = ReconnectStateMachine(BackoffPolicy(jitter = { it }))
        val first = machine.transition(ReconnectEvent.Start) as ReconnectState.Connecting
        assertThat(first.generation).isEqualTo(1)
        val waiting = machine.transition(ReconnectEvent.Failed(first.generation)) as ReconnectState.Waiting
        assertThat(waiting.delayMillis).isEqualTo(500)
        val second = machine.transition(ReconnectEvent.DelayElapsed) as ReconnectState.Connecting
        assertThat(second.generation).isEqualTo(2)
        machine.transition(ReconnectEvent.Authenticated(second.generation, TransportPathKind.DIRECT))
        val retry = machine.transition(ReconnectEvent.Disconnected(second.generation)) as ReconnectState.Waiting
        assertThat(retry.failureCount).isEqualTo(1)
        val stale = runCatching { machine.transition(ReconnectEvent.Failed(first.generation)) }.exceptionOrNull() as NetworkException
        assertThat(stale.code).isEqualTo(NetworkError.STATE_TRANSITION_INVALID)
        assertThat(machine.transition(ReconnectEvent.Stop)).isEqualTo(ReconnectState.Stopped)
    }

    private class TestPath(
        override val kind: TransportPathKind,
        private val authenticationDelay: Long,
        override val generation: Long = 0,
        val closed: AtomicBoolean = AtomicBoolean(false),
    ) : PathConnection {
        override val channel: DuplexByteChannel = object : DuplexByteChannel {
            override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int = -1
            override suspend fun write(source: ByteArray, offset: Int, length: Int) = Unit
            override suspend fun close() = Unit
        }

        fun withGeneration(value: Long): TestPath = TestPath(kind, authenticationDelay, value, closed)

        override suspend fun authenticate() {
            delay(authenticationDelay)
        }

        override suspend fun close() {
            closed.set(true)
        }
    }
}
