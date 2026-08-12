package io.github.verybigsad.pimobile.settingswiring

import android.app.NotificationManager
import android.content.Context
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.notifications.NotificationPermissionStatus
import io.github.verybigsad.pimobile.push.PushNotificationChannels
import io.github.verybigsad.pimobile.push.ProviderUnavailableReason
import io.github.verybigsad.pimobile.push.UnifiedPushProvider
import io.github.verybigsad.pimobile.push.UnifiedPushProviderState
import io.github.verybigsad.pimobile.push.UnifiedPushRegistrationState
import io.github.verybigsad.pimobile.push.UnifiedPushState
import io.github.verybigsad.pimobile.settings.AboutUiState
import io.github.verybigsad.pimobile.settings.AutoLockTimeoutState
import io.github.verybigsad.pimobile.settings.ConnectionRouteState
import io.github.verybigsad.pimobile.settings.ConnectionSettingsUiState
import io.github.verybigsad.pimobile.settings.ConnectionStatus
import io.github.verybigsad.pimobile.settings.DeviceCertificateState
import io.github.verybigsad.pimobile.settings.EndpointRegistrationState
import io.github.verybigsad.pimobile.settings.MacIdentityState
import io.github.verybigsad.pimobile.settings.NotificationChannelUiState
import io.github.verybigsad.pimobile.settings.NotificationPermissionState
import io.github.verybigsad.pimobile.settings.NotificationSettingsUiState
import io.github.verybigsad.pimobile.settings.PasskeySessionState
import io.github.verybigsad.pimobile.settings.PushDistributorOption
import io.github.verybigsad.pimobile.settings.PushDistributorState
import io.github.verybigsad.pimobile.settings.SecuritySettingsUiState
import io.github.verybigsad.pimobile.settings.SettingsUiState
import io.github.verybigsad.pimobile.settings.UpdateUiStateAdapter
import io.github.verybigsad.pimobile.state.PiAppState
import io.github.verybigsad.pimobile.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Pure mappers from app/coordinator state into typed settings UI state. */
object SettingsMappers {
    fun connection(
        trust: TrustState,
        connection: ConnectionState,
        relayUrl: String?,
    ): ConnectionSettingsUiState {
        val status = when {
            trust is TrustState.Unpaired -> ConnectionStatus.UNPAIRED
            trust is TrustState.Revoked || connection is ConnectionState.Revoked -> ConnectionStatus.REVOKED
            connection is ConnectionState.Ready -> ConnectionStatus.CONNECTED
            connection is ConnectionState.DeviceAuthenticated -> ConnectionStatus.CONNECTED
            connection is ConnectionState.Connecting -> ConnectionStatus.CONNECTING
            connection is ConnectionState.PairingProvisional -> ConnectionStatus.CONNECTING
            else -> ConnectionStatus.DISCONNECTED
        }
        val identity = when (trust) {
            is TrustState.Trusted -> MacIdentityState.Available(
                macDisplayName = trust.macDisplayName,
                identityFingerprint = trust.certificateSerial,
            )
            else -> MacIdentityState.Unavailable
        }
        val route = when (connection) {
            is ConnectionState.Ready -> routeFor(connection.path, relayUrl)
            is ConnectionState.DeviceAuthenticated -> routeFor(connection.path, relayUrl)
            is ConnectionState.Connecting -> routeFor(connection.path, relayUrl)
            else -> ConnectionRouteState.Unavailable
        }
        return ConnectionSettingsUiState(status = status, macIdentity = identity, route = route)
    }

    private fun routeFor(path: TransportPath, relayUrl: String?): ConnectionRouteState = when (path) {
        TransportPath.DIRECT -> ConnectionRouteState.Direct
        TransportPath.RELAY ->
            relayUrl?.takeIf(String::isNotBlank)?.let { ConnectionRouteState.Relay(it) }
                ?: ConnectionRouteState.Unavailable
    }

    fun security(
        trust: TrustState,
        authentication: PasskeyAuthentication?,
        autoLockMinutes: Int?,
    ): SecuritySettingsUiState = SecuritySettingsUiState(
        deviceCertificate = when (trust) {
            is TrustState.Trusted -> DeviceCertificateState.Available(
                serial = trust.certificateSerial,
                notAfterEpochMillis = trust.certificateNotAfterEpochMillis,
            )
            else -> DeviceCertificateState.Unavailable
        },
        passkeySession = authentication?.let { PasskeySessionState.Active(it.expiresAtEpochMillis) }
            ?: PasskeySessionState.Unavailable,
        autoLockTimeout = autoLockMinutes?.let { AutoLockTimeoutState.Timeout(it) }
            ?: AutoLockTimeoutState.Unavailable,
        canRevokeThisDevice = trust is TrustState.Trusted,
    )

