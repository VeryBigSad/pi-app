package io.github.verybigsad.pimobile.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.verybigsad.pimobile.update.UpdateCandidate
import io.github.verybigsad.pimobile.update.UpdateError
import io.github.verybigsad.pimobile.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateSheetComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val candidate = UpdateCandidate(
        versionCode = 42L,
        versionName = "1.4.0",
        publishedAt = "2026-08-11T00:00:00Z",
        releasePageUrl = "https://example.com/r",
        apkUrl = "https://example.com/a.apk",
        apkSizeBytes = 25_000_000L,
        apkSha256 = "a1b2c3d4e5f6" + "0".repeat(52),
    )

    @Test
    fun disabledStateRendersHonestLabel() {
        // Debuggable builds disable the updater by design (UpdateRuntime.isEnabled).
        composeRule.setContent {
            UpdateSheet(state = UpdateState.Disabled)
        }
        composeRule.onNodeWithText("In-app updates are disabled in debug builds.").assertIsDisplayed()
    }

    @Test
    fun availableShowsCandidateAndConfirmsDownload() {
        var confirmed = 0L
        composeRule.setContent {
            UpdateSheet(
                state = UpdateState.Available(candidate),
                actions = UpdateSheetActions(onConfirmDownload = { confirmed = it }),
            )
        }
        composeRule.onNodeWithContentDescription("Candidate version 1.4.0").assertIsDisplayed()
        composeRule.onNodeWithText("sha256:a1b2c3d4e5f6").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Confirm update download").performClick()
        assertEquals(42L, confirmed)
    }

    @Test
    fun downloadingOffersPauseAndCancel() {
        var paused = false
        var cancelled = false
        composeRule.setContent {
            UpdateSheet(
                state = UpdateState.Downloading(candidate.copy(downloadedBytes = 12_500_000L)),
                actions = UpdateSheetActions(
                    onPauseDownload = { paused = true },
                    onCancelDownload = { cancelled = true },
                ),
            )
        }
        composeRule.onNodeWithText("12.5 MB of 25.0 MB").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pause download").performClick()
        composeRule.onNodeWithContentDescription("Cancel download").performClick()
        assertEquals(true, paused)
        assertEquals(true, cancelled)
    }

    @Test
    fun pausedOffersResume() {
        var resumed = false
        composeRule.setContent {
            UpdateSheet(
                state = UpdateState.Paused(candidate.copy(downloadedBytes = 5_000_000L)),
                actions = UpdateSheetActions(onResumeDownload = { resumed = true }),
            )
        }
        composeRule.onNodeWithContentDescription("Resume download").performClick()
        assertEquals(true, resumed)
    }

    @Test
    fun readyToInstallOffersInstall() {
        var installed = 0L
        composeRule.setContent {
            UpdateSheet(
                state = UpdateState.ReadyToInstall(candidate.copy(verified = true)),
                actions = UpdateSheetActions(onInstall = { installed = it }),
            )
        }
        composeRule.onNodeWithContentDescription("Update verified and ready to install").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Install update").performClick()
        assertEquals(42L, installed)
    }

    @Test
    fun installPermissionGuidesToSystemSettings() {
        var opened = false
        composeRule.setContent {
            UpdateSheet(
                state = UpdateState.InstallPermissionRequired(candidate),
                actions = UpdateSheetActions(onOpenInstallPermissionSettings = { opened = true }),
            )
        }
        composeRule.onNodeWithContentDescription("Open install-permission settings").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun failureShowsStableCode() {
        composeRule.setContent {
            UpdateSheet(state = UpdateState.Failed(UpdateError.SIGNATURE_MISMATCH, "signer differs", candidate))
        }
        composeRule
            .onNodeWithContentDescription("Update failed: ${UpdateError.SIGNATURE_MISMATCH}")
            .assertIsDisplayed()
    }

    @Test
    fun deniedNotificationsShowBanner() {
        composeRule.setContent {
            UpdateSheet(state = UpdateState.Idle, notificationsDenied = true)
        }
        composeRule.onNodeWithText(
            "Notifications are denied; you will not be alerted when an update is ready.",
        ).assertIsDisplayed()
    }
}
