package io.github.verybigsad.pimobile

import android.os.Trace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.AppendId
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.ConversationAction
import io.github.verybigsad.pimobile.model.ConversationEvent
import io.github.verybigsad.pimobile.model.ConversationReducer
import io.github.verybigsad.pimobile.model.ConversationState
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MessageContent
import io.github.verybigsad.pimobile.model.MessageContentKind
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.MessageRole
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.session.PasskeyProviderAvailability
import io.github.verybigsad.pimobile.session.SessionDetailScreen
import io.github.verybigsad.pimobile.session.SessionDetailUiState
import io.github.verybigsad.pimobile.session.SessionTheme
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.delay

@Composable
internal fun BenchmarkTimelineScreen(runId: Long) {
    key(runId) {
        var session by remember { mutableStateOf(BenchmarkTimelineFixture.initialSessionState()) }
        var catchUpStarted by remember { mutableStateOf(false) }
        var catchUpComplete by remember { mutableStateOf(false) }
        LaunchedEffect(catchUpStarted) {
            if (!catchUpStarted) return@LaunchedEffect
            Trace.beginSection(BenchmarkTimelineFixture.CATCH_UP_TRACE_NAME)
            try {
                repeat(BenchmarkTimelineFixture.CATCH_UP_EVENT_COUNT) { index ->
                    session = BenchmarkTimelineFixture.applyCatchUpEvent(session, index + 1)
                    delay(BenchmarkTimelineFixture.CATCH_UP_INTERVAL_MILLIS)
                }
            } finally {
                Trace.endSection()
            }
            catchUpComplete = true
        }
        SessionTheme {
            Box {
                SessionDetailScreen(
                    state = BenchmarkTimelineFixture.detailState(session),
                    onEvent = {},
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    val status = if (catchUpComplete) {
                        BenchmarkTimelineFixture.CATCH_UP_COMPLETE_DESCRIPTION
                    } else {
                        BenchmarkTimelineFixture.READY_DESCRIPTION
                    }
                    Text(
                        text = status,
                        modifier = Modifier.semantics { contentDescription = "$status:$runId" },
                    )
                    Button(
                        enabled = !catchUpStarted,
                        onClick = { catchUpStarted = true },
                        modifier = Modifier.semantics {
                            contentDescription = BenchmarkTimelineFixture.CATCH_UP_ACTION_DESCRIPTION
                        },
                    ) {
                        Text("Run catch-up")
                    }
                }
            }
        }
    }
}

internal object BenchmarkTimelineFixture {
    const val HISTORY_EVENT_COUNT = 10_000
    const val RETAINED_MESSAGE_COUNT = ConversationState.MAX_IN_MEMORY_FINALIZED_MESSAGES
    const val CATCH_UP_EVENT_COUNT = 100
    const val CATCH_UP_INTERVAL_MILLIS = 10L
    const val CATCH_UP_TRACE_NAME = "PiBenchmarkCatchUp"
    const val READY_DESCRIPTION = "Pi benchmark timeline ready"
    const val CATCH_UP_ACTION_DESCRIPTION = "Run deterministic benchmark catch-up"
    const val CATCH_UP_COMPLETE_DESCRIPTION = "Pi benchmark catch-up complete"

    private const val NOW_EPOCH_MILLIS = 1_700_000_000_000L
    private const val SESSION_NAME = "benchmark-timeline"
    private const val MAC_NAME = "benchmark-mac"
    private const val STREAM_EPOCH_NAME = "benchmark-epoch"

