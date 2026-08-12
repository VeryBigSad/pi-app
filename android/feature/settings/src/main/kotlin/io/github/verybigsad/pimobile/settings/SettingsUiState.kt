package io.github.verybigsad.pimobile.settings

import androidx.compose.runtime.Immutable

// ---------------------------------------------------------------------------
// Connection
// ---------------------------------------------------------------------------

@Immutable
sealed interface MacIdentityState {
    /** Identity not available (unpaired or trust material not loaded yet). */
    data object Unavailable : MacIdentityState

    data class Available(
        val macDisplayName: String,
        val identityFingerprint: String,
    ) : MacIdentityState {
        init {
            require(macDisplayName.isNotBlank())
            require(identityFingerprint.isNotBlank())
        }
    }
}

enum class ConnectionStatus {
    UNPAIRED,
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REVOKED,
}

@Immutable
sealed interface ConnectionRouteState {
    /** Route not established or not known yet. */
    data object Unavailable : ConnectionRouteState

    /** Direct LAN/TLS route; no relay URL involved. */
    data object Direct : ConnectionRouteState

    data class Relay(val relayUrl: String) : ConnectionRouteState {
        init {
            require(relayUrl.isNotBlank())
        }
    }
}

@Immutable
data class ConnectionSettingsUiState(
    val status: ConnectionStatus,
    val macIdentity: MacIdentityState,
    val route: ConnectionRouteState,
)

// ---------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------

@Immutable
sealed interface DeviceCertificateState {
    data object Unavailable : DeviceCertificateState

    data class Available(
        val serial: String,
        val notAfterEpochMillis: Long,
    ) : DeviceCertificateState {
        init {
            require(serial.isNotBlank())
            require(notAfterEpochMillis >= 0)
        }
    }
}

@Immutable
sealed interface PasskeySessionState {
    data object Unavailable : PasskeySessionState

    data class Active(val expiresAtEpochMillis: Long) : PasskeySessionState {
        init {
            require(expiresAtEpochMillis >= 0)
        }
    }
}

@Immutable
sealed interface AutoLockTimeoutState {
    data object Unavailable : AutoLockTimeoutState

    data object Disabled : AutoLockTimeoutState

    data class Timeout(val minutes: Int) : AutoLockTimeoutState {
        init {
            require(minutes > 0)
        }
    }
}

@Immutable
data class SecuritySettingsUiState(
    val deviceCertificate: DeviceCertificateState,
    val passkeySession: PasskeySessionState,
    val autoLockTimeout: AutoLockTimeoutState,
    /** True only when this device is currently trusted and can be revoked. */
    val canRevokeThisDevice: Boolean,
)

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Immutable
data class PushDistributorOption(
    val packageName: String,
    val label: String,
) {
    init {
        require(packageName.isNotBlank())
        require(label.isNotBlank())
    }
}

@Immutable
sealed interface PushDistributorState {
    data object Unavailable : PushDistributorState

    /** No compatible UnifiedPush distributor is installed. */
    data object NoneInstalled : PushDistributorState

    /** Installed distributors exist but the user must choose one explicitly. */
    data class SelectionRequired(val options: List<PushDistributorOption>) : PushDistributorState {
        init {
            require(options.isNotEmpty())
            require(options.map(PushDistributorOption::packageName).distinct().size == options.size)
        }
    }

    data class Available(
        val distributorName: String,
        val connected: Boolean,
        val alternatives: List<PushDistributorOption> = emptyList(),
    ) : PushDistributorState {
        init {
            require(distributorName.isNotBlank())
        }
    }
}

enum class EndpointRegistrationState {
    UNKNOWN,
    REGISTRATION_REQUESTED,
    REGISTERED,
    NOT_REGISTERED,
    FAILED,
}

enum class NotificationPermissionState {
    GRANTED,
    DENIED,

    /** Runtime permission not needed (API < 33). */
    NOT_REQUIRED,
}

/**
 * A notification channel surfaced in settings. Toggling is delegated to the
 * system channel settings screen (the app fires an intent); the row only
 * displays the last known system state.
 */
@Immutable
data class NotificationChannelUiState(
    val channelId: String,
    val label: String,
    /** Last known enabled state in system settings; null = unknown. */
    val enabledInSystem: Boolean?,
) {
    init {
        require(channelId.isNotBlank())
        require(label.isNotBlank())
    }
}

@Immutable
data class NotificationSettingsUiState(
    val distributor: PushDistributorState,
    val endpointRegistration: EndpointRegistrationState,
    val postNotificationsPermission: NotificationPermissionState,
    val channels: List<NotificationChannelUiState>,
)

// ---------------------------------------------------------------------------
// Updates
// ---------------------------------------------------------------------------

@Immutable
sealed interface UpdateAvailability {
    /** Never checked or no update feed wired yet. */
    data object Unknown : UpdateAvailability

    /** Assisted updater disabled (debuggable build or user policy). */
    data object Disabled : UpdateAvailability

    data object Checking : UpdateAvailability

    data object UpToDate : UpdateAvailability

    data class Available(val versionName: String) : UpdateAvailability {
        init {
            require(versionName.isNotBlank())
        }
    }

