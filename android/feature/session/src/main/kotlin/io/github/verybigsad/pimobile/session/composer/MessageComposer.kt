package io.github.verybigsad.pimobile.session.composer

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageComposer(
    state: MessageComposerState,
    onIntent: (MessageComposerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val submit = {
        if (state.isStreaming) onIntent(MessageComposerIntent.Steer) else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onIntent(MessageComposerIntent.Send)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it.message)
            onIntent(MessageComposerIntent.ErrorShown)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.restoredFromCache) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Draft restored") },
                    modifier = Modifier.semantics { contentDescription = "Draft restored from cache" },
                )
            }
            StreamingAnnouncement(state.isStreaming)
            AttachmentStrip(state.attachments, state.enabled, onIntent)
            OutlinedTextField(
                value = state.text,
                onValueChange = { onIntent(MessageComposerIntent.TextChanged(it)) },
                enabled = state.enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 196.dp)
                    .semantics {
                        contentDescription = "Message composer"
                        testTag = "message-composer-field"
                    },
                placeholder = { Text("Message Pi on your Mac") },
                minLines = 1,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) submit() }),
                shape = RoundedCornerShape(18.dp),
            )
            SlashCommands(state, onIntent)
            VoiceStatus(state.voiceState)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = state.enabled && state.attachments.size < MAX_COMPOSER_ATTACHMENTS,
                    onClick = { onIntent(MessageComposerIntent.PickAttachments) },
                    modifier = Modifier.semantics { contentDescription = "Add image attachments" },
                ) { Text("Attach") }
                VoiceButton(state.voiceState, state.enabled, onIntent)
                if (state.isStreaming) {
                    TextButton(
                        enabled = state.enabled,
                        onClick = { onIntent(MessageComposerIntent.Stop) },
                        modifier = Modifier.semantics { contentDescription = "Stop running response" },
                    ) { Text("Stop") }
                }
                TextButton(
                    enabled = state.canSend,
                    onClick = submit,
                    modifier = Modifier.semantics {
                        contentDescription = if (state.isStreaming) "Steer running response" else "Send message"
                    },
                ) { Text(if (state.isStreaming) "Steer" else "Send") }
                if (state.isStreaming) {
                    TextButton(
                        enabled = state.canSend,
                        onClick = { onIntent(MessageComposerIntent.QueueSteering) },
                        modifier = Modifier.semantics { contentDescription = "Queue steering message" },
                    ) { Text(if (state.queuedSteering.isEmpty()) "Queue" else "Queued") }
                }
            }
            SteeringQueue(state.queuedSteering)
            SnackbarHost(hostState = snackbarHostState)
        }
    }
}

@Composable
private fun StreamingAnnouncement(isStreaming: Boolean) {
    if (isStreaming) {
        Text(
            "Response streaming. New text will steer the current response.",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun AttachmentStrip(
    attachments: List<ComposerAttachment>,
    enabled: Boolean,
    onIntent: (MessageComposerIntent) -> Unit,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            Card(
                modifier = Modifier.size(72.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (attachment.thumbnail != null) {
                        Image(
                            bitmap = attachment.thumbnail,
                            contentDescription = attachment.name,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Text("Image", modifier = Modifier.height(44.dp).padding(top = 12.dp))
                    }
                    IconButton(
                        enabled = enabled,
                        onClick = { onIntent(MessageComposerIntent.RemoveAttachment(attachment.id)) },
                        modifier = Modifier.size(24.dp).semantics {
                            contentDescription = "Remove ${attachment.name}"
                        },
                    ) { Text("×") }
                }
            }
        }
    }
}

@Composable
private fun SlashCommands(state: MessageComposerState, onIntent: (MessageComposerIntent) -> Unit) {
    if (!state.text.startsWith('/') || state.slashCommands.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.slashCommands.forEach { command ->
            FilterChip(
                selected = false,
                onClick = { onIntent(MessageComposerIntent.SelectSlashCommand(command)) },
                enabled = state.enabled,
                label = { Text(command.name) },
                modifier = Modifier.semantics { contentDescription = "Command ${command.name}: ${command.description}" },
            )
        }
    }
}

@Composable
private fun VoiceButton(voiceState: VoiceState, enabled: Boolean, onIntent: (MessageComposerIntent) -> Unit) {
    val recording = voiceState is VoiceState.Recording
    val label = when (voiceState) {
        VoiceState.Idle, is VoiceState.Failed -> "Voice"
        VoiceState.Recording -> "Stop voice"
        VoiceState.Processing -> "Processing"
        is VoiceState.Partial -> "Voice"
    }
    TextButton(
        enabled = enabled && voiceState !is VoiceState.Processing,
        onClick = {
            onIntent(if (recording) MessageComposerIntent.StopVoice else MessageComposerIntent.StartVoice)
        },
        modifier = Modifier.semantics {
            contentDescription = if (recording) "Stop voice dictation" else "Start voice dictation"
        },
    ) { Text(label) }
}

@Composable
private fun VoiceStatus(voiceState: VoiceState) {
    val text = when (voiceState) {
        VoiceState.Idle -> null
        VoiceState.Recording -> "Listening…"
        VoiceState.Processing -> "Processing voice…"
        is VoiceState.Partial -> "Transcript: ${voiceState.transcript}"
        is VoiceState.Failed -> "Voice unavailable: ${voiceState.message}"
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (voiceState is VoiceState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SteeringQueue(items: List<QueuedSteeringItem>) {
    if (items.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = "Queued steering messages" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            AssistChip(onClick = {}, label = { Text(item.text, maxLines = 1) })
        }
    }
}
