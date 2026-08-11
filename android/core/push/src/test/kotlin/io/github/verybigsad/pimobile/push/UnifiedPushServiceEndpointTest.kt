package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint

class UnifiedPushServiceEndpointTest {
    private val service = UnifiedPushService()
    private lateinit var registrar: ConfigurableRegistrar

    @Before
    fun setUp() {
        UnifiedPushRuntime.clear()
        registrar = ConfigurableRegistrar()
        UnifiedPushRuntime.install(registrar) { WakeReconnectResult.COMPLETED }
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
    }

    @Test
    fun acceptedEndpointUpdatesOnlyRedactedState() {
        service.onNewEndpoint(
            PushEndpoint("https://push.example/up/private-token", null, false),
            UnifiedPushClient.PUSH_INSTANCE,
        )

        assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
            UnifiedPushRegistrationState.EndpointAvailable(false),
        )
        assertThat(UnifiedPushRuntime.state.value.toString()).doesNotContain("private-token")
        assertThat(registrar.endpoint?.url).isEqualTo("https://push.example/up/private-token")
    }

    @Test
    fun registrarRetryAndRejectionAreExplicit() {
        registrar.result = EndpointRegistrationResult.RETRY_REQUIRED
        service.onNewEndpoint(endpoint(), UnifiedPushClient.PUSH_INSTANCE)
        assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
            UnifiedPushRegistrationState.EndpointRetryRequired(false),
        )

        registrar.result = EndpointRegistrationResult.REJECTED
        service.onNewEndpoint(endpoint(), UnifiedPushClient.PUSH_INSTANCE)
        assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
            UnifiedPushRegistrationState.EndpointRejected(null),
        )
    }

    @Test
    fun registrarExceptionBecomesContentFreeNotConfiguredState() {
        registrar.throwOnRegister = true

        service.onNewEndpoint(endpoint(), UnifiedPushClient.PUSH_INSTANCE)

        assertThat(UnifiedPushRuntime.state.value.registration)
            .isEqualTo(UnifiedPushRegistrationState.NotConfigured)
        assertThat(UnifiedPushRuntime.state.value.toString()).doesNotContain("private registrar detail")
    }

    @Test
    fun invalidEndpointNeverReachesRegistrar() {
        service.onNewEndpoint(
            PushEndpoint("http://push.example/up/private-token", null, false),
            UnifiedPushClient.PUSH_INSTANCE,
        )

        assertThat(registrar.endpoint).isNull()
        assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
            UnifiedPushRegistrationState.EndpointRejected(EndpointInvalidReason.URL_NOT_HTTPS),
        )
    }

    @Test
    fun wrongInstanceNeverReachesRegistrar() {
        service.onNewEndpoint(endpoint(), "wrong-instance")

        assertThat(registrar.endpoint).isNull()
        assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
            UnifiedPushRegistrationState.EndpointRejected(EndpointInvalidReason.WRONG_INSTANCE),
        )
    }

    @Test
    fun connectorFailuresMapToStableContentFreeCodes() {
        val expected = mapOf(
            FailedReason.INTERNAL_ERROR to RegistrationFailure.INTERNAL_ERROR,
            FailedReason.NETWORK to RegistrationFailure.NETWORK,
            FailedReason.ACTION_REQUIRED to RegistrationFailure.ACTION_REQUIRED,
            FailedReason.VAPID_REQUIRED to RegistrationFailure.VAPID_REQUIRED,
        )

        expected.forEach { (connectorReason, registrationFailure) ->
            service.onRegistrationFailed(connectorReason, UnifiedPushClient.PUSH_INSTANCE)
            assertThat(UnifiedPushRuntime.state.value.registration).isEqualTo(
                UnifiedPushRegistrationState.RegistrationFailed(registrationFailure),
            )
        }
    }

    @Test
    fun temporaryUnavailableAndUnregisteredAreExplicit() {
        service.onTempUnavailable(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(UnifiedPushRuntime.state.value.registration)
            .isEqualTo(UnifiedPushRegistrationState.TemporarilyUnavailable)

        service.onUnregistered(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(UnifiedPushRuntime.state.value.registration)
            .isEqualTo(UnifiedPushRegistrationState.Unregistered)
        assertThat(registrar.unregistered).containsExactly(UnifiedPushClient.PUSH_INSTANCE)
    }

    private fun endpoint() = PushEndpoint("https://push.example/up/token", null, false)

    private class ConfigurableRegistrar : UnifiedPushEndpointRegistrar {
        var result = EndpointRegistrationResult.ACCEPTED
        var endpoint: UnifiedPushEndpoint? = null
        var throwOnRegister = false
        val unregistered = mutableListOf<String>()

        override fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult {
            if (throwOnRegister) {
                error("private registrar detail")
            }
            this.endpoint = endpoint
            return result
        }

        override fun unregister(instance: String): EndpointRegistrationResult {
            unregistered += instance
            return result
        }
    }
}
