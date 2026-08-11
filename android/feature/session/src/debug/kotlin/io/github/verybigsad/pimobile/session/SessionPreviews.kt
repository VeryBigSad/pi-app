package io.github.verybigsad.pimobile.session

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.verybigsad.pimobile.model.AppendId
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.ConnectionState
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
import io.github.verybigsad.pimobile.model.ProvisionalMessage
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

private const val PreviewNow = 2_000_000L
private val PreviewMacId = MacId("mac-preview")
private val PreviewSessionId = SessionId("session-preview")

@Preview(name = "Session inbox light", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SessionInboxPreview() {
    SessionTheme(SessionThemeMode.LIGHT) {
        SessionListScreen(state = previewListState(), onEvent = {})
    }
}

@Preview(name = "Session detail dark", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SessionDetailPreview() {
    SessionTheme(SessionThemeMode.DARK) {
        SessionDetailScreen(state = previewDetailState(), onEvent = {})
    }
}

@Preview(name = "Narrow at 200 percent", widthDp = 300, heightDp = 900, fontScale = 2f, showBackground = true)
@Composable
private fun SessionDetailLargeTextPreview() {
    SessionTheme(SessionThemeMode.LIGHT) {
        SessionDetailScreen(
            state = previewDetailState().copy(
                modelName = "long-model-name",
                thinkingLevel = "high",
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Tablet two pane", widthDp = 1100, heightDp = 800, showBackground = true)
@Composable
private fun SessionDetailTabletPreview() {
    SessionTheme(SessionThemeMode.LIGHT) {
        SessionDetailScreen(state = previewDetailState(), onEvent = {})
    }
}

@Preview(name = "No passkey provider", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun SessionLockedPreview() {
    val metadata = previewMetadata()
    val trusted = previewTrust()
    SessionTheme(SessionThemeMode.DARK) {
        SessionListScreen(
            state = SessionListUiState(
                trust = trusted,
                connection = ConnectionState.DeviceAuthenticated(
                    path = TransportPath.RELAY,
                    macId = metadata.macId,
                    deviceAuthentication = MutualTlsAuthentication("certificate-preview", PreviewNow),
                ),
                passkeyProvider = PasskeyProviderAvailability.Unavailable(
                    "Install or enable a compatible Credential Manager provider.",
                ),
                retainedAuthentication = null,
                nowEpochMillis = PreviewNow,
                sessions = emptyList(),
                lastSyncedLabel = null,
            ),
            onEvent = {},
        )
    }
}

@Preview(name = "Approval offer", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun ApprovalPreview() {
    SessionTheme(SessionThemeMode.LIGHT) {
        SessionDetailScreen(
            state = previewDetailState().copy(approvalOffer = previewApprovalOffer()),
            onEvent = {},
        )
    }
}

internal fun previewMetadata(): SessionMetadata = SessionMetadata(
    id = PreviewSessionId,
    macId = PreviewMacId,
    displayName = "Compose session UX",
    repositoryPath = "~/personal/pi-app",
    worktreePath = "~/personal/pi-app",
    parentSessionId = null,
    updatedAtEpochMillis = PreviewNow,
)

private fun previewTrust(): TrustState.Trusted = TrustState.Trusted(
    macId = PreviewMacId,
    macDisplayName = "MacBook Pro",
    certificateSerial = "certificate-preview",
    certificateNotAfterEpochMillis = PreviewNow + 2_500_000L,
)

private fun previewAuthentication(): PasskeyAuthentication = PasskeyAuthentication(
    assertionId = "assertion-preview",
    verifiedAtEpochMillis = PreviewNow,
    expiresAtEpochMillis = PreviewNow + 300_000L,
)

private fun previewConnection(): ConnectionState.Ready = ConnectionState.Ready(
    path = TransportPath.DIRECT,
    macId = PreviewMacId,
    userAuthentication = previewAuthentication(),
    deviceAuthentication = MutualTlsAuthentication("certificate-preview", PreviewNow),
)

private fun previewConversation(): ConversationState = ConversationState(
    sessionId = PreviewSessionId,
    finalizedMessages = persistentListOf(
        FinalizedMessage(
            id = MessageId("message-user"),
            role = MessageRole.USER,
            content = persistentListOf(
                MessageContent("text-user", MessageContentKind.TEXT, 1, "Finish the native session experience without inventing host state."),
            ),
            appendOrdinal = 1,
            createdAtEpochMillis = PreviewNow - 60_000,
            finalizedAtEpochMillis = PreviewNow - 59_000,
        ),
        FinalizedMessage(
            id = MessageId("message-tool"),
            role = MessageRole.ASSISTANT,
            content = persistentListOf(
                MessageContent("thinking-final", MessageContentKind.THINKING, 2, "Reviewing trust and canonical-state boundaries."),
                MessageContent("tool-call", MessageContentKind.TOOL_CALL, 1, "read android/core/model/SessionState.kt"),
                MessageContent("tool-result", MessageContentKind.TOOL_RESULT, 1, "SessionState read; no execution result inferred."),
            ),
            appendOrdinal = 2,
            createdAtEpochMillis = PreviewNow - 50_000,
            finalizedAtEpochMillis = PreviewNow - 30_000,
        ),
    ),
    provisionalMessages = persistentMapOf(
        MessageId("message-live") to ProvisionalMessage(
            id = MessageId("message-live"),
            role = MessageRole.ASSISTANT,
            content = persistentListOf(
                MessageContent("text-live", MessageContentKind.TEXT, 8, "Building the state-driven Compose surface…"),
            ),
            revision = 8,
            startedAtEpochMillis = PreviewNow - 20_000,
        ),
    ),
    cursor = EventCursor(StreamEpoch("epoch-preview"), 42, null),
    availability = CanonicalAvailability.Current,
    runState = SessionRunState.STREAMING,
    lastAppendId = AppendId("append-preview"),
    lastSettlementId = null,
    hasOlderMessages = true,
)

internal fun previewDetailState(): SessionDetailUiState {
    val metadata = previewMetadata()
    return SessionDetailUiState(
        session = SessionState(
            metadata = metadata,
            conversation = previewConversation(),
            draft = DraftState(
                sessionId = metadata.id,
                typedText = "Please include accessibility tests.",
                transcriptionText = "And verify the narrow layout.",
                revision = 3,
                updatedAtEpochMillis = PreviewNow,
            ),
            trust = previewTrust(),
            connection = previewConnection(),
        ),
        passkeyProvider = PasskeyProviderAvailability.Available("Credential Manager"),
        retainedAuthentication = previewAuthentication(),
        nowEpochMillis = PreviewNow + 1_000,
        macDisplayName = "MacBook Pro",
        modelName = "codex-large",
        thinkingLevel = "high",
        elapsedLabel = "1m 12s",
        lastSyncedLabel = "Synced just now",
    )
}

private fun previewListState(): SessionListUiState {
    val metadata = previewMetadata()
    val base = SessionListItemUiState(
        metadata = metadata,
        runState = SessionRunState.STREAMING,
        bucket = SessionBucket.WORKING,
        latestActivity = "Rendering provisional assistant text",
        lastActiveLabel = "Active now",
    )
    return SessionListUiState(
        trust = previewTrust(),
        connection = previewConnection(),
        passkeyProvider = PasskeyProviderAvailability.Available("Credential Manager"),
        retainedAuthentication = previewAuthentication(),
        nowEpochMillis = PreviewNow + 1_000,
        sessions = listOf(
            base.copy(
                runState = SessionRunState.WAITING_FOR_INPUT,
                bucket = SessionBucket.NEEDS_YOU,
                latestActivity = "Waiting for a bounded input response",
                blockerCount = 1,
            ),
            base,
            base.copy(
                metadata = metadata.copy(
                    id = SessionId("session-review"),
                    displayName = "Review protocol fixtures",
                    parentSessionId = metadata.id,
                ),
                runState = SessionRunState.SETTLED,
                bucket = SessionBucket.READY_TO_REVIEW,
                latestActivity = "Host reported durable settlement",
                lastActiveLabel = "4 minutes ago",
                treeDepth = 1,
                parentSessionLabel = metadata.displayName,
            ),
        ),
        lastSyncedLabel = "Synced just now",
    )
}

private fun previewApprovalOffer(): ApprovalOfferUiState = ApprovalOfferUiState(
    offerId = "offer-preview",
    operationId = "tool-call-preview",
    operationName = "bash",
    normalizedArguments = "rm -rf -- build",
    targetLabel = "Working directory",
    targetValue = "/Users/example/personal/pi-app",
    reasons = listOf("Recursive deletion", "Destructive file-system operation"),
    policyVersion = "policy-v1",
    argumentHash = "8e9ec2e5662fce1041b182abc201ca2dc184615bba11edb132240330e46f9c66",
    expiresAtLabel = "12:01:30",
    remainingSeconds = 73,
)
