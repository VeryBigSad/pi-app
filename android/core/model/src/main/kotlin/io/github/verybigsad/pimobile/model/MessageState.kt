package io.github.verybigsad.pimobile.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

enum class MessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
    UNKNOWN,
}

enum class MessageContentKind {
    TEXT,
    THINKING,
    TOOL_CALL,
    TOOL_RESULT,
    IMAGE,
    UNKNOWN,
}

data class MessageContent(
    val stableId: String,
    val kind: MessageContentKind,
    val contentVersion: Long,
    val projection: String,
) {
    init {
        require(stableId.isNotBlank())
        require(contentVersion >= 0)
    }
}

data class FinalizedMessage(
    val id: MessageId,
    val role: MessageRole,
    val content: PersistentList<MessageContent> = persistentListOf(),
    val appendOrdinal: Long,
    val createdAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long,
) {
    init {
        require(appendOrdinal >= 0)
        require(createdAtEpochMillis >= 0)
        require(finalizedAtEpochMillis >= createdAtEpochMillis)
        require(content.map(MessageContent::stableId).distinct().size == content.size)
    }
}

data class ProvisionalMessage(
    val id: MessageId,
    val role: MessageRole,
    val content: PersistentList<MessageContent> = persistentListOf(),
    val revision: Long,
    val startedAtEpochMillis: Long,
) {
    init {
        require(revision >= 0)
        require(startedAtEpochMillis >= 0)
        require(content.map(MessageContent::stableId).distinct().size == content.size)
    }
}
