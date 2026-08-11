package io.github.verybigsad.pimobile.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBridgePacketTest {
    @Test
    fun oldOrFeatureIncompleteWebViewIsRefused() {
        assertFalse(terminalEngineCompatible("90.0.4430.210", true, true))
        assertFalse(terminalEngineCompatible("91.0.4472.114", false, true))
        assertFalse(terminalEngineCompatible("91.0.4472.114", true, false))
        assertFalse(terminalEngineCompatible("invalid", true, true))
        assertTrue(terminalEngineCompatible("91.0.4472.114", true, true))
    }

    @Test
    fun binaryPacketPreservesUnsignedPrefixAndArbitraryBytes() {
        val bytes = byteArrayOf(0, -1, -61, 40, 27, 91, 65)
        val decoded = TerminalBridgePacket.decode(
            TerminalBridgePacket.encode(ULong.MAX_VALUE, ULong.MAX_VALUE, bytes),
        )

        assertEquals(ULong.MAX_VALUE, decoded.terminalGeneration)
        assertEquals(ULong.MAX_VALUE, decoded.sequence)
        assertArrayEquals(bytes, decoded.bytes)
    }

    @Test
    fun sequencesAreContiguousPerGenerationAndDirection() {
        val state = TerminalSequenceState()
        assertEquals(SequenceDecision.NO_GENERATION, state.acceptInput(1u, 0u))
        state.begin(1u)

        assertEquals(SequenceDecision.ACCEPTED, state.acceptInput(1u, 0u))
        assertEquals(SequenceDecision.DUPLICATE, state.acceptInput(1u, 0u))
        assertEquals(SequenceDecision.GAP, state.acceptInput(1u, 2u))
        assertEquals(SequenceDecision.ACCEPTED, state.acceptInput(1u, 1u))
        assertEquals(SequenceDecision.ACCEPTED, state.acceptOutput(1u, 0u))
        assertEquals(SequenceDecision.GENERATION_MISMATCH, state.acceptOutput(2u, 1u))

        state.begin(2u)
        assertEquals(SequenceDecision.ACCEPTED, state.acceptInput(2u, 0u))
        assertEquals(SequenceDecision.ACCEPTED, state.acceptOutput(2u, 0u))
    }

    @Test
    fun disconnectedInputIsRejectedAndNeverQueuedForReplay() {
        val state = TerminalSequenceState()
        state.begin(4u)
        state.disconnect()

        assertFalse(state.connected)
        assertEquals(SequenceDecision.GENERATION_MISMATCH, state.acceptInput(4u, 0u))
        state.begin(5u)
        assertEquals(SequenceDecision.ACCEPTED, state.acceptInput(5u, 0u))
    }

    @Test
    fun historyAndSavedStateExposeTheirRealBounds() {
        TerminalHistorySnapshot(
            terminalGeneration = 1u,
            capturedAt = "2026-08-09T00:00:00Z",
            text = "one\ntwo\n",
            truncatedLines = true,
            truncatedBytes = false,
        ).validate()
        val state = TerminalSavedState(null, 80, 24, true)

        assertNull(state.lastGeneration)
        assertFalse(state.screenRestorable)
        assertFalse(state.scrollbackRestorable)
        assertTrue(state.requiresReconnect)
        assertTrue(state.wasConnected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedTerminalPacketIsRejectedBeforeAllocation() {
        TerminalBridgePacket.encode(0u, 0u, ByteArray(MaximumTerminalDataBytes + 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedHistoryIsRejected() {
        TerminalHistorySnapshot(
            terminalGeneration = 1u,
            capturedAt = "2026-08-09T00:00:00Z",
            text = "x".repeat(MaximumHistoryBytes + 1),
            truncatedLines = false,
            truncatedBytes = true,
        ).validate()
    }
}
