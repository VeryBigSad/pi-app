package io.github.verybigsad.pimobile.voice

private val UUID_V4_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal fun isVoiceSessionId(value: String): Boolean = UUID_V4_PATTERN.matches(value)

object VoiceAudioSpec {
    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_DURATION_MS / 1_000
    const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE
    const val PRE_ROLL_MS = 300
    const val PREFERRED_CHUNK_MS = 8_000
    const val FORCED_CHUNK_MS = 12_000
    const val OVERLAP_MS = 500
    const val SILENCE_BOUNDARY_MS = 200
    const val MAX_CHUNK_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * FORCED_CHUNK_MS / 1_000
    const val MAX_PACKET_BYTES = 64 * 1_024
    const val MAX_BACKLOG_MS = 30_000
}

enum class MicrophonePermissionState {
    GRANTED,
    REQUEST_REQUIRED,
    DENIED,
}

enum class VoiceCapturePhase {
    IDLE,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    STARTING,
    CAPTURING,
    STOPPING,
    COMPLETED,
    CANCELING,
    CANCELED,
    FAILED,
    HOST_ERROR,
    CLOSED,
}

enum class VoiceCancellationReason {
    USER,
    BACKGROUND,
    PERMISSION_REVOKED,
    HOST_ERROR,
    CLOSED,
}

enum class VoiceFrontendErrorCode {
    AUDIO_INITIALIZATION,
    AUDIO_START,
    AUDIO_READ,
    MAC_TRANSPORT,
    MAC_QUEUE_FULL,
    MAC_BACKLOG_LIMIT,
}

enum class VoiceStartResult {
    STARTED,
    ALREADY_ACTIVE,
    NOT_FOREGROUND,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    CLOSED,
    FAILED,
}

enum class MacVoiceErrorCode(val wireCode: String) {
    KEY_UNAVAILABLE("VOICE_KEY_UNAVAILABLE"),
    KEY_PERMISSIONS("VOICE_KEY_PERMISSIONS"),
    QUOTA("VOICE_QUOTA"),
    RETRY_AFTER_LONG("VOICE_RETRY_AFTER_LONG"),
    RATE_LIMITED("VOICE_RATE_LIMITED"),
    NETWORK("VOICE_NETWORK"),
    RESPONSE_INVALID("VOICE_RESPONSE_INVALID"),
    CANCELED("VOICE_CANCELED"),
    QUEUE_FULL("VOICE_QUEUE_FULL"),
    UNKNOWN("VOICE_UNKNOWN"),
}

enum class MacVoiceQuotaWindow(val wireCode: String) {
    REQUESTS_PER_MINUTE("VOICE_RPM_LIMIT"),
    REQUESTS_PER_DAY("VOICE_RPD_LIMIT"),
    AUDIO_SECONDS_PER_HOUR("VOICE_ASH_LIMIT"),
    AUDIO_SECONDS_PER_DAY("VOICE_ASD_LIMIT"),
    UTC_DAY_BUDGET("VOICE_DAILY_BUDGET"),
    UTC_MONTH_BUDGET("VOICE_MONTHLY_BUDGET"),
}

data class MacVoiceError(
    val code: MacVoiceErrorCode,
    val quotaWindow: MacVoiceQuotaWindow? = null,
    val resetAtEpochMilliseconds: Long? = null,
    val retryAfterMilliseconds: Long? = null,
) {
    init {
        require(resetAtEpochMilliseconds == null || resetAtEpochMilliseconds >= 0)
        require(retryAfterMilliseconds == null || retryAfterMilliseconds >= 0)
        require(code == MacVoiceErrorCode.QUOTA || quotaWindow == null)
    }

    companion object {
        fun fromWire(
            code: String,
            detailCode: String? = null,
            resetAtEpochMilliseconds: Long? = null,
            retryAfterMilliseconds: Long? = null,
        ): MacVoiceError {
            val parsedCode = MacVoiceErrorCode.entries.firstOrNull { it.wireCode == code } ?: MacVoiceErrorCode.UNKNOWN
            val parsedWindow = if (parsedCode == MacVoiceErrorCode.QUOTA) {
                MacVoiceQuotaWindow.entries.firstOrNull { it.wireCode == detailCode }
            } else {
                null
            }
            return MacVoiceError(
                code = parsedCode,
                quotaWindow = parsedWindow,
                resetAtEpochMilliseconds = resetAtEpochMilliseconds,
                retryAfterMilliseconds = retryAfterMilliseconds,
            )
        }
    }
}

data class VoiceFrontendState(
    val permission: MicrophonePermissionState,
    val foreground: Boolean,
    val phase: VoiceCapturePhase,
    val sessionId: String? = null,
    val queueDepth: Int = 0,
    val queuedAudioMilliseconds: Int = 0,
    val cancellationReason: VoiceCancellationReason? = null,
    val frontendError: VoiceFrontendErrorCode? = null,
    val hostError: MacVoiceError? = null,
)

data class VoiceSessionDescriptor(
    val sessionId: String,
    val sampleRateHz: Int = VoiceAudioSpec.SAMPLE_RATE_HZ,
    val channelCount: Int = VoiceAudioSpec.CHANNEL_COUNT,
    val bitsPerSample: Int = VoiceAudioSpec.BITS_PER_SAMPLE,
    val frameDurationMilliseconds: Int = VoiceAudioSpec.FRAME_DURATION_MS,
    val maximumChunkBytes: Int = VoiceAudioSpec.MAX_CHUNK_BYTES,
    val maximumPacketBytes: Int = VoiceAudioSpec.MAX_PACKET_BYTES,
) {
    init {
        require(isVoiceSessionId(sessionId))
        require(sampleRateHz == VoiceAudioSpec.SAMPLE_RATE_HZ)
        require(channelCount == VoiceAudioSpec.CHANNEL_COUNT)
        require(bitsPerSample == VoiceAudioSpec.BITS_PER_SAMPLE)
        require(frameDurationMilliseconds == VoiceAudioSpec.FRAME_DURATION_MS)
        require(maximumChunkBytes == VoiceAudioSpec.MAX_CHUNK_BYTES)
        require(maximumPacketBytes == VoiceAudioSpec.MAX_PACKET_BYTES)
    }
}
