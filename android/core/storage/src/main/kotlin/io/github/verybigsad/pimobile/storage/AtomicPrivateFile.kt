package io.github.verybigsad.pimobile.storage

import android.system.Os
import android.util.AtomicFile
import java.io.File

internal fun AtomicFile.existsIncludingBackup(): Boolean =
    baseFile.exists() || File("${baseFile.path}.bak").exists()

internal fun AtomicFile.readBytesAtomically(): ByteArray = openRead().use { input -> input.readBytes() }

internal fun AtomicFile.writeBytesAtomically(value: ByteArray) {
    val output = startWrite()
    try {
        output.write(value)
        output.fd.sync()
        finishWrite(output)
        restrictOwnerOnly(baseFile)
    } catch (error: Throwable) {
        failWrite(output)
        throw error
    }
}

internal fun restrictOwnerOnly(file: File) {
    if (!file.exists()) return
    runCatching { Os.chmod(file.path, OWNER_READ_WRITE_MODE) }
}

private const val OWNER_READ_WRITE_MODE = 0x180