    fun initialSessionState(): SessionState {
        val sessionId = SessionId(SESSION_NAME)
        val macId = MacId(MAC_NAME)
        val firstRetainedOrdinal = HISTORY_EVENT_COUNT - RETAINED_MESSAGE_COUNT + 1
        val messages = (firstRetainedOrdinal..HISTORY_EVENT_COUNT)
            .map(::message)
            .toPersistentList()
        return SessionState(
            metadata = SessionMetadata(
                id = sessionId,
                macId = macId,
                displayName = "Benchmark timeline",
                repositoryPath = "/benchmark/repository",
                worktreePath = "/benchmark/worktree",
                parentSessionId = null,
                updatedAtEpochMillis = NOW_EPOCH_MILLIS,
            ),
            conversation = ConversationState(
                sessionId = sessionId,
                finalizedMessages = messages,
                provisionalMessages = persistentMapOf(),
                cursor = cursor(HISTORY_EVENT_COUNT),
                availability = CanonicalAvailability.Current,
                runState = SessionRunState.IDLE,
                lastAppendId = AppendId("benchmark-append-$HISTORY_EVENT_COUNT"),
                lastSettlementId = null,
                hasOlderMessages = true,
            ),
            draft = DraftState.empty(sessionId),
            trust = TrustState.Trusted(
                macId = macId,
                macDisplayName = "Benchmark Mac",
                certificateSerial = "benchmark-certificate",
                certificateNotAfterEpochMillis = NOW_EPOCH_MILLIS + 86_400_000L,
            ),
            connection = ConnectionState.Ready(
                path = TransportPath.DIRECT,
                macId = macId,
                userAuthentication = PasskeyAuthentication(
                    assertionId = "benchmark-assertion",
                    verifiedAtEpochMillis = NOW_EPOCH_MILLIS - 1L,
                    expiresAtEpochMillis = NOW_EPOCH_MILLIS + 86_400_000L,
                ),
                deviceAuthentication = MutualTlsAuthentication(
                    certificateSerial = "benchmark-certificate",
                    verifiedAtEpochMillis = NOW_EPOCH_MILLIS - 1L,
                ),
            ),
        )
    }

    fun applyCatchUpEvent(state: SessionState, eventIndex: Int): SessionState {
        require(eventIndex in 1..CATCH_UP_EVENT_COUNT)
        val ordinal = HISTORY_EVENT_COUNT + eventIndex
        val conversation = ConversationReducer.reduce(
            state.conversation,
            ConversationAction.EventReceived(
                ConversationEvent.MessageFinalized(
                    cursor = cursor(ordinal),
                    appendId = AppendId("benchmark-append-$ordinal"),
                    message = message(ordinal),
                ),
            ),
        )
        return state.copy(conversation = conversation)
    }

    fun detailState(session: SessionState): SessionDetailUiState = SessionDetailUiState(
        session = session,
        passkeyProvider = PasskeyProviderAvailability.Available("Benchmark provider"),
        retainedAuthentication = null,
        nowEpochMillis = NOW_EPOCH_MILLIS,
        macDisplayName = "Benchmark Mac",
        modelName = "benchmark-model",
        thinkingLevel = "standard",
        elapsedLabel = null,
        lastSyncedLabel = "Benchmark fixture",
    )

    private fun cursor(sequence: Int): EventCursor = EventCursor(
        streamEpoch = StreamEpoch(STREAM_EPOCH_NAME),
        sequence = sequence.toLong(),
        leafId = null,
    )

    private fun message(ordinal: Int): FinalizedMessage {
        val createdAt = NOW_EPOCH_MILLIS + ordinal * 1_000L
        return FinalizedMessage(
            id = MessageId("benchmark-message-$ordinal"),
            role = if (ordinal % 2 == 0) MessageRole.ASSISTANT else MessageRole.USER,
            content = listOf(
                MessageContent(
                    stableId = "text",
                    kind = MessageContentKind.TEXT,
                    contentVersion = 0,
                    projection = "{\"text\":\"Benchmark timeline message $ordinal\",\"type\":\"text\"}",
                ),
            ).toPersistentList(),
            appendOrdinal = ordinal.toLong(),
            createdAtEpochMillis = createdAt,
            finalizedAtEpochMillis = createdAt + 1L,
        )
    }
}
