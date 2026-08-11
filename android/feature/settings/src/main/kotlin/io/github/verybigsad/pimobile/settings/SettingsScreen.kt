package io.github.verybigsad.pimobile.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val UNAVAILABLE = "Unavailable"

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions = SettingsActions(),
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = System.currentTimeMillis(),
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .testTag("settings-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            item(key = "connection") {
                ConnectionSection(state.connection)
            }
            item(key = "security") {
                SecuritySection(state.security, actions, nowEpochMillis)
            }
            item(key = "notifications") {
                NotificationsSection(state.notifications, actions)
            }
            item(key = "updates") {
                UpdatesSection(state.updates, actions, nowEpochMillis)
            }
            item(key = "about") {
                AboutSection(state.about, actions)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared building blocks
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LabelValueRow(
    label: String,
    value: String?,
    valueIsError: Boolean = false,
) {
    val display = value ?: UNAVAILABLE
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            display,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                valueIsError -> MaterialTheme.colorScheme.error
                value == null -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

@Composable
private fun ConnectionSection(state: ConnectionSettingsUiState) {
    SectionCard(title = "Connection") {
        val statusLabel = when (state.status) {
            ConnectionStatus.UNPAIRED -> "Not paired"
            ConnectionStatus.DISCONNECTED -> "Disconnected"
            ConnectionStatus.CONNECTING -> "Connecting"
            ConnectionStatus.CONNECTED -> "Connected"
            ConnectionStatus.REVOKED -> "Access revoked"
        }
        LabelValueRow(
            label = "Status",
            value = statusLabel,
            valueIsError = state.status == ConnectionStatus.REVOKED,
        )
        when (val identity = state.macIdentity) {
            MacIdentityState.Unavailable -> {
                LabelValueRow(label = "Mac", value = null)
                LabelValueRow(label = "Identity fingerprint", value = null)
            }
            is MacIdentityState.Available -> {
                LabelValueRow(label = "Mac", value = identity.macDisplayName)
                LabelValueRow(label = "Identity fingerprint", value = identity.identityFingerprint)
            }
        }
        when (val route = state.route) {
            ConnectionRouteState.Unavailable -> LabelValueRow(label = "Route", value = null)
            ConnectionRouteState.Direct -> LabelValueRow(label = "Route", value = "Direct (local network)")
            is ConnectionRouteState.Relay -> {
                LabelValueRow(label = "Route", value = "Relay")
                LabelValueRow(label = "Relay URL", value = route.relayUrl)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------

@Composable
private fun SecuritySection(
    state: SecuritySettingsUiState,
    actions: SettingsActions,
    nowEpochMillis: Long,
) {
    var showRevokeConfirm by rememberSaveable { mutableStateOf(false) }

    SectionCard(title = "Security") {
        when (val certificate = state.deviceCertificate) {
            DeviceCertificateState.Unavailable -> {
                LabelValueRow(label = "Device certificate", value = null)
            }
            is DeviceCertificateState.Available -> {
                LabelValueRow(label = "Device certificate", value = "Serial ${certificate.serial}")
                val expiryLabel = formatExpiryLabel(certificate.notAfterEpochMillis, nowEpochMillis)
                LabelValueRow(
                    label = "Certificate expiry",
                    value = expiryLabel,
                    valueIsError = expiryLabel == "Expired",
                )
            }
        }
        when (val passkey = state.passkeySession) {
            PasskeySessionState.Unavailable -> LabelValueRow(label = "Passkey session", value = null)
            is PasskeySessionState.Active -> LabelValueRow(
                label = "Passkey session",
                value = formatExpiryLabel(passkey.expiresAtEpochMillis, nowEpochMillis),
            )
        }
        LabelValueRow(
            label = "Auto-lock",
            value = when (val timeout = state.autoLockTimeout) {
                AutoLockTimeoutState.Unavailable -> null
                AutoLockTimeoutState.Disabled -> "Disabled"
                is AutoLockTimeoutState.Timeout -> "${timeout.minutes} min"
            },
        )
        Button(
            onClick = { showRevokeConfirm = true },
            enabled = state.canRevokeThisDevice,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Revoke this device. Requires confirmation." },
        ) {
            Text("Revoke this device")
        }
    }

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Revoke this device?") },
            text = {
                Text(
                    "This device will immediately lose access to your Mac. " +
                        "You will need to pair again to reconnect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRevokeConfirm = false
                        actions.onRevokeThisDevice()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Composable
private fun NotificationsSection(
    state: NotificationSettingsUiState,
    actions: SettingsActions,
) {
    SectionCard(title = "Notifications") {
        when (val distributor = state.distributor) {
            PushDistributorState.Unavailable ->
                LabelValueRow(label = "Push distributor", value = null)
            PushDistributorState.NoneInstalled ->
                LabelValueRow(
                    label = "Push distributor",
                    value = "None installed",
                    valueIsError = true,
                )
            is PushDistributorState.Available -> {
                LabelValueRow(label = "Push distributor", value = distributor.distributorName)
                LabelValueRow(
                    label = "Distributor status",
                    value = if (distributor.connected) "Connected" else "Not connected",
                    valueIsError = !distributor.connected,
                )
            }
        }
        LabelValueRow(
            label = "Endpoint registration",
            value = when (state.endpointRegistration) {
                EndpointRegistrationState.UNKNOWN -> null
                EndpointRegistrationState.REGISTERED -> "Registered"
                EndpointRegistrationState.NOT_REGISTERED -> "Not registered"
            },
            valueIsError = state.endpointRegistration == EndpointRegistrationState.NOT_REGISTERED,
        )
        when (state.postNotificationsPermission) {
            NotificationPermissionState.NOT_REQUIRED ->
                LabelValueRow(label = "Notification permission", value = "Not required")
            NotificationPermissionState.GRANTED ->
                LabelValueRow(label = "Notification permission", value = "Granted")
            NotificationPermissionState.DENIED -> {
                LabelValueRow(
                    label = "Notification permission",
                    value = "Denied",
                    valueIsError = true,
                )
                TextButton(
                    onClick = actions.onRequestNotificationPermission,
                    modifier = Modifier.semantics {
                        contentDescription = "Request notification permission"
                    },
                ) {
                    Text("Grant permission")
                }
            }
        }
        if (state.channels.isNotEmpty()) {
            Text(
                "Channels",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            state.channels.forEach { channel ->
                NotificationChannelRow(
                    channel = channel,
                    onClick = { actions.onOpenChannelSettings(channel.channelId) },
                )
            }
        }
    }
}

@Composable
private fun NotificationChannelRow(
    channel: NotificationChannelUiState,
    onClick: () -> Unit,
) {
    val stateLabel = when (channel.enabledInSystem) {
        null -> "status unknown"
        true -> "on"
        false -> "off"
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription =
                    "${channel.label} notifications: $stateLabel. Opens system channel settings."
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(channel.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                stateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Updates
// ---------------------------------------------------------------------------

@Composable
private fun UpdatesSection(
    state: UpdateUiState,
    actions: SettingsActions,
    nowEpochMillis: Long,
) {
    SectionCard(title = "Updates") {
        LabelValueRow(label = "Current version", value = state.currentVersionLabel)
        LabelValueRow(
            label = "Last checked",
            value = formatLastCheckedLabel(state.lastCheckedEpochMillis, nowEpochMillis),
        )
        when (val availability = state.availability) {
            UpdateAvailability.Unknown ->
                LabelValueRow(label = "Update status", value = null)
            UpdateAvailability.Disabled ->
                LabelValueRow(label = "Update status", value = "Unavailable in debug builds")
            UpdateAvailability.Checking ->
                LabelValueRow(label = "Update status", value = "Checking…")
            UpdateAvailability.UpToDate ->
                LabelValueRow(label = "Update status", value = "Up to date")
            is UpdateAvailability.Available -> Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Update available: ${availability.versionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(12.dp)
                        .semantics {
                            contentDescription = "Update available: version ${availability.versionName}"
                        },
                )
            }
            is UpdateAvailability.Failed ->
                LabelValueRow(
                    label = "Update status",
                    value = "Check failed: ${availability.reason}",
                    valueIsError = true,
                )
        }
        Button(
            onClick = actions.onCheckForUpdates,
            enabled = state.availability !is UpdateAvailability.Checking &&
                state.availability !is UpdateAvailability.Disabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Check for updates now" },
        ) {
            Text("Check now")
        }
        if (state.availability is UpdateAvailability.Available) {
            TextButton(
                onClick = actions.onOpenUpdateSheet,
                modifier = Modifier.semantics { contentDescription = "Review update details" },
            ) {
                Text("Review update")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

@Composable
private fun AboutSection(
    state: AboutUiState,
    actions: SettingsActions,
) {
    SectionCard(title = "About") {
        LabelValueRow(label = "Pi version", value = state.piVersion)
        LabelValueRow(label = "Host version", value = state.hostVersion)
        LabelValueRow(label = "Protocol version", value = state.protocolVersion)
        TextButton(
            onClick = actions.onOpenLicenses,
            modifier = Modifier.semantics { contentDescription = "Open source licenses" },
        ) {
            Text("Open source licenses")
        }
    }
}
