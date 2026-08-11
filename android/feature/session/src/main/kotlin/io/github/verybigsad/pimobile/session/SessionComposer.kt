package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.SessionRunState

@Composable
internal fun SessionComposer(
    draft: DraftState,
    runState: SessionRunState,
    enabled: Boolean,
    onEvent: (SessionDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .imePadding(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            draft.transcriptionText?.let { transcription ->
                TranscriptionDraft(
                    text = transcription,
                    enabled = enabled,
                    onTextChanged = { onEvent(SessionDetailEvent.UpdateTranscription(it)) },
                    onInsert = { onEvent(SessionDetailEvent.InsertTranscription) },
                    onDiscard = { onEvent(SessionDetailEvent.DiscardTranscription) },
                )
            }
            OutlinedTextField(
                value = draft.typedText,
                onValueChange = { onEvent(SessionDetailEvent.UpdateTypedText(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .semantics { contentDescription = "Typed message draft" },
                enabled = enabled,
                label = { Text("Message") },
                placeholder = { Text(if (enabled) "Message Pi on your Mac" else "Composer unavailable in this state") },
                minLines = 2,
                maxLines = 7,
                shape = RoundedCornerShape(18.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactTextButton(
                    label = "Attach",
                    description = "Attach an image to this message",
                    onClick = { onEvent(SessionDetailEvent.Attach) },
                    enabled = enabled,
                )
                CompactTextButton(
                    label = "Voice",
                    description = "Open voice transcription",
                    onClick = { onEvent(SessionDetailEvent.StartVoice) },
                    enabled = enabled,
                )
                if (runState.isAgentRunning()) {
                    CompactTextButton(
                        label = "Stop",
                        description = "Stop the running Pi agent",
                        onClick = { onEvent(SessionDetailEvent.Stop) },
                        enabled = enabled,
                    )
                    CompactTextButton(
                        label = "Steer now",
                        description = "Send this text as steering during the current run",
                        onClick = { onEvent(SessionDetailEvent.SteerNow) },
                        enabled = enabled && draft.typedText.isNotBlank(),
                        outlined = false,
                    )
                    CompactTextButton(
                        label = "Queue follow-up",
                        description = "Queue this text after the current run",
                        onClick = { onEvent(SessionDetailEvent.QueueFollowUp) },
                        enabled = enabled && draft.typedText.isNotBlank(),
                        outlined = false,
                    )
                } else {
                    CompactTextButton(
                        label = "Send",
                        description = "Send typed message to Pi on the Mac",
                        onClick = { onEvent(SessionDetailEvent.Send) },
                        enabled = enabled && draft.typedText.isNotBlank(),
                        outlined = false,
                    )
                }
            }
            Text(
                text = if (enabled) {
                    "Typed text and transcription remain separate until you insert the transcription. Nothing is auto-sent."
                } else {
                    "Draft preserved. Sending requires a READY passkey-authenticated connection and canonical state."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuickReplies(enabled = enabled, onEvent = onEvent)
        }
    }
}

@Composable
private fun TranscriptionDraft(
    text: String,
    enabled: Boolean,
    onTextChanged: (String) -> Unit,
    onInsert: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Transcription draft",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .semantics { contentDescription = "Editable transcription draft" },
                enabled = enabled,
                minLines = 2,
                maxLines = 5,
                label = { Text("Transcription") },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactTextButton(
                    label = "Insert into message",
                    description = "Insert transcription into typed message draft",
                    onClick = onInsert,
                    enabled = enabled && text.isNotBlank(),
                    outlined = false,
                )
                CompactTextButton(
                    label = "Discard transcription",
                    description = "Discard transcription and leave typed text unchanged",
                    onClick = onDiscard,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun QuickReplies(
    enabled: Boolean,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Quick replies",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Continue", "Change approach", "Summarize").forEach { reply ->
                CompactTextButton(
                    label = reply,
                    description = "Use quick reply: $reply",
                    onClick = { onEvent(SessionDetailEvent.UseQuickReply(reply)) },
                    enabled = enabled,
                )
            }
        }
    }
}
