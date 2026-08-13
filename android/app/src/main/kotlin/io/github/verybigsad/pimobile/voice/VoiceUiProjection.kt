package io.github.verybigsad.pimobile.voice

import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.VoiceCaptureErrorUiState
import io.github.verybigsad.pimobile.session.VoiceCaptureUiPhase
import io.github.verybigsad.pimobile.session.VoiceCaptureUiState

internal fun VoiceFrontendState.toVoiceCaptureUiState(
    targetSessionId: SessionId,
    finalTranscriptReady: Boolean,
): VoiceCaptureUiState = VoiceCaptureUiState(
    targetSessionId = targetSessionId,
    phase = phase.toUiPhase(),
    queueDepth = queueDepth,
    queuedAudioMilliseconds = queuedAudioMilliseconds,
    error = hostError?.toUiError() ?: frontendError?.toUiError(),
    finalTranscriptReady = finalTranscriptReady,
)

private fun VoiceCapturePhase.toUiPhase(): VoiceCaptureUiPhase = when (this) {
    VoiceCapturePhase.IDLE -> VoiceCaptureUiPhase.IDLE
    VoiceCapturePhase.PERMISSION_REQUIRED -> VoiceCaptureUiPhase.PERMISSION_REQUIRED
    VoiceCapturePhase.PERMISSION_DENIED -> VoiceCaptureUiPhase.PERMISSION_DENIED
    VoiceCapturePhase.STARTING -> VoiceCaptureUiPhase.STARTING
    VoiceCapturePhase.CAPTURING -> VoiceCaptureUiPhase.CAPTURING
    VoiceCapturePhase.STOPPING,
    VoiceCapturePhase.COMPLETED,
    -> VoiceCaptureUiPhase.PROCESSING

    VoiceCapturePhase.CANCELING -> VoiceCaptureUiPhase.CANCELING
    VoiceCapturePhase.CANCELED -> VoiceCaptureUiPhase.CANCELED
    VoiceCapturePhase.FAILED,
    VoiceCapturePhase.HOST_ERROR,
    -> VoiceCaptureUiPhase.FAILED

    VoiceCapturePhase.CLOSED -> VoiceCaptureUiPhase.CLOSED
}

private fun VoiceFrontendErrorCode.toUiError(): VoiceCaptureErrorUiState = VoiceCaptureErrorUiState(
    title = "Voice capture failed",
    detail = when (this) {
        VoiceFrontendErrorCode.AUDIO_INITIALIZATION -> "The microphone could not be initialized."
        VoiceFrontendErrorCode.AUDIO_START -> "The microphone could not start."
        VoiceFrontendErrorCode.AUDIO_READ -> "Audio capture stopped unexpectedly."
        VoiceFrontendErrorCode.MAC_TRANSPORT -> "The secure connection to the Mac voice service was interrupted."
        VoiceFrontendErrorCode.MAC_QUEUE_FULL -> "The Mac voice queue is full."
        VoiceFrontendErrorCode.MAC_BACKLOG_LIMIT -> "The queued audio limit was reached."
    },
)

private fun MacVoiceError.toUiError(): VoiceCaptureErrorUiState = VoiceCaptureErrorUiState(
    title = when (code) {
        MacVoiceErrorCode.KEY_UNAVAILABLE -> "Voice transcription is not configured"
        MacVoiceErrorCode.KEY_PERMISSIONS -> "The Mac cannot use voice transcription"
        MacVoiceErrorCode.QUOTA -> "Voice limit reached"
        MacVoiceErrorCode.RETRY_AFTER_LONG -> "Voice transcription is temporarily unavailable"
        MacVoiceErrorCode.RATE_LIMITED -> "Voice request rate reached"
        MacVoiceErrorCode.NETWORK -> "The Mac could not reach voice transcription"
        MacVoiceErrorCode.RESPONSE_INVALID -> "The Mac received an invalid voice response"
        MacVoiceErrorCode.CANCELED -> "Voice dictation was canceled"
        MacVoiceErrorCode.QUEUE_FULL -> "The Mac voice queue is full"
        MacVoiceErrorCode.UNKNOWN -> "Voice transcription failed"
    },
    detail = if (code == MacVoiceErrorCode.QUOTA) quotaWindow?.label() else null,
    retryAfterMilliseconds = retryAfterMilliseconds,
    resetAtEpochMilliseconds = resetAtEpochMilliseconds,
)

private fun MacVoiceQuotaWindow.label(): String = when (this) {
    MacVoiceQuotaWindow.REQUESTS_PER_MINUTE -> "Request limit per minute"
    MacVoiceQuotaWindow.REQUESTS_PER_DAY -> "Request limit per day"
    MacVoiceQuotaWindow.AUDIO_SECONDS_PER_HOUR -> "Audio limit per hour"
    MacVoiceQuotaWindow.AUDIO_SECONDS_PER_DAY -> "Audio limit per day"
    MacVoiceQuotaWindow.UTC_DAY_BUDGET -> "Daily voice budget"
    MacVoiceQuotaWindow.UTC_MONTH_BUDGET -> "Monthly voice budget"
}
