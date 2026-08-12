package io.github.verybigsad.pimobile.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Durable queue of UnifiedPush endpoint operations. ACCEPTED is returned only after the
 * operation is fsynced to disk; the coordinator drains it onto the READY connection.
 *
 * Owned by core/storage: it is a durable, atomically written queue under noBackupFilesDir.
 * wakePublicKey remains nullable for keyless ntfy/UnifiedPush distributors; queued
 * registrations are drained with the field omitted.
 */
class DurableEndpointQueue(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push-endpoint-ops.json"))
    private val lock = Mutex()

    data class Operation(
        val endpointId: String,
        val distributor: String,
        val endpoint: String,
        val wakePublicKey: String?,
        val revoke: Boolean,
    )

    suspend fun enqueue(operation: Operation): Boolean = lock.withLock {
        val operations = readAll().filterNot { it.endpointId == operation.endpointId } + operation
        writeAll(operations)
        true
    }

    suspend fun all(): List<Operation> = lock.withLock { readAll() }

    suspend fun remove(endpointId: String) = lock.withLock {
        writeAll(readAll().filterNot { it.endpointId == endpointId })
    }

    /** Drops operations whose target host is no longer trusted. */
    suspend fun clear() = lock.withLock {
        writeAll(emptyList())
    }

    private fun readAll(): List<Operation> {
        val text = runCatching { String(file.readFully(), Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun field(name: String) = (obj[name] as? JsonPrimitive)?.contentOrNull
            val endpointId = field("endpointId") ?: return@mapNotNull null
            val distributor = field("distributor") ?: return@mapNotNull null
            val endpoint = field("endpoint") ?: return@mapNotNull null
            Operation(
                endpointId = endpointId,
                distributor = distributor,
                endpoint = endpoint,
                wakePublicKey = field("wakePublicKey"),
                revoke = (obj["revoke"] as? JsonPrimitive)?.contentOrNull == "true",
            )
        }
    }

    private fun writeAll(operations: List<Operation>) {
        val array = JsonArray(
            operations.map { operation ->
                buildJsonObject {
                    put("endpointId", operation.endpointId)
                    put("distributor", operation.distributor)
                    put("endpoint", operation.endpoint)
                    put("wakePublicKey", operation.wakePublicKey?.let(::JsonPrimitive) ?: JsonPrimitive(null))
                    put("revoke", operation.revoke)
                }
            },
        )
        val output = file.startWrite()
        try {
            output.write(array.toString().encodeToByteArray())
            output.fd.sync()
            file.finishWrite(output)
            runCatching { android.system.Os.chmod(file.baseFile.path, 0x180) }
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}
