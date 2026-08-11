package io.github.verybigsad.pimobile.session.composer

import androidx.compose.ui.graphics.ImageBitmap
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.session.isAgentRunning

internal const val MAX_COMPOSER_ATTACHMENTS = 4
internal const val MAX_COMPOSER_ATTACHMENT_BYTES = 10 * 1024 * 1024

internal data class MessageComposerState(
    val text: String,
    val runState: SessionRunState,
    val enabled: Boolean,
    val restoredFromCache: Boolean = false,
    val voiceState: VoiceState = VoiceState.Idle,
    val queuedSteering: List<QueuedSteeringItem> = emptyList(),
    val attachments: List<ComposerAttachment> = emptyList(),
    val slashCommands: List<SlashCommand> = emptyList(),
    val error: ComposerError? = null,
) {
    init {
        require(attachments.size <= MAX_COMPOSER_ATTACHMENTS)
        require(attachments.all { it.byteCount in 1..MAX_COMPOSER_ATTACHMENT_BYTES })
        require(queuedSteering.map { it.id }.distinct().size == queuedSteering.size)
        require(attachments.map { it.id }.distinct().size == attachments.size)
    }

    val isStreaming: Boolean get() = runState.isAgentRunning()
    val canSend: Boolean get() = enabled && text.isNotBlank()
}

internal data class QueuedSteeringItem(
    val id: String,
    val text: String,
)

internal data class ComposerAttachment(
    val id: String,
    val name: String,
    val byteCount: Int,
    val thumbnail: ImageBitmap? = null,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(byteCount in 1..MAX_COMPOSER_ATTACHMENT_BYTES)
    }
}

internal data class SlashCommand(
    val name: String,
    val description: String,
) {
    init {
        require(name.startsWith('/'))
    }
}

internal sealed interface VoiceState {
    data object Idle : VoiceState
    data object Recording : VoiceState
    data object Processing : VoiceState
    data class Partial(val transcript: String) : VoiceState
    data class Failed(val message: String) : VoiceState
}

internal enum class ComposerError(val message: String) {
    QUEUE_FULL("Steering queue is full"),
    VOICE_UNAVAILABLE("Voice dictation is unavailable"),
}

/** Intents are reduced by the session owner; the composer never sends transport messages itself. */
internal sealed interface MessageComposerIntent {
    data class TextChanged(val text: String) : MessageComposerIntent
    data object Send : MessageComposerIntent
    data object Stop : MessageComposerIntent
    data object Steer : MessageComposerIntent
    data object QueueSteering : MessageComposerIntent
    data object PickAttachments : MessageComposerIntent
    data class RemoveAttachment(val id: String) : MessageComposerIntent
    data object StartVoice : MessageComposerIntent
    data object StopVoice : MessageComposerIntent
    data class PartialTranscript(val text: String) : MessageComposerIntent
    data class FinalTranscript(val text: String) : MessageComposerIntent
    data class SelectSlashCommand(val command: SlashCommand) : MessageComposerIntent
    data object ErrorShown : MessageComposerIntent
}

internal fun validateAttachments(attachments: List<ComposerAttachment>): Boolean =
    attachments.size <= MAX_COMPOSER_ATTACHMENTS &&
        attachments.map(ComposerAttachment::id).distinct().size == attachments.size &&
        attachments.all { it.byteCount in 1..MAX_COMPOSER_ATTACHMENT_BYTES }
