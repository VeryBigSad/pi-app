package io.github.verybigsad.pimobile.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

private const val NOW = 1_800_000_000_000L

private fun fullState() = SettingsUiState(
    connection = ConnectionSettingsUiState(
        status = ConnectionStatus.CONNECTED,
        macIdentity = MacIdentityState.Available("Workstation", "SHA256:ab:cd"),
        route = ConnectionRouteState.Relay("wss://relay.example.com"),
    ),
    security = SecuritySettingsUiState(
        deviceCertificate = DeviceCertificateState.Available("0A1B", NOW + 86_400_000L),
        passkeySession = PasskeySessionState.Active(NOW + 3_600_000L),
        autoLockTimeout = AutoLockTimeoutState.Timeout(15),
        canRevokeThisDevice = true,
    ),
    notifications = NotificationSettingsUiState(
        distributor = PushDistributorState.Available("ntfy", connected = true),
        endpointRegistration = EndpointRegistrationState.REGISTERED,
        postNotificationsPermission = NotificationPermissionState.GRANTED,
        channels = listOf(
            NotificationChannelUiState("sessions", "Sessions", enabledInSystem = true),
            NotificationChannelUiState("approvals", "Approvals", enabledInSystem = false),
        ),
    ),
    updates = UpdateUiState(
        currentVersionName = "1.4.0",
        currentVersionCode = 42,
        lastCheckedEpochMillis = NOW - 3_600_000L,
        availability = UpdateAvailability.Available("1.5.0"),
    ),
    about = AboutUiState(piVersion = "1.4.0", hostVersion = "0.9.2", protocolVersion = "3"),
)

class SettingsScreenComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun scrollToSection(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNodeWithTag("settings-list").performScrollToNode(matcher)
    }

    @Test
    fun rendersAllSectionsAndConnectionData() {
        compose.setContent { MaterialTheme { SettingsScreen(fullState(), nowEpochMillis = NOW) } }

        listOf("Connection", "Security", "Notifications", "Updates", "About").forEach {
            scrollToSection(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        scrollToSection(hasText("Workstation"))
        compose.onNodeWithText("Workstation").assertIsDisplayed()
        scrollToSection(hasText("SHA256:ab:cd"))
        compose.onNodeWithText("SHA256:ab:cd").assertIsDisplayed()
        scrollToSection(hasText("wss://relay.example.com"))
        compose.onNodeWithText("wss://relay.example.com").assertIsDisplayed()
        scrollToSection(hasText("Connected"))
        assertThat(
            compose.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty(),
        ).isTrue()
    }

    @Test
    fun unavailableStateRendersHonestUnavailableLabels() {
        compose.setContent {
            MaterialTheme { SettingsScreen(SettingsUiState.Unavailable, nowEpochMillis = NOW) }
        }

        val nodes = compose.onAllNodes(hasText("Unavailable")).fetchSemanticsNodes()
        assertThat(nodes.size).isAtLeast(6)
    }

    @Test
    fun revokeRequiresConfirmationBeforeCallback() {
        var revoked = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    fullState(),
                    actions = SettingsActions(onRevokeThisDevice = { revoked = true }),
                    nowEpochMillis = NOW,
                )
            }
        }

        compose.onNodeWithText("Revoke this device").assertIsEnabled().performClick()
        compose.onNodeWithText("Revoke this device?").assertIsDisplayed()
        assertThat(revoked).isFalse()

        compose.onNodeWithText("Cancel").performClick()
        assertThat(revoked).isFalse()

        compose.onNodeWithText("Revoke this device").performClick()
        compose.onNodeWithText("Revoke", substring = false).performClick()
        assertThat(revoked).isTrue()
    }

    @Test
    fun revokeDisabledWhenDeviceNotTrusted() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(SettingsUiState.Unavailable, nowEpochMillis = NOW)
            }
        }
        compose.onNodeWithText("Revoke this device").assertIsNotEnabled()
    }

    @Test
    fun channelRowOpensSystemChannelSettings() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    fullState(),
                    actions = SettingsActions(onOpenChannelSettings = { opened += it }),
                    nowEpochMillis = NOW,
                )
            }
        }

        compose.onNodeWithContentDescription(
            "Approvals notifications: off. Opens system channel settings.",
        ).performScrollTo().performClick()
        assertThat(opened).containsExactly("approvals")
    }

    @Test
    fun checkNowDisabledWhileChecking() {
        val checking = fullState().let {
            it.copy(updates = it.updates.copy(availability = UpdateAvailability.Checking))
        }
        compose.setContent {
            MaterialTheme { SettingsScreen(checking, nowEpochMillis = NOW) }
        }
        scrollToSection(hasText("Check now"))
        compose.onNodeWithText("Check now").assertIsNotEnabled()
    }

    @Test
    fun checkNowInvokesCallback() {
        var checks = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    fullState(),
                    actions = SettingsActions(onCheckForUpdates = { checks++ }),
                    nowEpochMillis = NOW,
                )
            }
        }
        scrollToSection(hasText("Check now"))
        compose.onNodeWithText("Check now").performClick()
        assertThat(checks).isEqualTo(1)
        compose.onNodeWithText("Update available: 1.5.0").assertIsDisplayed()
    }

    @Test
    fun deniedPermissionOffersGrantAction() {
        val denied = fullState().let {
            it.copy(
                notifications = it.notifications.copy(
                    postNotificationsPermission = NotificationPermissionState.DENIED,
                ),
            )
        }
        var requested = false
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    denied,
                    actions = SettingsActions(onRequestNotificationPermission = { requested = true }),
                    nowEpochMillis = NOW,
                )
            }
        }
        compose.onNodeWithText("Denied").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Request notification permission")
            .performScrollTo()
            .performClick()
        assertThat(requested).isTrue()
    }
}
