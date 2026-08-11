package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.TrustState

@Composable
fun SessionListScreen(
    state: SessionListUiState,
    onEvent: (SessionListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionListTopBar(
            state = state,
            onRefresh = { onEvent(SessionListEvent.Refresh) },
        )
        when (val access = state.access) {
            is SessionContentAccess.Locked -> AccessLockedCard(
                state = access,
                onAction = { action -> onEvent(action.toListEvent()) },
                modifier = Modifier.padding(16.dp),
            )

            is SessionContentAccess.Offline -> {
                OfflineBanner(access)
                SessionListContent(state, onEvent)
            }

            is SessionContentAccess.Online -> SessionListContent(state, onEvent)
        }
    }
}

@Composable
private fun SessionListTopBar(
    state: SessionListUiState,
    onRefresh: () -> Unit,
) {
    val macName = when (val trust = state.trust) {
        is TrustState.Trusted -> trust.macDisplayName
        is TrustState.Revoked -> "Revoked Mac"
        TrustState.Unpaired -> "No paired Mac"
    }
    val connectionLabel = when (val connection = state.connection) {
        is ConnectionState.Ready -> when (connection.path) {
            io.github.verybigsad.pimobile.model.TransportPath.DIRECT -> "Direct"
            io.github.verybigsad.pimobile.model.TransportPath.RELAY -> "Relayed"
        }

        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.PairingProvisional -> "Provisional pairing"
        is ConnectionState.DeviceAuthenticated -> "Passkey required"
        is ConnectionState.Disconnected -> "Offline"
        is ConnectionState.Revoked -> "Revoked"
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        val refresh: @Composable () -> Unit = {
            CompactTextButton(
                label = if (state.isRefreshing) "Refreshing…" else "Refresh",
                description = "Refresh session list from Mac",
                onClick = onRefresh,
                enabled = !state.isRefreshing && state.access is SessionContentAccess.Online,
            )
        }
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SessionListIdentity(macName, connectionLabel)
                refresh()
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionListIdentity(macName, connectionLabel, Modifier.weight(1f))
                refresh()
            }
        }
    }
}

@Composable
private fun SessionListIdentity(
    macName: String,
    connectionLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Sessions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "$macName · $connectionLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionListContent(
    state: SessionListUiState,
    onEvent: (SessionListEvent) -> Unit,
) {
    val listState = rememberLazyListState()
    val grouped = SessionBucket.entries.mapNotNull { bucket ->
        state.sessions.filter { it.bucket == bucket }.takeIf(List<*>::isNotEmpty)?.let { bucket to it }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (grouped.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                EmptySessionsCard()
            }
        }
        grouped.forEach { (bucket, sessions) ->
            item(key = "bucket:${bucket.name}", contentType = "bucket_header") {
                Text(
                    text = bucket.label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { heading() },
                )
            }
            items(
                items = sessions,
                key = { it.metadata.id.value },
                contentType = { "session_row" },
            ) { session ->
                SessionListRow(
                    state = session,
                    onOpen = { onEvent(SessionListEvent.OpenSession(session.metadata.id)) },
                    onActions = { onEvent(SessionListEvent.OpenSessionActions(session.metadata.id)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionListRow(
    state: SessionListItemUiState,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val description = buildString {
        append(state.metadata.displayName)
        append(". ")
        append(state.runState.displayLabel())
        append(". Repository ")
        append(state.metadata.repositoryPath)
        append(". Worktree ")
        append(state.metadata.worktreePath)
        append(". ")
        append(state.latestActivity)
        state.parentSessionLabel?.let { append(". Child of $it") }
        if (state.blockerCount > 0) append(". ${state.blockerCount} blocking requests")
    }
    Card(
        modifier = Modifier
            .padding(start = (minOf(state.treeDepth, 3) * 12).dp)
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onActions)
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 380.dp
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SessionRowTitle(state)
                        SessionRowStatus(state)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) { SessionRowTitle(state) }
                        SessionRowStatus(state)
                    }
                }
                Text(
                    text = state.latestActivity,
                    style = MaterialTheme.typography.bodyLarge,
                )
                KeyValueText("Repository", state.metadata.repositoryPath)
                KeyValueText("Worktree", state.metadata.worktreePath)
                state.parentSessionLabel?.let { parent ->
                    KeyValueText("Child session of", parent)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.lastActiveLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTextButton(
                        label = "Actions",
                        description = "Open actions for ${state.metadata.displayName}",
                        onClick = onActions,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRowTitle(state: SessionListItemUiState) {
    Text(
        text = if (state.treeDepth > 0) "↳ ${state.metadata.displayName}" else state.metadata.displayName,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SessionRowStatus(state: SessionListItemUiState) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StateChip(
            label = state.runState.displayLabel(),
            tone = when (state.bucket) {
                SessionBucket.NEEDS_YOU, SessionBucket.INDETERMINATE -> StateTone.WARNING
                SessionBucket.WORKING -> StateTone.PRIMARY
                SessionBucket.READY_TO_REVIEW -> StateTone.POSITIVE
                SessionBucket.DONE -> StateTone.NEUTRAL
            },
        )
        if (state.blockerCount > 0) {
            StateChip("${state.blockerCount} blockers", StateTone.WARNING)
        }
    }
}

@Composable
private fun EmptySessionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No sessions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "No session summaries are available from the Mac yet. Refresh after a Pi session exists in a configured working directory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LockAction.toListEvent(): SessionListEvent = when (this) {
    LockAction.PAIR_MAC -> SessionListEvent.PairMac
    LockAction.AUTHENTICATE -> SessionListEvent.Authenticate
    LockAction.RETRY_CONNECTION -> SessionListEvent.RetryConnection
}
