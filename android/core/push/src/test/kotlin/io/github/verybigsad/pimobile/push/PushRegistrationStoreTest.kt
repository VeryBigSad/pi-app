package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PushRegistrationStoreTest {
    @Test
    fun fullSnapshotSurvivesRoundTrip() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)

        store.saveEndpoint(endpoint(temporary = true, withKeys = true))
        val loaded = PushRegistrationStore.forTest(persistence).load()

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.uploadState).isEqualTo(EndpointUploadState.PENDING)
        assertThat(loaded.pendingRemoval).isNull()
        val endpoint = loaded.endpoint!!
        assertThat(endpoint.url).isEqualTo(ENDPOINT_URL)
        assertThat(endpoint.instance).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(endpoint.temporary).isTrue()
        assertThat(endpoint.publicKey).isEqualTo(PUBLIC_KEY)
        assertThat(endpoint.authSecret).isEqualTo(AUTH_SECRET)
    }

    @Test
    fun endpointWithoutKeysRoundTripsAsNullKeys() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)

        store.saveEndpoint(endpoint(temporary = false, withKeys = false))
        val loaded = PushRegistrationStore.forTest(persistence).load()

        assertThat(loaded!!.endpoint!!.temporary).isFalse()
        assertThat(loaded.endpoint.publicKey).isNull()
        assertThat(loaded.endpoint.authSecret).isNull()
    }

    @Test
    fun saveEndpointReplacesPreviousAndClearsPendingRemoval() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)
        store.saveRemoval(UnifiedPushClient.PUSH_INSTANCE)

        val updated = store.saveEndpoint(endpoint(temporary = false, withKeys = false))

        assertThat(updated.pendingRemoval).isNull()
        assertThat(updated.endpoint).isNotNull()
        assertThat(updated.uploadState).isEqualTo(EndpointUploadState.PENDING)
    }

    @Test
    fun markUploadedFlipsOnlyUploadState() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)
        store.saveEndpoint(endpoint(temporary = false, withKeys = false))

        val updated = store.markUploaded()

        assertThat(updated!!.uploadState).isEqualTo(EndpointUploadState.UPLOADED)
        assertThat(updated.endpoint).isNotNull()
    }

    @Test
    fun saveRemovalDropsEndpointButKeepsPendingRemoval() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)
        store.saveEndpoint(endpoint(temporary = false, withKeys = false))

        val updated = store.saveRemoval(UnifiedPushClient.PUSH_INSTANCE)

        assertThat(updated.endpoint).isNull()
        assertThat(updated.pendingRemoval).isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
        assertThat(PushRegistrationStore.forTest(persistence).load()!!.pendingRemoval)
            .isEqualTo(UnifiedPushClient.PUSH_INSTANCE)
    }

    @Test
    fun clearPendingRemovalKeepsRest() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)
        store.saveRemoval(UnifiedPushClient.PUSH_INSTANCE)

        val updated = store.clearPendingRemoval()

        assertThat(updated!!.pendingRemoval).isNull()
    }

    @Test
    fun clearDropsSnapshot() {
        val persistence = MemoryPersistence()
        val store = PushRegistrationStore.forTest(persistence)
        store.saveEndpoint(endpoint(temporary = false, withKeys = false))

        store.clear()

        assertThat(persistence.bytes).isNull()
        assertThat(store.load()).isNull()
    }

    @Test
    fun corruptedBytesDecodeAsAbsent() {
        val store = PushRegistrationStore.forTest(MemoryPersistence("garbage-without-equals".toByteArray()))

        assertThat(store.load()).isNull()
    }

    @Test
    fun unknownVersionDecodesAsAbsent() {
        val store = PushRegistrationStore.forTest(MemoryPersistence("version=99\nupload=PENDING\n".toByteArray()))

        assertThat(store.load()).isNull()
    }

    @Test
    fun malformedBase64DecodesAsAbsent() {
        val store = PushRegistrationStore.forTest(
            MemoryPersistence("version=1\nurl=!!!\ninstance=aa\ntemporary=0\nupload=PENDING\nremoval=-\n".toByteArray()),
        )

        assertThat(store.load()).isNull()
    }

    @Test
    fun oversizedBytesDecodeAsAbsent() {
        val store = PushRegistrationStore.forTest(
            MemoryPersistence(ByteArray(PushRegistrationStore.MAX_SERIALIZED_BYTES + 1) { 'a'.code.toByte() }),
        )

        assertThat(store.load()).isNull()
    }

    @Test
    fun truncatedEndpointFieldsDecodeAsAbsent() {
        val store = PushRegistrationStore.forTest(
            MemoryPersistence("version=1\nurl=aHR0cHM6Ly9wdXNoLmV4YW1wbGU\ntemporary=0\nupload=PENDING\nremoval=-\n".toByteArray()),
        )

        assertThat(store.load()).isNull()
    }

    private fun endpoint(temporary: Boolean, withKeys: Boolean) = UnifiedPushEndpoint(
        url = ENDPOINT_URL,
        instance = UnifiedPushClient.PUSH_INSTANCE,
        temporary = temporary,
        publicKey = if (withKeys) PUBLIC_KEY else null,
        authSecret = if (withKeys) AUTH_SECRET else null,
    )

    private class MemoryPersistence(var bytes: ByteArray? = null) : PushRegistrationPersistence {
        override fun read(): ByteArray? = bytes

        override fun write(bytes: ByteArray) {
            this.bytes = bytes
        }

        override fun clear() {
            bytes = null
        }
    }

    companion object {
        private const val ENDPOINT_URL = "https://push.example/up/private"
        private val PUBLIC_KEY = "B".repeat(87)
        private val AUTH_SECRET = "a".repeat(22)
    }
}
