package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.VoiceCaptureUiPhase
import org.junit.Test

class VoiceUiProjectionTest {
    @Test
    fun mapsCaptureQueueMetricsWithoutChangingTheTargetSession() {
        val projected = VoiceFrontendState(
            permission = MicrophonePermissionState.GRANTED,
            foreground = true,
            phase = VoiceCapturePhase.CAPTURING,
            sessionId = "b05f7eac-38cf-4da3-a3cf-7a49c3b53077",
            queueDepth = 3,
            queuedAudioMilliseconds = 1_240,
        ).toVoiceCaptureUiState(SessionId("session-voice"), finalTranscriptReady = false)

        assertThat(projected.targetSessionId).isEqualTo(SessionId("session-voice"))
        assertThat(projected.phase).isEqualTo(VoiceCaptureUiPhase.CAPTURING)
        assertThat(projected.queueDepth).isEqualTo(3)
        assertThat(projected.queuedAudioMilliseconds).isEqualTo(1_240)
    }

    @Test
    fun mapsCompletedAudioToProcessingUntilTheFinalTranscriptIsHandled() {
        val projected = VoiceFrontendState(
            permission = MicrophonePermissionState.GRANTED,
            foreground = true,
            phase = VoiceCapturePhase.COMPLETED,
            sessionId = "b05f7eac-38cf-4da3-a3cf-7a49c3b53077",
        ).toVoiceCaptureUiState(SessionId("session-voice"), finalTranscriptReady = true)

        assertThat(projected.phase).isEqualTo(VoiceCaptureUiPhase.PROCESSING)
        assertThat(projected.finalTranscriptReady).isTrue()
    }

    @Test
    fun mapsHostBudgetTelemetryWithoutCreatingAUsageBalance() {
        val projected = VoiceFrontendState(
            permission = MicrophonePermissionState.GRANTED,
            foreground = true,
            phase = VoiceCapturePhase.HOST_ERROR,
            sessionId = "b05f7eac-38cf-4da3-a3cf-7a49c3b53077",
            hostError = MacVoiceError(
                code = MacVoiceErrorCode.QUOTA,
                quotaWindow = MacVoiceQuotaWindow.UTC_DAY_BUDGET,
                retryAfterMilliseconds = 60_000,
            ),
        ).toVoiceCaptureUiState(SessionId("session-voice"), finalTranscriptReady = false)

        assertThat(projected.phase).isEqualTo(VoiceCaptureUiPhase.FAILED)
        assertThat(projected.error?.title).isEqualTo("Voice limit reached")
        assertThat(projected.error?.detail).isEqualTo("Daily voice budget")
        assertThat(projected.error?.retryAfterMilliseconds).isEqualTo(60_000)
        assertThat(projected.error?.resetAtEpochMilliseconds).isNull()
    }
}
