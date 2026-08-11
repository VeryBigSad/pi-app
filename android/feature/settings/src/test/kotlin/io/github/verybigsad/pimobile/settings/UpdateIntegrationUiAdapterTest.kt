package io.github.verybigsad.pimobile.settings

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.update.UpdateCandidate
import io.github.verybigsad.pimobile.update.UpdateError
import io.github.verybigsad.pimobile.update.UpdateState
import org.junit.Test

class UpdateIntegrationUiAdapterTest {
    private val candidate = UpdateCandidate(
        versionCode = 42L,
        versionName = "1.4.0",
        publishedAt = "2026-08-11T00:00:00Z",
        releasePageUrl = "https://example.com/r",
        apkUrl = "https://example.com/a.apk",
        apkSizeBytes = 25_000_000L,
        apkSha256 = "a1b2c3d4e5f6" + "0".repeat(52),
    )

    private var lastChecked: Long? = null
    private val adapter = UpdateIntegrationUiAdapter(
        currentVersionName = "1.3.0",
        currentVersionCode = 41L,
        lastCheckedEpochMillis = { lastChecked },
    )

    @Test
    fun disabledMapsToDisabledAvailability() {
        val ui = adapter.toUiState(UpdateState.Disabled)
        assertThat(ui.availability).isEqualTo(UpdateAvailability.Disabled)
        assertThat(ui.currentVersionLabel).isEqualTo("1.3.0 (41)")
    }

    @Test
    fun idleWithoutCheckIsUnknown() {
        lastChecked = null
        assertThat(adapter.toUiState(UpdateState.Idle).availability).isEqualTo(UpdateAvailability.Unknown)
    }

    @Test
    fun idleAfterCheckIsUpToDate() {
        lastChecked = 123_000L
        val ui = adapter.toUiState(UpdateState.Idle)
        assertThat(ui.availability).isEqualTo(UpdateAvailability.UpToDate)
        assertThat(ui.lastCheckedEpochMillis).isEqualTo(123_000L)
    }

    @Test
    fun candidateStatesMapToAvailable() {
        lastChecked = 1L
        assertThat(adapter.toUiState(UpdateState.Available(candidate)).availability)
            .isEqualTo(UpdateAvailability.Available("1.4.0"))
        assertThat(adapter.toUiState(UpdateState.Downloading(candidate)).availability)
            .isEqualTo(UpdateAvailability.Available("1.4.0"))
        assertThat(adapter.toUiState(UpdateState.Paused(candidate)).availability)
            .isEqualTo(UpdateAvailability.Available("1.4.0"))
        assertThat(adapter.toUiState(UpdateState.ReadyToInstall(candidate)).availability)
            .isEqualTo(UpdateAvailability.Available("1.4.0"))
        assertThat(adapter.toUiState(UpdateState.InstallPermissionRequired(candidate)).availability)
            .isEqualTo(UpdateAvailability.Available("1.4.0"))
    }

    @Test
    fun failureExposesStableCode() {
        val ui = adapter.toUiState(UpdateState.Failed(UpdateError.SIGNATURE_MISMATCH, "signer mismatch", candidate))
        assertThat(ui.availability).isEqualTo(UpdateAvailability.Failed(UpdateError.SIGNATURE_MISMATCH))
    }

    @Test
    fun shortHashLabelTruncates() {
        assertThat(shortHashLabel("a1b2c3d4e5f60708")).isEqualTo("sha256:a1b2c3d4e5f6")
    }

    @Test
    fun sizeLabelFormatsMegabytes() {
        assertThat(formatSizeLabel(25_000_000L)).isEqualTo("25.0 MB")
        assertThat(formatSizeLabel(512_000L)).isEqualTo("512 KB")
    }
}
