package io.github.verybigsad.pimobile.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WakeReceiptStoreTest {
    @Test
    fun persistsOnlyFixedLengthHashes() {
        val persistence = MemoryPersistence()
        val store = WakeReceiptStore.forTest(persistence)
        val wakeId = wakeId("abcdefghijklmnopqrstuv")

        store.record(wakeId)

        assertThat(persistence.value).hasLength(WakeReceiptStore.RECEIPT_CHARS)
        assertThat(persistence.value).doesNotContain(wakeId.value)
        assertThat(persistence.value.all { it in WakeReceiptStore.HEX_CHARS }).isTrue()
        assertThat(store.contains(wakeId)).isTrue()
    }

    @Test
    fun keepsBoundedMostRecentReceipts() {
        val persistence = MemoryPersistence()
        val store = WakeReceiptStore.forTest(persistence)
        val ids = (0..WakeReceiptStore.MAX_RECEIPTS).map { index ->
            wakeId(index.toString().padStart(OpaqueWakePayload.MIN_WAKE_ID_BYTES, 'a'))
        }

        ids.forEach(store::record)

        assertThat(persistence.value.split(WakeReceiptStore.SEPARATOR)).hasSize(WakeReceiptStore.MAX_RECEIPTS)
        assertThat(store.contains(ids.first())).isFalse()
        assertThat(store.contains(ids.last())).isTrue()
        assertThat(persistence.value.length).isAtMost(WakeReceiptStore.MAX_SERIALIZED_CHARS)
    }

    @Test
    fun duplicateReceiptIsStoredOnce() {
        val persistence = MemoryPersistence()
        val store = WakeReceiptStore.forTest(persistence)
        val wakeId = wakeId("abcdefghijklmnopqrstuv")

        store.record(wakeId)
        store.record(wakeId)

        assertThat(persistence.value.split(WakeReceiptStore.SEPARATOR)).hasSize(1)
    }

    @Test
    fun oversizedOrMalformedPersistenceIsDiscarded() {
        val oversized = MemoryPersistence("x".repeat(WakeReceiptStore.MAX_SERIALIZED_CHARS + 1))
        val oversizedStore = WakeReceiptStore.forTest(oversized)
        assertThat(oversizedStore.contains(wakeId("abcdefghijklmnopqrstuv"))).isFalse()
        assertThat(oversized.value).isEmpty()

        val malformed = MemoryPersistence("not-a-receipt\n" + "g".repeat(64))
        val malformedStore = WakeReceiptStore.forTest(malformed)
        assertThat(malformedStore.contains(wakeId("abcdefghijklmnopqrstuv"))).isFalse()
    }

    @Test
    fun singletonWorkNameNeverContainsWakeId() {
        val wakeId = wakeId("abcdefghijklmnopqrstuv")

        assertThat(WakeWorkNames.UNIQUE_WORK_NAME).doesNotContain(wakeId.value)
        assertThat(WakeWorkNames.UNIQUE_WORK_NAME).isEqualTo("pi-push-reconnect")
    }

    private fun wakeId(value: String): OpaqueWakeId =
        (OpaqueWakePayload.parse(value) as WakePayloadParseResult.Valid).wakeId

    private class MemoryPersistence(initial: String = "") : WakeReceiptPersistence {
        var value = initial

        override fun read(): String = value

        override fun write(value: String) {
            this.value = value
        }
    }
}
