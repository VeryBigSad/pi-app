package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.AppendId
import io.github.verybigsad.pimobile.model.ConversationEvent
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.ProvisionalMessage
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.SettlementId
import io.github.verybigsad.pimobile.state.StorageMappers
import io.github.verybigsad.pimobile.storage.AuthoritativeFinalMetadata
import io.github.verybigsad.pimobile.storage.FinalMetadataSource
import io.github.verybigsad.pimobile.storage.FinalizedMessageState
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.wire.WireMessages.string
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Maps wire `event` records (sessionId/streamEpoch/sequence/piType/raw/projection) into model
 * reducer events and storage entities.
 *
 * Adapter gaps:
 * - The wire event carries no appendId/settlementId; sequence-derived opaque ids
 *   ("seq-<epoch>-<sequence>") are used. They are stable and distinct from 8-hex leaf ids.
 * - Provisional revisions are assigned from a per-message monotonic counter because the
 *   projection does not carry one.
 * - createdAt/finalizedAt fall back to the event receive time when the projection's message
 *   timestamp is absent.
 * Unknown piTypes are skipped (null result) rather than guessed; a later idle snapshot
 * repairs canonical content.
 */
class EventProjectionMapper(
    private val nowEpochMillis: () -> Long,
) {
    private val provisionalRevisions = HashMap<String, Long>()

    data class Mapped(
        val cursor: EventCursor,
        val conversationEvent: ConversationEvent?,
        val finalized: MessageEntity?,
    )

    fun map(sessionId: SessionId, event: JsonObject): Mapped? {
        val epoch = event.string("streamEpoch") ?: return null
        val sequence = event.string("sequence")?.takeIf { Uint64Decimal.isCanonical(it) } ?: return null
        val leaf = event.string("leafId")?.takeIf { LEAF_PATTERN.matches(it) }
        val cursor = EventCursor(
            StreamEpoch(epoch),
            Uint64Decimal(sequence),
            leaf?.let(::LeafId),
        )
        val piType = event.string("piType") ?: return Mapped(cursor, null, null)
        val projection = event["projection"] as? JsonObject ?: JsonObject(emptyMap())
        val message = projection["message"] as? JsonObject
        val now = nowEpochMillis()
        return when (piType) {
            "message_start" -> {
                val provisional = provisionalMessage(message, revision = 0, now) ?: return Mapped(cursor, null, null)
                provisionalRevisions[provisional.id.value] = 0
                Mapped(cursor, ConversationEvent.ProvisionalStarted(cursor, provisional), null)
            }

            "message_update" -> {
                val id = message?.string("id") ?: return Mapped(cursor, null, null)
                val revision = (provisionalRevisions[id] ?: 0) + 1
                val provisional = provisionalMessage(message, revision, now) ?: return Mapped(cursor, null, null)
                provisionalRevisions[id] = revision
                Mapped(cursor, ConversationEvent.ProvisionalReplaced(cursor, provisional), null)
            }

            "message_end" -> {
                val finalized = finalizedMessage(sessionId, event, message, cursor, now)
                    ?: return Mapped(cursor, null, null)
                val model = StorageMappers.finalizedMessage(finalized) ?: return Mapped(cursor, null, null)
                provisionalRevisions.remove(model.id.value)
                Mapped(
                    cursor,
                    ConversationEvent.MessageFinalized(cursor, AppendId(appendIdFor(cursor)), model),
                    finalized,
                )
            }

            "agent_start" -> Mapped(cursor, ConversationEvent.RunStateChanged(cursor, SessionRunState.STREAMING), null)
            "agent_retry", "agent_error" -> {
                val willRetry = (projection["willRetry"] as? JsonPrimitive)?.booleanOrNull == true
                Mapped(cursor, ConversationEvent.RunStateChanged(cursor, if (willRetry) SessionRunState.RETRYING else SessionRunState.FAULTED), null)
            }

            "agent_compaction_start" -> Mapped(cursor, ConversationEvent.RunStateChanged(cursor, SessionRunState.COMPACTING), null)
            "agent_compaction_end" -> Mapped(cursor, ConversationEvent.RunStateChanged(cursor, SessionRunState.STREAMING), null)
            "agent_settled" -> Mapped(
                cursor,
                ConversationEvent.AgentSettled(cursor, SettlementId("settlement-${cursor.streamEpoch.value}-${cursor.sequence.text}")),
                null,
            )
            // agent_end publishes nothing by contract; unknown piTypes are retained but not executed.
            else -> Mapped(cursor, null, null)
        }
    }

    private fun provisionalMessage(message: JsonObject?, revision: Long, now: Long): ProvisionalMessage? {
        message ?: return null
        val id = message.string("id") ?: return null
        return ProvisionalMessage(
            id = MessageId(id),
            role = role(message.string("role")),
            content = StorageMappers.content((message["content"] ?: return null).toString()),
            revision = revision,
            startedAtEpochMillis = now,
        )
    }

    private fun finalizedMessage(
        sessionId: SessionId,
        event: JsonObject,
        message: JsonObject?,
        cursor: EventCursor,
        now: Long,
    ): MessageEntity? {
        message ?: return null
        val id = message.string("id") ?: return null
        val content = message["content"] ?: return null
        val rawJson = event.string("rawJson")
        val rawRef = event["rawRef"] as? JsonObject
        val rawSize = event.string("rawSize")?.toULongOrNull()?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong() ?: return null
        val rawSha256 = event.string("rawSha256") ?: return null
        val projection = (event["projection"] as? JsonObject)?.toString() ?: return null
        if (rawJson == null && rawRef == null) return null
        return MessageEntity(
            sessionId = sessionId.value,
            messageId = id,
            parentId = null,
            appendOrder = cursor.sequence.text,
            appendId = appendIdFor(cursor),
            role = StorageMappers.storedRole(role(message.string("role"))),
            state = FinalizedMessageState.FINALIZED,
            contentJson = content.toString(),
            authoritativeFinal = AuthoritativeFinalMetadata(
                source = FinalMetadataSource.AUTHORITATIVE,
                rawJson = rawJson,
                rawRef = rawRef?.toString(),
                rawSizeBytes = rawSize,
                rawSha256 = rawSha256,
                projectionJson = projection,
                signature = null,
                redacted = false,
                createdAtEpochMs = message.string("timestamp")?.toLongOrNull() ?: now,
                finalizedAtEpochMs = now,
            ),
        )
    }

    private fun role(role: String?): io.github.verybigsad.pimobile.model.MessageRole = when (role) {
        "user" -> io.github.verybigsad.pimobile.model.MessageRole.USER
        "assistant" -> io.github.verybigsad.pimobile.model.MessageRole.ASSISTANT
        "tool", "toolResult", "tool_result" -> io.github.verybigsad.pimobile.model.MessageRole.TOOL
        "system" -> io.github.verybigsad.pimobile.model.MessageRole.SYSTEM
        else -> io.github.verybigsad.pimobile.model.MessageRole.UNKNOWN
    }

    companion object {
        private val LEAF_PATTERN = Regex("^[0-9a-f]{8}$")

        /** Sequence-derived opaque append id; stable, unique per cursor, never an 8-hex leaf id. */
        fun appendIdFor(cursor: EventCursor): String = "seq-${cursor.streamEpoch.value}-${cursor.sequence.text}"
    }
}
