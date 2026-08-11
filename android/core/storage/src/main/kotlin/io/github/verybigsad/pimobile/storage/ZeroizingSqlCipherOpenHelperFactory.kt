package io.github.verybigsad.pimobile.storage

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal class ZeroizingSqlCipherOpenHelperFactory(key: ByteArray) : SupportSQLiteOpenHelper.Factory {
    private val passphrase = key.copyOf()
    private var helperCreated = false

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        check(!helperCreated)
        helperCreated = true
        val delegate = try {
            SupportOpenHelperFactory(passphrase, null, true).create(configuration)
        } catch (error: Exception) {
            clear()
            throw error
        }
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String?
                get() = delegate.databaseName

            override val writableDatabase: SupportSQLiteDatabase
                get() = delegate.writableDatabase

            override val readableDatabase: SupportSQLiteDatabase
                get() = delegate.readableDatabase

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                delegate.setWriteAheadLoggingEnabled(enabled)
            }

            override fun close() {
                try {
                    delegate.close()
                } finally {
                    clear()
                }
            }
        }
    }

    fun clear() {
        passphrase.fill(0)
    }
}
