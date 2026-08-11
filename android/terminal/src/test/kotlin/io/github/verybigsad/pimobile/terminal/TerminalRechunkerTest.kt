package io.github.verybigsad.pimobile.terminal

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalRechunkerTest {
    private fun coalesce(frames: List<TerminalInput>): Pair<TerminalRechunker, List<TerminalInput>> {
        val rechunker = TerminalRechunker()
        val packets = mutableListOf<TerminalInput>()
        for (frame in frames) {
            when (val result = rechunker.accept(frame)) {
                TerminalCoalesceResult.Buffered -> Unit
                is TerminalCoalesceResult.Coalesced -> packets += result.packet
                is TerminalCoalesceResult.Rejected -> fail("unexpected rejection: ${result.reason}")
            }
        }
        return rechunker to packets
    }

    private fun roundTrip(packets: List<TerminalInput>): List<TerminalInput> {
        val splitter = TerminalRechunker()
        val frames = packets.flatMap(splitter::split)
        return coalesce(frames).second
    }

    private fun assertCoalesced(
        result: TerminalCoalesceResult,
        generation: ULong,
        sequence: ULong,
        bytes: ByteArray,
    ) {
        val packet = (result as? TerminalCoalesceResult.Coalesced)?.packet
            ?: throw AssertionError("expected a coalesced packet, got $result")
        assertEquals(generation, packet.terminalGeneration)
        assertEquals(sequence, packet.sequence)
        assertArrayEquals(bytes, packet.bytes)
    }

    private fun assertValidUtf8(bytes: ByteArray) {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes))
    }

    @Test
    fun splitFramesRespectPimbBoundAndKeepWireSequencesContiguous() {
        val splitter = TerminalRechunker()
        val frames = splitter.split(TerminalInput(9u, 0u, Random(42).nextBytes(MaximumTerminalDataBytes)))

        assertEquals(17, frames.size)
        frames.forEachIndexed { index, frame ->
            assertEquals(9uL, frame.terminalGeneration)
            assertEquals(index.toULong(), frame.sequence)
            assertTrue(frame.bytes.size <= MaximumPimbTerminalDataBytes)
        }
        assertArrayEquals(ByteArray(0), frames.last().bytes)
    }

    @Test
    fun exactBoundarySizesGetExplicitTerminators() {
        val splitter = TerminalRechunker()
        val exact = splitter.split(TerminalInput(1u, 0u, ByteArray(MaximumPimbTerminalDataBytes) { 0x61 }))
        assertEquals(listOf(MaximumPimbTerminalDataBytes, 0), exact.map { it.bytes.size })

        val doubleExact = splitter.split(TerminalInput(1u, 1u, ByteArray(2 * MaximumPimbTerminalDataBytes) { 0x62 }))
        assertEquals(listOf(MaximumPimbTerminalDataBytes, MaximumPimbTerminalDataBytes, 0), doubleExact.map { it.bytes.size })

        val below = splitter.split(TerminalInput(1u, 2u, ByteArray(MaximumPimbTerminalDataBytes - 1) { 0x63 }))
        assertEquals(listOf(MaximumPimbTerminalDataBytes - 1, 0), below.map { it.bytes.size })

        val above = splitter.split(TerminalInput(1u, 3u, ByteArray(MaximumPimbTerminalDataBytes + 1) { 0x64 }))
        assertEquals(listOf(MaximumPimbTerminalDataBytes, 1, 0), above.map { it.bytes.size })
    }

    @Test
    fun emptyAndSmallPacketsRoundTrip() {
        val packets = listOf(
            TerminalInput(5u, 0u, ByteArray(0)),
            TerminalInput(5u, 1u, byteArrayOf(0x1b, 0x5b, 0x41)),
        )
        val coalesced = roundTrip(packets)

        assertEquals(2, coalesced.size)
        assertEquals(0uL, coalesced[0].sequence)
        assertEquals(1uL, coalesced[1].sequence)
        assertEquals(5uL, coalesced[0].terminalGeneration)
        assertArrayEquals(ByteArray(0), coalesced[0].bytes)
        assertArrayEquals(byteArrayOf(0x1b, 0x5b, 0x41), coalesced[1].bytes)
    }

    @Test
    fun largePacketsRoundTripWithPacketSequenceContinuity() {
        val random = Random(7)
        val packets = (0 until 6).map { index ->
            TerminalInput(ULong.MAX_VALUE, index.toULong(), random.nextBytes(MaximumTerminalDataBytes - index * 65_000))
        }
        val coalesced = roundTrip(packets)

        assertEquals(packets.size, coalesced.size)
        packets.forEachIndexed { index, packet ->
            assertEquals(packet.terminalGeneration, coalesced[index].terminalGeneration)
            assertEquals(index.toULong(), coalesced[index].sequence)
            assertArrayEquals(packet.bytes, coalesced[index].bytes)
        }
    }

    @Test
    fun splitsNeverBreakUtf8CodePoints() {
        val text = buildString {
            var bytes = 0
            var index = 0
            while (bytes < MaximumTerminalDataBytes) {
                val piece = when (index % 4) {
                    0 -> "a"
                    1 -> "é"
                    2 -> "界"
                    else -> "🙂"
                }
                append(piece)
                bytes += piece.toByteArray(Charsets.UTF_8).size
                index += 1
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val frames = TerminalRechunker().split(TerminalInput(3u, 0u, bytes))

        assertTrue(frames.size > 2)
        frames.dropLast(1).forEach { frame ->
            assertTrue(frame.bytes.isNotEmpty())
            assertValidUtf8(frame.bytes)
        }
        val coalesced = coalesce(frames).second
        assertEquals(1, coalesced.size)
        assertArrayEquals(bytes, coalesced[0].bytes)
        assertEquals(text, String(coalesced[0].bytes, Charsets.UTF_8))
    }

    @Test
    fun multiByteCharacterStraddlingTheExactFrameBoundaryIsNotSplit() {
        val prefix = ByteArray(MaximumPimbTerminalDataBytes - 1) { 0x61 }
        val emoji = "🙂".toByteArray(Charsets.UTF_8)
        assertEquals(4, emoji.size)
        val bytes = prefix + emoji + ByteArray(10) { 0x62 }
        val frames = TerminalRechunker().split(TerminalInput(1u, 0u, bytes))

        assertEquals(3, frames.size)
        assertEquals(MaximumPimbTerminalDataBytes - 1, frames[0].bytes.size)
        assertEquals(4 + 10, frames[1].bytes.size)
        frames.dropLast(1).forEach { assertValidUtf8(it.bytes) }
        assertArrayEquals(bytes, coalesce(frames).second.single().bytes)
    }

    @Test
    fun invalidUtf8StillTerminatesAndRoundTrips() {
        val bytes = ByteArray(MaximumPimbTerminalDataBytes + 4) { 0x80.toByte() } + byteArrayOf(0x61)
        val frames = TerminalRechunker().split(TerminalInput(1u, 0u, bytes))

        assertTrue(frames.all { it.bytes.size <= MaximumPimbTerminalDataBytes })
        assertArrayEquals(bytes, coalesce(frames).second.single().bytes)
    }

    @Test
    fun wireSequenceGapIsRejectedAndPoisonsUntilReset() {
        val rechunker = TerminalRechunker()
        assertEquals(TerminalCoalesceResult.Buffered, rechunker.accept(TerminalInput(2u, 0u, byteArrayOf(1))))

        val gap = rechunker.accept(TerminalInput(2u, 3u, byteArrayOf(2)))
        assertEquals(
            TerminalCoalesceResult.Rejected(TerminalRechunkRejection.WIRE_SEQUENCE_GAP),
            gap,
        )
        assertEquals(
            TerminalCoalesceResult.Rejected(TerminalRechunkRejection.WIRE_SEQUENCE_GAP),
            rechunker.accept(TerminalInput(2u, 1u, byteArrayOf(2))),
        )

        rechunker.reset()
        assertEquals(TerminalCoalesceResult.Buffered, rechunker.accept(TerminalInput(2u, 0u, byteArrayOf(2))))
        assertCoalesced(rechunker.accept(TerminalInput(2u, 1u, ByteArray(0))), 2u, 0u, byteArrayOf(2))
    }

    @Test
    fun oversizedFrameIsRejected() {
        val rechunker = TerminalRechunker()
        val result = rechunker.accept(TerminalInput(1u, 0u, ByteArray(MaximumPimbTerminalDataBytes + 1)))
        assertEquals(TerminalCoalesceResult.Rejected(TerminalRechunkRejection.FRAME_TOO_LARGE), result)
    }

    @Test
    fun bufferedBytesBeyondTheBridgeBoundAreRejected() {
        val rechunker = TerminalRechunker()
        var sequence = 0uL
        var last: TerminalCoalesceResult = TerminalCoalesceResult.Buffered
        while (last == TerminalCoalesceResult.Buffered) {
            last = rechunker.accept(TerminalInput(1u, sequence, ByteArray(MaximumPimbTerminalDataBytes)))
            sequence += 1u
        }
        assertEquals(TerminalCoalesceResult.Rejected(TerminalRechunkRejection.PACKET_TOO_LARGE), last)
        assertEquals(
            TerminalCoalesceResult.Rejected(TerminalRechunkRejection.PACKET_TOO_LARGE),
            rechunker.accept(TerminalInput(1u, sequence, ByteArray(0))),
        )
    }

    @Test
    fun generationChangeRestartsBothDirectionsAndDropsPartialPackets() {
        val splitter = TerminalRechunker()
        splitter.split(TerminalInput(1u, 0u, ByteArray(100) { 0x61 }))
        val fresh = splitter.split(TerminalInput(2u, 0u, ByteArray(10) { 0x62 }))
        assertEquals(0uL, fresh.first().sequence)

        val rechunker = TerminalRechunker()
        assertEquals(TerminalCoalesceResult.Buffered, rechunker.accept(TerminalInput(1u, 0u, byteArrayOf(1))))
        assertEquals(TerminalCoalesceResult.Buffered, rechunker.accept(fresh[0]))
        assertCoalesced(rechunker.accept(fresh[1]), 2u, 0u, ByteArray(10) { 0x62 })
    }

    @Test
    fun duplicateWireSequenceIsRejected() {
        val rechunker = TerminalRechunker()
        assertEquals(TerminalCoalesceResult.Buffered, rechunker.accept(TerminalInput(1u, 0u, byteArrayOf(1))))
        assertCoalesced(rechunker.accept(TerminalInput(1u, 1u, ByteArray(0))), 1u, 0u, byteArrayOf(1))
        assertEquals(
            TerminalCoalesceResult.Rejected(TerminalRechunkRejection.WIRE_SEQUENCE_GAP),
            rechunker.accept(TerminalInput(1u, 1u, byteArrayOf(1))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedBridgePacketIsRejectedBeforeSplitting() {
        TerminalRechunker().split(TerminalInput(1u, 0u, ByteArray(MaximumTerminalDataBytes + 1)))
    }
}
