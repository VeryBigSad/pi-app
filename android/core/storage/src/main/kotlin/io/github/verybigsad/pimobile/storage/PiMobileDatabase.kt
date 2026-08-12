package io.github.verybigsad.pimobile.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        CommandReceiptEntity::class,
        DraftEntity::class,
        TrustStateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(StorageTypeConverters::class)
abstract class PiMobileDatabase : RoomDatabase() {
    abstract fun dao(): PiMobileDao
}
