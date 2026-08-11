package io.github.verybigsad.pimobile.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

private val previewState = SettingsUiState(
    connection = ConnectionSettingsUiState(
        status = ConnectionStatus.CONNECTED,
        macIdentity = MacIdentityState.Available(
            macDisplayName = "Mikhail's MacBook Pro",
            identityFingerprint = "SHA256:9f:2c:aa:10:be:77",
        ),
        route = ConnectionRouteState.Relay("wss://relay.pi.example.com"),
    ),
    security = SecuritySettingsUiState(
        deviceCertificate = DeviceCertificateState.Available(
            serial = "0A1B2C3D",
            notAfterEpochMillis = 1_800_000_000_000,
        ),
        passkeySession = PasskeySessionState.Active(expiresAtEpochMillis = 1_800_000_000_000),
        autoLockTimeout = AutoLockTimeoutState.Timeout(15),
        canRevokeThisDevice = true,
    ),
    notifications = NotificationSettingsUiState(
        distributor = PushDistributorState.Available("ntfy", connected = true),
        endpointRegistration = EndpointRegistrationState.REGISTERED,
        postNotificationsPermission = NotificationPermissionState.GRANTED,
        channels = listOf(
            NotificationChannelUiState("sessions", "Sessions", enabledInSystem = true),
            NotificationChannelUiState("approvals", "Approvals", enabledInSystem = null),
        ),
    ),
    updates = UpdateUiState(
        currentVersionName = "1.4.0",
        currentVersionCode = 42,
        lastCheckedEpochMillis = 1_700_000_000_000,
        availability = UpdateAvailability.UpToDate,
    ),
    about = AboutUiState(
        piVersion = "1.4.0",
        hostVersion = "0.9.2",
        protocolVersion = "3",
    ),
)

@Preview(name = "Settings light, phone", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenLightPreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        SettingsScreen(state = previewState)
    }
}

@Preview(name = "Settings dark, phone", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SettingsScreen(state = previewState)
    }
}

@Preview(name = "Settings dark, tablet", showBackground = true, widthDp = 1280)
@Composable
private fun SettingsScreenTabletPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        SettingsScreen(state = previewState)
    }
}

@Preview(name = "Settings unavailable", showBackground = true, widthDp = 360)
@Composable
private fun SettingsScreenUnavailablePreview() {
    MaterialTheme(colorScheme = lightColorScheme()) {
        SettingsScreen(state = SettingsUiState.Unavailable)
    }
}
