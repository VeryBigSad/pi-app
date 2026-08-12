package io.github.verybigsad.pimobile.model

import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class ConversationReducerTest {
    private val sessionId = SessionId("session")
    private val epoch = StreamEpoch("epoch")

    @Test
    fun finalMessageAtomicallyReplacesProvisionalAndAdvancesAppendCursor() {
        val snapshot = snapshot(cursor(0), persistentListOf())
        var state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot),
        )
        val provisional = ProvisionalMessage(
            id = MessageId("message"),
            role = MessageRole.ASSISTANT,
            content = persistentListOf(MessageContent("text", MessageContentKind.TEXT, 0, "partial")),
            revision = 0,
            startedAtEpochMillis = 1,
        )
        state = event(state, ConversationEvent.ProvisionalStarted(cursor(1), provisional))
        val final = FinalizedMessage(
            id = provisional.id,
            role = MessageRole.ASSISTANT,
            content = persistentListOf(MessageContent("text", MessageContentKind.TEXT, 1, "authoritative")),
            appendOrdinal = 1,
            createdAtEpochMillis = 1,
            finalizedAtEpochMillis = 2,
        )
        state = event(state, ConversationEvent.MessageFinalized(cursor(2), AppendId("append-1"), final))

        assertThat(state.provisionalMessages).isEmpty()
        assertThat(state.finalizedMessages.single()).isEqualTo(final)
        assertThat(state.lastAppendId).isEqualTo(AppendId("append-1"))
        assertThat(state.cursor).isEqualTo(cursor(2))
    }

    @Test
    fun canonicalNoOpAtSnapshotFenceSuccessorKeepsLaterLiveEventContiguous() {
        var state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(5), persistentListOf())),
        )

        state = ConversationReducer.reduce(state, ConversationAction.CursorAdvanced(cursor(6)))
        state = event(state, ConversationEvent.RunStateChanged(cursor(7), SessionRunState.STREAMING))

        assertThat(state.availability).isEqualTo(CanonicalAvailability.Current)
        assertThat(state.cursor).isEqualTo(cursor(7))
        assertThat(state.runState).isEqualTo(SessionRunState.STREAMING)
    }

    @Test
    fun sequenceGapDropsAllProvisionalStateAndWaitsForCanonicalSnapshot() {
        var state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(5), persistentListOf())),
        )
        state = event(
            state,
            ConversationEvent.ProvisionalStarted(
                cursor(6),
                ProvisionalMessage(MessageId("message"), MessageRole.ASSISTANT, revision = 0, startedAtEpochMillis = 1),
            ),
        )
        state = event(state, ConversationEvent.RunStateChanged(cursor(8), SessionRunState.STREAMING))

        assertThat(state.provisionalMessages).isEmpty()
        assertThat(state.runState).isEqualTo(SessionRunState.WAITING_FOR_CANONICAL)
        assertThat(state.cursor).isEqualTo(cursor(6))
        assertThat(state.availability).isEqualTo(
            CanonicalAvailability.Unavailable(CanonicalResetReason.SEQUENCE_GAP, cursor(6), cursor(8)),
        )
    }

    @Test
    fun replayedSequenceIsIdempotentlyIgnored() {
        val state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(3), persistentListOf())),
        )
        val replayed = event(state, ConversationEvent.RunStateChanged(cursor(3), SessionRunState.STREAMING))
        assertThat(replayed).isEqualTo(state)
    }

    @Test
    fun snapshotCapsMemoryWithoutChangingAppendOrder() {
        val messages = (0..550).map { ordinal ->
            FinalizedMessage(
                id = MessageId("message-$ordinal"),
                role = MessageRole.USER,
                appendOrdinal = ordinal.toLong(),
                createdAtEpochMillis = ordinal.toLong(),
                finalizedAtEpochMillis = ordinal.toLong(),
            )
        }
        val state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(0), persistentListOf(*messages.toTypedArray()))),
        )
        assertThat(state.finalizedMessages).hasSize(ConversationState.MAX_IN_MEMORY_FINALIZED_MESSAGES)
        assertThat(state.finalizedMessages.first().appendOrdinal).isEqualTo(51)
        assertThat(state.finalizedMessages.last().appendOrdinal).isEqualTo(550)
        assertThat(state.hasOlderMessages).isTrue()
    }

    private fun event(state: ConversationState, value: ConversationEvent): ConversationState =
        ConversationReducer.reduce(state, ConversationAction.EventReceived(value))

    private fun snapshot(cursor: EventCursor, messages: kotlinx.collections.immutable.PersistentList<FinalizedMessage>) = CanonicalSnapshot(
        sessionId = sessionId,
        cursor = cursor,
        finalizedMessages = messages,
        lastAppendId = null,
        runState = SessionRunState.IDLE,
        hasOlderMessages = false,
    )

    private fun cursor(sequence: Long) = EventCursor(epoch, sequence, LeafId("deadbeef"))

    private fun cursor(sequence: String) = EventCursor(epoch, Uint64Decimal(sequence), LeafId("deadbeef"))

    @Test
    fun uint64SequenceRoundTripsAcrossSignedLongBoundary() {
        // 2^63 - 1, 2^63, 2^64 - 1: unrepresentable or negative as signed Long/SQLite INTEGER.
        val boundary = (Long.MAX_VALUE).toString()
        val twoTo63 = "9223372036854775808"
        val max = "18446744073709551615"
        var state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(boundary), persistentListOf())),
        )

        // Replay at the same sequence is idempotently ignored.
        val replayed = event(state, ConversationEvent.RunStateChanged(cursor(boundary), SessionRunState.STREAMING))
        assertThat(replayed).isEqualTo(state)

        // Contiguous successor crossing the signed-Long boundary is accepted.
        state = event(state, ConversationEvent.RunStateChanged(cursor(twoTo63), SessionRunState.STREAMING))
        assertThat(state.availability).isEqualTo(CanonicalAvailability.Current)
        assertThat(state.cursor).isEqualTo(cursor(twoTo63))
        assertThat(state.runState).isEqualTo(SessionRunState.STREAMING)

        // Noncontiguous successor is a gap, not best effort.
        state = event(state, ConversationEvent.RunStateChanged(cursor("9223372036854775810"), SessionRunState.IDLE))
        assertThat(state.availability).isEqualTo(
            CanonicalAvailability.Unavailable(
                CanonicalResetReason.SEQUENCE_GAP,
                cursor(twoTo63),
                cursor("9223372036854775810"),
            ),
        )

        // Snapshot at uint64 max commits and replays idempotently.
        state = ConversationReducer.reduce(
            state,
            ConversationAction.SnapshotCommitted(snapshot(cursor(max), persistentListOf())),
        )
        assertThat(state.availability).isEqualTo(CanonicalAvailability.Current)
        val replayedMax = event(state, ConversationEvent.RunStateChanged(cursor(max), SessionRunState.IDLE))
        assertThat(replayedMax).isEqualTo(state)
    }

    @Test
    fun cursorAtUint64MaxReplaysIdempotently() {
        val max = "18446744073709551615"
        val state = ConversationReducer.reduce(
            ConversationState.awaitingCanonical(sessionId),
            ConversationAction.SnapshotCommitted(snapshot(cursor(max), persistentListOf())),
        )
        // No valid successor exists past uint64 max; every canonical sequence is <= max, so events replay.
        val next = event(state, ConversationEvent.RunStateChanged(cursor("0"), SessionRunState.STREAMING))
        assertThat(next.availability).isEqualTo(CanonicalAvailability.Current) // 0 <= max: replay, ignored
    }

    @Test
    fun processRestartRestoresUint64CursorAndResumesContiguousReplay() {
        val twoTo63 = "9223372036854775808"
        val restored = ConversationState.restored(
            sessionId = sessionId,
            finalizedMessages = persistentListOf(),
            cursor = cursor(twoTo63),
            hasOlderMessages = false,
        )
        assertThat(restored.availability).isEqualTo(CanonicalAvailability.Current)

        // Replay of the restored cursor is ignored.
        val replayed = event(restored, ConversationEvent.RunStateChanged(cursor(twoTo63), SessionRunState.IDLE))
        assertThat(replayed).isEqualTo(restored)

        // Contiguous successor resumes the stream.
        val successor = "9223372036854775809"
        val next = event(restored, ConversationEvent.RunStateChanged(cursor(successor), SessionRunState.IDLE))
        assertThat(next.cursor).isEqualTo(cursor(successor))
        assertThat(next.availability).isEqualTo(CanonicalAvailability.Current)

        // A leap past the successor is detected as a gap even across restart.
        val gap = event(next, ConversationEvent.RunStateChanged(cursor("18446744073709551615"), SessionRunState.IDLE))
        assertThat(gap.availability).isEqualTo(
            CanonicalAvailability.Unavailable(
                CanonicalResetReason.SEQUENCE_GAP,
                cursor(successor),
                cursor("18446744073709551615"),
            ),
        )
    }
}
