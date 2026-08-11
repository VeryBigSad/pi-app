package io.github.verybigsad.pimobile.storage

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PiMobileDatabase::class.java,
    )

    @Test
    fun migration1To3PreservesRowsWithoutInventingCanonicalCursor() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO `sessions` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `lastAppendId`, `leafId`, `lastSequence`, `updatedAtEpochMs`
                ) VALUES ('session-1', '/tmp/project', NULL, 'openai', 'model', 'medium',
                    'append-old', '7fa3c91e', 9, 10)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `messages` (
                    `sessionId`, `messageId`, `parentId`, `appendOrder`, `role`, `status`,
                    `contentJson`, `signature`, `timestampEpochMs`
                ) VALUES ('session-1', 'message-1', NULL, 1, 'assistant', 'final',
                    '{"text":"preserved"}', 'signature', 11)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `command_receipts` (
                    `commandId`, `sessionId`, `state`, `resultDigest`, `updatedAtEpochMs`
                ) VALUES ('command-1', 'session-1', 'acked', NULL, 12)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            StorageMigrations.MIGRATION_1_2,
            StorageMigrations.MIGRATION_2_3,
        ).use { database ->
            database.query(
                """
                SELECT `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                    `canonical_last_append_id`
                FROM `sessions`
                WHERE `sessionId` = 'session-1'
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.isNull(0)).isTrue()
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.isNull(2)).isTrue()
                assertThat(cursor.isNull(3)).isTrue()
            }
            database.query(
                """
                SELECT `contentJson`, `final_source`, `final_raw_json`, `final_raw_sha256`,
                    `final_signature`, `final_finalized_at_epoch_ms`, `appendOrder`
                FROM `messages`
                WHERE `messageId` = 'message-1'
                """.trimIndent(),
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("{\"text\":\"preserved\"}")
                assertThat(cursor.getString(1)).isEqualTo(FinalMetadataSource.LEGACY_V1.name)
                assertThat(cursor.getString(2)).isEqualTo("{\"text\":\"preserved\"}")
                assertThat(cursor.getString(3)).matches("^[0-9a-f]{64}$")
                assertThat(cursor.getString(4)).isEqualTo("signature")
                assertThat(cursor.getLong(5)).isEqualTo(11)
                assertThat(cursor.getString(6)).isEqualTo("1")
            }
            database.query("SELECT `state` FROM `command_receipts` WHERE `commandId` = 'command-1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo(CommandReceiptState.ACKED.name)
            }
        }
    }

    @Test
    fun migration2To3ConvertsUint64ColumnsToCanonicalDecimalText() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO `sessions` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                    `canonical_last_append_id`, `updatedAtEpochMs`
                ) VALUES ('session-1', '/tmp/project', NULL, 'openai', 'model', 'medium',
                    'epoch-1', 9223372036854775807, '7fa3c91e', 'append-1', 10)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO `messages` (
                    `sessionId`, `messageId`, `parentId`, `appendOrder`, `appendId`, `role`,
                    `state`, `contentJson`, `final_source`, `final_raw_json`, `final_raw_ref`,
                    `final_raw_size_bytes`, `final_raw_sha256`, `final_projection_json`,
                    `final_signature`, `final_redacted`, `final_created_at_epoch_ms`,
                    `final_finalized_at_epoch_ms`
                ) VALUES ('session-1', 'message-1', NULL, 9223372036854775807, 'append-1',
                    'ASSISTANT', 'FINALIZED', '{}', 'AUTHORITATIVE', NULL, 'blob/ref-1', 3,
                    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', '{}',
                    'signature', 0, 11, 12)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            StorageMigrations.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT `canonical_sequence` FROM `sessions` WHERE `sessionId` = 'session-1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("9223372036854775807")
            }
            database.query("SELECT `appendOrder` FROM `messages` WHERE `messageId` = 'message-1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("9223372036854775807")
            }
            database.query(
                "SELECT `sql` FROM `sqlite_master` WHERE `name` IN ('sessions', 'messages')"
            ).use { cursor ->
                val ddl = buildString {
                    while (cursor.moveToNext()) append(cursor.getString(0)).append('\n')
                }
                assertThat(ddl).contains("CHECK")
                assertThat(ddl).contains("18446744073709551615")
            }
            database.query(
                "SELECT `name` FROM `sqlite_master` WHERE `type` = 'table' AND `name` IN ('drafts', 'trust_states')"
            ).use { cursor ->
                val tables = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertThat(tables).containsExactly("drafts", "trust_states")
            }
        }
    }

    @Test
    fun migratedCheckConstraintsRejectNonCanonicalUint64() {
        helper.createDatabase(DATABASE_NAME, 2).apply { close() }
        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            StorageMigrations.MIGRATION_2_3,
        )
        database.execSQL(
            """
            INSERT INTO `sessions` (
                `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                `canonical_last_append_id`, `updatedAtEpochMs`
            ) VALUES ('session-1', '/tmp/project', NULL, 'openai', 'model', 'medium',
                'epoch-1', '18446744073709551615', '7fa3c91e', 'append-1', 10)
            """.trimIndent(),
        )
        database.query("SELECT `canonical_sequence` FROM `sessions` WHERE `sessionId` = 'session-1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("18446744073709551615")
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(
                """
                INSERT INTO `sessions` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                    `canonical_last_append_id`, `updatedAtEpochMs`
                ) VALUES ('session-2', '/tmp/project', NULL, 'openai', 'model', 'medium',
                    'epoch-1', '007', NULL, NULL, 11)
                """.trimIndent(),
            )
        }
        assertThrows(SQLiteConstraintException::class.java) {
            database.execSQL(
                """
                INSERT INTO `sessions` (
                    `sessionId`, `cwd`, `displayName`, `provider`, `modelId`, `thinkingLevel`,
                    `canonical_stream_epoch`, `canonical_sequence`, `canonical_leaf_id`,
                    `canonical_last_append_id`, `updatedAtEpochMs`
                ) VALUES ('session-3', '/tmp/project', NULL, 'openai', 'model', 'medium',
                    'epoch-1', '18446744073709551616', NULL, NULL, 12)
                """.trimIndent(),
            )
        }
        database.close()
    }

    private companion object {
        const val DATABASE_NAME = "storage-migration-test"
    }
}
