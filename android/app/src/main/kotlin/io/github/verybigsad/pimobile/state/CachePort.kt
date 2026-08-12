package io.github.verybigsad.pimobile.state

import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.CanonicalResyncSignal
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.TrustStateEntity

data class OlderMessagesPage(
    val messages: List<MessageEntity>,
    val hasMore: Boolean,
)

/**
 * Storage boundary for the coordinator. Drafts and trust state survive sync resets;
 * conversation content is committed transactionally before any acknowledgement is sent.
 */
interface CachePort {
    suspend fun loadTrustState(macId: String): TrustStateEntity?

    suspend fun loadSessions(): List<SessionEntity>

    suspend fun loadSession(sessionId: String): SessionEntity?

    /** Persists catalog metadata without replacing retained canonical messages. */
    suspend fun upsertSession(session: SessionEntity)

    suspend fun loadRecentMessages(sessionId: String, limit: Int): List<MessageEntity>

    /** Retained message count for one session; shrinks under quota eviction. */
    suspend fun messageCount(sessionId: String): Int

    /**
     * One keyset page of messages strictly older than [beforeAppendOrder], ascending order.
     * [OlderMessagesPage.hasMore] reflects retained storage, not the host's full history:
     * quota-evicted rows are honestly absent.
     */
    suspend fun loadOlderMessages(sessionId: String, beforeAppendOrder: String, limit: Int): OlderMessagesPage

    suspend fun loadDrafts(): List<DraftEntity>

    suspend fun upsertDraft(draft: DraftEntity)

    suspend fun upsertTrustState(trustState: TrustStateEntity)

    suspend fun deleteTrustState(macId: String)

    /** Clears resync-able cache content; drafts and trust rows survive. */
    suspend fun markCanonicalUnavailable()

    /**
     * Commits every accepted canonical event's cursor and, when present, its finalized message
     * in one transaction. The caller must not acknowledge the event before this returns.
     */
    suspend fun commitCanonicalEvent(session: SessionEntity, finalized: MessageEntity?)

    /** Atomically replaces a complete snapshot; drafts are retained. */
    suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>)

    /** Drops one session's canonical content (sync.reset); drafts are retained. */
    suspend fun resetSessionContent(session: SessionEntity)

    /**
     * Atomically revokes [macId] and removes every host-bound session, message, draft,
     * command receipt, and cursor. This app supports one active Mac, so a full purge is safer
     * than allowing a new Mac to inherit ambiguous unnamespaced rows.
     */
    suspend fun revokeAndPurge(macId: String, revokedAtEpochMs: Long, reasonCode: String)

    /** All committed resume cursors for sync.resume. */
    suspend fun committedCursors(): List<Pair<String, CanonicalAppendCursor>>

    suspend fun acknowledgeCanonicalResync(signal: CanonicalResyncSignal): Boolean
}
