package io.github.verybigsad.pimobile.session.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.session.CompactTextButton
import io.github.verybigsad.pimobile.session.StateChip
import io.github.verybigsad.pimobile.session.StateTone

@Composable
fun SessionInboxScreen(
    state: SessionInboxUiState,
    onEvent: (SessionInboxEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullResyncContainer(
        isRefreshing = state.isRefreshing,
        onRefresh = { onEvent(SessionInboxEvent.RequestResync) },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            InboxHeader(state, onRefresh = { onEvent(SessionInboxEvent.RequestResync) })
            if (state.isOffline) OfflineNotice(state.offlineLabel)
            when (val load = state.loadState) {
                SessionInboxLoadState.Loading -> InboxStatus("Loading sessions", "Syncing your session catalog from the Mac.")
                is SessionInboxLoadState.Error -> InboxStatus("Couldn't load sessions", load.message, retry = { onEvent(SessionInboxEvent.RequestResync) })
                SessionInboxLoadState.Ready -> InboxContent(state, onEvent)
            }
        }
    }
}

@Composable
private fun PullResyncContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isRefreshing) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, amount -> dragged = (dragged + amount).coerceAtLeast(0f) },
                    onDragEnd = {
                        if (!isRefreshing && dragged >= threshold) onRefresh()
                        dragged = 0f
                    },
                    onDragCancel = { dragged = 0f },
                )
            },
    ) {
        content()
        if (isRefreshing || dragged > 0f) {
            Text(
                if (isRefreshing) "Refreshing sessions…" else if (dragged >= threshold) "Release to resync" else "Pull to resync",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .semantics {
                        contentDescription = if (isRefreshing) "Refreshing session inbox" else "Pull to refresh session inbox"
                    },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InboxHeader(state: SessionInboxUiState, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Sessions",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Your Pi inbox",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(12.dp))
        CompactTextButton(
            label = if (state.isRefreshing) "Refreshing…" else "Refresh",
            description = "Resync session inbox from Mac",
            onClick = onRefresh,
            enabled = !state.isRefreshing,
        )
    }
}

@Composable
private fun OfflineNotice(lastSyncedLabel: String?) {
    Text(
        text = lastSyncedLabel?.let { "Offline · $it" } ?: "Offline · showing saved sessions",
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Offline session catalog" }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun InboxContent(state: SessionInboxUiState, onEvent: (SessionInboxEvent) -> Unit) {
    if (state.items.isEmpty()) {
        InboxStatus("No sessions yet", "Start Pi on your Mac, then pull down to refresh.", retry = { onEvent(SessionInboxEvent.RequestResync) })
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(360.dp),
                contentPadding = PaddingValues(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.items, key = { it.id.value }) { item -> InboxRow(item, onEvent) }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items.size, key = { state.items[it].id.value }) { InboxRow(state.items[it], onEvent) }
            }
        }
    }
}

@Composable
private fun InboxStatus(title: String, message: String, retry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            message,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (retry != null) {
            CompactTextButton("Try again", "Resync session inbox", retry, Modifier.padding(top = 12.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(item: SessionInboxItemUiState, onEvent: (SessionInboxEvent) -> Unit) {
    val description = buildString {
        append(item.title)
        append(", ").append(item.activity.label)
        append(", ").append(item.trustBadge.label)
        append(", updated ").append(item.relativeTimestamp)
        if (item.unreadCount > 0) append(", ${item.unreadCount} unread")
        if (item.hasSettlement) append(", settled")
        item.catalogLabel?.let { append(", $it") }
        append(". ${item.preview}")
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (item.treeDepth.coerceAtMost(3) * 10).dp)
            .combinedClickable(
                onClick = { onEvent(SessionInboxEvent.Open(item.id)) },
                onLongClick = { onEvent(SessionInboxEvent.OpenActions(item.id)) },
            )
            .semantics {
                role = Role.Button
                contentDescription = description
            },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.catalogLabel?.let { label ->
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(item.relativeTimestamp, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StateChip(item.trustBadge.label, trustTone(item.trustBadge))
                StateChip(item.activity.label, activityTone(item.activity))
                if (item.unreadCount > 0) StateChip("${item.unreadCount} unread", StateTone.PRIMARY)
                if (item.hasSettlement) StateChip("Settled", StateTone.POSITIVE)
            }
            Text(item.preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.repositoryPath, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.worktreePath, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompactTextButton("Actions", "Open actions for ${item.title}", onClick = { onEvent(SessionInboxEvent.OpenActions(item.id)) })
            }
        }
    }
}

private fun trustTone(badge: SessionTrustBadge): StateTone = when (badge) {
    SessionTrustBadge.TRUSTED -> StateTone.POSITIVE
    SessionTrustBadge.REVOKED, SessionTrustBadge.EXPIRED -> StateTone.WARNING
    SessionTrustBadge.PROVISIONAL -> StateTone.NEUTRAL
}

private fun activityTone(activity: SessionActivityIndicator): StateTone = when (activity) {
    SessionActivityIndicator.STREAMING -> StateTone.PRIMARY
    SessionActivityIndicator.AWAITING_APPROVAL, SessionActivityIndicator.WAITING -> StateTone.WARNING
    SessionActivityIndicator.SETTLED -> StateTone.POSITIVE
    SessionActivityIndicator.IDLE -> StateTone.NEUTRAL
}
