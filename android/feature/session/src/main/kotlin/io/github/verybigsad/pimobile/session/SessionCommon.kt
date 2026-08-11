package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.TransportPath

@Composable
internal fun AccessLockedCard(
    state: SessionContentAccess.Locked,
    onAction: (LockAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = state.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.code?.let {
                KeyValueText(label = "Code", value = it)
            }
            state.action?.let { action ->
                Button(
                    onClick = { onAction(action) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = action.accessibilityLabel() },
                ) {
                    Text(action.buttonLabel())
                }
            }
        }
    }
}

@Composable
internal fun OfflineBanner(
    state: SessionContentAccess.Offline,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Offline. Cached session data. ${state.lastSyncedLabel ?: "Last sync unavailable"}"
            },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Offline · cached data", fontWeight = FontWeight.Bold)
            Text(
                text = state.lastSyncedLabel ?: "Last sync time unavailable",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun RunStateCard(
    runState: SessionRunState,
    modifier: Modifier = Modifier,
) {
    val container = when (runState) {
        SessionRunState.FAULTED -> MaterialTheme.colorScheme.errorContainer
        SessionRunState.WAITING_FOR_INPUT -> MaterialTheme.colorScheme.secondaryContainer
        SessionRunState.WAITING_FOR_CANONICAL -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when (runState) {
        SessionRunState.FAULTED -> MaterialTheme.colorScheme.onErrorContainer
        SessionRunState.WAITING_FOR_INPUT -> MaterialTheme.colorScheme.onSecondaryContainer
        SessionRunState.WAITING_FOR_CANONICAL -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Session state ${runState.displayLabel()}. ${runState.supportingCopy()}"
            },
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(runState.displayLabel(), fontWeight = FontWeight.Bold)
            Text(runState.supportingCopy(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun ExecutionTarget(
    metadata: SessionMetadata,
    macDisplayName: String,
    connection: ConnectionState,
    modifier: Modifier = Modifier,
) {
    val path = when (connection) {
        is ConnectionState.Ready -> connection.path.displayLabel()
        is ConnectionState.DeviceAuthenticated -> connection.path.displayLabel()
        is ConnectionState.Connecting -> connection.path.displayLabel()
        is ConnectionState.PairingProvisional -> "provisional ${connection.path.displayLabel()}"
        else -> "offline"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Execution target",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(macDisplayName, fontWeight = FontWeight.Bold)
            KeyValueText("Repository", metadata.repositoryPath)
            KeyValueText("Worktree", metadata.worktreePath)
            KeyValueText("Connection", path)
        }
    }
}

@Composable
internal fun StateChip(
    label: String,
    tone: StateTone,
    modifier: Modifier = Modifier,
) {
    val colors = when (tone) {
        StateTone.PRIMARY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        StateTone.POSITIVE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        StateTone.WARNING -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        StateTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "State: $label" },
        color = colors.first,
        contentColor = colors.second,
        shape = RoundedCornerShape(100.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

internal enum class StateTone {
    PRIMARY,
    POSITIVE,
    WARNING,
    NEUTRAL,
}

@Composable
internal fun MetadataFlow(
    modelName: String?,
    thinkingLevel: String?,
    elapsedLabel: String?,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modelName?.let { StateChip("Model · $it", StateTone.NEUTRAL) }
        thinkingLevel?.let { StateChip("Thinking · $it", StateTone.NEUTRAL) }
        elapsedLabel?.let { StateChip("Elapsed · $it", StateTone.NEUTRAL) }
    }
}

@Composable
internal fun KeyValueText(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospaced: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospaced) FontFamily.Monospace else null,
        )
    }
}

@Composable
internal fun HorizontalCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

@Composable
internal fun CompactTextButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = true,
) {
    val buttonModifier = modifier
        .heightIn(min = 48.dp)
        .semantics { contentDescription = description }
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, overflow = TextOverflow.Visible)
        }
    } else {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(label, overflow = TextOverflow.Visible)
        }
    }
}

private fun LockAction.buttonLabel(): String = when (this) {
    LockAction.PAIR_MAC -> "Pair a Mac"
    LockAction.AUTHENTICATE -> "Unlock with passkey"
    LockAction.RETRY_CONNECTION -> "Try Mac again"
}

private fun LockAction.accessibilityLabel(): String = when (this) {
    LockAction.PAIR_MAC -> "Pair a Mac to access sessions"
    LockAction.AUTHENTICATE -> "Unlock sessions with passkey"
    LockAction.RETRY_CONNECTION -> "Retry connection to Mac"
}

private fun TransportPath.displayLabel(): String = when (this) {
    TransportPath.DIRECT -> "direct"
    TransportPath.RELAY -> "relayed"
}