    fun notifications(
        push: UnifiedPushState,
        permission: NotificationPermissionStatus,
        channels: List<NotificationChannelUiState>,
        providers: List<UnifiedPushProvider> = emptyList(),
    ): NotificationSettingsUiState {
        val options = providers.map { PushDistributorOption(it.packageName, it.displayName) }
        return NotificationSettingsUiState(
            distributor = when (val provider = push.provider) {
                UnifiedPushProviderState.NotChecked -> PushDistributorState.Unavailable
                is UnifiedPushProviderState.ProviderUnavailable -> when (provider.reason) {
                    ProviderUnavailableReason.NO_DISTRIBUTOR -> PushDistributorState.NoneInstalled
                    ProviderUnavailableReason.CONNECTOR_ERROR -> PushDistributorState.Unavailable
                }
                is UnifiedPushProviderState.ProviderSelectionRequired -> {
                    options.takeIf { it.isNotEmpty() }
                        ?.let(PushDistributorState::SelectionRequired)
                        ?: PushDistributorState.Unavailable
                }
                is UnifiedPushProviderState.ProviderSelected -> PushDistributorState.Available(
                    distributorName = providers.firstOrNull { it.packageName == provider.packageName }
                        ?.displayName ?: provider.packageName,
                    connected = push.registration is UnifiedPushRegistrationState.EndpointAvailable,
                    alternatives = options,
                )
            },
            endpointRegistration = when (push.registration) {
                is UnifiedPushRegistrationState.EndpointAvailable -> EndpointRegistrationState.REGISTERED
                UnifiedPushRegistrationState.RegistrationRequested -> {
                    EndpointRegistrationState.REGISTRATION_REQUESTED
                }
                is UnifiedPushRegistrationState.RegistrationFailed,
                is UnifiedPushRegistrationState.EndpointRejected,
                UnifiedPushRegistrationState.EndpointUnregistrationRejected -> EndpointRegistrationState.FAILED
                UnifiedPushRegistrationState.NotConfigured -> EndpointRegistrationState.UNKNOWN
                else -> EndpointRegistrationState.NOT_REGISTERED
            },
            postNotificationsPermission = when (permission) {
                NotificationPermissionStatus.GRANTED -> NotificationPermissionState.GRANTED
                NotificationPermissionStatus.DENIED -> NotificationPermissionState.DENIED
                NotificationPermissionStatus.NOT_REQUIRED -> NotificationPermissionState.NOT_REQUIRED
            },
            channels = channels,
        )
    }

    fun about(piVersion: String?): AboutUiState = AboutUiState(
        piVersion = piVersion,
        hostVersion = null,
        protocolVersion = null,
    )
}

/**
 * Combines coordinator, push, update, and notification-permission flows into the typed
 * [SettingsUiState] consumed by the settings destination. Channel system-state is read on
 * each emission (the destination re-collects on resume).
 */
class SettingsProjection(
    private val context: Context,
    appState: StateFlow<PiAppState>,
    pushState: StateFlow<UnifiedPushState>,
    updateState: StateFlow<UpdateState>,
    permissionState: StateFlow<NotificationPermissionStatus>,
    updateAdapter: UpdateUiStateAdapter<UpdateState>,
    autoLockMinutes: Int?,
    relayUrl: () -> String?,
    piVersion: String?,
    providers: () -> List<UnifiedPushProvider>,
    scope: CoroutineScope,
) {
    val state: StateFlow<SettingsUiState> = combine(
        appState,
        pushState,
        updateState,
        permissionState,
    ) { app, push, update, permission ->
        SettingsUiState(
            connection = SettingsMappers.connection(app.trust, app.connection, relayUrl()),
            security = SettingsMappers.security(app.trust, app.authentication, autoLockMinutes),
            notifications = SettingsMappers.notifications(
                push = push,
                permission = permission,
                channels = channelStates(),
                providers = providers(),
            ),
            updates = updateAdapter.toUiState(update),
            about = SettingsMappers.about(piVersion),
        )
    }.stateIn(scope, SharingStarted.Eagerly, SettingsUiState.Unavailable)

    private fun channelStates(): List<NotificationChannelUiState> {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return listOf(
            PushNotificationChannels.NEEDS_YOU to "Needs you",
            PushNotificationChannels.FINISHED to "Finished",
            PushNotificationChannels.SYNC_PROBLEMS to "Sync problems",
        ).map { (id, label) ->
            val channel = manager.getNotificationChannel(id)
            NotificationChannelUiState(
                channelId = id,
                label = label,
                enabledInSystem = channel?.let { it.importance != NotificationManager.IMPORTANCE_NONE },
            )
        }
    }
}
