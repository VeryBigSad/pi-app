package io.github.verybigsad.pimobile.wire

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.terminal.TerminalInput
import io.github.verybigsad.pimobile.terminal.TerminalRechunker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TerminalInputActorTest {
    @Test
    fun controllerLogicalPasteReachesHostAsBoundedContiguousFrames() = runTest {
        val hostFrames = mutableListOf<TerminalInput>()
        val actor = TerminalInputActor(this, sendFrame = { hostFrames += it })
        actor.activate(11u)
        val bytes = ByteArray(65_536 * 2 + 17) { (it % 113).toByte() }

        actor.submit(11u, bytes).await()

        assertThat(hostFrames.map { it.sequence }).containsExactly(0uL, 1uL, 2uL).inOrder()
        assertThat(hostFrames.map { it.bytes.size }).containsExactly(65_536, 65_536, 17).inOrder()
        assertThat(hostFrames.flatMap { it.bytes.asIterable() }.toByteArray()).isEqualTo(bytes)
        actor.close()
    }

    @Test
    fun boundaryInputsShareOneActorOwnedSequence() = runTest {
        val hostFrames = mutableListOf<TerminalInput>()
        val actor = TerminalInputActor(this, sendFrame = { hostFrames += it })
        actor.activate(3u)
        listOf(0, 1, 65_535, 65_536, 65_537).map { size ->
            actor.submit(3u, ByteArray(size) { 0x41 })
        }.forEach { it.await() }

        assertThat(hostFrames.map { it.sequence }).containsExactlyElementsIn(
            hostFrames.indices.map(Int::toULong),
        ).inOrder()
        assertThat(hostFrames.map { it.bytes.size }).containsExactly(0, 1, 65_535, 65_536, 65_536, 1).inOrder()
        actor.close()
    }

    @Test
    fun rapidConcurrentLogicalInputsNeverInterleaveFrames() = runTest {
        val hostFrames = mutableListOf<TerminalInput>()
        val actor = TerminalInputActor(this, sendFrame = { hostFrames += it })
        actor.activate(5u)
        val submissions = (1..64).map { marker ->
            async { actor.submit(5u, ByteArray(65_537) { marker.toByte() }).await() }
        }
        submissions.awaitAll()

        assertThat(hostFrames.map { it.sequence }).containsExactlyElementsIn(
            hostFrames.indices.map(Int::toULong),
        ).inOrder()
        assertThat(hostFrames.size).isEqualTo(128)
        hostFrames.chunked(2).forEach { pair ->
            assertThat(pair[0].bytes.first()).isEqualTo(pair[1].bytes.first())
            assertThat(pair.map { it.bytes.size }).containsExactly(65_536, 1).inOrder()
        }
        actor.close()
    }

    @Test
    fun explicitGenerationResetDropsStaleQueuedInputAndRestartsSequence() = runTest {
        val hostFrames = mutableListOf<TerminalInput>()
        val actor = TerminalInputActor(this, sendFrame = { hostFrames += it })
        actor.activate(1u)
        val stale = actor.submit(1u, byteArrayOf(1))
        actor.deactivate(1u)
        actor.activate(2u)
        val fresh = actor.submit(2u, byteArrayOf(2))

        assertFailure(stale, TerminalInputWriteError.GENERATION_CHANGED)
        fresh.await()
        assertThat(hostFrames).hasSize(1)
        assertThat(hostFrames.single().terminalGeneration).isEqualTo(2uL)
        assertThat(hostFrames.single().sequence).isEqualTo(0uL)
        actor.close()
    }

    @Test
    fun exhaustionAndUncertainDeliveryNeverReplay() = runTest {
        val exhaustedFrames = mutableListOf<TerminalInput>()
        val exhausted = TerminalInputActor(
            this,
            rechunker = TerminalRechunker(ULong.MAX_VALUE),
            sendFrame = { exhaustedFrames += it },
        )
        exhausted.activate(7u)
        assertFailure(exhausted.submit(7u, ByteArray(65_537)), TerminalInputWriteError.SEQUENCE_EXHAUSTED)
        assertThat(exhaustedFrames).isEmpty()
        exhausted.submit(7u, byteArrayOf(1)).await()
        assertThat(exhaustedFrames.single().sequence).isEqualTo(ULong.MAX_VALUE)
        exhausted.close()

        val attempts = mutableListOf<TerminalInput>()
        val uncertain = TerminalInputActor(this, sendFrame = {
            attempts += it
            throw IllegalStateException("forced")
        })
        uncertain.activate(9u)
        val first = uncertain.submit(9u, byteArrayOf(1))
        val second = uncertain.submit(9u, byteArrayOf(2))
        assertFailure(first, TerminalInputWriteError.UNCERTAIN_DELIVERY)
        assertFailure(second, TerminalInputWriteError.UNCERTAIN_DELIVERY)
        assertThat(attempts).hasSize(1)
        uncertain.close()
    }

    private suspend fun assertFailure(
        submission: kotlinx.coroutines.Deferred<Unit>,
        code: TerminalInputWriteError,
    ) {
        val error = runCatching { submission.await() }.exceptionOrNull() as TerminalInputWriteException
        assertThat(error.code).isEqualTo(code)
    }
}
