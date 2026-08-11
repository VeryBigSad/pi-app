package io.github.verybigsad.pimobile.agents

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.agents.RelativeTime.endedLabel
import io.github.verybigsad.pimobile.agents.RelativeTime.startedLabel

private val CONTENT_MAX_WIDTH = 840.dp
private val INDENT_STEP = 20.dp
private const val CLOCK_TICK_MILLIS = 30_000L

/** Live overload: collects [AgentsStore.state] and ticks relative timestamps every 30s. */
@Composable
fun AgentsScreen(store: AgentsStore, modifier: Modifier = Modifier) {
    val state by store.state.collectAsState()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(CLOCK_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    AgentsScreen(state = state, nowEpochMillis = now, modifier = modifier, onToggleSession = store::toggleSession)
}

@Composable
fun AgentsScreen(
    state: AgentsUiState,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier,
    onToggleSession: (String) -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 16.dp
        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                Text(
                    text = "Agents",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.isOffline) {
                item(key = "offline") { OfflineBanner() }
            }
            if (state.isEmpty) {
                item(key = "empty") { EmptyAgentsCard(isOffline = state.isOffline) }
            } else {
                state.sessions.forEach { session ->
                    item(key = "header-${session.sessionId}") {
                        SessionHeader(
                            session = session,
                            onToggle = { onToggleSession(session.sessionId) },
                        )
                    }
                    if (session.expanded) {
                        items(
                            count = session.agents.size,
                            key = { index -> "agent-${session.sessionId}-${session.agents[index].agentId}" },
                            contentType = { "agent" },
                        ) { index ->
                            AgentRow(session.agents[index], nowEpochMillis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = "Offline — showing last known agent state",
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = "Offline. Showing last known agent state." },
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun EmptyAgentsCard(isOffline: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No agents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (isOffline) {
                    "Reconnect to see live agent activity."
                } else {
                    "Agent activity appears here when a session starts agents."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionHeader(session: AgentSessionUiState, onToggle: () -> Unit) {
    val stateDescription = if (session.expanded) "Expanded" else "Collapsed"
    val runningCount = session.agents.count { it.status == AgentStatus.RUNNING }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = if (session.expanded) "Collapse session" else "Expand session",
                onClick = onToggle,
            )
            .semantics(mergeDescendants = true) { this.stateDescription = stateDescription },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (session.expanded) "▾" else "▸",
                style = MaterialTheme.typography.titleMedium,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Session ${session.sessionId.take(8)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${session.agents.size} agents · $runningCount running",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentRow(agent: AgentUiState, nowEpochMillis: Long) {
    val timing = buildList {
        add(startedLabel(agent.startedAtEpochMillis, nowEpochMillis))
        agent.endedAtEpochMillis?.let { add(endedLabel(it, nowEpochMillis)) }
    }.joinToString(" · ")
    val toolLabel = if (agent.toolUses == 1) "1 tool use" else "${agent.toolUses} tool uses"
    val a11y = buildString {
        append(agent.description).append(", type ").append(agent.agentType)
        append(", status ").append(agent.status.label)
        append(", ").append(timing).append(", ").append(toolLabel)
        agent.model?.let { append(", model ").append(it) }
        if (agent.depth > 0) append(", nested level ").append(agent.depth)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = INDENT_STEP * agent.depth),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { contentDescription = a11y },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentStatusChip(agent.status)
                agent.model?.let { ModelBadge(it) }
                Spacer(Modifier.width(0.dp))
            }
            Text(
                agent.description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${agent.agentType} · $timing · $toolLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AgentStatusChip(status: AgentStatus) {
    val (container, content) = when (status) {
        AgentStatus.RUNNING ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        AgentStatus.WAITING ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        AgentStatus.COMPLETED ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        AgentStatus.FAILED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        AgentStatus.STOPPED ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.semantics { contentDescription = "Status ${status.label}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status == AgentStatus.RUNNING) {
                PulsingDot(content)
            }
            Text(status.label, style = MaterialTheme.typography.labelLarge, color = content)
        }
    }
}

@Composable
private fun PulsingDot(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "running-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "running-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

@Composable
private fun ModelBadge(model: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.semantics { contentDescription = "Model $model" },
    ) {
        Text(
            text = model,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
