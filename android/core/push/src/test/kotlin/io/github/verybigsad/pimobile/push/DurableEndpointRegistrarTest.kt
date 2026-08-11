package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DurableEndpointRegistrarTest {
    private var enqueues = 0

    @Test
    fun registerPersistsPendingUploadThenAccepts() {
        val store = store()
        val registrar = DurableEndpointRegistrar.forTest(store) { enqueues += 1 }

        val result = registrar.register(endpoint())

        assertThat(result).isEqualTo(EndpointRegistrationResult.ACCEPTED)
        assertThat(enqueues).isEqualTo(1)
        val snapshot = store.load()!!
        assertThat(snapshot.endpoint!!.url).isEqualTo(ENDPOINT_URL)
        assertThat(snapshot.uploadState).isEqualTo(EndpointUploadState.PENDING)
    }

    @Test
    fun registerAcceptsEvenWhenSchedulingFails() {
        val store = store()
        val registrar = DurableEndpointRegistrar.forTest(store) { error("work manager not ready") }

        val result = registrar.register(endpoint())

        assertThat(result).isEqualTo(EndpointRegistrationResult.ACCEPTED)
        assertThat(store.load()!!.uploadState).isEqualTo(EndpointUploadState.PENDING)
    }

    @Test
    fun registerRetriesWhenPersistenceFails() {
        val registrar = DurableEndpointRegistrar.forTest(store(failing = true)) { enqueues += 1 }

        val result = registrar.register(endpoint())

        assertThat(result).isEqualTo(EndpointRegistrationResult.RETRY_REQUIRED)
        assertThat(enqueues).isEqualTo(0)
    }

    @Test
    fun unregisterPersistsPendingRemovalThenAccepts() {
        val store = store()
        val registrar = DurableEndpointRegistrar.forTest(store) { enqueues += 1 }

        val result = registrar.unregister(UnifiedPushClient.PUSH_INSTANCE)

        assertThat(result).isEqualTo(EndpointRegistrationResult.ACCEPTED)
        assertThat(enqueues).isEqualTo(1)
        val snapshot = store.load()!!
        assertThat(snapshot.endpoint).isNull()
        assertThat(snapshot.pendingRemoval).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
    }

    @Test
    fun unregisterRetriesWhenPersistenceFails() {
        val registrar = DurableEndpointRegistrar.forTest(store(failing = true)) { enqueues += 1 }

        val result = registrar.unregister(UnifiedPushClient.PUSH_INSTANCE)

        assertThat(result).isEqualTo(EndpointRegistrationResult.RETRY_REQUIRED)
        assertThat(enqueues).isEqualTo(0)
    }

    private fun store(failing: Boolean = false): PushRegistrationStore =
        PushRegistrationStore.forTest(
            object : PushRegistrationPersistence {
                private var bytes: ByteArray? = null

                override fun read(): ByteArray? {
                    if (failing) error("disk gone")
                    return bytes
                }

                override fun write(bytes: ByteArray) {
                    if (failing) error("disk gone")
                    this.bytes = bytes
                }

                override fun clear() {
                    bytes = null
                }
            },
        )

    private fun endpoint() = UnifiedPushEndpoint(
        url = ENDPOINT_URL,
        instance = UnifiedPushClient.PUSH_INSTANCE,
        temporary = false,
        publicKey = null,
        authSecret = null,
    )

    companion object {
        private const val ENDPOINT_URL = "https://push.example/up/private"
    }
}
