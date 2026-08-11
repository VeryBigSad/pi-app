package io.github.verybigsad.pimobile.network

import java.time.Duration
import java.time.Instant

class ReplayWindow(
    private val retention: Duration,
    private val maxEntries: Int,
) {
    private val entries = linkedMapOf<String, Instant>()

    @Synchronized
    fun consume(key: String, now: Instant, replayError: NetworkError) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().value.isAfter(now)) iterator.remove()
        }
        if (entries.containsKey(key)) {
            throw NetworkException(replayError, "Authenticated value was already consumed")
        }
        if (entries.size >= maxEntries) {
            throw NetworkException(NetworkError.REPLAY_CACHE_FULL, "Replay window capacity was reached")
        }
        entries[key] = now.plus(retention)
    }

    @Synchronized
    fun size(): Int = entries.size
}
