package io.github.verybigsad.pimobile.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout


enum class TransportPathKind {
    DIRECT,
    RELAY,
}

interface PathConnection {
    val kind: TransportPathKind
    val generation: Long
    val channel: DuplexByteChannel
    suspend fun authenticate()
    suspend fun close()
}

fun interface PathAttempt {
    suspend fun connect(generation: Long): PathConnection
}

class PathRaceCoordinator(
    initialGeneration: Long = 0,
    private val pathTimeoutMillis: Long = 20_000,
) {
    private val generations = AtomicLong(initialGeneration)

    init {
        require(pathTimeoutMillis in 1..60_000)
    }

    suspend fun connect(attempts: List<PathAttempt>): PathConnection {
        if (attempts.isEmpty()) throw NetworkException(NetworkError.PATHS_FAILED, "No transport paths were supplied")
        val generation = generations.updateAndGet { current ->
            if (current == Long.MAX_VALUE) throw NetworkException(NetworkError.PATHS_FAILED, "Transport generation was exhausted")
            current + 1
        }
        return coroutineScope {
            val results = Channel<RaceResult>(attempts.size)
            val jobs = attempts.map { attempt ->
                async {
                    var connection: PathConnection? = null
                    var claim: AtomicBoolean? = null
                    try {
                        withTimeout(pathTimeoutMillis) {
                            connection = attempt.connect(generation)
                            if (connection.generation != generation) {
                                throw NetworkException(NetworkError.PATHS_FAILED, "Transport path returned a stale generation")
                            }
                            connection.authenticate()
                        }
                        val authenticated = requireNotNull(connection)
                        val acknowledgement = CompletableDeferred<Unit>()
                        claim = AtomicBoolean(false)
                        results.send(RaceResult.Success(authenticated, claim, acknowledgement))
                        acknowledgement.await()
                    } catch (error: TimeoutCancellationException) {
                        results.trySend(RaceResult.Failure(error))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        results.trySend(RaceResult.Failure(error))
                    } finally {
                        if (claim?.get() != true) runCatching { connection?.close() }
                    }
                }
            }
            var failures = 0
            var winner: PathConnection? = null
            while (failures < attempts.size && winner == null) {
                when (val result = results.receive()) {
                    is RaceResult.Failure -> failures += 1
                    is RaceResult.Success -> {
                        winner = result.connection
                        result.claim.set(true)
                        result.acknowledgement.complete(Unit)
                    }
                }
            }
            jobs.forEach { job -> if (!job.isCompleted) job.cancelAndJoin() }
            results.close()
            winner ?: throw NetworkException(NetworkError.PATHS_FAILED, "All transport paths failed authentication")
        }
    }

    fun currentGeneration(): Long = generations.get()

    private sealed interface RaceResult {
        data class Success(
            val connection: PathConnection,
            val claim: AtomicBoolean,
            val acknowledgement: CompletableDeferred<Unit>,
        ) : RaceResult

        data class Failure(val error: Throwable) : RaceResult
    }
}
