package io.github.verybigsad.pimobile.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

enum class SessionRunState {
    IDLE,
    STREAMING,
    RETRYING,
    COMPACTING,
    WAITING_FOR_INPUT,
    SETTLED,
    WAITING_FOR_CANONICAL,
    FAULTED,
}

enum class CanonicalResetReason {
    INITIAL_LOAD,
    EXPLICIT_RESET,
    SEQUENCE_GAP,
    EPOCH_CHANGED,
    INVALID_TRANSITION,
    PROCESS_CACHE_INVALID,
}

sealed interface CanonicalAvailability {
    data object Current : CanonicalAvailability

    data class Unavailable(
        val reason: CanonicalResetReason,
        val lastCommittedCursor: EventCursor?,
        val observedCursor: EventCursor?,
    ) : CanonicalAvailability
}

data class ConversationState(
    val sessionId: SessionId,
    val finalizedMessages: PersistentList<FinalizedMessage>,
    val provisionalMessages: PersistentMap<MessageId, ProvisionalMessage>,
    val cursor: EventCursor?,
    val availability: CanonicalAvailability,
    val runState: SessionRunState,
    val lastAppendId: AppendId?,
    val lastSettlementId: SettlementId?,
    val hasOlderMessages: Boolean,
) {
    init {
        require(finalizedMessages.map(FinalizedMessage::id).distinct().size == finalizedMessages.size)
        require(finalizedMessages.zipWithNext().all { (left, right) -> left.appendOrdinal < right.appendOrdinal })
        require(provisionalMessages.all { (id, message) -> id == message.id })
        require(finalizedMessages.none { provisionalMessages.containsKey(it.id) })
        require(availability is CanonicalAvailability.Current || provisionalMessages.isEmpty())
    }

    companion object {
        const val MAX_IN_MEMORY_FINALIZED_MESSAGES = 500

        fun awaitingCanonical(sessionId: SessionId): ConversationState = ConversationState(
            sessionId = sessionId,
            finalizedMessages = persistentListOf(),
            provisionalMessages = persistentMapOf(),
            cursor = null,
            availability = CanonicalAvailability.Unavailable(
                reason = CanonicalResetReason.INITIAL_LOAD,
                lastCommittedCursor = null,
                observedCursor = null,
            ),
            runState = SessionRunState.WAITING_FOR_CANONICAL,
            lastAppendId = null,
            lastSettlementId = null,
            hasOlderMessages = false,
        )

        fun restored(
            sessionId: SessionId,
            finalizedMessages: PersistentList<FinalizedMessage>,
            cursor: EventCursor?,
            hasOlderMessages: Boolean,
        ): ConversationState {
            val retained = finalizedMessages.takeLast(MAX_IN_MEMORY_FINALIZED_MESSAGES).toPersistentList()
            return ConversationState(
                sessionId = sessionId,
                finalizedMessages = retained,
                provisionalMessages = persistentMapOf(),
                cursor = cursor,
                availability = if (cursor == null) {
                    CanonicalAvailability.Unavailable(
                        reason = CanonicalResetReason.PROCESS_CACHE_INVALID,
                        lastCommittedCursor = null,
                        observedCursor = null,
                    )
                } else {
                    CanonicalAvailability.Current
                },
                runState = if (cursor == null) SessionRunState.WAITING_FOR_CANONICAL else SessionRunState.IDLE,
                lastAppendId = null,
                lastSettlementId = null,
                hasOlderMessages = hasOlderMessages || finalizedMessages.size > retained.size,
            )
        }
    }
}

data class CanonicalSnapshot(
    val sessionId: SessionId,
    val cursor: EventCursor,
    val finalizedMessages: PersistentList<FinalizedMessage>,
    val lastAppendId: AppendId?,
    val runState: SessionRunState,
    val hasOlderMessages: Boolean,
) {
    init {
        require(runState != SessionRunState.WAITING_FOR_CANONICAL)
        require(finalizedMessages.map(FinalizedMessage::id).distinct().size == finalizedMessages.size)
        require(finalizedMessages.zipWithNext().all { (left, right) -> left.appendOrdinal < right.appendOrdinal })
    }
}

sealed interface ConversationEvent {
    val cursor: EventCursor

    data class ProvisionalStarted(
        override val cursor: EventCursor,
        val message: ProvisionalMessage,
    ) : ConversationEvent

    data class ProvisionalReplaced(
        override val cursor: EventCursor,
        val message: ProvisionalMessage,
    ) : ConversationEvent

    data class ProvisionalDiscarded(
        override val cursor: EventCursor,
        val messageId: MessageId,
    ) : ConversationEvent

    data class MessageFinalized(
        override val cursor: EventCursor,
        val appendId: AppendId,
        val message: FinalizedMessage,
    ) : ConversationEvent

    data class RunStateChanged(
        override val cursor: EventCursor,
        val runState: SessionRunState,
    ) : ConversationEvent

    data class AgentSettled(
        override val cursor: EventCursor,
        val settlementId: SettlementId,
    ) : ConversationEvent
}

sealed interface ConversationAction {
    data class EventReceived(val event: ConversationEvent) : ConversationAction

