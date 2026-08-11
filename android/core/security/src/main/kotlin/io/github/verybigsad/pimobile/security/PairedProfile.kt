package io.github.verybigsad.pimobile.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Durable paired-Mac profile. It must survive an EncryptedCache reset because it is the only
 * way to reconnect and perform the mandated canonical recovery. Private keys never leave
 * Android Keystore; this store holds identity and endpoint metadata only.
 *
 * Owned by core/security: pairing identity and endpoint metadata are security-domain state.
 */
data class PairedProfile(
    val deviceId: String,
    val macId: String,
    val macDisplayName: String,
    val relayWssUrl: String,
    val routeId: String,
    val deviceRouteKeyId: String,
    val directCandidates: List<DirectCandidate>,
    /** PEM-encoded CA chain (leaf's issuers) captured from pair.result. */
    val caCertificatePem: String,
    val certificateSerial: String,
    val certificateNotAfterEpochMillis: Long,
    val endpointId: String?,
) {
    init {
        require(deviceId.isNotBlank())
        require(macId.isNotBlank())
        require(macDisplayName.isNotBlank())
        require(relayWssUrl.startsWith("wss://"))
        require(routeId.isNotBlank())
        require(deviceRouteKeyId.isNotBlank())
        require(certificateSerial.isNotBlank())
        require(certificateNotAfterEpochMillis >= 0)
    }
}

/** Durable paired-profile boundary; coordinator code depends on this, not the storage mechanism. */
interface ProfileStore {
    fun load(): PairedProfile?
    fun save(profile: PairedProfile)
    fun delete()
}

/**
 * Keystore-wrapped, atomically written paired-profile file under noBackupFilesDir.
 */
class PairedProfileStore(context: Context) : ProfileStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "paired-profile.bin"))
    private val lock = Any()

    override fun load(): PairedProfile? = synchronized(lock) {
        val envelope = runCatching { file.readFully() }.getOrNull() ?: return null
        if (envelope.size < GCM_IV_BYTES + GCM_TAG_BYTES) {
            delete()
            return null
        }
        val plaintext = try {
            decrypt(envelope)
        } catch (_: Exception) {
            delete()
            return null
        } finally {
        }
        try {
            parse(String(plaintext, Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    override fun save(profile: PairedProfile): Unit = synchronized(lock) {
        val plaintext = serialize(profile).encodeToByteArray()
        try {
            writeAtomically(encrypt(plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    override fun delete(): Unit = synchronized(lock) {
        file.delete()
        File(file.baseFile.path + ".bak").delete()
    }

    private fun writeAtomically(bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
            runCatching { android.system.Os.chmod(file.baseFile.path, 0x180) }
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        // AndroidKeyStore GCM keys forbid caller-provided IVs; the cipher generates one.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES)
        return iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        val iv = envelope.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(envelope.copyOfRange(GCM_IV_BYTES, envelope.size))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun serialize(profile: PairedProfile): String = buildJsonObject {
        put("version", 1)
        put("deviceId", profile.deviceId)
        put("macId", profile.macId)
        put("macDisplayName", profile.macDisplayName)
        put("relayWssUrl", profile.relayWssUrl)
        put("routeId", profile.routeId)
        put("deviceRouteKeyId", profile.deviceRouteKeyId)
        put(
            "directCandidates",
            JsonArray(
                profile.directCandidates.map { candidate ->
                    buildJsonObject {
                        put("host", candidate.host)
                        put("port", candidate.port)
                    }
                },
            ),
        )
        put("caCertificatePem", profile.caCertificatePem)
        put("certificateSerial", profile.certificateSerial)
        put("certificateNotAfterEpochMillis", profile.certificateNotAfterEpochMillis)
        put("endpointId", profile.endpointId?.let(::JsonPrimitive) ?: JsonPrimitive(null))
    }.toString()

    private fun parse(text: String): PairedProfile? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        fun required(field: String): String? = (root[field] as? JsonPrimitive)?.contentOrNull
        if ((root["version"] as? JsonPrimitive)?.intOrNullCompat() != 1) return null
        val candidates = (root["directCandidates"] as? JsonArray)?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val host = (obj["host"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val port = (obj["port"] as? JsonPrimitive)?.intOrNullCompat() ?: return@mapNotNull null
            DirectCandidate(host, port)
        } ?: return null
        return PairedProfile(
            deviceId = required("deviceId") ?: return null,
            macId = required("macId") ?: return null,
            macDisplayName = required("macDisplayName") ?: return null,
            relayWssUrl = required("relayWssUrl") ?: return null,
            routeId = required("routeId") ?: return null,
            deviceRouteKeyId = required("deviceRouteKeyId") ?: return null,
            directCandidates = candidates,
            caCertificatePem = required("caCertificatePem") ?: return null,
            certificateSerial = required("certificateSerial") ?: return null,
            certificateNotAfterEpochMillis = (root["certificateNotAfterEpochMillis"] as? JsonPrimitive)?.longOrNullCompat() ?: return null,
            endpointId = (root["endpointId"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    companion object {
        private const val KEY_ALIAS = "pimobile-profile-wrap-v1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    }
}

/** Derives the Mac instanceId from the paired CA certificate subject CN ("Pi Mobile Local CA <uuid>"). */
object MacIdentityDeriver {
    private val CN_UUID = Regex("^Pi Mobile Local CA ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$")

    fun deriveMacId(caCertificate: java.security.cert.X509Certificate): String {
        val cn = caCertificate.subjectX500Principal.name
            .split(',')
            .firstOrNull { it.startsWith("CN=") }
            ?.removePrefix("CN=")
            ?: throw IllegalArgumentException("paired CA certificate has no subject CN")
        return CN_UUID.matchEntire(cn)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("paired CA certificate CN does not carry a Mac instance id")
    }

    fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}

private fun JsonPrimitive.intOrNullCompat(): Int? = runCatching { int }.getOrNull()

private fun JsonPrimitive.longOrNullCompat(): Long? = runCatching { long }.getOrNull()
