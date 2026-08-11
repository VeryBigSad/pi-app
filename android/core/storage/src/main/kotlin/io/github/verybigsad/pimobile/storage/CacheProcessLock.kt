package io.github.verybigsad.pimobile.storage

import android.content.Context
import java.io.RandomAccessFile

internal class CacheProcessLock(context: Context) {
    private val lockFile = context.noBackupFilesDir.resolve(LOCK_FILE)

    fun <T> exclusive(block: () -> T): T {
        RandomAccessFile(lockFile, "rw").use { file ->
            restrictOwnerOnly(lockFile)
            file.channel.use { channel ->
                channel.lock().use { return block() }
            }
        }
    }

    private companion object {
        const val LOCK_FILE = "cache-open.lock"
    }
}
