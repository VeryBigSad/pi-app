package io.github.verybigsad.pimobile.session.list

import androidx.compose.runtime.Immutable
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.session.SessionListItemUiState
import io.github.verybigsad.pimobile.session.SessionListUiState
import io.github.verybigsad.pimobile.session.sanitizeStructuredDisplay

@Immutable
data class SessionCatalogMetadata(
    val provider: String? = null,
    val model: String? = null,
    val unreadCount: Int = 0,
) {
    init {
        require(provider == null || provider.isNotBlank())
        require(model == null || model.isNotBlank())
        require(unreadCount >= 0)
    }
}

@Immutable
sealed interface SessionInboxLoadState {
    data object Loading : SessionInboxLoadState
    data object Ready : SessionInboxLoadState
    data class Error(val message: String) : SessionInboxLoadState {
        init {
            require(message.isNotBlank())
        }
    }
}

enum class SessionTrustBadge(val label: String) {
    TRUSTED("Trusted"),
    REVOKED("Revoked"),
    EXPIRED("Expired"),
    PROVISIONAL("Provisional"),
}

enum class SessionActivityIndicator(val label: String) {
    STREAMING("Streaming"),
    AWAITING_APPROVAL("Awaiting approval"),
    WAITING("Waiting"),
    SETTLED("Settled"),
    IDLE("Idle"),
}

@Immutable
data class SessionInboxItemUiState(
    val id: SessionId,
    val title: String,
    val repositoryPath: String,
    val worktreePath: String,
    val preview: String,
    val updatedAtEpochMillis: Long,
    val relativeTimestamp: String,
    val trustBadge: SessionTrustBadge,
    val activity: SessionActivityIndicator,
    val unreadCount: Int,
    val hasSettlement: Boolean,
    val provider: String?,
    val model: String?,
    val treeDepth: Int,
) {
    init {
        require(title.isNotBlank())
        require(repositoryPath.isNotBlank())
        require(worktreePath.isNotBlank())
        require(preview.isNotBlank())
        require(updatedAtEpochMillis >= 0)
        require(relativeTimestamp.isNotBlank())
        require(unreadCount >= 0)
        require(treeDepth in 0..8)
    }

    val catalogLabel: String?
        get() = listOfNotNull(provider, model).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Immutable
data class SessionInboxUiState(
    val items: List<SessionInboxItemUiState>,
    val loadState: SessionInboxLoadState,
    val isRefreshing: Boolean,
    val isOffline: Boolean,
    val offlineLabel: String?,
)

sealed interface SessionInboxEvent {
    data class Open(val sessionId: SessionId) : SessionInboxEvent
    data class OpenActions(val sessionId: SessionId) : SessionInboxEvent
    data object RequestResync : SessionInboxEvent
}

fun SessionListUiState.toSessionInboxUiState(
    catalog: Map<SessionId, SessionCatalogMetadata> = emptyMap(),
    loadState: SessionInboxLoadState = SessionInboxLoadState.Ready,
): SessionInboxUiState = SessionInboxUiState(
    items = sessions.map { it.toInboxItem(nowEpochMillis, trust, connection, catalog[it.metadata.id]) },
    loadState = loadState,
    isRefreshing = isRefreshing,
    isOffline = access is io.github.verybigsad.pimobile.session.SessionContentAccess.Offline,
    offlineLabel = (access as? io.github.verybigsad.pimobile.session.SessionContentAccess.Offline)?.lastSyncedLabel,
)

fun SessionInboxEvent.toSessionListEvent(): io.github.verybigsad.pimobile.session.SessionListEvent = when (this) {
    is SessionInboxEvent.Open -> io.github.verybigsad.pimobile.session.SessionListEvent.OpenSession(sessionId)
    is SessionInboxEvent.OpenActions -> io.github.verybigsad.pimobile.session.SessionListEvent.OpenSessionActions(sessionId)
    SessionInboxEvent.RequestResync -> io.github.verybigsad.pimobile.session.SessionListEvent.Refresh
}

private fun SessionListItemUiState.toInboxItem(
    nowEpochMillis: Long,
    trust: TrustState,
    connection: ConnectionState,
    catalog: SessionCatalogMetadata?,
): SessionInboxItemUiState = SessionInboxItemUiState(
    id = metadata.id,
    title = metadata.displayName,
    repositoryPath = metadata.repositoryPath,
    worktreePath = metadata.worktreePath,
    preview = sanitizePreview(latestActivity),
    updatedAtEpochMillis = metadata.updatedAtEpochMillis,
    relativeTimestamp = relativeTime(metadata.updatedAtEpochMillis, nowEpochMillis),
    trustBadge = trustBadge(trust, connection, nowEpochMillis),
    activity = activityIndicator(runState, blockerCount),
    unreadCount = catalog?.unreadCount ?: 0,
    hasSettlement = runState == SessionRunState.SETTLED,
    provider = catalog?.provider,
    model = catalog?.model,
    treeDepth = treeDepth,
)

internal fun sanitizePreview(value: String): String = sanitizeStructuredDisplay(value)
    .lineSequence()
    .joinToString(" ") { it.trim() }
    .trim()
    .ifBlank { "No recent message" }

internal fun relativeTime(updatedAtEpochMillis: Long, nowEpochMillis: Long): String {
    val seconds = ((nowEpochMillis - updatedAtEpochMillis).coerceAtLeast(0) / 1_000)
    return when {
        seconds < 10 -> "Just now"
        seconds < 60 -> "${seconds}s ago"
        seconds < 3_600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3_600}h ago"
        seconds < 604_800 -> "${seconds / 86_400}d ago"
        else -> "${seconds / 604_800}w ago"
    }
}

private fun trustBadge(trust: TrustState, connection: ConnectionState, nowEpochMillis: Long): SessionTrustBadge = when {
    trust is TrustState.Revoked || connection is ConnectionState.Revoked -> SessionTrustBadge.REVOKED
    trust is TrustState.Trusted && trust.certificateNotAfterEpochMillis <= nowEpochMillis -> SessionTrustBadge.EXPIRED
    trust is TrustState.Unpaired || connection is ConnectionState.PairingProvisional -> SessionTrustBadge.PROVISIONAL
    else -> SessionTrustBadge.TRUSTED
}

private fun activityIndicator(runState: SessionRunState, blockerCount: Int): SessionActivityIndicator = when {
    blockerCount > 0 -> SessionActivityIndicator.AWAITING_APPROVAL
    runState == SessionRunState.STREAMING || runState == SessionRunState.RETRYING || runState == SessionRunState.COMPACTING -> SessionActivityIndicator.STREAMING
    runState == SessionRunState.WAITING_FOR_INPUT || runState == SessionRunState.WAITING_FOR_CANONICAL -> SessionActivityIndicator.WAITING
    runState == SessionRunState.SETTLED -> SessionActivityIndicator.SETTLED
    else -> SessionActivityIndicator.IDLE
}
