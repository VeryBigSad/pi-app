package io.github.verybigsad.pimobile.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MessageContent
import io.github.verybigsad.pimobile.model.MessageContentKind
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.MessageRole
import io.github.verybigsad.pimobile.model.ProvisionalMessage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Presentation rows extend [SessionDetailUiState] without mutating its transport-backed state.
 * Message rows retain protocol stable keys; decorations are deterministic from message timestamps.
 */
internal sealed interface TimelineRow {
    val stableKey: String
    val contentType: String

    data class DateSeparator(val epochMillis: Long) : TimelineRow {
        override val stableKey = "date:${dayKey(epochMillis)}"
        override val contentType = "date_separator"
    }

    data class Message(val entry: TimelineEntry) : TimelineRow {
        override val stableKey = entry.stableKey
        override val contentType = entry.contentType
    }

    data class CanonicalGap(val entry: TimelineEntry.CanonicalUnavailable) : TimelineRow {
        override val stableKey = entry.stableKey
        override val contentType = entry.contentType
    }

    data class Settlement(val settlementId: String) : TimelineRow {
        override val stableKey = "settlement:$settlementId"
        override val contentType = "settlement"
    }
}

internal fun buildTimelineRows(state: SessionDetailUiState): List<TimelineRow> {
    val entries = buildTimelineEntries(state.session)
    if (entries.singleOrNull() is TimelineEntry.CanonicalUnavailable) {
        return listOf(TimelineRow.CanonicalGap(entries.single() as TimelineEntry.CanonicalUnavailable))
    }
    var previousDay: String? = null
    return buildList {
        entries.forEach { entry ->
            val timestamp = entry.timestampMillis()
            val day = dayKey(timestamp)
            if (day != previousDay) add(TimelineRow.DateSeparator(timestamp))
            add(TimelineRow.Message(entry))
            previousDay = day
        }
        state.session.conversation.lastSettlementId?.let { add(TimelineRow.Settlement(it.value)) }
    }
}

@Composable
internal fun SessionTimeline(
    state: SessionDetailUiState,
    onEvent: (SessionDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    contextContent: (@Composable () -> Unit)? = null,
) {
    // Rows are a pure append-oriented projection. Stable protocol IDs prevent streaming
    // replacements from invalidating LazyColumn slots or restarting visible composition.
    val rows = remember(state.session.conversation) { buildTimelineRows(state) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newestIndex = remember(rows, contextContent) { rows.lastIndex + if (contextContent == null) 0 else 1 }
    val unreadCount by remember(listState, newestIndex) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: newestIndex
            (newestIndex - lastVisible).coerceAtLeast(0)
        }
    }
    LaunchedEffect(rows, newestIndex) {
        withFrameNanos { }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= listState.layoutInfo.totalItemsCount - 2) {
            listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Session timeline" },
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            contextContent?.let { content -> item(key = "session_context", contentType = "session_context") { content() } }
            if (state.session.conversation.hasOlderMessages && state.session.conversation.availability is CanonicalAvailability.Current) {
                item(key = "older", contentType = "older_control") {
                    CompactTextButton("Load older messages", "Load older finalized messages from encrypted cache or Mac", { onEvent(SessionDetailEvent.LoadOlder) }, Modifier.fillMaxWidth())
                }
            }
            items(rows, key = TimelineRow::stableKey, contentType = TimelineRow::contentType) { row ->
                when (row) {
                    is TimelineRow.DateSeparator -> DateSeparator(row.epochMillis)
                    is TimelineRow.Settlement -> SettlementMarker()
                    is TimelineRow.CanonicalGap -> CanonicalUnavailableCard(row.entry.explanation)
                    is TimelineRow.Message -> MessageRow(row.entry, state.expandedContentIds, onEvent)
                }
            }
        }
        if (unreadCount > 0 && rows.isNotEmpty()) {
            CompactTextButton(
                label = if (unreadCount == 1) "Jump to latest · 1 unread" else "Jump to latest · $unreadCount unread",
                description = "Scroll conversation to latest message; $unreadCount unread messages",
                onClick = { scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                outlined = false,
            )
        }
    }
}