    data class Failed(val reason: String) : UpdateAvailability {
        init {
            require(reason.isNotBlank())
        }
    }
}

@Immutable
data class UpdateUiState(
    val currentVersionName: String?,
    val currentVersionCode: Long?,
    val lastCheckedEpochMillis: Long?,
    val availability: UpdateAvailability,
) {
    init {
        require(currentVersionName == null || currentVersionName.isNotBlank())
        require(currentVersionCode == null || currentVersionCode >= 0)
        require(lastCheckedEpochMillis == null || lastCheckedEpochMillis >= 0)
    }

    /** e.g. "1.4.0 (42)"; null when version data absent. */
    val currentVersionLabel: String?
        get() = when {
            currentVersionName != null && currentVersionCode != null ->
                "$currentVersionName ($currentVersionCode)"
            currentVersionName != null -> currentVersionName
            else -> null
        }
}

/** Maps a future core/update state into [UpdateUiState]. */
fun interface UpdateUiStateAdapter<in S> {
    fun toUiState(source: S): UpdateUiState
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

@Immutable
data class AboutUiState(
    val piVersion: String?,
    val hostVersion: String?,
    val protocolVersion: String?,
) {
    init {
        require(piVersion == null || piVersion.isNotBlank())
        require(hostVersion == null || hostVersion.isNotBlank())
        require(protocolVersion == null || protocolVersion.isNotBlank())
    }
}

// ---------------------------------------------------------------------------
// Aggregate
// ---------------------------------------------------------------------------

@Immutable
data class SettingsUiState(
    val connection: ConnectionSettingsUiState,
    val security: SecuritySettingsUiState,
    val notifications: NotificationSettingsUiState,
    val updates: UpdateUiState,
    val about: AboutUiState,
) {
    companion object {
        /** Everything unavailable; used before any data source reports. */
        val Unavailable = SettingsUiState(
            connection = ConnectionSettingsUiState(
                status = ConnectionStatus.UNPAIRED,
                macIdentity = MacIdentityState.Unavailable,
                route = ConnectionRouteState.Unavailable,
            ),
            security = SecuritySettingsUiState(
                deviceCertificate = DeviceCertificateState.Unavailable,
                passkeySession = PasskeySessionState.Unavailable,
                autoLockTimeout = AutoLockTimeoutState.Unavailable,
                canRevokeThisDevice = false,
            ),
            notifications = NotificationSettingsUiState(
                distributor = PushDistributorState.Unavailable,
                endpointRegistration = EndpointRegistrationState.UNKNOWN,
                postNotificationsPermission = NotificationPermissionState.NOT_REQUIRED,
                channels = emptyList(),
            ),
            updates = UpdateUiState(
                currentVersionName = null,
                currentVersionCode = null,
                lastCheckedEpochMillis = null,
                availability = UpdateAvailability.Unknown,
            ),
            about = AboutUiState(
                piVersion = null,
                hostVersion = null,
                protocolVersion = null,
            ),
        )
    }
}

/**
 * Intents/actions the host app performs on behalf of the settings screen.
 * Defaults are no-ops so previews and tests can omit them.
 */
@Immutable
data class SettingsActions(
    val onRevokeThisDevice: () -> Unit = {},
    val onOpenChannelSettings: (channelId: String) -> Unit = {},
    val onOpenAppNotificationSettings: () -> Unit = {},
    val onRequestNotificationPermission: () -> Unit = {},
    val onSelectPushDistributor: (packageName: String) -> Unit = {},
    val onRequestPushRegistration: () -> Unit = {},
    val onUnregisterPush: () -> Unit = {},
    val onCheckForUpdates: () -> Unit = {},
    val onOpenUpdateSheet: () -> Unit = {},
    val onOpenLicenses: () -> Unit = {},
)

// ---------------------------------------------------------------------------
// Formatting helpers (pure, unit-tested)
// ---------------------------------------------------------------------------

private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS

/** Relative expiry label: "Expired", "Expires in 5m", "Expires in 3h 12m", "Expires in 2d 4h". */
fun formatExpiryLabel(notAfterEpochMillis: Long, nowEpochMillis: Long): String {
    val remaining = notAfterEpochMillis - nowEpochMillis
    if (remaining <= 0) return "Expired"
    val days = remaining / DAY_MILLIS
    val hours = (remaining % DAY_MILLIS) / HOUR_MILLIS
    val minutes = (remaining % HOUR_MILLIS) / MINUTE_MILLIS
    val relative = when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
    return "Expires in $relative"
}

/** Relative last-checked label; null when never checked. */
fun formatLastCheckedLabel(lastCheckedEpochMillis: Long?, nowEpochMillis: Long): String? {
    if (lastCheckedEpochMillis == null) return null
    val elapsed = nowEpochMillis - lastCheckedEpochMillis
    if (elapsed < 0) return "Checked just now"
    val minutes = elapsed / MINUTE_MILLIS
    val hours = elapsed / HOUR_MILLIS
    val days = elapsed / DAY_MILLIS
    return when {
        minutes < 1 -> "Checked just now"
        hours < 1 -> "Checked ${minutes}m ago"
        days < 1 -> "Checked ${hours}h ago"
        else -> "Checked ${days}d ago"
    }
}
