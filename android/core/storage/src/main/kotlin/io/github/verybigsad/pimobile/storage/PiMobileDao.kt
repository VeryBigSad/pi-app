package io.github.verybigsad.pimobile.storage

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Global encrypted-cache quota from docs/protocol-v1.md; drafts, trust state, and cursors are exempt. */
object CacheQuota {
    const val MAX_MESSAGES = 50_000
    const val MAX_BYTES = 512L * 1024 * 1024
    internal const val EVICTION_BATCH = 512
    internal const val MAX_EVICTION_ROUNDS = 512
}

data class CacheEvictionReport(
    val evictedMessages: Int,
    val remainingMessages: Int,
    val remainingBytes: Long,
)

@Dao
interface PiMobileDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAtEpochMs DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY LENGTH(appendOrder), appendOrder")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun session(sessionId: String): SessionEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY LENGTH(appendOrder), appendOrder")
    suspend fun messages(sessionId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun messageCount(sessionId: String): Int

    /**
     * Keyset page of finalized messages in canonical uint64 append order. Pass the last
     * `appendOrder` of the previous page as [afterAppendOrder] (null for the first page).
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
            AND (
                :afterAppendOrder IS NULL
                OR LENGTH(appendOrder) > LENGTH(:afterAppendOrder)
                OR (LENGTH(appendOrder) = LENGTH(:afterAppendOrder) AND appendOrder > :afterAppendOrder)
            )
        ORDER BY LENGTH(appendOrder), appendOrder
        LIMIT :limit
        """,
    )
    suspend fun messagesPage(sessionId: String, afterAppendOrder: String?, limit: Int): List<MessageEntity>

    /**
     * Keyset page of messages strictly older than [beforeAppendOrder], newest-first.
     * Eviction-aware by construction: quota-evicted rows simply never appear, so callers
     * must treat a short or empty page as the honest end of retained history.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
            AND (
                LENGTH(appendOrder) < LENGTH(:beforeAppendOrder)
                OR (LENGTH(appendOrder) = LENGTH(:beforeAppendOrder) AND appendOrder < :beforeAppendOrder)
            )
        ORDER BY LENGTH(appendOrder) DESC, appendOrder DESC
        LIMIT :limit
        """,
    )
    suspend fun messagesOlderPage(sessionId: String, beforeAppendOrder: String, limit: Int): List<MessageEntity>

    /** Retained rows strictly older than [beforeAppendOrder]; shrinks as quota eviction runs. */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE sessionId = :sessionId
            AND (
                LENGTH(appendOrder) < LENGTH(:beforeAppendOrder)
                OR (LENGTH(appendOrder) = LENGTH(:beforeAppendOrder) AND appendOrder < :beforeAppendOrder)
            )
        """,
    )
    suspend fun messageCountBefore(sessionId: String, beforeAppendOrder: String): Int

    /** Bounded live view: the newest [limit] messages in ascending canonical append order. */
    fun observeRecentMessages(sessionId: String, limit: Int): Flow<List<MessageEntity>> =
        observeRecentMessagesDescending(sessionId, limit).map { page -> page.asReversed() }

    @Query(
        """
        SELECT * FROM messages WHERE sessionId = :sessionId
        ORDER BY LENGTH(appendOrder) DESC, appendOrder DESC
        LIMIT :limit
        """,
    )
    fun observeRecentMessagesDescending(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    @Upsert
    suspend fun upsertSession(session: SessionEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Upsert
    suspend fun upsertCommandReceipt(receipt: CommandReceiptEntity)

    @Query("SELECT * FROM command_receipts WHERE commandId = :commandId")
    suspend fun commandReceipt(commandId: String): CommandReceiptEntity?

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun sessionCount(): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM command_receipts")
    suspend fun clearCommandReceipts()

    @Query("SELECT * FROM drafts WHERE sessionId = :sessionId")
    suspend fun draft(sessionId: String): DraftEntity?

    @Query("SELECT * FROM drafts WHERE sessionId = :sessionId")
    fun observeDraft(sessionId: String): Flow<DraftEntity?>

    @Upsert
    suspend fun upsertDraft(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE sessionId = :sessionId")
    suspend fun deleteDraft(sessionId: String)

    @Query("DELETE FROM drafts")
    suspend fun clearDrafts()

    @Query("SELECT * FROM trust_states WHERE macId = :macId")
    suspend fun trustState(macId: String): TrustStateEntity?

    @Query("SELECT * FROM trust_states ORDER BY updatedAtEpochMs DESC")
    fun observeTrustStates(): Flow<List<TrustStateEntity>>

    @Upsert
    suspend fun upsertTrustState(trustState: TrustStateEntity)

    @Query("DELETE FROM trust_states WHERE macId = :macId")
    suspend fun deleteTrustState(macId: String)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun totalMessageCount(): Int

    @Query(
        """
        SELECT COALESCE(SUM(
            LENGTH(contentJson) + LENGTH(final_projection_json) + COALESCE(LENGTH(final_raw_json), 0)
        ), 0) FROM messages
        """,
    )
    suspend fun totalMessageBytes(): Long

    @Query(
        """
        DELETE FROM messages WHERE rowid IN (
            SELECT m.rowid FROM messages m
            INNER JOIN sessions s ON s.sessionId = m.sessionId
            ORDER BY s.updatedAtEpochMs ASC, LENGTH(m.appendOrder) ASC, m.appendOrder ASC
            LIMIT :count
        )
        """,
    )
    suspend fun deleteLeastRecentlyUsedMessages(count: Int): Int

    /**
     * Enforces the global message quota: oldest messages (least-recently-updated session first,
     * then lowest append order) are evicted until both bounds hold. Drafts, trust state, and
     * committed cursors are never touched.
     */
    @Transaction
    suspend fun enforceCacheQuota(
        maxMessages: Int = CacheQuota.MAX_MESSAGES,
        maxBytes: Long = CacheQuota.MAX_BYTES,
    ): CacheEvictionReport {
        require(maxMessages >= 0)
        require(maxBytes >= 0)
        var evicted = 0
        val excess = totalMessageCount() - maxMessages
        if (excess > 0) evicted += deleteLeastRecentlyUsedMessages(excess)
        var rounds = 0
        while (rounds < CacheQuota.MAX_EVICTION_ROUNDS) {
            val totalBytes = totalMessageBytes()
            if (totalBytes <= maxBytes) break
            val count = totalMessageCount()
            if (count == 0) break
            val averageBytes = maxOf(1L, totalBytes / count)
            val estimated = (totalBytes - maxBytes + averageBytes - 1) / averageBytes
            val toRemove = minOf(CacheQuota.EVICTION_BATCH.toLong(), maxOf(1L, estimated)).toInt()
            val removed = deleteLeastRecentlyUsedMessages(toRemove)
            evicted += removed
            if (removed == 0) break
            rounds++
        }
        return CacheEvictionReport(
            evictedMessages = evicted,
            remainingMessages = totalMessageCount(),
            remainingBytes = totalMessageBytes(),
        )
    }

    @Transaction
    suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) {
        require(messages.all { it.sessionId == session.sessionId })
        require(messages.map(MessageEntity::messageId).distinct().size == messages.size)
        require(messages.map(MessageEntity::appendOrder).distinct().size == messages.size)
        require(
            messages.zipWithNext().all { (left, right) ->
                compareCanonicalUint64(left.appendOrder, right.appendOrder) < 0
            },
        )
        upsertSession(session)
        deleteMessages(session.sessionId)
        if (messages.isNotEmpty()) upsertMessages(messages)
        enforceCacheQuota()
    }

    @Transaction
    suspend fun commitCanonicalEvent(session: SessionEntity, finalized: MessageEntity?) {
        require(finalized == null || finalized.sessionId == session.sessionId)
        require(finalized == null || finalized.authoritativeFinal.source == FinalMetadataSource.AUTHORITATIVE)
        upsertSession(session)
        if (finalized != null) upsertMessages(listOf(finalized))
        enforceCacheQuota()
    }

    /** Compatibility entry point for callers that only have a finalized event. */
    @Transaction
    suspend fun commitFinalizedMessage(session: SessionEntity, message: MessageEntity) =
        commitCanonicalEvent(session, message)

    /** Clears resync-able cache data only; drafts and trust state survive a sync reset. */
    @Transaction
    suspend fun clearAll() {
        clearSessions()
        clearCommandReceipts()
    }

    /** Full local trust-boundary reset for a user-initiated unpair. */
    @Transaction
    suspend fun revokeAndPurge(macId: String, revokedAtEpochMs: Long, reasonCode: String) {
        require(macId.isNotBlank())
        require(revokedAtEpochMs >= 0)
        require(reasonCode.isNotBlank())
        clearSessions()
        clearDrafts()
        clearCommandReceipts()
        upsertTrustState(
            TrustStateEntity(
                macId = macId,
                status = StoredTrustStatus.REVOKED,
                displayName = null,
                certificateSerial = null,
                certificateNotAfterEpochMs = null,
                revokedAtEpochMs = revokedAtEpochMs,
                revocationReasonCode = reasonCode,
                updatedAtEpochMs = revokedAtEpochMs,
            ),
        )
    }
}
