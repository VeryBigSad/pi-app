package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class UnifiedPushClientTest {
    private lateinit var platform: FakePlatform
    private lateinit var client: UnifiedPushClient

    @Before
    fun setUp() {
        UnifiedPushRuntime.clear()
        platform = FakePlatform()
        client = UnifiedPushClient(platform)
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
    }

    @Test
    fun providerListIsStableSortedAndDeduplicated() {
        platform.providers = listOf("org.example.z", "org.example.a", "org.example.z")

        assertThat(client.availableProviders()).containsExactly("org.example.a", "org.example.z").inOrder()
    }

    @Test
    fun noDistributorProducesExplicitProviderUnavailableState() {
        assertThat(client.refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR),
        )
        assertThat(client.requestRegistration()).isEqualTo(UnifiedPushRegistrationState.ProviderUnavailable)
        assertThat(UnifiedPushRuntime.state.value.provider).isEqualTo(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.NO_DISTRIBUTOR),
        )
    }

    @Test
    fun exactlyOneProviderIsPersistedAndSelectedBeforeRegistration() {
        platform.providers = listOf("org.example.distributor")

        assertThat(client.refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderSelected("org.example.distributor"),
        )
        assertThat(platform.saved).isEqualTo("org.example.distributor")
        assertThat(client.requestRegistration()).isEqualTo(UnifiedPushRegistrationState.RegistrationRequested)
        assertThat(platform.registeredInstance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
    }

    @Test
    fun multipleProvidersRequireExplicitSelection() {
        platform.providers = listOf("org.example.first", "org.example.second")

        assertThat(client.refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderSelectionRequired(2),
        )
        assertThat(client.requestRegistration()).isEqualTo(UnifiedPushRegistrationState.NotConfigured)
        assertThat(platform.registeredInstance).isNull()
    }

    @Test
    fun providerChoicesUseSanitizedPackageLabels() {
        platform.providers = listOf("org.example.distributor")
        platform.labels["org.example.distributor"] = "  Example\u202E Push\n  "

        assertThat(client.availableProviderChoices()).containsExactly(
            UnifiedPushProvider("org.example.distributor", "Example Push"),
        )
    }

    @Test
    fun refusesPackageThatIsNotAnInstalledDistributor() {
        platform.providers = listOf("org.example.distributor")

        assertThat(client.selectProvider("org.example.forged")).isEqualTo(
            UnifiedPushProviderState.ProviderSelectionRequired(1),
        )
        assertThat(platform.saved).isNull()
    }

    @Test
    fun selectedProviderRegistersOpaqueInstanceWithoutContentOrFcmData() {
        platform.providers = listOf("org.example.distributor")
        assertThat(client.selectProvider("org.example.distributor")).isEqualTo(
            UnifiedPushProviderState.ProviderSelected("org.example.distributor"),
        )

        assertThat(client.requestRegistration()).isEqualTo(UnifiedPushRegistrationState.RegistrationRequested)
        assertThat(platform.registeredInstance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(platform.messageForDistributor).isNull()
        assertThat(platform.vapid).isNull()
    }

    @Test
    fun connectorErrorsNeverExposeExceptionTextInState() {
        platform.throwOnProviders = true

        assertThat(client.refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR),
        )
        assertThat(UnifiedPushRuntime.state.value.toString()).doesNotContain("sensitive exception text")
    }

    @Test
    fun savedDistributorReadFailureIsProviderUnavailable() {
        platform.providers = listOf("org.example.distributor")
        platform.throwOnSaved = true

        assertThat(client.refreshProviderState()).isEqualTo(
            UnifiedPushProviderState.ProviderUnavailable(ProviderUnavailableReason.CONNECTOR_ERROR),
        )
        assertThat(client.requestRegistration()).isEqualTo(UnifiedPushRegistrationState.ProviderUnavailable)
    }

    @Test
    fun unregisterWithoutEndpointIntegrationIsExplicitlyNotConfigured() {
        assertThat(client.unregister()).isEqualTo(UnifiedPushRegistrationState.NotConfigured)
    }

    @Test
    fun unregisterUsesOnlyFixedOpaqueInstanceAndCleansServerEndpoint() {
        platform.providers = listOf("org.example.distributor")
        platform.saved = "org.example.distributor"
        var endpointInstance: String? = null
        UnifiedPushRuntime.install(
            endpointRegistrar = object : UnifiedPushEndpointRegistrar {
                override fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult =
                    EndpointRegistrationResult.ACCEPTED

                override fun unregister(instance: String): EndpointRegistrationResult {
                    endpointInstance = instance
                    return EndpointRegistrationResult.ACCEPTED
                }
            },
            wakeReconnector = { WakeReconnectResult.COMPLETED },
        )

        assertThat(client.unregister()).isEqualTo(UnifiedPushRegistrationState.Unregistered)
        assertThat(platform.unregisteredInstance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(endpointInstance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
    }

    private class FakePlatform : UnifiedPushConnectorPlatform {
        var providers: List<String> = emptyList()
        var saved: String? = null
        var registeredInstance: String? = null
        var unregisteredInstance: String? = null
        var messageForDistributor: String? = "unset"
        var vapid: String? = "unset"
        var throwOnProviders = false
        var throwOnSaved = false

        override fun distributors(): List<String> {
            if (throwOnProviders) {
                error("sensitive exception text")
            }
            return providers
        }

        val labels = mutableMapOf<String, CharSequence?>()

        override fun providerLabel(packageName: String): CharSequence? = labels[packageName]

        override fun savedDistributor(): String? {
            if (throwOnSaved) {
                error("sensitive exception text")
            }
            return saved
        }

        override fun saveDistributor(packageName: String) {
            saved = packageName
        }

        override fun register(instance: String, messageForDistributor: String?, vapid: String?) {
            registeredInstance = instance
            this.messageForDistributor = messageForDistributor
            this.vapid = vapid
        }

        override fun unregister(instance: String) {
            unregisteredInstance = instance
        }
    }
}
