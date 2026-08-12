package io.github.verybigsad.pimobile.terminal

internal const val MaximumPimbTerminalDataBytes = 65_536
private const val MaximumUtf8BackoffBytes = 3

/** Stable rejection reasons for the outbound terminal wire rechunker. */
enum class TerminalRechunkRejection {
    GENERATION_CHANGE,
    LOGICAL_INPUT_TOO_LARGE,
    WIRE_SEQUENCE_EXHAUSTED,
}

class TerminalRechunkException(
    val reason: TerminalRechunkRejection,
) : IllegalStateException(reason.name)

/**
 * Converts logical WebView input into bounded PIMB terminal frames.
 *
 * One instance belongs to one connection. Generation transitions are explicit through [reset];
 * [split] rejects stale generations and allocates contiguous uint64 wire sequences atomically.
 */
class TerminalRechunker(
    private val initialSequence: ULong = 0uL,
) {
    private var generation: ULong? = null
    private var nextWireSequence: ULong? = initialSequence

    fun reset(terminalGeneration: ULong) {
        generation = terminalGeneration
        nextWireSequence = initialSequence
    }

    fun deactivate(terminalGeneration: ULong) {
        if (generation == terminalGeneration) {
            generation = null
            nextWireSequence = initialSequence
        }
    }

    fun split(terminalGeneration: ULong, bytes: ByteArray): List<TerminalInput> {
        if (generation != terminalGeneration) reject(TerminalRechunkRejection.GENERATION_CHANGE)
        if (bytes.size > MaximumTerminalDataBytes) reject(TerminalRechunkRejection.LOGICAL_INPUT_TOO_LARGE)

        val ranges = chunkRanges(bytes)
        val firstSequence = nextWireSequence ?: reject(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED)
        val sequenceDelta = (ranges.size - 1).toULong()
        if (sequenceDelta > ULong.MAX_VALUE - firstSequence) {
            reject(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED)
        }

        val frames = ranges.mapIndexed { index, range ->
            TerminalInput(
                terminalGeneration = terminalGeneration,
                sequence = firstSequence + index.toULong(),
                bytes = bytes.copyOfRange(range.first, range.last + 1),
            )
        }
        val lastSequence = firstSequence + sequenceDelta
        nextWireSequence = if (lastSequence == ULong.MAX_VALUE) null else lastSequence + 1u
        return frames
    }

    private fun chunkRanges(bytes: ByteArray): List<IntRange> {
        if (bytes.isEmpty()) return listOf(0 until 0)
        val ranges = ArrayList<IntRange>()
        var offset = 0
        while (offset < bytes.size) {
            val end = utf8SafeEnd(
                bytes,
                offset,
                minOf(offset + MaximumPimbTerminalDataBytes, bytes.size),
            )
            ranges += offset until end
            offset = end
        }
        return ranges
    }

    private fun utf8SafeEnd(bytes: ByteArray, start: Int, limit: Int): Int {
        if (limit == bytes.size) return limit
        var end = limit
        var backoff = 0
        while (end > start && backoff < MaximumUtf8BackoffBytes && bytes[end].isUtf8Continuation()) {
            end -= 1
            backoff += 1
        }
        return end
    }

    private fun Byte.isUtf8Continuation(): Boolean = toInt() and 0b1100_0000 == 0b1000_0000

    private fun reject(reason: TerminalRechunkRejection): Nothing = throw TerminalRechunkException(reason)
}
