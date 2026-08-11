package io.github.verybigsad.pimobile.terminal

import java.io.ByteArrayOutputStream

internal const val MaximumPimbTerminalDataBytes = 65_536
private const val MaximumUtf8BackoffBytes = 3

internal enum class TerminalRechunkRejection {
    FRAME_TOO_LARGE,
    PACKET_TOO_LARGE,
    WIRE_SEQUENCE_GAP,
    WIRE_SEQUENCE_EXHAUSTED,
}

internal sealed interface TerminalCoalesceResult {
    data object Buffered : TerminalCoalesceResult

    data class Coalesced(val packet: TerminalInput) : TerminalCoalesceResult

    data class Rejected(val reason: TerminalRechunkRejection) : TerminalCoalesceResult
}

/**
 * Rechunks terminal packets between the WebView bridge (up to [MaximumTerminalDataBytes]
 * data bytes per packet) and PIMB TERMINAL_BYTES frames (up to [MaximumPimbTerminalDataBytes]
 * data bytes per frame). Use one instance per direction: [split] is the outbound
 * bridge-to-PIMB direction and [accept] is the inbound PIMB-to-bridge direction.
 *
 * Wire convention, identical for both peers:
 * - A bridge packet is carried as zero or more data frames followed by exactly one
 *   zero-data terminator frame, so packet boundaries never depend on chunk sizes.
 * - Every frame repeats the packet generation and takes the next contiguous wire
 *   sequence. Wire sequences count frames and restart at zero per generation;
 *   bridge sequences count packets and are reassigned contiguously by the receiver,
 *   so packet numbering survives end to end while frame numbering stays contiguous.
 * - Data frames never exceed [MaximumPimbTerminalDataBytes] and never split a UTF-8
 *   code point: a cut landing on a continuation byte backs off up to three bytes,
 *   which always reaches a boundary for well-formed UTF-8. For invalid byte strings
 *   the cut happens after the bounded back-off; it never loops or stalls.
 * - A generation change restarts both directions' stream state; partially buffered
 *   packets from a previous generation are dropped, never replayed.
 *
 * Inbound is fail-closed: the first [TerminalCoalesceResult.Rejected] poisons the
 * direction and every later [accept] rejects with the same reason until [reset].
 * Callers must treat rejection as fatal to the connection, never replay input.
 */
internal class TerminalRechunker {
    private var splitGeneration: ULong? = null
    private var nextWireSequence: ULong = 0u
    private var coalesceGeneration: ULong? = null
    private var expectedWireSequence: ULong? = 0u
    private var nextBridgeSequence: ULong = 0u
    private var inboundRejection: TerminalRechunkRejection? = null
    private val coalesceBuffer = ByteArrayOutputStream()

    fun split(packet: TerminalInput): List<TerminalInput> {
        require(packet.bytes.size <= MaximumTerminalDataBytes) { "bridge packet is out of bounds" }
        if (splitGeneration != packet.terminalGeneration) {
            splitGeneration = packet.terminalGeneration
            nextWireSequence = 0u
        }
        val frames = mutableListOf<TerminalInput>()
        var offset = 0
        while (offset < packet.bytes.size) {
            val end = utf8SafeEnd(
                packet.bytes,
                offset,
                minOf(offset + MaximumPimbTerminalDataBytes, packet.bytes.size),
            )
            frames += TerminalInput(packet.terminalGeneration, advanceWireSequence(), packet.bytes.copyOfRange(offset, end))
            offset = end
        }
        frames += TerminalInput(packet.terminalGeneration, advanceWireSequence(), ByteArray(0))
        return frames
    }

    fun accept(frame: TerminalInput): TerminalCoalesceResult {
        inboundRejection?.let { return TerminalCoalesceResult.Rejected(it) }
        if (frame.bytes.size > MaximumPimbTerminalDataBytes) {
            return reject(TerminalRechunkRejection.FRAME_TOO_LARGE)
        }
        if (coalesceGeneration != frame.terminalGeneration) {
            coalesceGeneration = frame.terminalGeneration
            expectedWireSequence = 0u
            nextBridgeSequence = 0u
            coalesceBuffer.reset()
        }
        val expected = expectedWireSequence
            ?: return reject(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED)
        if (frame.sequence != expected) {
            return reject(TerminalRechunkRejection.WIRE_SEQUENCE_GAP)
        }
        expectedWireSequence = if (expected == ULong.MAX_VALUE) null else expected + 1u
        if (frame.bytes.isNotEmpty()) {
            if (coalesceBuffer.size() + frame.bytes.size > MaximumTerminalDataBytes) {
                return reject(TerminalRechunkRejection.PACKET_TOO_LARGE)
            }
            coalesceBuffer.write(frame.bytes)
            return TerminalCoalesceResult.Buffered
        }
        if (nextBridgeSequence == ULong.MAX_VALUE) {
            return reject(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED)
        }
        val packet = TerminalInput(frame.terminalGeneration, nextBridgeSequence, coalesceBuffer.toByteArray())
        coalesceBuffer.reset()
        nextBridgeSequence += 1u
        return TerminalCoalesceResult.Coalesced(packet)
    }

    fun reset() {
        splitGeneration = null
        nextWireSequence = 0u
        coalesceGeneration = null
        expectedWireSequence = 0u
        nextBridgeSequence = 0u
        inboundRejection = null
        coalesceBuffer.reset()
    }

    private fun advanceWireSequence(): ULong {
        val current = nextWireSequence
        check(current != ULong.MAX_VALUE) { "terminal wire sequence is exhausted" }
        nextWireSequence = current + 1u
        return current
    }

    private fun reject(reason: TerminalRechunkRejection): TerminalCoalesceResult.Rejected {
        inboundRejection = reason
        return TerminalCoalesceResult.Rejected(reason)
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
}
