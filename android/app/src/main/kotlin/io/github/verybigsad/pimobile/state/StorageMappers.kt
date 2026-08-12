package io.github.verybigsad.pimobile.state

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MessageContent
import io.github.verybigsad.pimobile.model.MessageContentKind
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.MessageRole
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.StoredMessageRole
import io.github.verybigsad.pimobile.storage.StoredTrustStatus
import io.github.verybigsad.pimobile.storage.TrustStateEntity
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adapter notes:
 * - Model appendOrdinal is a signed Long while the wire append order is uint64; rows above
 *   Long.MAX_VALUE are dropped rather than silently wrapped.
 */
object StorageMappers {
    private val json = Json

    fun sessionMetadata(entity: SessionEntity, macId: MacId): SessionMetadata = SessionMetadata(
        id = SessionId(entity.sessionId),
        macId = macId,
        displayName = entity.displayName?.takeIf(String::isNotBlank) ?: entity.sessionId,
        repositoryPath = entity.repositoryPath,
        worktreePath = entity.worktreePath,
        parentSessionId = entity.parentSessionId?.let(::SessionId),
        updatedAtEpochMillis = entity.updatedAtEpochMs,
    )

    fun finalizedMessage(entity: MessageEntity): FinalizedMessage? {
        val ordinal = entity.appendOrder.toULongOrNull() ?: return null
        if (ordinal > Long.MAX_VALUE.toULong()) return null
        return FinalizedMessage(
            id = MessageId(entity.messageId),
            role = role(entity.role),
            content = content(entity.contentJson),
            appendOrdinal = ordinal.toLong(),
            createdAtEpochMillis = entity.authoritativeFinal.createdAtEpochMs,
            finalizedAtEpochMillis = entity.authoritativeFinal.finalizedAtEpochMs,
        )
    }

    fun role(role: StoredMessageRole): MessageRole = when (role) {
        StoredMessageRole.USER -> MessageRole.USER
        StoredMessageRole.ASSISTANT -> MessageRole.ASSISTANT
        StoredMessageRole.TOOL -> MessageRole.TOOL
        StoredMessageRole.SYSTEM -> MessageRole.SYSTEM
        StoredMessageRole.UNKNOWN -> MessageRole.UNKNOWN
    }

    fun storedRole(role: MessageRole): StoredMessageRole = when (role) {
        MessageRole.USER -> StoredMessageRole.USER
        MessageRole.ASSISTANT -> StoredMessageRole.ASSISTANT
        MessageRole.TOOL -> StoredMessageRole.TOOL
        MessageRole.SYSTEM -> StoredMessageRole.SYSTEM
        MessageRole.UNKNOWN -> StoredMessageRole.UNKNOWN
    }

    fun content(contentJson: String): kotlinx.collections.immutable.PersistentList<MessageContent> {
        val parsed = runCatching { json.parseToJsonElement(contentJson) }.getOrNull()
        val parts: List<JsonElement> = when (parsed) {
            is JsonArray -> parsed
            is JsonObject, is JsonPrimitive, null -> listOfNotNull(parsed)
            else -> emptyList()
        }
        return parts.mapIndexed { index, part ->
            val obj = part as? JsonObject
            val type = obj?.get("type")?.jsonPrimitive?.contentOrNull
            MessageContent(
                stableId = obj?.get("id")?.jsonPrimitive?.contentOrNull ?: "content-$index",
                kind = when (type) {
                    "text" -> MessageContentKind.TEXT
                    "thinking" -> MessageContentKind.THINKING
                    "tool_call", "toolCall" -> MessageContentKind.TOOL_CALL
                    "tool_result", "toolResult" -> MessageContentKind.TOOL_RESULT
                    "image" -> MessageContentKind.IMAGE
                    else -> MessageContentKind.UNKNOWN
                },
                contentVersion = 0,
                projection = part.toString(),
            )
        }.distinctBy(MessageContent::stableId).toPersistentList()
    }

    fun cursor(cursor: CanonicalAppendCursor): EventCursor = EventCursor(
        streamEpoch = StreamEpoch(cursor.streamEpoch),
        sequence = Uint64Decimal(cursor.sequence),
        leafId = cursor.leafId?.let(::LeafId),
    )

    fun cursor(cursor: EventCursor): CanonicalAppendCursor = CanonicalAppendCursor(
        streamEpoch = cursor.streamEpoch.value,
        sequence = cursor.sequence.text,
        leafId = cursor.leafId?.value,
        lastAppendId = null,
    )

    fun trustState(entity: TrustStateEntity): io.github.verybigsad.pimobile.model.TrustState = when (entity.status) {
        StoredTrustStatus.TRUSTED -> io.github.verybigsad.pimobile.model.TrustState.Trusted(
            macId = MacId(entity.macId),
            macDisplayName = requireNotNull(entity.displayName),
            certificateSerial = requireNotNull(entity.certificateSerial),
            certificateNotAfterEpochMillis = requireNotNull(entity.certificateNotAfterEpochMs),
        )

        StoredTrustStatus.REVOKED -> io.github.verybigsad.pimobile.model.TrustState.Revoked(
            macId = MacId(entity.macId),
            revokedAtEpochMillis = requireNotNull(entity.revokedAtEpochMs),
            reasonCode = requireNotNull(entity.revocationReasonCode),
        )
    }

    fun draftSessionId(draft: DraftEntity): SessionId = SessionId(draft.sessionId)

}
