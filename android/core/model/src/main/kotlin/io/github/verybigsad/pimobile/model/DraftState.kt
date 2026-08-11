package io.github.verybigsad.pimobile.model

data class DraftState(
    val sessionId: SessionId,
    val typedText: String,
    val transcriptionText: String?,
    val revision: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(revision >= 0)
        require(updatedAtEpochMillis >= 0)
    }

    companion object {
        fun empty(sessionId: SessionId): DraftState = DraftState(
            sessionId = sessionId,
            typedText = "",
            transcriptionText = null,
            revision = 0,
            updatedAtEpochMillis = 0,
        )
    }
}

sealed interface DraftAction {
    val expectedRevision: Long
    val updatedAtEpochMillis: Long

    data class ReplaceTypedText(
        val text: String,
        override val expectedRevision: Long,
        override val updatedAtEpochMillis: Long,
    ) : DraftAction

    data class ReplaceTranscription(
        val text: String?,
        override val expectedRevision: Long,
        override val updatedAtEpochMillis: Long,
    ) : DraftAction

    data class Clear(
        override val expectedRevision: Long,
        override val updatedAtEpochMillis: Long,
    ) : DraftAction
}

object DraftReducer {
    fun reduce(state: DraftState, action: DraftAction): DraftState {
        require(action.expectedRevision == state.revision)
        require(action.updatedAtEpochMillis >= state.updatedAtEpochMillis)
        val nextRevision = Math.addExact(state.revision, 1)
        return when (action) {
            is DraftAction.ReplaceTypedText -> state.copy(
                typedText = action.text,
                revision = nextRevision,
                updatedAtEpochMillis = action.updatedAtEpochMillis,
            )

            is DraftAction.ReplaceTranscription -> state.copy(
                transcriptionText = action.text,
                revision = nextRevision,
                updatedAtEpochMillis = action.updatedAtEpochMillis,
            )

            is DraftAction.Clear -> state.copy(
                typedText = "",
                transcriptionText = null,
                revision = nextRevision,
                updatedAtEpochMillis = action.updatedAtEpochMillis,
            )
        }
    }
}