@Composable
private fun MessageRow(entry: TimelineEntry, expandedIds: Set<String>, onEvent: (SessionDetailEvent) -> Unit) = when (entry) {
    is TimelineEntry.Finalized -> MessageBubble(entry.message.id, entry.message.role, entry.message.content, "Final message", false, expandedIds, onEvent)
    is TimelineEntry.Provisional -> MessageBubble(entry.message.id, entry.message.role, entry.message.content, "Live · provisional · may be replaced", true, expandedIds, onEvent)
    is TimelineEntry.CanonicalUnavailable -> Unit
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    messageId: MessageId,
    role: MessageRole,
    content: List<MessageContent>,
    status: String,
    provisional: Boolean,
    expandedIds: Set<String>,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    var contextOpen by rememberSaveable(messageId.value) { mutableStateOf(false) }
    val visibleText = remember(content) { content.filter { it.kind == MessageContentKind.TEXT }.joinToString("\n") { sanitizeStructuredDisplay(displayMessageText(it)) } }
    val roleLabel = role.displayLabel()
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { contextOpen = true }).semantics {
            contentDescription = "$roleLabel. $status. Long press for message actions"
            traversalIndex = 1f
        },
        colors = CardDefaults.cardColors(containerColor = if (role == MessageRole.USER) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        border = if (provisional) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(roleLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StateChip(status, if (provisional) StateTone.PRIMARY else StateTone.NEUTRAL)
            }
            if (content.isEmpty()) Text(if (provisional) "Waiting for content…" else "No displayable content", color = MaterialTheme.colorScheme.onSurfaceVariant)
            content.forEach { block ->
                ContentBlock(block, provisional, block.globalId(messageId) in expandedIds,
                    onToggle = { onEvent(SessionDetailEvent.ToggleContent(block.globalId(messageId))) },
                    onInspect = { onEvent(SessionDetailEvent.InspectRaw(messageId, block.stableId)) })
            }
            CompactTextButton("Inspect raw", "Inspect raw event for $roleLabel message", { onEvent(SessionDetailEvent.InspectRaw(messageId, null)) })
        }
    }
    if (contextOpen) MessageContextMenu(visibleText, { contextOpen = false })
}

@Composable
private fun ContentBlock(block: MessageContent, provisional: Boolean, expanded: Boolean, onToggle: () -> Unit, onInspect: () -> Unit) {
    when (block.kind) {
        MessageContentKind.TEXT -> Text(sanitizeStructuredDisplay(displayMessageText(block)), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { traversalIndex = 2f })
        MessageContentKind.THINKING -> ExpandableCard("Thinking", if (provisional) "Live thinking" else "Final thinking", displayMessageText(block), expanded, onToggle, onInspect)
        MessageContentKind.TOOL_CALL -> ToolCard("Tool call", "Requested", block.projection, expanded, onToggle, onInspect)
        MessageContentKind.TOOL_RESULT -> ToolCard("Tool result", if (provisional) "Receiving" else "Complete", block.projection, expanded, onToggle, onInspect)
        MessageContentKind.IMAGE -> ImageAttachment(block.projection, onInspect)
        MessageContentKind.UNKNOWN -> ExpandableCard("Retained unknown content", "Not interpreted", block.projection, expanded, onToggle, onInspect)
    }
}

@Composable
private fun ExpandableCard(title: String, status: String, text: String, expanded: Boolean, onToggle: () -> Unit, onInspect: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(status, style = MaterialTheme.typography.labelMedium) }
                CompactTextButton(if (expanded) "Collapse" else "Show", "${if (expanded) "Collapse" else "Show"} $title", onToggle)
            }
            if (expanded) { Text(sanitizeStructuredDisplay(text), style = MaterialTheme.typography.bodyMedium); CompactTextButton("Inspect raw", "Inspect raw $title content", onInspect) }
        }
    }
}

@Composable
private fun ToolCard(title: String, status: String, text: String, expanded: Boolean, onToggle: () -> Unit, onInspect: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); StateChip(status, StateTone.NEUTRAL) }
            Text(boundedPreview(text), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
            CompactTextButton(if (expanded) "Hide result" else "Show result", "${if (expanded) "Hide" else "Show"} $title details", onToggle)
            if (expanded) { CopyableCodeBlock(sanitizeStructuredDisplay(text)); CompactTextButton("Inspect raw", "Inspect raw $title", onInspect) }
        }
    }
}

