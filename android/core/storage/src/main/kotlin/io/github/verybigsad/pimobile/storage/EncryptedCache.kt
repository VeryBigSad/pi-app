package io.github.verybigsad.pimobile.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabaseCorruptException
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File
import java.io.IOException
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

enum class CacheResetReason(internal val code: Byte) {
    KEY_UNAVAILABLE(1),
    DATABASE_UNREADABLE(2),
}

data class CanonicalResyncSignal(
    val generation: String,
    val reason: CacheResetReason,
)

data class OpenedCache(
    val database: PiMobileDatabase,
    val canonicalResync: CanonicalResyncSignal?,
) {
    val resetReason: CacheResetReason?
        get() = canonicalResync?.reason
}

enum class CacheFailureCode {
    NATIVE_LIBRARY_UNAVAILABLE,
    KEYSTORE_UNAVAILABLE,
    STORAGE_IO,
    DATABASE_OPEN_FAILED,
    WIPE_FAILED,
}

class CacheOpenException(
    val code: CacheFailureCode,
    cause: Throwable? = null,
) : IllegalStateException("Encrypted cache unavailable: ${code.name}", cause)

private class CacheDatabaseCorruptException : Exception()

object EncryptedCache {
    internal const val DATABASE_NAME = "pi-mobile-cache.db"

    private var nativeLoaded = false
    private var singleton: PiMobileDatabase? = null
    private var singletonDatabasePath: String? = null
    private var singletonSignal: CanonicalResyncSignal? = null

    @Synchronized
    fun open(context: Context): OpenedCache {
        val applicationContext = context.applicationContext
        val databasePath = applicationContext.getDatabasePath(DATABASE_NAME).absolutePath
        singleton?.takeIf { it.isOpen && singletonDatabasePath == databasePath }?.let { database ->
            return OpenedCache(database, singletonSignal)
        }
        closeSingleton()
        loadNativeLibrary()
        return withProcessLock(applicationContext) {
            openLocked(applicationContext).also { opened ->
                singleton = opened.database
                singletonDatabasePath = databasePath
                singletonSignal = opened.canonicalResync
            }
        }
    }

    @Synchronized
    fun acknowledgeCanonicalResync(context: Context, signal: CanonicalResyncSignal): Boolean {
        val applicationContext = context.applicationContext
        return withProcessLock(applicationContext) {
            try {
                CacheResetStore(applicationContext).acknowledge(signal).also { acknowledged ->
                    if (acknowledged && singletonSignal == signal) singletonSignal = null
                }
            } catch (error: CacheResetStorageException) {
                throw CacheOpenException(CacheFailureCode.STORAGE_IO, error)
            }
        }
    }

    @Synchronized
    fun wipe(context: Context) {
        val applicationContext = context.applicationContext
        withProcessLock(applicationContext) {
            closeSingleton()
            try {
                deleteDatabaseArtifacts(applicationContext)
                CacheKeyStore(applicationContext).invalidate()
                CacheResetStore(applicationContext).clear()
            } catch (error: Exception) {
                throw CacheOpenException(CacheFailureCode.WIPE_FAILED, error)
            }
        }
    }

    private fun openLocked(context: Context): OpenedCache {
        val resets = CacheResetStore(context)
        val resetState = try {
            resets.load()
        } catch (_: CacheResetStateInvalidException) {
            return recover(context, resets.begin(CacheResetReason.DATABASE_UNREADABLE))
        } catch (error: CacheResetStorageException) {
            throw CacheOpenException(CacheFailureCode.STORAGE_IO, error)
        }
        if (resetState?.phase == CacheResetPhase.RECOVERING) return recover(context, resetState)

        val keys = try {
            CacheKeyStore(context)
        } catch (error: Exception) {
            throw CacheOpenException(CacheFailureCode.KEYSTORE_UNAVAILABLE, error)
        }
        val artifacts = databaseArtifacts(context)
        val databaseExists = artifacts.first().exists()
        val sidecarExists = artifacts.drop(1).any(File::exists)
        val envelopeExists = keys.hasEnvelope()
        if (databaseExists && !envelopeExists) {
            return recover(context, resets.begin(CacheResetReason.KEY_UNAVAILABLE))
        }
        if (!databaseExists && (envelopeExists || sidecarExists)) {
            return recover(context, resets.begin(CacheResetReason.DATABASE_UNREADABLE))
        }

        val key = try {
            keys.loadOrCreate()
        } catch (_: CacheKeyMaterialInvalidException) {
            return recover(context, resets.begin(CacheResetReason.KEY_UNAVAILABLE))
        } catch (error: CacheKeyStorageException) {
            throw CacheOpenException(CacheFailureCode.KEYSTORE_UNAVAILABLE, error)
        }
        val database = try {
            openDatabase(context, key)
        } catch (error: Exception) {
            if (error.isRecoverableDatabaseFailure()) {
                return recover(context, resets.begin(CacheResetReason.DATABASE_UNREADABLE))
            }
            throw CacheOpenException(CacheFailureCode.DATABASE_OPEN_FAILED, error)
        }
        return OpenedCache(database, resetState?.signal)
    }

