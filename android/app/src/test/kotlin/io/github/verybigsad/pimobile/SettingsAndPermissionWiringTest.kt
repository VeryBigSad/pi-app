package io.github.verybigsad.pimobile

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.notifications.NotificationPermissionPolicy
import io.github.verybigsad.pimobile.notifications.NotificationPermissionStatus
import io.github.verybigsad.pimobile.push.UnifiedPushProviderState
import io.github.verybigsad.pimobile.push.UnifiedPushRegistrationState
import io.github.verybigsad.pimobile.push.UnifiedPushState
import io.github.verybigsad.pimobile.settings.ConnectionRouteState
import io.github.verybigsad.pimobile.settings.ConnectionStatus
import io.github.verybigsad.pimobile.settings.DeviceCertificateState
import io.github.verybigsad.pimobile.settings.EndpointRegistrationState
import io.github.verybigsad.pimobile.settings.MacIdentityState
import io.github.verybigsad.pimobile.settings.NotificationPermissionState
import io.github.verybigsad.pimobile.settings.PasskeySessionState
import io.github.verybigsad.pimobile.settings.PushDistributorState
import io.github.verybigsad.pimobile.settingswiring.SettingsMappers
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test
    fun statusBelowApi33IsNotRequired() {
        assertThat(NotificationPermissionPolicy.statusFor(32, granted = false))
            .isEqualTo(NotificationPermissionStatus.NOT_REQUIRED)
    }

    @Test
    fun statusOnApi33ReflectsGrant() {
        assertThat(NotificationPermissionPolicy.statusFor(33, granted = true))
            .isEqualTo(NotificationPermissionStatus.GRANTED)
        assertThat(NotificationPermissionPolicy.statusFor(34, granted = false))
            .isEqualTo(NotificationPermissionStatus.DENIED)
    }

    @Test
    fun requestsOnceOnlyWhenUpdatesEnabled() {
        // First update-check enable on 33+ without grant requests.
        assertThat(
            NotificationPermissionPolicy.shouldRequestOnUpdateEnable(33, false, false, updatesEnabled = true),
        ).isTrue()
        // Never re-requests after the first attempt (denied -> in-app banner only).
        assertThat(
            NotificationPermissionPolicy.shouldRequestOnUpdateEnable(33, false, true, updatesEnabled = true),
        ).isFalse()
        // Debuggable builds keep the updater disabled and never request.
        assertThat(
            NotificationPermissionPolicy.shouldRequestOnUpdateEnable(33, false, false, updatesEnabled = false),
        ).isFalse()
        // Below 33 there is nothing to request.
        assertThat(
            NotificationPermissionPolicy.shouldRequestOnUpdateEnable(29, false, false, updatesEnabled = true),
        ).isFalse()
        // Already granted: no-op.
        assertThat(
            NotificationPermissionPolicy.shouldRequestOnUpdateEnable(33, true, false, updatesEnabled = true),
        ).isFalse()
    }
}

class SettingsMappersTest {
    private val trusted = TrustState.Trusted(
        macId = MacId("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
        macDisplayName = "Test Mac",
        certificateSerial = "aa".repeat(32),
        certificateNotAfterEpochMillis = 10_000_000L,
    )

    @Test
    fun unpairedConnectionIsHonest() {
        val state = SettingsMappers.connection(
            TrustState.Unpaired,
            ConnectionState.Disconnected(DisconnectReason.NEVER_CONNECTED),
            relayUrl = null,
        )
        assertThat(state.status).isEqualTo(ConnectionStatus.UNPAIRED)
        assertThat(state.macIdentity).isEqualTo(MacIdentityState.Unavailable)
        assertThat(state.route).isEqualTo(ConnectionRouteState.Unavailable)
    }

    @Test
    fun relayRouteSurfacesUrl() {
        val state = SettingsMappers.connection(
            trusted,
            ConnectionState.Connecting(TransportPath.RELAY, attempt = 1),
            relayUrl = "wss://relay.example.com",
        )
        assertThat(state.status).isEqualTo(ConnectionStatus.CONNECTING)
        assertThat(state.route).isEqualTo(ConnectionRouteState.Relay("wss://relay.example.com"))
        assertThat((state.macIdentity as MacIdentityState.Available).macDisplayName).isEqualTo("Test Mac")
    }

    @Test
    fun securityMapsCertificateAndPasskey() {
        val auth = PasskeyAuthentication("assertion-1", 1_000L, 2_000L)
        val state = SettingsMappers.security(trusted, auth, autoLockMinutes = 5)
        val certificate = state.deviceCertificate as DeviceCertificateState.Available
        assertThat(certificate.serial).isEqualTo("aa".repeat(32))
        assertThat(certificate.notAfterEpochMillis).isEqualTo(10_000_000L)
        assertThat(state.passkeySession).isEqualTo(PasskeySessionState.Active(2_000L))
        assertThat(state.canRevokeThisDevice).isTrue()
    }

    @Test
    fun securityUnavailableWhenUnpaired() {
        val state = SettingsMappers.security(TrustState.Unpaired, null, autoLockMinutes = null)
        assertThat(state.deviceCertificate).isEqualTo(DeviceCertificateState.Unavailable)
        assertThat(state.passkeySession).isEqualTo(PasskeySessionState.Unavailable)
        assertThat(state.canRevokeThisDevice).isFalse()
    }

    @Test
    fun notificationsMapPushRuntime() {
        val push = UnifiedPushState(
            provider = UnifiedPushProviderState.ProviderSelected("org.unifiedpush.distributor"),
            registration = UnifiedPushRegistrationState.EndpointAvailable(temporary = false),
        )
        val state = SettingsMappers.notifications(push, NotificationPermissionStatus.DENIED, emptyList())
        val distributor = state.distributor as PushDistributorState.Available
        assertThat(distributor.distributorName).isEqualTo("org.unifiedpush.distributor")
        assertThat(distributor.connected).isTrue()
        assertThat(state.endpointRegistration).isEqualTo(EndpointRegistrationState.REGISTERED)
        assertThat(state.postNotificationsPermission).isEqualTo(NotificationPermissionState.DENIED)
    }

    @Test
    fun notificationsWithoutDistributorAreHonest() {
        val push = UnifiedPushState(
            provider = UnifiedPushProviderState.ProviderUnavailable(
                io.github.verybigsad.pimobile.push.ProviderUnavailableReason.NO_DISTRIBUTOR,
            ),
        )
        val state = SettingsMappers.notifications(push, NotificationPermissionStatus.NOT_REQUIRED, emptyList())
        assertThat(state.distributor).isEqualTo(PushDistributorState.NoneInstalled)
        assertThat(state.endpointRegistration).isEqualTo(EndpointRegistrationState.UNKNOWN)
        assertThat(state.postNotificationsPermission).isEqualTo(NotificationPermissionState.NOT_REQUIRED)
    }
}