    data class SyncReset(
        val reason: CanonicalResetReason,
        val observedCursor: EventCursor?,
    ) : ConversationAction

    data class SnapshotCommitted(val snapshot: CanonicalSnapshot) : ConversationAction
}

object ConversationReducer {
    fun reduce(state: ConversationState, action: ConversationAction): ConversationState = when (action) {
        is ConversationAction.SyncReset -> unavailable(state, action.reason, action.observedCursor)
        is ConversationAction.SnapshotCommitted -> commitSnapshot(state, action.snapshot)
        is ConversationAction.EventReceived -> applyEvent(state, action.event)
    }

    private fun commitSnapshot(state: ConversationState, snapshot: CanonicalSnapshot): ConversationState {
        require(snapshot.sessionId == state.sessionId)
        val retained = snapshot.finalizedMessages.takeLast(ConversationState.MAX_IN_MEMORY_FINALIZED_MESSAGES).toPersistentList()
        return ConversationState(
            sessionId = state.sessionId,
            finalizedMessages = retained,
            provisionalMessages = persistentMapOf(),
            cursor = snapshot.cursor,
            availability = CanonicalAvailability.Current,
            runState = snapshot.runState,
            lastAppendId = snapshot.lastAppendId,
            lastSettlementId = null,
            hasOlderMessages = snapshot.hasOlderMessages || snapshot.finalizedMessages.size > retained.size,
        )
    }

    private fun applyEvent(state: ConversationState, event: ConversationEvent): ConversationState {
        if (state.availability !is CanonicalAvailability.Current) return state
        val current = state.cursor ?: return unavailable(state, CanonicalResetReason.INVALID_TRANSITION, event.cursor)
        if (event.cursor.streamEpoch != current.streamEpoch) {
            return unavailable(state, CanonicalResetReason.EPOCH_CHANGED, event.cursor)
        }
        if (event.cursor.sequence <= current.sequence) return state
        val expectedSequence = current.sequence.incremented()
        if (expectedSequence == null || event.cursor.sequence != expectedSequence) {
            return unavailable(state, CanonicalResetReason.SEQUENCE_GAP, event.cursor)
        }

        val next = when (event) {
            is ConversationEvent.ProvisionalStarted -> {
                if (state.provisionalMessages.containsKey(event.message.id) || state.finalizedMessages.any { it.id == event.message.id }) return invalid(state, event.cursor)
                state.copy(provisionalMessages = state.provisionalMessages.putting(event.message.id, event.message))
            }

            is ConversationEvent.ProvisionalReplaced -> {
                val previous = state.provisionalMessages[event.message.id] ?: return invalid(state, event.cursor)
                if (event.message.revision <= previous.revision || event.message.startedAtEpochMillis != previous.startedAtEpochMillis) return invalid(state, event.cursor)
                state.copy(provisionalMessages = state.provisionalMessages.putting(event.message.id, event.message))
            }

            is ConversationEvent.ProvisionalDiscarded -> state.copy(
                provisionalMessages = state.provisionalMessages.removing(event.messageId),
            )

            is ConversationEvent.MessageFinalized -> finalizeMessage(state, event) ?: return invalid(state, event.cursor)
            is ConversationEvent.RunStateChanged -> {
                if (event.runState == SessionRunState.WAITING_FOR_CANONICAL) return invalid(state, event.cursor)
                state.copy(runState = event.runState)
            }

            is ConversationEvent.AgentSettled -> {
                if (state.lastSettlementId == event.settlementId) return invalid(state, event.cursor)
                state.copy(lastSettlementId = event.settlementId, runState = SessionRunState.SETTLED)
            }
        }
        return next.copy(cursor = event.cursor)
    }

    private fun finalizeMessage(
        state: ConversationState,
        event: ConversationEvent.MessageFinalized,
    ): ConversationState? {
        if (state.finalizedMessages.any { it.id == event.message.id }) return null
        val lastOrdinal = state.finalizedMessages.lastOrNull()?.appendOrdinal
        if (lastOrdinal != null && event.message.appendOrdinal <= lastOrdinal) return null
        val appended = state.finalizedMessages.adding(event.message)
        val retained = appended.takeLast(ConversationState.MAX_IN_MEMORY_FINALIZED_MESSAGES).toPersistentList()
        return state.copy(
            finalizedMessages = retained,
            provisionalMessages = state.provisionalMessages.removing(event.message.id),
            lastAppendId = event.appendId,
            hasOlderMessages = state.hasOlderMessages || appended.size > retained.size,
        )
    }

    private fun invalid(state: ConversationState, cursor: EventCursor): ConversationState =
        unavailable(state, CanonicalResetReason.INVALID_TRANSITION, cursor)

    private fun unavailable(
        state: ConversationState,
        reason: CanonicalResetReason,
        observedCursor: EventCursor?,
    ): ConversationState = state.copy(
        provisionalMessages = persistentMapOf(),
        availability = CanonicalAvailability.Unavailable(reason, state.cursor, observedCursor),
        runState = SessionRunState.WAITING_FOR_CANONICAL,
    )
}