@Composable
private fun CopyableCodeBlock(text: String) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalCodeBlock(text)
        CompactTextButton("Copy code", "Copy code block", { copyText(context, text) })
    }
}

@Composable
private fun ImageAttachment(metadata: String, onInspect: () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Image attachment" }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Image attachment", fontWeight = FontWeight.Bold)
            Text(boundedPreview(metadata), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompactTextButton("Inspect attachment", "Inspect raw image attachment metadata", onInspect)
        }
    }
}

@Composable
private fun MessageContextMenu(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message actions") },
        text = { Text(if (text.isBlank()) "No text content to copy or share." else boundedPreview(text)) },
        confirmButton = {
            TextButton(onClick = { copyText(context, text); onDismiss() }, enabled = text.isNotBlank()) { Text("Copy") }
        },
        dismissButton = {
            Row { TextButton(onClick = { shareText(context, text); onDismiss() }, enabled = text.isNotBlank()) { Text("Share") }; TextButton(onClick = onDismiss) { Text("Cancel") } }
        },
    )
}

@Composable
private fun DateSeparator(epochMillis: Long) = Text(
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis)),
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Messages from ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))}" },
)

@Composable
private fun SettlementMarker() = Text(
    "Agent settled",
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Agent settled; durable completion reported by host" },
)

@Composable
private fun CanonicalUnavailableCard(explanation: String) {
    Card(Modifier.fillMaxWidth().semantics { contentDescription = "Waiting for canonical state. $explanation" }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waiting for canonical state", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(explanation)
        }
    }
}

internal fun boundedPreview(value: String, limit: Int = 320): String {
    require(limit > 0)
    val sanitized = sanitizeStructuredDisplay(value)
    return if (sanitized.length <= limit) sanitized else sanitized.take(limit).trimEnd() + "…"
}

private fun TimelineEntry.timestampMillis(): Long = when (this) {
    is TimelineEntry.Finalized -> message.createdAtEpochMillis
    is TimelineEntry.Provisional -> message.startedAtEpochMillis
    is TimelineEntry.CanonicalUnavailable -> 0L
}

private fun MessageRole.displayLabel(): String = when (this) {
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> "Pi"
    MessageRole.TOOL -> "Tool"
    MessageRole.SYSTEM -> "System"
    MessageRole.UNKNOWN -> "Unknown role"
}

private fun MessageContent.globalId(messageId: MessageId): String = "${messageId.value}:$stableId"
private fun dayKey(epochMillis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMillis))
private fun copyText(context: Context, value: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pi message", value)) }
private fun shareText(context: Context, value: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value), "Share message").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

/**
 * Human-readable text for one content part. The model retains the canonical JSON projection;
 * text and thinking parts carry their display string in a named field of that projection, so
 * rendering extracts the field instead of showing the raw envelope. Anything else (tool calls,
 * unknown kinds, malformed JSON) falls back to the raw projection unchanged.
 */
internal fun displayMessageText(content: MessageContent): String {
    val field = when (content.kind) {
        MessageContentKind.TEXT -> "text"
        MessageContentKind.THINKING -> "thinking"
        else -> return content.projection
    }
    val parsed = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(content.projection) as? kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return content.projection
    val value = parsed[field] as? kotlinx.serialization.json.JsonPrimitive
    return if (value != null && value.isString) value.content else content.projection
}

private val csiPattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val oscPattern = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

/** Strips terminal/host control surface while preserving printable content and layout. */
internal fun sanitizeStructuredDisplay(value: String): String {
    val withoutAnsi = value.replace("\r\n", "\n").replace('\r', '\n').replace(csiPattern, "").replace(oscPattern, "")
    return buildString(withoutAnsi.length) {
        var index = 0
        while (index < withoutAnsi.length) {
            val point = withoutAnsi.codePointAt(index)
            if (point == '\n'.code || point == '\t'.code) appendCodePoint(point) else {
                val type = Character.getType(point)
                if (type != Character.CONTROL.toInt() && type != Character.FORMAT.toInt()) appendCodePoint(point)
            }
            index += Character.charCount(point)
        }
    }
}
