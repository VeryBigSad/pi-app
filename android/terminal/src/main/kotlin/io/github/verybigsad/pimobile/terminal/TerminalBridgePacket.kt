package io.github.verybigsad.pimobile.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val TerminalBridgePrefixBytes = 16
internal const val MaximumTerminalDataBytes = 1_048_560
internal const val MaximumPendingTerminalBytes = 1_048_576
internal const val MaximumHistoryBytes = 1_048_576
internal const val MaximumHistoryLines = 5_000

internal fun terminalEngineCompatible(
    version: String?,
    hasMessageListener: Boolean,
    hasPostMessage: Boolean,
): Boolean {
    val major = version?.substringBefore('.')?.toIntOrNull() ?: return false
    return major >= MinimumTerminalWebViewMajor && hasMessageListener && hasPostMessage
}

internal object TerminalBridgePacket {
    fun encode(generation: ULong, sequence: ULong, bytes: ByteArray): ByteArray {
        require(bytes.size <= MaximumTerminalDataBytes)
        return ByteBuffer.allocate(TerminalBridgePrefixBytes + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(generation.toLong())
            .putLong(sequence.toLong())
            .put(bytes)
            .array()
    }

    fun decode(packet: ByteArray): TerminalInput {
        require(packet.size >= TerminalBridgePrefixBytes)
        require(packet.size - TerminalBridgePrefixBytes <= MaximumTerminalDataBytes)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        return TerminalInput(
            terminalGeneration = buffer.long.toULong(),
            sequence = buffer.long.toULong(),
            bytes = packet.copyOfRange(TerminalBridgePrefixBytes, packet.size),
        )
    }
}

internal enum class SequenceDecision {
    ACCEPTED,
    NO_GENERATION,
    GENERATION_MISMATCH,
    DUPLICATE,
    GAP,
    EXHAUSTED,
}

internal class TerminalSequenceState {
    var generation: ULong? = null
        private set
    var connected: Boolean = false
        private set
    private var nextInput: ULong? = null
    private var nextOutput: ULong? = null

    fun begin(generation: ULong) {
        this.generation = generation
        connected = true
        nextInput = 0u
        nextOutput = 0u
    }

    fun disconnect() {
        connected = false
    }

    fun clear() {
        generation = null
        connected = false
        nextInput = null
        nextOutput = null
    }

    fun acceptInput(generation: ULong, sequence: ULong): SequenceDecision =
        accept(generation, sequence, input = true)

    fun acceptOutput(generation: ULong, sequence: ULong): SequenceDecision =
        accept(generation, sequence, input = false)

    private fun accept(generation: ULong, sequence: ULong, input: Boolean): SequenceDecision {
        val current = this.generation ?: return SequenceDecision.NO_GENERATION
        if (!connected || current != generation) return SequenceDecision.GENERATION_MISMATCH
        val expected = if (input) nextInput else nextOutput
        if (expected == null) return SequenceDecision.EXHAUSTED
        if (sequence < expected) return SequenceDecision.DUPLICATE
        if (sequence > expected) return SequenceDecision.GAP
        val next = if (sequence == ULong.MAX_VALUE) null else sequence + 1u
        if (input) nextInput = next else nextOutput = next
        return SequenceDecision.ACCEPTED
    }
}

internal fun TerminalHistorySnapshot.validate() {
    require(capturedAt.isNotBlank() && capturedAt.length <= 128)
    require(runCatching { java.time.Instant.parse(capturedAt) }.isSuccess)
    require(text.toByteArray(Charsets.UTF_8).size <= MaximumHistoryBytes)
    val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    require(lines <= MaximumHistoryLines)
}
