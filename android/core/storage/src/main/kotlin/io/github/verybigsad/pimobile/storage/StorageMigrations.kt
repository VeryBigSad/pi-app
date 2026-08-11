package io.github.verybigsad.pimobile.storage

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

private val MIGRATION_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

object StorageMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sessions_v2` (
                    `sessionId` TEXT NOT NULL,
                    `cwd` TEXT NOT NULL,
                    `displayName` TEXT,
                    `provider` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `thinkingLevel` TEXT NOT NULL,
                    `canonical_stream_epoch` TEXT,
                    `canonical_sequence` INTEGER,
                    `canonical_leaf_id` TEXT,
                    `canonical_last_append_id` TEXT,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sessions_v2` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                    `canonical_last_append_id`, `updatedAtEpochMs`
                )
                SELECT `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    NULL, NULL, NULL, NULL, `updatedAtEpochMs`
                FROM `sessions`
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_v2` (
                    `sessionId` TEXT NOT NULL,
                    `messageId` TEXT NOT NULL,
                    `parentId` TEXT,
                    `appendOrder` INTEGER NOT NULL,
                    `appendId` TEXT,
                    `role` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `contentJson` TEXT NOT NULL,
                    `final_source` TEXT NOT NULL,
                    `final_raw_json` TEXT,
                    `final_raw_ref` TEXT,
                    `final_raw_size_bytes` INTEGER NOT NULL,
                    `final_raw_sha256` TEXT NOT NULL,
                    `final_projection_json` TEXT NOT NULL,
                    `final_signature` TEXT,
                    `final_redacted` INTEGER NOT NULL,
                    `final_created_at_epoch_ms` INTEGER NOT NULL,
                    `final_finalized_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `messageId`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            migrateMessages(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `command_receipts_v2` (
                    `commandId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `resultDigest` TEXT,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`commandId`)
                )
                """.trimIndent(),
            )
            migrateCommandReceipts(db)
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("DROP TABLE `sessions`")
            db.execSQL("DROP TABLE `command_receipts`")
            db.execSQL("ALTER TABLE `sessions_v2` RENAME TO `sessions`")
            db.execSQL("ALTER TABLE `messages_v2` RENAME TO `messages`")
            db.execSQL("ALTER TABLE `command_receipts_v2` RENAME TO `command_receipts`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_sessionId_appendOrder` " +
                    "ON `messages` (`sessionId`, `appendOrder`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_sessionId_appendId` " +
                    "ON `messages` (`sessionId`, `appendId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_command_receipts_sessionId` " +
                    "ON `command_receipts` (`sessionId`)",
            )
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Protocol uint64 columns become canonical decimal TEXT with CHECK constraints.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `sessions_v3` (
                    `sessionId` TEXT NOT NULL,
                    `cwd` TEXT NOT NULL,
                    `displayName` TEXT,
                    `provider` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `thinkingLevel` TEXT NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `canonical_stream_epoch` TEXT,
                    `canonical_sequence` TEXT CHECK (${canonicalUint64Check("canonical_sequence", nullable = true)}),
                    `canonical_leaf_id` TEXT,
                    `canonical_last_append_id` TEXT,
                    PRIMARY KEY(`sessionId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `sessions_v3` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `updatedAtEpochMs`, `canonical_stream_epoch`, `canonical_sequence`,
                    `canonical_leaf_id`, `canonical_last_append_id`
                )
                SELECT `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `updatedAtEpochMs`, `canonical_stream_epoch`, CAST(`canonical_sequence` AS TEXT),
                    `canonical_leaf_id`, `canonical_last_append_id`
                FROM `sessions`
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_v3` (
                    `sessionId` TEXT NOT NULL,
                    `messageId` TEXT NOT NULL,
                    `parentId` TEXT,
                    `appendOrder` TEXT NOT NULL CHECK (${canonicalUint64Check("appendOrder", nullable = false)}),
                    `appendId` TEXT,
                    `role` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `contentJson` TEXT NOT NULL,
                    `final_source` TEXT NOT NULL,
                    `final_raw_json` TEXT,
                    `final_raw_ref` TEXT,
                    `final_raw_size_bytes` INTEGER NOT NULL,
                    `final_raw_sha256` TEXT NOT NULL,
                    `final_projection_json` TEXT NOT NULL,
                    `final_signature` TEXT,
                    `final_redacted` INTEGER NOT NULL,
                    `final_created_at_epoch_ms` INTEGER NOT NULL,
                    `final_finalized_at_epoch_ms` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `messageId`),
                    FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `messages_v3` (
                    `sessionId`, `messageId`, `parentId`, `appendOrder`, `appendId`, `role`,
                    `state`, `contentJson`, `final_source`, `final_raw_json`, `final_raw_ref`,
                    `final_raw_size_bytes`, `final_raw_sha256`, `final_projection_json`,
                    `final_signature`, `final_redacted`, `final_created_at_epoch_ms`,
                    `final_finalized_at_epoch_ms`
                )
                SELECT `sessionId`, `messageId`, `parentId`, CAST(`appendOrder` AS TEXT), `appendId`, `role`,
                    `state`, `contentJson`, `final_source`, `final_raw_json`, `final_raw_ref`,
                    `final_raw_size_bytes`, `final_raw_sha256`, `final_projection_json`,
                    `final_signature`, `final_redacted`, `final_created_at_epoch_ms`,
                    `final_finalized_at_epoch_ms`
                FROM `messages`
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `drafts` (
                    `sessionId` TEXT NOT NULL,
                    `typedText` TEXT NOT NULL,
                    `transcriptionText` TEXT,
                    `revision` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `trust_states` (
                    `macId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `displayName` TEXT,
                    `certificateSerial` TEXT,
                    `certificateNotAfterEpochMs` INTEGER,
                    `revokedAtEpochMs` INTEGER,
                    `revocationReasonCode` TEXT,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`macId`)
                )
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `messages`")
            db.execSQL("DROP TABLE `sessions`")
            db.execSQL("ALTER TABLE `sessions_v3` RENAME TO `sessions`")
            db.execSQL("ALTER TABLE `messages_v3` RENAME TO `messages`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_sessionId_appendOrder` " +
                    "ON `messages` (`sessionId`, `appendOrder`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_sessionId_appendId` " +
                    "ON `messages` (`sessionId`, `appendId`)",
            )
        }
    }

    private fun canonicalUint64Check(column: String, nullable: Boolean): String {
        val positive =
            "LENGTH(`$column`) >= 1 AND LENGTH(`$column`) <= 20 " +
                "AND `$column` NOT GLOB '*[^0-9]*' " +
                "AND (`$column` = '0' OR `$column` NOT GLOB '0*') " +
                "AND (LENGTH(`$column`) < 20 OR `$column` <= '$CANONICAL_UINT64_MAX_TEXT')"
        return if (nullable) "`$column` IS NULL OR ($positive)" else positive
    }

    private fun migrateMessages(db: SupportSQLiteDatabase) {
        db.query(
            """
            SELECT `sessionId`, `messageId`, `parentId`, `appendOrder`, `role`, `status`,
                `contentJson`, `signature`, `timestampEpochMs`
            FROM `messages`
            ORDER BY `sessionId`, `appendOrder`
            """.trimIndent(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val contentJson = cursor.string("contentJson")
                val timestamp = cursor.long("timestampEpochMs")
                db.execSQL(
                    """
                    INSERT INTO `messages_v2` (
                        `sessionId`, `messageId`, `parentId`, `appendOrder`, `appendId`, `role`,
                        `state`, `contentJson`, `final_source`, `final_raw_json`, `final_raw_ref`,
                        `final_raw_size_bytes`, `final_raw_sha256`, `final_projection_json`,
                        `final_signature`, `final_redacted`, `final_created_at_epoch_ms`,
                        `final_finalized_at_epoch_ms`
                    ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, 0, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        cursor.string("sessionId"),
                        cursor.string("messageId"),
                        cursor.nullableString("parentId"),
                        cursor.long("appendOrder"),
                        storedRole(cursor.string("role")).name,
                        finalizedState(cursor.string("status")).name,
                        contentJson,
                        FinalMetadataSource.LEGACY_V1.name,
                        contentJson,
                        contentJson.encodeToByteArray().size.toLong(),
                        sha256(contentJson),
                        contentJson,
                        cursor.nullableString("signature"),
                        timestamp,
                        timestamp,
                    ),
                )
            }
        }
    }

    private fun migrateCommandReceipts(db: SupportSQLiteDatabase) {
        db.query(
            "SELECT `commandId`, `sessionId`, `state`, `resultDigest`, `updatedAtEpochMs` FROM `command_receipts`",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val digest = cursor.nullableString("resultDigest")?.takeIf { MIGRATION_SHA256_PATTERN.matches(it) }
                db.execSQL(
                    """
                    INSERT INTO `command_receipts_v2` (
                        `commandId`, `sessionId`, `state`, `resultDigest`, `updatedAtEpochMs`
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        cursor.string("commandId"),
                        cursor.string("sessionId"),
                        commandState(cursor.string("state")).name,
                        digest,
                        cursor.long("updatedAtEpochMs"),
                    ),
                )
            }
        }
    }

    private fun storedRole(value: String): StoredMessageRole =
        StoredMessageRole.entries.singleOrNull { it.name == value.uppercase() } ?: StoredMessageRole.UNKNOWN

    private fun finalizedState(value: String): FinalizedMessageState = when (value.uppercase()) {
        "FINAL", "FINALIZED" -> FinalizedMessageState.FINALIZED
        else -> FinalizedMessageState.LEGACY_UNKNOWN
    }

    private fun commandState(value: String): CommandReceiptState =
        CommandReceiptState.entries.singleOrNull { it.name == value.uppercase() } ?: CommandReceiptState.UNKNOWN

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { byte -> "%02x".format(byte) }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
}
