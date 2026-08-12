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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.SessionRunState
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SessionComposer(
    draft: DraftState,
    runState: SessionRunState,
    commandNotice: CommandNoticeUiState?,
    voicePermission: VoicePermissionUiState?,
    voice: VoiceCaptureUiState?,
    enabled: Boolean,
    onEvent: (SessionDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val voiceState = voice ?: voicePermission?.toVoiceCaptureUiState(draft)
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
            VoiceStatusSurface(
                voice = voiceState,
                hasTranscriptionDraft = draft.transcriptionText != null,
                composerEnabled = enabled,
                onEvent = onEvent,
            )
            draft.transcriptionText?.let { transcription ->
                TranscriptionDraft(
                    text = transcription,
                    finalTranscript = voiceState?.finalTranscriptReady == true,
                    enabled = enabled,
                    onTextChanged = { onEvent(SessionDetailEvent.UpdateTranscription(it)) },
                    onInsert = { onEvent(SessionDetailEvent.InsertTranscription) },
                    onDiscard = { onEvent(SessionDetailEvent.DiscardTranscription) },
                )
            }
            commandNotice?.let { notice ->
                Text(
                    text = notice.message(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
                VoiceActions(
                    voice = voiceState,
                    hasTranscriptionDraft = draft.transcriptionText != null,
                    composerEnabled = enabled,
                    onEvent = onEvent,
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
private fun VoiceActions(
    voice: VoiceCaptureUiState?,
    hasTranscriptionDraft: Boolean,
    composerEnabled: Boolean,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    when {
        voice?.finalTranscriptReady == true && !hasTranscriptionDraft -> CompactTextButton(
            label = "Voice",
            description = "Start voice dictation",
            onClick = { onEvent(SessionDetailEvent.StartVoice) },
            enabled = composerEnabled,
        )

        voice == null || voice.phase == VoiceCaptureUiPhase.IDLE -> CompactTextButton(
            label = "Voice",
            description = "Start voice dictation",
            onClick = { onEvent(SessionDetailEvent.StartVoice) },
            enabled = composerEnabled,
        )

        voice.phase == VoiceCaptureUiPhase.STARTING || voice.phase == VoiceCaptureUiPhase.CAPTURING -> {
            CompactTextButton(
                label = "Stop voice",
                description = "Stop voice dictation and request a final transcription",
                onClick = { onEvent(SessionDetailEvent.StopVoice) },
                enabled = true,
                outlined = false,
            )
            CompactTextButton(
                label = "Cancel voice",
                description = "Cancel voice dictation and discard captured audio",
                onClick = { onEvent(SessionDetailEvent.CancelVoice) },
                enabled = true,
            )
        }

        else -> Unit
    }
}

@Composable
private fun VoiceStatusSurface(
    voice: VoiceCaptureUiState?,
    hasTranscriptionDraft: Boolean,
    composerEnabled: Boolean,
    onEvent: (SessionDetailEvent) -> Unit,
) {
    voice ?: return
    if (voice.phase == VoiceCaptureUiPhase.IDLE || voice.finalTranscriptReady && !hasTranscriptionDraft) return
    val error = voice.error
    val (containerColor, contentColor) = when {
        error != null || voice.phase == VoiceCaptureUiPhase.FAILED -> {
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }

        voice.phase == VoiceCaptureUiPhase.PERMISSION_REQUIRED ||
            voice.phase == VoiceCaptureUiPhase.PERMISSION_DENIED ||
            voice.phase == VoiceCaptureUiPhase.REQUESTING_PERMISSION -> {
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        }

        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = voice.accessibilityDescription(hasTranscriptionDraft)
                liveRegion = LiveRegionMode.Polite
            },
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = voice.title(hasTranscriptionDraft),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor,
            )
            Text(
                text = voice.supportingCopy(hasTranscriptionDraft),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
            VoiceBacklog(voice, contentColor)
            error?.let { VoiceErrorDetails(it, contentColor) }
            when (voice.phase) {
                VoiceCaptureUiPhase.PERMISSION_REQUIRED -> CompactTextButton(
                    label = "Allow microphone",
                    description = "Ask Android for microphone access again",
                    onClick = { onEvent(SessionDetailEvent.StartVoice) },
                    enabled = composerEnabled,
                    outlined = false,
                )

                VoiceCaptureUiPhase.PERMISSION_DENIED -> CompactTextButton(
                    label = if (voice.canOpenPermissionSettings) "Open settings" else "Allow microphone",
                    description = if (voice.canOpenPermissionSettings) {
                        "Open Android app settings to enable microphone access"
                    } else {
                        "Ask Android for microphone access again"
                    },
                    onClick = {
                        onEvent(
                            if (voice.canOpenPermissionSettings) {
                                SessionDetailEvent.OpenVoicePermissionSettings
                            } else {
                                SessionDetailEvent.StartVoice
                            },
                        )
                    },
                    enabled = composerEnabled,
                    outlined = false,
                )

                VoiceCaptureUiPhase.CANCELED,
                VoiceCaptureUiPhase.FAILED,
                VoiceCaptureUiPhase.CLOSED,
                -> CompactTextButton(
                    label = "Try voice again",
                    description = "Start a new voice dictation",
                    onClick = { onEvent(SessionDetailEvent.StartVoice) },
                    enabled = composerEnabled,
                    outlined = false,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun VoiceBacklog(
    voice: VoiceCaptureUiState,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    if (voice.queueDepth == 0 && voice.queuedAudioMilliseconds == 0) return
    Text(
        text = "Audio backlog: ${voice.queueDepth} chunks; ${voice.queuedAudioMilliseconds} ms queued.",
        style = MaterialTheme.typography.bodySmall,
        color = contentColor,
    )
}

@Composable
private fun VoiceErrorDetails(
    error: VoiceCaptureErrorUiState,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    error.detail?.let { detail ->
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
    error.retryAfterMilliseconds?.let { retryAfterMilliseconds ->
        Text(
            text = "Retry after ${retryAfterLabel(retryAfterMilliseconds)}.",
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
    error.resetAtEpochMilliseconds?.let { resetAtEpochMilliseconds ->
        Text(
            text = "Reset time: ${DateFormat.getDateTimeInstance().format(Date(resetAtEpochMilliseconds))}.",
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
        )
    }
}

private fun VoiceCaptureUiState.title(hasTranscriptionDraft: Boolean): String = when {
    finalTranscriptReady && hasTranscriptionDraft -> "Final transcription ready"
    error != null -> error.title
    else -> when (phase) {
        VoiceCaptureUiPhase.REQUESTING_PERMISSION -> "Waiting for microphone permission"
        VoiceCaptureUiPhase.PERMISSION_REQUIRED,
        VoiceCaptureUiPhase.PERMISSION_DENIED,
        -> "Microphone access needed"

        VoiceCaptureUiPhase.STARTING -> "Starting voice dictation"
        VoiceCaptureUiPhase.CAPTURING -> "Listening"
        VoiceCaptureUiPhase.PROCESSING -> "Processing voice"
        VoiceCaptureUiPhase.CANCELING -> "Canceling voice dictation"
        VoiceCaptureUiPhase.CANCELED -> "Voice dictation canceled"
        VoiceCaptureUiPhase.FAILED -> "Voice transcription unavailable"
        VoiceCaptureUiPhase.CLOSED -> "Voice capture unavailable"
        VoiceCaptureUiPhase.IDLE -> "Voice dictation"
    }
}

private fun VoiceCaptureUiState.supportingCopy(hasTranscriptionDraft: Boolean): String = when {
    finalTranscriptReady && hasTranscriptionDraft -> {
        "Review or edit it, then insert it into your message. It will not be sent automatically."
    }

    error != null -> "Your typed message has not changed."
    else -> when (phase) {
        VoiceCaptureUiPhase.REQUESTING_PERMISSION -> "Android is asking for access before recording starts."
        VoiceCaptureUiPhase.PERMISSION_REQUIRED -> "Allow microphone access to start voice dictation."
        VoiceCaptureUiPhase.PERMISSION_DENIED -> if (canOpenPermissionSettings) {
            "Microphone access is blocked in Android settings. Enable it there, then try voice again."
        } else {
            "Microphone access was denied. You can ask Android again when ready."
        }

        VoiceCaptureUiPhase.STARTING -> "Preparing the microphone."
        VoiceCaptureUiPhase.CAPTURING -> "Stop to request a final transcript, or cancel to discard captured audio."
        VoiceCaptureUiPhase.PROCESSING -> "Audio capture has stopped. Waiting for the Mac's final transcription."
        VoiceCaptureUiPhase.CANCELING -> "Discarding captured audio."
        VoiceCaptureUiPhase.CANCELED -> "Captured audio was discarded. Your typed message has not changed."
        VoiceCaptureUiPhase.FAILED -> "Your typed message has not changed."
        VoiceCaptureUiPhase.CLOSED -> "Return to the app and try voice again."
        VoiceCaptureUiPhase.IDLE -> "Voice dictation is ready."
    }
}

private fun VoiceCaptureUiState.accessibilityDescription(hasTranscriptionDraft: Boolean): String = buildString {
    append(title(hasTranscriptionDraft))
    append(". ")
    append(supportingCopy(hasTranscriptionDraft))
    if (queueDepth > 0 || queuedAudioMilliseconds > 0) {
        append(" Audio backlog: ")
        append(queueDepth)
        append(" chunks and ")
        append(queuedAudioMilliseconds)
        append(" milliseconds queued.")
    }
    error?.detail?.let {
        append(" ")
        append(it)
    }
    error?.retryAfterMilliseconds?.let {
        append(" Retry after ")
        append(retryAfterLabel(it))
        append(".")
    }
}

private fun retryAfterLabel(milliseconds: Long): String = when {
    milliseconds < 1_000 -> "$milliseconds ms"
    milliseconds < 60_000 -> {
        val seconds = milliseconds / 1_000
        "$seconds ${if (seconds == 1L) "second" else "seconds"}"
    }

    milliseconds % 60_000 == 0L -> {
        val minutes = milliseconds / 60_000
        "$minutes ${if (minutes == 1L) "minute" else "minutes"}"
    }

    else -> {
        val seconds = milliseconds / 1_000
        "$seconds ${if (seconds == 1L) "second" else "seconds"}"
    }
}

private fun VoicePermissionUiState.toVoiceCaptureUiState(draft: DraftState): VoiceCaptureUiState = VoiceCaptureUiState(
    targetSessionId = draft.sessionId,
    phase = VoiceCaptureUiPhase.PERMISSION_DENIED,
    canOpenPermissionSettings = this is VoicePermissionUiState.PermanentlyDenied,
)

private fun CommandNoticeUiState.message(): String = when (this) {
    CommandNoticeUiState.Sending -> "Sending command…"
    CommandNoticeUiState.AwaitingHost -> "Command sent. Waiting for the Mac to confirm it."
    CommandNoticeUiState.Acknowledged -> "Command accepted by the Mac."
    is CommandNoticeUiState.Failed -> if (retryable) "Command was not confirmed ($code). Draft preserved; retry when ready."
    else "Command failed ($code). Draft preserved."
}

@Composable
private fun TranscriptionDraft(
    text: String,
    finalTranscript: Boolean,
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
                text = if (finalTranscript) "Final transcription draft" else "Voice transcription draft",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = if (finalTranscript) {
                    "Edit this final transcript before inserting it. It will not be sent automatically."
                } else {
                    "This transcript stays separate from your typed message until you insert it."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .semantics {
                        contentDescription = if (finalTranscript) {
                            "Editable final transcription draft"
                        } else {
                            "Editable voice transcription draft"
                        }
                    },
                enabled = enabled,
                minLines = 2,
                maxLines = 5,
                label = { Text(if (finalTranscript) "Final transcription" else "Transcription") },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactTextButton(
                    label = "Insert into message",
                    description = "Insert transcription into typed message draft without sending it",
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
