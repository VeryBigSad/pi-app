package io.github.verybigsad.pimobile.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CacheKeyMaterialInvalidException(cause: Throwable? = null) : Exception(null, cause)

internal class CacheKeyStorageException(cause: Throwable) : Exception(null, cause)

internal class CacheKeyStore(context: Context) {
    private val keyFile = AtomicFile(context.noBackupFilesDir.resolve(KEY_FILE))
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    fun hasEnvelope(): Boolean = keyFile.existsIncludingBackup()

    fun loadOrCreate(): ByteArray {
        if (hasEnvelope()) return loadExisting()
        return create()
    }

    fun invalidate() {
        try {
            keyFile.delete()
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            if (hasEnvelope() || keyStore.containsAlias(KEY_ALIAS)) throw IOException()
        } catch (error: Exception) {
            throw CacheKeyStorageException(error)
        }
    }

    private fun loadExisting(): ByteArray {
        val envelope = try {
            keyFile.readBytesAtomically()
        } catch (error: IOException) {
            throw CacheKeyStorageException(error)
        }
        try {
            if (envelope.size != ENVELOPE_SIZE || !envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                throw CacheKeyMaterialInvalidException()
            }
            val wrappingKey = existingWrappingKey() ?: throw CacheKeyMaterialInvalidException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey,
                GCMParameterSpec(GCM_TAG_BITS, envelope.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)),
            )
            cipher.updateAAD(AAD)
            return cipher.doFinal(envelope, MAGIC.size + IV_SIZE, envelope.size - MAGIC.size - IV_SIZE).also { key ->
                if (key.size != DATABASE_KEY_SIZE) {
                    key.fill(0)
                    throw CacheKeyMaterialInvalidException()
                }
            }
        } catch (error: CacheKeyMaterialInvalidException) {
            throw error
        } catch (error: Exception) {
            if (error.isInvalidatedKeyMaterial()) throw CacheKeyMaterialInvalidException(error)
            throw CacheKeyStorageException(error)
        } finally {
            envelope.fill(0)
        }
    }

    private fun create(): ByteArray {
        val databaseKey = ByteArray(DATABASE_KEY_SIZE).also(SecureRandom()::nextBytes)
        var createdWrappingKey = false
        try {
            val wrappingKey = existingWrappingKey() ?: generateWrappingKey().also { createdWrappingKey = true }
            val envelope = wrap(databaseKey, wrappingKey)
            try {
                keyFile.writeBytesAtomically(envelope)
            } finally {
                envelope.fill(0)
            }
            return databaseKey
        } catch (error: Exception) {
            databaseKey.fill(0)
            if (createdWrappingKey) runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            if (error is CacheKeyStorageException) throw error
            throw CacheKeyStorageException(error)
        }
    }

    private fun wrap(value: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        if (iv.size != IV_SIZE) throw CacheKeyStorageException(IllegalStateException())
        val encrypted = cipher.doFinal(value)
        return ByteArray(ENVELOPE_SIZE).also { envelope ->
            MAGIC.copyInto(envelope)
            iv.copyInto(envelope, MAGIC.size)
            encrypted.copyInto(envelope, MAGIC.size + IV_SIZE)
            encrypted.fill(0)
        }
    }

    private fun existingWrappingKey(): SecretKey? {
        if (!keyStore.containsAlias(KEY_ALIAS)) return null
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: throw CacheKeyMaterialInvalidException()
    }

    private fun generateWrappingKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(WRAPPING_KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }

    private fun Throwable.isInvalidatedKeyMaterial(): Boolean =
        generateSequence(this) { it.cause }.any { cause ->
            cause is AEADBadTagException ||
                cause is InvalidKeyException ||
                cause is KeyPermanentlyInvalidatedException ||
                cause is UnrecoverableKeyException
        }

    internal companion object {
        const val KEY_ALIAS = "io.github.verybigsad.pimobile.cache.wrap.v1"
        const val KEY_FILE = "cache-key.v1"
        const val DATABASE_KEY_SIZE = 32
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val WRAPPING_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val IV_SIZE = 12
        private const val ENVELOPE_SIZE = 4 + IV_SIZE + DATABASE_KEY_SIZE + (GCM_TAG_BITS / 8)
        private val MAGIC = byteArrayOf(0x50, 0x4d, 0x4b, 0x31)
        private val AAD = "io.github.verybigsad.pimobile.cache.v1".encodeToByteArray()
    }
}
