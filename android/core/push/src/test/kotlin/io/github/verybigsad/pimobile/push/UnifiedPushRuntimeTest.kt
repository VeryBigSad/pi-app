package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

class UnifiedPushRuntimeTest {
    private lateinit var registrar: RecordingRegistrar

    @Before
    fun setUp() {
        UnifiedPushRuntime.clear()
        registrar = RecordingRegistrar()
    }

    @After
    fun tearDown() {
        UnifiedPushRuntime.clear()
    }

    @Test
    fun endpointRegistrationIsAvailableOnlyThroughRegistrarAbstraction() {
        UnifiedPushRuntime.install(registrar) { WakeReconnectResult.COMPLETED }
        val endpoint = endpoint()

        assertThat(UnifiedPushRuntime.registerEndpoint(endpoint)).isEqualTo(EndpointRegistrationResult.ACCEPTED)
        assertThat(registrar.registered).containsExactly(endpoint)
    }

    @Test
    fun endpointAndWakeHandlersAreExplicitlyUnavailableBeforeInstall() = runBlocking {
        assertThat(UnifiedPushRuntime.registerEndpoint(endpoint())).isNull()
        assertThat(UnifiedPushRuntime.unregisterEndpoint(UnifiedPushClient.PUSH_INSTANCE)).isNull()
        assertThat(UnifiedPushRuntime.reconnect(wakeId())).isNull()
    }

    @Test
    fun unregisterIsDelegatedWithoutEndpointContent() {
        UnifiedPushRuntime.install(registrar) { WakeReconnectResult.COMPLETED }

        assertThat(UnifiedPushRuntime.unregisterEndpoint(UnifiedPushClient.PUSH_INSTANCE))
            .isEqualTo(EndpointRegistrationResult.ACCEPTED)
        assertThat(registrar.unregistered).containsExactly(UnifiedPushClient.PUSH_INSTANCE)
    }

    @Test
    fun reconnectReceivesOnlyOpaqueWakeId() = runBlocking {
        var received: OpaqueWakeId? = null
        UnifiedPushRuntime.install(registrar) {
            received = it
            WakeReconnectResult.REJECTED
        }

        assertThat(UnifiedPushRuntime.reconnect(wakeId())).isEqualTo(WakeReconnectResult.REJECTED)
        assertThat(received).isEqualTo(wakeId())
    }

    @Test
    fun endpointUploaderIsExposedForBackgroundUploads() {
        val uploader = object : UnifiedPushEndpointUploader {
            override suspend fun upload(endpoint: UnifiedPushEndpoint): EndpointUploadResult =
                EndpointUploadResult.UPLOADED

            override suspend fun remove(instance: String): EndpointUploadResult =
                EndpointUploadResult.UPLOADED
        }

        UnifiedPushRuntime.install(registrar, uploader) { WakeReconnectResult.COMPLETED }

        assertThat(UnifiedPushRuntime.endpointUploader()).isSameInstanceAs(uploader)
        assertThat(UnifiedPushRuntime.hasIntegration()).isTrue()
    }

    @Test
    fun endpointUploaderDefaultsToAbsentForLegacyInstalls() {
        UnifiedPushRuntime.install(registrar) { WakeReconnectResult.COMPLETED }

        assertThat(UnifiedPushRuntime.endpointUploader()).isNull()
        UnifiedPushRuntime.clear()
        assertThat(UnifiedPushRuntime.hasIntegration()).isFalse()
    }

    private fun endpoint() = UnifiedPushEndpoint(
        url = "https://push.example/up/private",
        instance = UnifiedPushClient.PUSH_INSTANCE,
        temporary = false,
        publicKey = null,
        authSecret = null,
    )

    private fun wakeId() = (OpaqueWakePayload.parse("abcdefghijklmnopqrstuv") as WakePayloadParseResult.Valid).wakeId

    private class RecordingRegistrar : UnifiedPushEndpointRegistrar {
        val registered = mutableListOf<UnifiedPushEndpoint>()
        val unregistered = mutableListOf<String>()

        override fun register(endpoint: UnifiedPushEndpoint): EndpointRegistrationResult {
            registered += endpoint
            return EndpointRegistrationResult.ACCEPTED
        }

        override fun unregister(instance: String): EndpointRegistrationResult {
            unregistered += instance
            return EndpointRegistrationResult.ACCEPTED
        }
    }
}
