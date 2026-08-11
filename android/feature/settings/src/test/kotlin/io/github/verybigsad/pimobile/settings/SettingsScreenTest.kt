package io.github.verybigsad.pimobile.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsUiStateTest {
    @Test
    fun unavailableStateHasNoDataAndNoActionsRequired() {
        val state = SettingsUiState.Unavailable

        assertThat(state.connection.macIdentity).isEqualTo(MacIdentityState.Unavailable)
        assertThat(state.connection.route).isEqualTo(ConnectionRouteState.Unavailable)
        assertThat(state.security.deviceCertificate).isEqualTo(DeviceCertificateState.Unavailable)
        assertThat(state.security.canRevokeThisDevice).isFalse()
        assertThat(state.notifications.endpointRegistration)
            .isEqualTo(EndpointRegistrationState.UNKNOWN)
        assertThat(state.updates.currentVersionLabel).isNull()
        assertThat(state.updates.availability).isEqualTo(UpdateAvailability.Unknown)
        assertThat(state.about.piVersion).isNull()
    }

    @Test
    fun macIdentityRejectsBlankFields() {
        assertThrows(IllegalArgumentException::class.java) {
            MacIdentityState.Available("", "fp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MacIdentityState.Available("Mac", "")
        }
    }

    @Test
    fun autoLockTimeoutRejectsNonPositiveMinutes() {
        assertThrows(IllegalArgumentException::class.java) {
            AutoLockTimeoutState.Timeout(0)
        }
    }

    @Test
    fun updateUiStateRejectsBlankVersionName() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateUiState(
                currentVersionName = "  ",
                currentVersionCode = 1,
                lastCheckedEpochMillis = null,
                availability = UpdateAvailability.Unknown,
            )
        }
    }

    @Test
    fun versionLabelCombinesNameAndCode() {
        val state = UpdateUiState(
            currentVersionName = "1.4.0",
            currentVersionCode = 42,
            lastCheckedEpochMillis = null,
            availability = UpdateAvailability.UpToDate,
        )
        assertThat(state.currentVersionLabel).isEqualTo("1.4.0 (42)")

        val nameOnly = state.copy(currentVersionCode = null)
        assertThat(nameOnly.currentVersionLabel).isEqualTo("1.4.0")
    }

    @Test
    fun updateAdapterMapsSourceState() {
        data class FakeCoreUpdate(
            val versionName: String,
            val versionCode: Long,
            val lastCheck: Long?,
            val latest: String?,
        )

        val adapter = UpdateUiStateAdapter<FakeCoreUpdate> { source ->
            UpdateUiState(
                currentVersionName = source.versionName,
                currentVersionCode = source.versionCode,
                lastCheckedEpochMillis = source.lastCheck,
                availability = when {
                    source.latest == null -> UpdateAvailability.Unknown
                    source.latest == source.versionName -> UpdateAvailability.UpToDate
                    else -> UpdateAvailability.Available(source.latest)
                },
            )
        }

        val mapped = adapter.toUiState(FakeCoreUpdate("1.0.0", 7, 123L, "1.1.0"))
        assertThat(mapped.availability).isEqualTo(UpdateAvailability.Available("1.1.0"))
        assertThat(mapped.lastCheckedEpochMillis).isEqualTo(123L)
    }
}

class SettingsFormattingTest {
    @Test
    fun expiryLabelHandlesExpiredAndRelativeWindows() {
        val now = 10 * 24 * 60 * 60 * 1000L

        assertThat(formatExpiryLabel(notAfterEpochMillis = now - 1, nowEpochMillis = now))
            .isEqualTo("Expired")
        assertThat(formatExpiryLabel(notAfterEpochMillis = now, nowEpochMillis = now))
            .isEqualTo("Expired")
        assertThat(
            formatExpiryLabel(
                notAfterEpochMillis = now + 5 * 60_000L,
                nowEpochMillis = now,
            ),
        ).isEqualTo("Expires in 5m")
        assertThat(
            formatExpiryLabel(
                notAfterEpochMillis = now + 3 * 3_600_000L + 12 * 60_000L,
                nowEpochMillis = now,
            ),
        ).isEqualTo("Expires in 3h 12m")
        assertThat(
            formatExpiryLabel(
                notAfterEpochMillis = now + 2 * 86_400_000L + 4 * 3_600_000L,
                nowEpochMillis = now,
            ),
        ).isEqualTo("Expires in 2d 4h")
    }

    @Test
    fun lastCheckedLabelHandlesNullAndRelativeWindows() {
        val now = 10 * 24 * 60 * 60 * 1000L

        assertThat(formatLastCheckedLabel(null, now)).isNull()
        assertThat(formatLastCheckedLabel(now, now)).isEqualTo("Checked just now")
        assertThat(formatLastCheckedLabel(now + 60_000L, now)).isEqualTo("Checked just now")
        assertThat(formatLastCheckedLabel(now - 30 * 60_000L, now)).isEqualTo("Checked 30m ago")
        assertThat(formatLastCheckedLabel(now - 5 * 3_600_000L, now)).isEqualTo("Checked 5h ago")
        assertThat(formatLastCheckedLabel(now - 3 * 86_400_000L, now)).isEqualTo("Checked 3d ago")
    }
}
