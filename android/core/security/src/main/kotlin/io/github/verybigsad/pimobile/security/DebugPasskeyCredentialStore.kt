package io.github.verybigsad.pimobile.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private const val MaxRecords = 64
private const val MaxSerializedBytes = 64 * 1024

/**
 * Durable record for one debug passkey credential. The signing key itself lives in Android
 * Keystore under [keyAlias] whenever hardware-backed generation succeeded; otherwise the
 * software key material is kept inline (debug-only, release builds never reach this code).
 */
@ConsistentCopyVisibility
data class DebugPasskeyCredentialRecord internal constructor(
    val credentialIdBase64Url: String,
    val keyAlias: String?,
    val userHandleBase64Url: String,
    val signCount: Long,
    val softwarePrivateKeyBase64Url: String?,
    val softwarePublicKeyBase64Url: String?,
)

/** Persistence boundary for debug passkey credentials; see [DebugPasskeyAuthenticator]. */
interface DebugPasskeyCredentialStore {
    fun load(): List<DebugPasskeyCredentialRecord>

    fun put(record: DebugPasskeyCredentialRecord)

    fun updateSignCount(credentialIdBase64Url: String, signCount: Long)
}

/** Process-local store; used when no Android application context is available (unit tests). */
internal class InMemoryDebugPasskeyCredentialStore : DebugPasskeyCredentialStore {
    private val records = LinkedHashMap<String, DebugPasskeyCredentialRecord>()

    override fun load(): List<DebugPasskeyCredentialRecord> = synchronized(records) { records.values.toList() }

    override fun put(record: DebugPasskeyCredentialRecord): Unit = synchronized(records) {
        records[record.credentialIdBase64Url] = record
        while (records.size > MaxRecords) records.remove(records.keys.first())
    }

    override fun updateSignCount(credentialIdBase64Url: String, signCount: Long): Unit = synchronized(records) {
        val record = records[credentialIdBase64Url] ?: return
        records[credentialIdBase64Url] = record.copy(signCount = signCount)
    }
}

/**
 * Atomically written JSON record file under noBackupFilesDir. Keystore keys referenced by
 * the records are durable on their own; this file carries identity, alias, user handle and
 * the signature counter so assertions survive process death.
 */
internal class AndroidDebugPasskeyCredentialStore(context: Context) : DebugPasskeyCredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val lock = Any()

    override fun load(): List<DebugPasskeyCredentialRecord> = synchronized(lock) {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = runCatching { file.readFully() }.getOrNull() ?: return emptyList()
        if (bytes.isEmpty() || bytes.size > MaxSerializedBytes) return emptyList()
        parse(String(bytes, Charsets.UTF_8))
    }

    override fun put(record: DebugPasskeyCredentialRecord): Unit = synchronized(lock) {
        val records = loadLocked().associateBy { it.credentialIdBase64Url }.toMutableMap()
        records[record.credentialIdBase64Url] = record
        while (records.size > MaxRecords) records.remove(records.keys.first())
        writeAtomically(serialize(records.values.toList()))
    }

    override fun updateSignCount(credentialIdBase64Url: String, signCount: Long): Unit = synchronized(lock) {
        val records = loadLocked().associateBy { it.credentialIdBase64Url }.toMutableMap()
        val record = records[credentialIdBase64Url] ?: return
        records[credentialIdBase64Url] = record.copy(signCount = signCount)
        writeAtomically(serialize(records.values.toList()))
    }

    internal fun deleteAllForTest(): Unit = synchronized(lock) {
        file.delete()
        File(file.baseFile.path + ".bak").delete()
    }

    private fun loadLocked(): List<DebugPasskeyCredentialRecord> {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = runCatching { file.readFully() }.getOrNull() ?: return emptyList()
        if (bytes.isEmpty() || bytes.size > MaxSerializedBytes) return emptyList()
        return parse(String(bytes, Charsets.UTF_8))
    }

    private fun writeAtomically(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun serialize(records: List<DebugPasskeyCredentialRecord>): ByteArray = buildJsonObject {
        put("version", 1)
        put(
            "credentials",
            JsonArray(
                records.map { record ->
                    buildJsonObject {
                        put("id", record.credentialIdBase64Url)
                        put("keyAlias", record.keyAlias?.let(::JsonPrimitive) ?: JsonPrimitive(null))
                        put("userHandle", record.userHandleBase64Url)
                        put("signCount", record.signCount)
                        put("softwarePrivateKey", record.softwarePrivateKeyBase64Url?.let(::JsonPrimitive) ?: JsonPrimitive(null))
                        put("softwarePublicKey", record.softwarePublicKeyBase64Url?.let(::JsonPrimitive) ?: JsonPrimitive(null))
                    }
                },
            ),
        )
    }.toString().encodeToByteArray()

    private fun parse(text: String): List<DebugPasskeyCredentialRecord> {
        val root = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        }.getOrNull() as? JsonObject ?: return emptyList()
        if ((root["version"] as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() } != 1L) return emptyList()
        val entries = runCatching { root.getValue("credentials").jsonArray }.getOrNull() ?: return emptyList()
        if (entries.size > MaxRecords) return emptyList()
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            fun required(field: String): String? = (obj[field] as? JsonPrimitive)?.contentOrNull
            val id = required("id") ?: return@mapNotNull null
            val userHandle = required("userHandle") ?: return@mapNotNull null
            val signCount = (obj["signCount"] as? JsonPrimitive)?.let { runCatching { it.long }.getOrNull() }
                ?.takeIf { it >= 0 } ?: return@mapNotNull null
            if (runCatching { Base64Url.decode(id, 128) }.isFailure) return@mapNotNull null
            if (runCatching { Base64Url.decode(userHandle, 128) }.isFailure) return@mapNotNull null
            val keyAlias = required("keyAlias")?.takeIf { it.length <= 256 }
            val softwarePrivateKey = required("softwarePrivateKey")
                ?.takeIf { runCatching { Base64Url.decode(it, 256) }.isSuccess }
            val softwarePublicKey = required("softwarePublicKey")
                ?.takeIf { runCatching { Base64Url.decode(it, 256) }.isSuccess }
            if (keyAlias == null && (softwarePrivateKey == null || softwarePublicKey == null)) {
                return@mapNotNull null
            }
            DebugPasskeyCredentialRecord(
                credentialIdBase64Url = id,
                keyAlias = keyAlias,
                userHandleBase64Url = userHandle,
                signCount = signCount,
                softwarePrivateKeyBase64Url = softwarePrivateKey,
                softwarePublicKeyBase64Url = softwarePublicKey,
            )
        }
    }

    internal companion object {
        const val FILE_NAME = "debug-passkeys.json"
    }
}
