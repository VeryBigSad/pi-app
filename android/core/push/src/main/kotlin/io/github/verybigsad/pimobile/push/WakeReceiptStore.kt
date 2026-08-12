package io.github.verybigsad.pimobile.push

import android.annotation.SuppressLint
import android.content.Context
import java.security.MessageDigest

internal object WakeWorkNames {
    const val UNIQUE_WORK_NAME = "pi-push-reconnect"

    fun workName(wakeId: OpaqueWakeId): String = "$UNIQUE_WORK_NAME-${receiptId(wakeId)}"

    fun receiptId(wakeId: OpaqueWakeId): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(wakeId.value.toByteArray(Charsets.US_ASCII))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private const val HEX = "0123456789abcdef"
}

internal interface WakeReceiptPersistence {
    fun read(): String

    fun write(value: String)
}

private class SharedPreferencesWakeReceiptPersistence(context: Context) : WakeReceiptPersistence {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): String = preferences.getString(RECEIPTS_KEY, "").orEmpty()

    @SuppressLint("UseKtx")
    override fun write(value: String) {
        check(preferences.edit().putString(RECEIPTS_KEY, value).commit())
    }

    companion object {
        const val PREFERENCES_NAME = "pi_push_receipts_v1"
        const val RECEIPTS_KEY = "completed_wake_hashes"
    }
}

internal interface WakePendingPersistence {
    fun read(): String

    fun write(value: String)
}

private class SharedPreferencesWakePendingPersistence(context: Context) : WakePendingPersistence {
    private val preferences = context.getSharedPreferences(
        SharedPreferencesWakeReceiptPersistence.PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): String = preferences.getString(PENDING_KEY, "").orEmpty()

    @SuppressLint("UseKtx")
    override fun write(value: String) {
        check(preferences.edit().putString(PENDING_KEY, value).commit())
    }

    companion object {
        const val PENDING_KEY = "pending_wake_ids"
    }
}

internal class WakePendingStore private constructor(
    private val persistence: WakePendingPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesWakePendingPersistence(context.applicationContext))

    fun enqueue(wakeId: OpaqueWakeId) = synchronized(WakeReceiptStore.lock) {
        val values = load().filterNot { it == wakeId.value }.toMutableList()
        values += wakeId.value
        while (values.size > MAX_PENDING) values.removeAt(0)
        persistence.write(values.joinToString(SEPARATOR))
    }

    fun remove(wakeId: OpaqueWakeId) = synchronized(WakeReceiptStore.lock) {
        persistence.write(load().filterNot { it == wakeId.value }.joinToString(SEPARATOR))
    }

    fun all(): List<OpaqueWakeId> = synchronized(WakeReceiptStore.lock) {
        load().map(::OpaqueWakeId)
    }

    private fun load(): List<String> {
        val stored = persistence.read()
        if (stored.isEmpty()) return emptyList()
        if (stored.length > MAX_SERIALIZED_CHARS) {
            persistence.write("")
            return emptyList()
        }
        return stored.split(SEPARATOR).mapNotNull { value ->
            when (val parsed = OpaqueWakePayload.parse(value)) {
                is WakePayloadParseResult.Valid -> parsed.wakeId.value
                is WakePayloadParseResult.Invalid -> null
            }
        }.distinct().takeLast(MAX_PENDING)
    }

    internal companion object {
        const val MAX_PENDING = 256
        const val SEPARATOR = "\n"
        const val MAX_SERIALIZED_CHARS = MAX_PENDING * (OpaqueWakePayload.MAX_WAKE_ID_BYTES + SEPARATOR.length)

        fun forTest(persistence: WakePendingPersistence): WakePendingStore = WakePendingStore(persistence)
    }
}

internal class WakeReceiptStore private constructor(
    private val persistence: WakeReceiptPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesWakeReceiptPersistence(context.applicationContext))

    fun contains(wakeId: OpaqueWakeId): Boolean = synchronized(lock) {
        load().contains(WakeWorkNames.receiptId(wakeId))
    }

    fun record(wakeId: OpaqueWakeId) = synchronized(lock) {
        val receipt = WakeWorkNames.receiptId(wakeId)
        val values = load().filterNot { it == receipt }.toMutableList()
        values += receipt
        while (values.size > MAX_RECEIPTS) {
            values.removeAt(0)
        }
        persistence.write(values.joinToString(SEPARATOR))
    }

    private fun load(): List<String> {
        val stored = persistence.read()
        if (stored.isEmpty()) {
            return emptyList()
        }
        if (stored.length > MAX_SERIALIZED_CHARS) {
            persistence.write("")
            return emptyList()
        }
        return stored.split(SEPARATOR)
            .filter { value -> value.length == RECEIPT_CHARS && value.all { it in HEX_CHARS } }
            .takeLast(MAX_RECEIPTS)
    }

    internal companion object {
        const val MAX_RECEIPTS = 256
        const val RECEIPT_CHARS = 64
        const val SEPARATOR = "\n"
        const val MAX_SERIALIZED_CHARS = MAX_RECEIPTS * (RECEIPT_CHARS + SEPARATOR.length)
        const val HEX_CHARS = "0123456789abcdef"
        val lock = Any()

        fun forTest(persistence: WakeReceiptPersistence): WakeReceiptStore = WakeReceiptStore(persistence)
    }
}