    private fun recover(context: Context, state: CacheResetState): OpenedCache {
        val resets = CacheResetStore(context)
        closeSingleton()
        try {
            deleteDatabaseArtifacts(context)
            val keys = CacheKeyStore(context)
            keys.invalidate()
            val key = keys.loadOrCreate()
            val database = try {
                openDatabase(context, key)
            } catch (error: Exception) {
                throw CacheOpenException(CacheFailureCode.DATABASE_OPEN_FAILED, error)
            }
            val ready = try {
                resets.markReady(state)
            } catch (error: Exception) {
                database.close()
                throw CacheOpenException(CacheFailureCode.STORAGE_IO, error)
            }
            return OpenedCache(database, ready.signal)
        } catch (error: CacheOpenException) {
            throw error
        } catch (error: CacheKeyStorageException) {
            throw CacheOpenException(CacheFailureCode.KEYSTORE_UNAVAILABLE, error)
        } catch (error: Exception) {
            throw CacheOpenException(CacheFailureCode.WIPE_FAILED, error)
        }
    }

    private fun openDatabase(context: Context, key: ByteArray): PiMobileDatabase {
        var database: PiMobileDatabase? = null
        val factory = ZeroizingSqlCipherOpenHelperFactory(key)
        try {
            database = Room.databaseBuilder(context, PiMobileDatabase::class.java, DATABASE_NAME)
                .openHelperFactory(factory)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    StorageMigrations.MIGRATION_1_2,
                    StorageMigrations.MIGRATION_2_3,
                    StorageMigrations.MIGRATION_3_4,
                )
                .enableMultiInstanceInvalidation()
                .build()
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA quick_check(1)").use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                    throw CacheDatabaseCorruptException()
                }
            }
            databaseArtifacts(context).filter(File::exists).forEach(::restrictOwnerOnly)
            return database
        } catch (error: Exception) {
            database?.close()
            factory.clear()
            throw error
        } finally {
            key.fill(0)
        }
    }

    private fun Throwable.isRecoverableDatabaseFailure(): Boolean =
        generateSequence(this) { it.cause }.any { cause ->
            cause is SQLiteNotADatabaseException ||
                cause is SQLiteDatabaseCorruptException ||
                cause is CacheDatabaseCorruptException
        }

    private fun deleteDatabaseArtifacts(context: Context) {
        val artifacts = databaseArtifacts(context)
        runCatching { context.deleteDatabase(DATABASE_NAME) }.getOrElse { throw IOException(it) }
        artifacts.forEach { file ->
            if (file.exists() && !file.delete()) throw IOException()
        }
        if (artifacts.any(File::exists)) throw IOException()
    }

    private fun databaseArtifacts(context: Context): List<File> {
        val database = context.getDatabasePath(DATABASE_NAME)
        return listOf(
            database,
            database.resolveSibling("${database.name}-wal"),
            database.resolveSibling("${database.name}-shm"),
            database.resolveSibling("${database.name}-journal"),
        )
    }

    private fun <T> withProcessLock(context: Context, block: () -> T): T = try {
        CacheProcessLock(context).exclusive(block)
    } catch (error: CacheOpenException) {
        throw error
    } catch (error: Exception) {
        throw CacheOpenException(CacheFailureCode.STORAGE_IO, error)
    }

    private fun loadNativeLibrary() {
        if (nativeLoaded) return
        try {
            System.loadLibrary("sqlcipher")
            nativeLoaded = true
        } catch (error: UnsatisfiedLinkError) {
            throw CacheOpenException(CacheFailureCode.NATIVE_LIBRARY_UNAVAILABLE, error)
        }
    }

    private fun closeSingleton() {
        singleton?.takeIf(PiMobileDatabase::isOpen)?.close()
        singleton = null
        singletonDatabasePath = null
        singletonSignal = null
    }
}
