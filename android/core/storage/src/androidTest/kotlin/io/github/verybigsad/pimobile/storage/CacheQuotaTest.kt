package io.github.verybigsad.pimobile.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheQuotaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clean() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun messageCountQuotaEvictsLeastRecentlyUsedSessionsFirst() = runBlocking {
        val database = openDatabase()
        database.dao().upsertSession(session("old", updatedAt = 1))
        database.dao().upsertSession(session("new", updatedAt = 2))
        database.dao().upsertMessages((1..4).map { message("old", it) })
        database.dao().upsertMessages((1..4).map { message("new", it) })

        val report = database.dao().enforceCacheQuota(maxMessages = 5, maxBytes = CacheQuota.MAX_BYTES)

        assertThat(report.evictedMessages).isEqualTo(3)
        assertThat(report.remainingMessages).isEqualTo(5)
        // The whole "old" session is older: its three oldest messages go first.
        assertThat(database.dao().messages("old").map { it.appendOrder }).containsExactly("4").inOrder()
        assertThat(database.dao().messages("new").map { it.appendOrder })
            .containsExactly("1", "2", "3", "4")
            .inOrder()
        database.close()
    }

    @Test
    fun byteQuotaEvictsOldestMessagesUntilUnderLimit() = runBlocking {
        val database = openDatabase()
        database.dao().upsertSession(session("session", updatedAt = 1))
        val payload = "x".repeat(1_024)
        database.dao().upsertMessages((1..10).map { message("session", it, content = payload) })

        val report = database.dao().enforceCacheQuota(maxMessages = CacheQuota.MAX_MESSAGES, maxBytes = 4_000)

        assertThat(report.remainingBytes).isAtMost(4_000)
        assertThat(report.remainingMessages).isEqualTo(3)
        val remaining = database.dao().messages("session")
        assertThat(remaining.map { it.appendOrder }).containsExactly("8", "9", "10").inOrder()
        database.close()
    }

    @Test
    fun evictionNeverTouchesDraftsTrustStateOrCommittedCursors() = runBlocking {
        val database = openDatabase()
        val session = session("session", updatedAt = 1).copy(
            canonicalCursor = CanonicalAppendCursor(
                streamEpoch = "epoch-1",
                sequence = "18446744073709551615",
                leafId = "7fa3c91e",
                lastAppendId = "append-4",
            ),
        )
        database.dao().upsertSession(session)
        database.dao().upsertDraft(DraftEntity("session", "draft text", "voice", 3, 5))
        database.dao().upsertTrustState(trusted("mac-1"))
        database.dao().upsertMessages((1..6).map { message("session", it) })

        database.dao().enforceCacheQuota(maxMessages = 0, maxBytes = 0)

        assertThat(database.dao().messageCount("session")).isEqualTo(0)
        assertThat(database.dao().session("session")).isEqualTo(session)
        assertThat(database.dao().draft("session")).isEqualTo(DraftEntity("session", "draft text", "voice", 3, 5))
        assertThat(database.dao().trustState("mac-1")).isEqualTo(trusted("mac-1"))
        database.close()
    }

    @Test
    fun snapshotAndFinalizedCommitsEnforceQuotaAutomatically() = runBlocking {
        val database = openDatabase()
        val session = session("session", updatedAt = 1)
        database.dao().replaceSessionSnapshot(session, (1..6).map { message("session", it) })
        // Default quota is far above the fixture; nothing is evicted but the call path runs.
        assertThat(database.dao().messageCount("session")).isEqualTo(6)
        database.dao().commitFinalizedMessage(session, message("session", 7))
        assertThat(database.dao().messageCount("session")).isEqualTo(7)
        database.close()
    }

    @Test
    fun canonicalCursorAndFinalizedMessageCommitTogether() = runBlocking {
        val database = openDatabase()
        val cursor = CanonicalAppendCursor("epoch-1", "2", null, "17")
        val session = session("session", updatedAt = 2).copy(canonicalCursor = cursor)
        database.dao().commitCanonicalEvent(session, message("session", 2))

        assertThat(database.dao().session("session")?.canonicalCursor).isEqualTo(cursor)
        assertThat(database.dao().messages("session").single().appendId).isEqualTo("append-2")
        database.close()
    }

    @Test
    fun unpairPurgeRemovesHostDataAndRetainsRevocation() = runBlocking {
        val database = openDatabase()
        database.dao().upsertSession(session("old-session", updatedAt = 1))
        database.dao().upsertMessages(listOf(message("old-session", 1)))
        database.dao().upsertDraft(DraftEntity("old-session", "draft", "voice", 1, 1))
        database.dao().upsertTrustState(trusted("old-mac"))

        database.dao().revokeAndPurge("old-mac", 2, "USER_UNPAIRED")

        assertThat(database.dao().sessionCount()).isEqualTo(0)
        assertThat(database.dao().messageCount("old-session")).isEqualTo(0)
        assertThat(database.dao().draft("old-session")).isNull()
        assertThat(database.dao().trustState("old-mac")?.status).isEqualTo(StoredTrustStatus.REVOKED)
        database.close()
    }

    @Test
    fun keysetPagingWalksCanonicalUint64OrderAcrossSignedLongBoundary() = runBlocking {
        val database = openDatabase()
        database.dao().upsertSession(session("session", updatedAt = 1))
        val orders = listOf("0", "1", "2", "10", "9223372036854775807", "9223372036854775808", "18446744073709551615")
        database.dao().upsertMessages(orders.map { message("session", it) })

        val first = database.dao().messagesPage("session", afterAppendOrder = null, limit = 3)
        assertThat(first.map { it.appendOrder }).containsExactly("0", "1", "2").inOrder()
        val second = database.dao().messagesPage("session", afterAppendOrder = "2", limit = 3)
        assertThat(second.map { it.appendOrder })
            .containsExactly("10", "9223372036854775807", "9223372036854775808")
            .inOrder()
        val third = database.dao().messagesPage("session", afterAppendOrder = "9223372036854775808", limit = 3)
        assertThat(third.map { it.appendOrder }).containsExactly("18446744073709551615")

        val recent = withTimeout(5_000) {
            database.dao().observeRecentMessages("session", 2).first()
        }
        assertThat(recent.map { it.appendOrder })
            .containsExactly("9223372036854775808", "18446744073709551615")
            .inOrder()
        database.close()
    }

    private fun openDatabase(): PiMobileDatabase = Room.databaseBuilder(
        context,
        PiMobileDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        StorageMigrations.MIGRATION_1_2,
        StorageMigrations.MIGRATION_2_3,
        StorageMigrations.MIGRATION_3_4,
    ).build()

    internal companion object {
        const val DATABASE_NAME = "cache-quota-test.db"

        fun session(id: String, updatedAt: Long) = SessionEntity(
            sessionId = id,
            cwd = "/tmp/project",
            displayName = null,
            provider = "openai",
            modelId = "model",
            thinkingLevel = "medium",
            canonicalCursor = null,
            updatedAtEpochMs = updatedAt,
        )

        fun message(sessionId: String, appendOrder: Int, content: String = "content"): MessageEntity =
            message(sessionId, appendOrder.toString(), content)

        fun message(sessionId: String, appendOrder: String, content: String = "content"): MessageEntity {
            val raw = """{"type":"assistant","order":"$appendOrder"}"""
            return MessageEntity(
                sessionId = sessionId,
                messageId = "message-$appendOrder",
                parentId = null,
                appendOrder = appendOrder,
                appendId = "append-$appendOrder",
                role = StoredMessageRole.ASSISTANT,
                state = FinalizedMessageState.FINALIZED,
                contentJson = content,
                authoritativeFinal = AuthoritativeFinalMetadata(
                    source = FinalMetadataSource.AUTHORITATIVE,
                    rawJson = raw,
                    rawRef = null,
                    rawSizeBytes = raw.encodeToByteArray().size.toLong(),
                    rawSha256 = sha256Hex(raw),
                    projectionJson = """{"type":"assistant"}""",
                    signature = null,
                    redacted = false,
                    createdAtEpochMs = 1,
                    finalizedAtEpochMs = 2,
                ),
            )
        }

        fun trusted(macId: String) = TrustStateEntity(
            macId = macId,
            status = StoredTrustStatus.TRUSTED,
            displayName = "Work Mac",
            certificateSerial = "serial-a",
            certificateNotAfterEpochMs = 10_000,
            revokedAtEpochMs = null,
            revocationReasonCode = null,
            updatedAtEpochMs = 1,
        )
    }
}
