package io.github.verybigsad.pimobile.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clean() {
        EncryptedCache.wipe(context)
    }

    @Test
    fun cacheIsEncryptedPersistentAndPreservesCanonicalFinalState() = runBlocking {
        EncryptedCache.wipe(context)
        val opened = EncryptedCache.open(context)
        val session = session("session-1")
        val message = message("session-1")
        opened.database.dao().replaceSessionSnapshot(session, listOf(message))
        opened.database.close()

        val header = ByteArray(SQLITE_HEADER.size)
        context.getDatabasePath(EncryptedCache.DATABASE_NAME).inputStream().use { input ->
            assertThat(input.read(header)).isEqualTo(header.size)
        }
        assertThat(header.contentEquals(SQLITE_HEADER)).isFalse()

        val reopened = EncryptedCache.open(context)
        assertThat(reopened.canonicalResync).isNull()
        assertThat(reopened.database.dao().session("session-1")).isEqualTo(session)
        assertThat(reopened.database.dao().messages("session-1")).containsExactly(message)
        reopened.database.close()
    }

    @Test
    fun corruptCacheIsWipedAndSignalsCanonicalResyncUntilAcknowledged() = runBlocking {
        EncryptedCache.wipe(context)
        EncryptedCache.open(context).database.close()
        FileOutputStream(context.getDatabasePath(EncryptedCache.DATABASE_NAME)).use { output ->
            output.write(ByteArray(4_096) { 0x5a })
            output.fd.sync()
        }

        val recovered = EncryptedCache.open(context)
        val signal = checkNotNull(recovered.canonicalResync)
        assertThat(signal.reason).isEqualTo(CacheResetReason.DATABASE_UNREADABLE)
        assertThat(signal.generation).hasLength(32)
        assertThat(recovered.database.dao().sessionCount()).isEqualTo(0)
        recovered.database.close()

        val reopened = EncryptedCache.open(context)
        assertThat(reopened.canonicalResync).isEqualTo(signal)
        assertThat(EncryptedCache.acknowledgeCanonicalResync(context, signal)).isTrue()
        assertThat(EncryptedCache.acknowledgeCanonicalResync(context, signal)).isFalse()
        assertThat(EncryptedCache.open(context).canonicalResync).isNull()
    }

    @Test
    fun deletedWrappingKeyWipesCacheAndSignalsCanonicalResyncOnApi29() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT == 29)
        EncryptedCache.wipe(context)
        val opened = EncryptedCache.open(context)
        opened.database.dao().upsertSession(session("session-key-loss"))
        opened.database.close()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(CacheKeyStore.KEY_ALIAS)
        }

        val recovered = EncryptedCache.open(context)
        assertThat(recovered.resetReason).isEqualTo(CacheResetReason.KEY_UNAVAILABLE)
        assertThat(recovered.database.dao().sessionCount()).isEqualTo(0)
        recovered.database.close()
    }

    @Test
    fun schemaDowngradeFailureDoesNotWipe() {
        EncryptedCache.wipe(context)
        val opened = EncryptedCache.open(context)
        opened.database.openHelper.writableDatabase.version = 99
        opened.database.close()
        val database = context.getDatabasePath(EncryptedCache.DATABASE_NAME)
        val envelope = context.noBackupFilesDir.resolve(CacheKeyStore.KEY_FILE)
        assertThat(database.exists()).isTrue()
        assertThat(envelope.exists()).isTrue()

        val failure = assertThrows(CacheOpenException::class.java) { EncryptedCache.open(context) }
        assertThat(failure.code).isEqualTo(CacheFailureCode.DATABASE_OPEN_FAILED)
        assertThat(database.exists()).isTrue()
        assertThat(envelope.exists()).isTrue()
    }

    @Test
    fun concurrentOpenReturnsOneProcessSingleton() {
        EncryptedCache.wipe(context)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val databases = executor.invokeAll(
                List(32) { index ->
                    Callable {
                        EncryptedCache.open(context).database.also { database ->
                            runBlocking { database.dao().upsertSession(session("concurrent-$index")) }
                        }
                    }
                },
            ).map { future -> future.get() }
            databases.forEach { database -> assertSame(databases.first(), database) }
            runBlocking { assertThat(databases.first().dao().sessionCount()).isEqualTo(32) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun wipeRemovesDatabaseWalShmAndJournal() {
        EncryptedCache.wipe(context)
        EncryptedCache.open(context).database.close()
        val database = context.getDatabasePath(EncryptedCache.DATABASE_NAME)
        val artifacts = listOf(
            database,
            database.resolveSibling("${database.name}-wal"),
            database.resolveSibling("${database.name}-shm"),
            database.resolveSibling("${database.name}-journal"),
        )
        artifacts.drop(1).forEach { file -> file.writeBytes(byteArrayOf(1, 2, 3)) }

        EncryptedCache.wipe(context)

        assertThat(artifacts.filter { it.exists() }).isEmpty()
    }

    @Test
    fun keyEnvelopeAndDatabaseAreOwnerOnlyAndExcludedFromBackup() {
        EncryptedCache.wipe(context)
        EncryptedCache.open(context).database.close()
        val envelope = context.noBackupFilesDir.resolve(CacheKeyStore.KEY_FILE)
        val database = context.getDatabasePath(EncryptedCache.DATABASE_NAME)

        assertThat(envelope.parentFile).isEqualTo(context.noBackupFilesDir)
        assertThat(envelope.readBytes()).hasLength(64)
        assertThat(envelope.resolveSibling("${envelope.name}.bak").exists()).isFalse()
        assertThat(Os.stat(envelope.path).st_mode and MODE_MASK).isEqualTo(OWNER_READ_WRITE)
        assertThat(Os.stat(database.path).st_mode and MODE_MASK).isEqualTo(OWNER_READ_WRITE)
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP).isEqualTo(0)

        val wrappingKey = KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            getKey(CacheKeyStore.KEY_ALIAS, null) as SecretKey
        }
        val keyInfo = SecretKeyFactory.getInstance(wrappingKey.algorithm, "AndroidKeyStore")
            .getKeySpec(wrappingKey, KeyInfo::class.java) as KeyInfo
        assertThat(keyInfo.keySize).isEqualTo(256)
        assertThat(keyInfo.blockModes.asList()).containsExactly(KeyProperties.BLOCK_MODE_GCM)
        assertThat(keyInfo.encryptionPaddings.asList()).containsExactly(KeyProperties.ENCRYPTION_PADDING_NONE)
    }

    @Test
    fun writeAheadLoggingIsExplicitlyEnabled() {
        EncryptedCache.wipe(context)
        val database = EncryptedCache.open(context).database.openHelper.writableDatabase
        database.query("PRAGMA journal_mode").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0).lowercase()).isEqualTo("wal")
        }
    }

    private fun session(id: String) = SessionEntity(
        sessionId = id,
        cwd = "/tmp/project",
        displayName = null,
        provider = "openai",
        modelId = "model",
        thinkingLevel = "medium",
        canonicalCursor = CanonicalAppendCursor(
            streamEpoch = "epoch-1",
            sequence = "7",
            leafId = "7fa3c91e",
            lastAppendId = "append-1",
        ),
        updatedAtEpochMs = 1,
        repositoryPath = "/tmp/repository",
        worktreePath = "/tmp/repository/.worktrees/feature",
        parentSessionId = "parent-session",
    )

    private fun message(sessionId: String): MessageEntity {
        val raw = """{"type":"assistant","signature":"final","redacted":true}"""
        return MessageEntity(
            sessionId = sessionId,
            messageId = "message-1",
            parentId = null,
            appendOrder = "1",
            appendId = "append-1",
            role = StoredMessageRole.ASSISTANT,
            state = FinalizedMessageState.FINALIZED,
            contentJson = """[{"kind":"text","text":"authoritative"}]""",
            authoritativeFinal = AuthoritativeFinalMetadata(
                source = FinalMetadataSource.AUTHORITATIVE,
                rawJson = raw,
                rawRef = null,
                rawSizeBytes = raw.encodeToByteArray().size.toLong(),
                rawSha256 = sha256(raw),
                projectionJson = """{"type":"assistant"}""",
                signature = "final",
                redacted = true,
                createdAtEpochMs = 1,
                finalizedAtEpochMs = 2,
            ),
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MODE_MASK = 0x1ff
        const val OWNER_READ_WRITE = 0x180
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}
