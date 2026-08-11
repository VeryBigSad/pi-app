package io.github.verybigsad.pimobile.storage

import android.content.Context
import android.util.AtomicFile
import java.io.IOException
import java.security.SecureRandom

internal enum class CacheResetPhase(val code: Byte) {
    RECOVERING(1),
    READY_FOR_CANONICAL_RESYNC(2),
}

internal data class CacheResetState(
    val phase: CacheResetPhase,
    val signal: CanonicalResyncSignal,
)

internal class CacheResetStateInvalidException : Exception()

internal class CacheResetStorageException(cause: Throwable) : Exception(null, cause)

internal class CacheResetStore(context: Context) {
    private val stateFile = AtomicFile(context.noBackupFilesDir.resolve(STATE_FILE))

    fun load(): CacheResetState? {
        if (!stateFile.existsIncludingBackup()) return null
        val value = try {
            stateFile.readBytesAtomically()
        } catch (error: IOException) {
            throw CacheResetStorageException(error)
        }
        try {
            if (value.size != STATE_SIZE || !value.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw CacheResetStateInvalidException()
            }
            val phase = CacheResetPhase.entries.singleOrNull { it.code == value[PHASE_OFFSET] }
                ?: throw CacheResetStateInvalidException()
            val reason = CacheResetReason.entries.singleOrNull { it.code == value[REASON_OFFSET] }
                ?: throw CacheResetStateInvalidException()
            return CacheResetState(
                phase = phase,
                signal = CanonicalResyncSignal(
                    generation = value.copyOfRange(TOKEN_OFFSET, STATE_SIZE).toHex(),
                    reason = reason,
                ),
            )
        } finally {
            value.fill(0)
        }
    }

    fun begin(reason: CacheResetReason): CacheResetState {
        val state = CacheResetState(
            phase = CacheResetPhase.RECOVERING,
            signal = CanonicalResyncSignal(
                generation = ByteArray(TOKEN_SIZE).also(SecureRandom()::nextBytes).toHex(),
                reason = reason,
            ),
        )
        write(state)
        return state
    }

    fun markReady(state: CacheResetState): CacheResetState =
        state.copy(phase = CacheResetPhase.READY_FOR_CANONICAL_RESYNC).also(::write)

    fun acknowledge(signal: CanonicalResyncSignal): Boolean {
        val current = load() ?: return false
        if (current.phase != CacheResetPhase.READY_FOR_CANONICAL_RESYNC || current.signal != signal) return false
        stateFile.delete()
        if (stateFile.existsIncludingBackup()) throw CacheResetStorageException(IOException())
        return true
    }

    fun clear() {
        stateFile.delete()
        if (stateFile.existsIncludingBackup()) throw CacheResetStorageException(IOException())
    }

    private fun write(state: CacheResetState) {
        val token = state.signal.generation.hexToByteArray()
        if (token.size != TOKEN_SIZE) throw CacheResetStateInvalidException()
        val value = ByteArray(STATE_SIZE)
        try {
            MAGIC.copyInto(value)
            value[PHASE_OFFSET] = state.phase.code
            value[REASON_OFFSET] = state.signal.reason.code
            token.copyInto(value, TOKEN_OFFSET)
            stateFile.writeBytesAtomically(value)
        } catch (error: CacheResetStateInvalidException) {
            throw error
        } catch (error: Exception) {
            throw CacheResetStorageException(error)
        } finally {
            token.fill(0)
            value.fill(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun String.hexToByteArray(): ByteArray {
        if (length % 2 != 0) throw CacheResetStateInvalidException()
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                ?: throw CacheResetStateInvalidException()
        }
    }

    private companion object {
        const val STATE_FILE = "cache-reset.v1"
        const val TOKEN_SIZE = 16
        const val PHASE_OFFSET = 4
        const val REASON_OFFSET = 5
        const val TOKEN_OFFSET = 6
        const val STATE_SIZE = TOKEN_OFFSET + TOKEN_SIZE
        val MAGIC = byteArrayOf(0x50, 0x4d, 0x52, 0x31)
    }
}
