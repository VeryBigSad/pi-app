package io.github.verybigsad.pimobile.terminal

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalRechunkerTest {
    @Test
    fun boundarySizesUseContiguousWireSequencesWithoutTerminators() {
        val rechunker = TerminalRechunker()
        rechunker.reset(7u)
        val sizes = listOf(0, 1, MaximumPimbTerminalDataBytes - 1, MaximumPimbTerminalDataBytes, MaximumPimbTerminalDataBytes + 1)
        var expectedSequence = 0uL

        sizes.forEach { size ->
            val bytes = ByteArray(size) { (it % 127).toByte() }
            val frames = rechunker.split(7u, bytes)
            assertArrayEquals(bytes, frames.flatMap { it.bytes.asIterable() }.toByteArray())
            assertTrue(frames.all { it.bytes.size <= MaximumPimbTerminalDataBytes })
            frames.forEach { frame ->
                assertEquals(expectedSequence, frame.sequence)
                expectedSequence += 1u
            }
        }
    }

    @Test
    fun largeUtf8InputDoesNotSplitCodePoints() {
        val bytes = ("a".repeat(MaximumPimbTerminalDataBytes - 1) + "🙂tail").toByteArray()
        val rechunker = TerminalRechunker()
        rechunker.reset(1u)
        val frames = rechunker.split(1u, bytes)

        frames.forEach {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(it.bytes))
        }
        assertArrayEquals(bytes, frames.flatMap { it.bytes.asIterable() }.toByteArray())
    }

    @Test
    fun generationChangeRequiresExplicitResetAndRestartsAtZero() {
        val rechunker = TerminalRechunker()
        rechunker.reset(4u)
        assertEquals(0uL, rechunker.split(4u, byteArrayOf(1)).single().sequence)
        assertRejected(TerminalRechunkRejection.GENERATION_CHANGE) {
            rechunker.split(5u, byteArrayOf(2))
        }
        rechunker.reset(5u)
        assertEquals(0uL, rechunker.split(5u, byteArrayOf(3)).single().sequence)
    }

    @Test
    fun uint64ExhaustionIsRejectedBeforePartialInput() {
        val rechunker = TerminalRechunker(ULong.MAX_VALUE - 1u)
        rechunker.reset(2u)
        assertRejected(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED) {
            rechunker.split(2u, ByteArray(MaximumPimbTerminalDataBytes * 2 + 1))
        }
        assertEquals(
            listOf(ULong.MAX_VALUE - 1u, ULong.MAX_VALUE),
            rechunker.split(2u, ByteArray(MaximumPimbTerminalDataBytes + 1)).map { it.sequence },
        )
        assertRejected(TerminalRechunkRejection.WIRE_SEQUENCE_EXHAUSTED) {
            rechunker.split(2u, byteArrayOf(1))
        }
    }

    @Test
    fun logicalInputBoundIsStrict() {
        val rechunker = TerminalRechunker()
        rechunker.reset(1u)
        assertRejected(TerminalRechunkRejection.LOGICAL_INPUT_TOO_LARGE) {
            rechunker.split(1u, ByteArray(MaximumTerminalDataBytes + 1))
        }
    }

    private fun assertRejected(reason: TerminalRechunkRejection, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (error: TerminalRechunkException) {
            assertEquals(reason, error.reason)
        }
    }
}
