package io.github.verybigsad.pimobile.voice

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class VoiceBoundaryTest {
    @Test
    fun parsesEveryMacQuotaWindowWithoutForwardingHostText() {
        val expected = mapOf(
            "VOICE_RPM_LIMIT" to MacVoiceQuotaWindow.REQUESTS_PER_MINUTE,
            "VOICE_RPD_LIMIT" to MacVoiceQuotaWindow.REQUESTS_PER_DAY,
            "VOICE_ASH_LIMIT" to MacVoiceQuotaWindow.AUDIO_SECONDS_PER_HOUR,
            "VOICE_ASD_LIMIT" to MacVoiceQuotaWindow.AUDIO_SECONDS_PER_DAY,
            "VOICE_DAILY_BUDGET" to MacVoiceQuotaWindow.UTC_DAY_BUDGET,
            "VOICE_MONTHLY_BUDGET" to MacVoiceQuotaWindow.UTC_MONTH_BUDGET,
        )

        for ((wire, window) in expected) {
            assertThat(
                MacVoiceError.fromWire(
                    code = "VOICE_QUOTA",
                    detailCode = wire,
                    resetAtEpochMilliseconds = 123,
                ),
            ).isEqualTo(
                MacVoiceError(
                    code = MacVoiceErrorCode.QUOTA,
                    quotaWindow = window,
                    resetAtEpochMilliseconds = 123,
                ),
            )
        }
    }

    @Test
    fun exposesRetryFailuresAndBoundsUnknownHostCodes() {
        assertThat(
            MacVoiceError.fromWire(
                code = "VOICE_RETRY_AFTER_LONG",
                resetAtEpochMilliseconds = 500,
                retryAfterMilliseconds = 121_000,
            ),
        ).isEqualTo(
            MacVoiceError(
                code = MacVoiceErrorCode.RETRY_AFTER_LONG,
                resetAtEpochMilliseconds = 500,
                retryAfterMilliseconds = 121_000,
            ),
        )
        assertThat(MacVoiceError.fromWire("VOICE_RATE_LIMITED").code).isEqualTo(MacVoiceErrorCode.RATE_LIMITED)
        assertThat(MacVoiceError.fromWire("host secret: abc").code).isEqualTo(MacVoiceErrorCode.UNKNOWN)
    }

    @Test
    fun productionSourceHasNoProviderClientKeyOrLoggingSurface() {
        val sourceRoot = File(System.getProperty("user.dir"), "src/main")
        val source = sourceRoot.walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
            .lowercase()

        assertThat(source).doesNotContain("groq")
        assertThat(source).doesNotContain("authorization")
        assertThat(source).doesNotContain("okhttp")
        assertThat(source).doesNotContain("java.net")
        assertThat(source).doesNotContain("android.util.log")
        assertThat(source).doesNotContain("log.")
    }
}
