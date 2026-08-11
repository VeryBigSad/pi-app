package io.github.verybigsad.pimobile.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftTrustSurvivalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clean() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun draftsTrustStateAndUint64CursorsSurviveProcessDeath() = runBlocking {
        val session = CacheQuotaTest.session("session", updatedAt = 1).copy(
            canonicalCursor = CanonicalAppendCursor(
                streamEpoch = "epoch-1",
                sequence = "9223372036854775808",
                leafId = "7fa3c91e",
                lastAppendId = "append-18446744073709551615",
            ),
        )
        val orders = listOf("0", "9223372036854775808", "18446744073709551615")
        val draft = DraftEntity(
            sessionId = "session",
            typedText = "typed",
            transcriptionText = "transcribed",
            revision = 7,
            updatedAtEpochMs = 9,
        )
        val trusted = CacheQuotaTest.trusted("mac-1")
        val revoked = TrustStateEntity(
            macId = "mac-2",
            status = StoredTrustStatus.REVOKED,
            displayName = null,
            certificateSerial = null,
            certificateNotAfterEpochMs = null,
            revokedAtEpochMs = 12,
            revocationReasonCode = "user-request",
            updatedAtEpochMs = 12,
        )

        openDatabase().let { database ->
            try {
                database.dao().replaceSessionSnapshot(session, orders.map { CacheQuotaTest.message("session", it) })
                database.dao().upsertDraft(draft)
                database.dao().upsertTrustState(trusted)
                database.dao().upsertTrustState(revoked)
            } finally {
                database.close()
            }
        }

        // Reopening a new database instance simulates a process restart.
        openDatabase().let { database ->
            try {
                assertThat(database.dao().session("session")).isEqualTo(session)
                assertThat(database.dao().messages("session").map { it.appendOrder })
                    .containsExactly("0", "9223372036854775808", "18446744073709551615")
                    .inOrder()
                assertThat(database.dao().draft("session")).isEqualTo(draft)
                assertThat(database.dao().trustState("mac-1")).isEqualTo(trusted)
                assertThat(database.dao().trustState("mac-2")).isEqualTo(revoked)
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun draftUpdatesSurviveIndependentOfSyncDataWipe() = runBlocking {
        openDatabase().let { database ->
            try {
                database.dao().replaceSessionSnapshot(
                    CacheQuotaTest.session("session", updatedAt = 1),
                    listOf(CacheQuotaTest.message("session", 1)),
                )
                database.dao().upsertDraft(DraftEntity("session", "keep me", null, 1, 2))
                database.dao().clearAll()
                database.dao().upsertDraft(DraftEntity("session", "keep me", "voice", 2, 3))
            } finally {
                database.close()
            }
        }

        openDatabase().let { database ->
            try {
                assertThat(database.dao().draft("session")).isEqualTo(
                    DraftEntity("session", "keep me", "voice", 2, 3),
                )
            } finally {
                database.close()
            }
        }
    }

    private fun openDatabase(): PiMobileDatabase = Room.databaseBuilder(
        context,
        PiMobileDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(StorageMigrations.MIGRATION_1_2, StorageMigrations.MIGRATION_2_3).build()

    private companion object {
        const val DATABASE_NAME = "draft-trust-survival-test.db"
    }
}
