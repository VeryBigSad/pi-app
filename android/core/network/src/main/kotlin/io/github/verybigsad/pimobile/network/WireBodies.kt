package io.github.verybigsad.pimobile.network

import io.github.verybigsad.pimobile.model.AppendId
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionCatalogEntry
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SettlementId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_SYNC_CURSORS = 512
private const val MAX_CATALOG_SESSIONS = 512
private const val MAX_CATALOG_SESSION_BYTES = 4 * 1_024
private const val MAX_VOICE_BODY_BYTES = 64 * 1_024
private const val MAX_VOICE_TEXT_CHARS = 16_384
private const val MAX_TERMINAL_HISTORY_ENTRIES = 5_000
private const val MAX_TERMINAL_HISTORY_BODY_BYTES = 1_024 * 1_024
private const val MAX_ERROR_CODE_CHARS = 64
private const val MAX_AGENT_SESSIONS = 512
private const val MAX_AGENTS_PER_SESSION = 256
private const val MAX_AGENT_DESCRIPTION_CHARS = 256
private const val MAX_AGENT_DESCRIPTION_BYTES = 1_024
private const val MAX_AGENT_TYPE_CHARS = 128
private const val MAX_AGENT_MODEL_CHARS = 128

private val uuidV4Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val wireOpaqueIdPattern = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val errorCodePattern = Regex("^[A-Z][A-Z0-9_]+$")

/**
 * Typed codecs for the frozen protocol v1 wire bodies (protocol/schema/messages.schema.json,
 * cross-language fixtures protocol/fixtures/pimb-v1.json `wireMessageCases`). Parsers validate
 * required fields, canonical uint64 decimal text, and bounds; unknown fields are retained by the
 * caller's envelope layer and never executed. All failures raise [NetworkError.MALFORMED_JSON].
 */
object WireBodies {
    data class AuthResult(
        val success: Boolean,
        val error: String?,
        val expiresAt: Instant?,
    )

    fun parseAuthResult(body: JsonObject): AuthResult {
        val success = body.requiredBoolean("success")
        val error = body.optionalString("error")?.also {
            if (it.length > MAX_ERROR_CODE_CHARS || !errorCodePattern.matches(it)) malformed("error")
        }
        val expiresAt = body.optionalString("expiresAt")?.let(::parseDateTime)
        return AuthResult(success, error, expiresAt)
    }

    data class SessionCursor(
        val sessionId: SessionId,
        val cursor: EventCursor,
    )

    data class SyncResume(
        val cursors: List<SessionCursor>,
    )

    fun parseSyncResume(body: JsonObject): SyncResume {
        val values = body.requiredArray("cursors")
        if (values.size > MAX_SYNC_CURSORS) malformed("cursors")
        return SyncResume(values.map { parseCursor(it.asObject("cursors")) })
    }

    fun encodeSyncResume(cursors: List<SessionCursor>): JsonObject {
        if (cursors.size > MAX_SYNC_CURSORS) malformed("cursors")
        return JsonObject(mapOf("cursors" to JsonArray(cursors.map(::encodeCursor))))
    }

    private fun parseCursor(value: JsonObject): SessionCursor {
        val sessionId = value.requiredUuid("sessionId")
        val epoch = value.requiredUuid("streamEpoch")
        val sequence = value.requiredUint64("sequence")
        return SessionCursor(SessionId(sessionId), EventCursor(StreamEpoch(epoch), sequence, value.optionalLeaf("leafId")))
    }

    private fun encodeCursor(cursor: SessionCursor): JsonObject = JsonObject(
        mapOf(
            "sessionId" to JsonPrimitive(cursor.sessionId.value),
            "streamEpoch" to JsonPrimitive(cursor.cursor.streamEpoch.value),
            "sequence" to JsonPrimitive(cursor.cursor.sequence.text),
            "leafId" to (cursor.cursor.leafId?.let { JsonPrimitive(it.value) } ?: JsonNull),
        ),
    )

    data class MessageAppend(
        val sessionId: SessionId,
        val streamEpoch: StreamEpoch,
        val appendId: AppendId,
        val leafId: LeafId?,
    )

    fun parseMessageAppend(body: JsonObject): MessageAppend = MessageAppend(
        sessionId = SessionId(body.requiredUuid("sessionId")),
        streamEpoch = StreamEpoch(body.requiredUuid("streamEpoch")),
        appendId = AppendId(body.requiredUint64("appendId").text),
        leafId = body.optionalLeaf("leafId"),
    )

    data class SessionSettled(
        val sessionId: SessionId,
        val settlementId: SettlementId,
    )

    fun parseSessionSettled(body: JsonObject): SessionSettled {
        val settlementId = body.requiredString("settlementId")
        if (!wireOpaqueIdPattern.matches(settlementId)) malformed("settlementId")
        return SessionSettled(SessionId(body.requiredUuid("sessionId")), SettlementId(settlementId))
    }

    data class SessionCatalog(
        val sessions: List<SessionCatalogEntry>,
    )

    fun parseSessionCatalog(body: JsonObject): SessionCatalog {
        val values = body.requiredArray("sessions")
        if (values.size > MAX_CATALOG_SESSIONS) malformed("sessions")
        val entries = values.map { value ->
            val entry = value.asObject("sessions")
            SessionCatalogEntry(
                id = SessionId(entry.requiredUuid("sessionId")),
                provider = entry.requiredString("provider", 64),
                model = entry.requiredString("model", 128),
                thinkingLevel = entry.requiredString("thinkingLevel", 32),
                repositoryPath = entry.requiredBoundedPath("repo"),
                worktreePath = entry.optionalBoundedPath("worktree"),
                workingDirectory = entry.requiredBoundedPath("cwd"),
                parentSessionId = entry.optionalUuid("parentId")?.let(::SessionId),
                createdAtEpochMillis = parseDateTime(entry.requiredString("createdAt")).toEpochMilli(),
                updatedAtEpochMillis = parseDateTime(entry.requiredString("updatedAt")).toEpochMilli(),
            )
        }
        if (entries.map(SessionCatalogEntry::id).distinct().size != entries.size) malformed("sessions")
        return SessionCatalog(entries)
    }

    /** Agent lifecycle status (protocol `agent.status` enum, frozen wire names). */
    enum class AgentStatus(val wireName: String) {
        RUNNING("running"),
        WAITING("waiting"),
        COMPLETED("completed"),
        FAILED("failed"),
        STOPPED("stopped"),

        ;

        companion object {
            fun fromWire(value: String): AgentStatus = entries.firstOrNull { it.wireName == value }
                ?: malformed("status")
        }
    }

    data class Agent(
        val agentId: String,
        val parentAgentId: String?,
        val description: String,
        val agentType: String,
        val status: AgentStatus,
        val startedAt: Instant,
        val endedAt: Instant?,
        val toolUses: Int?,
        val model: String?,
    )

    data class AgentsCatalogSession(
        val sessionId: SessionId,
        val agents: List<Agent>,
    )

    data class AgentsCatalog(
        val sessions: List<AgentsCatalogSession>,
    )

    data class AgentsUpdate(
        val sessionId: SessionId,
        val agent: Agent,
    )

    fun parseAgentsCatalog(body: JsonObject): AgentsCatalog {
        val values = body.requiredArray("sessions")
        if (values.size > MAX_AGENT_SESSIONS) malformed("sessions")
        val sessions = values.map { value ->
            val session = value.asObject("sessions")
            val agents = session.requiredArray("agents")
            if (agents.size > MAX_AGENTS_PER_SESSION) malformed("agents")
            AgentsCatalogSession(
                sessionId = SessionId(session.requiredUuid("sessionId")),
                agents = agents.map { parseAgent(it.asObject("agents")) },
            )
        }
        if (sessions.map { it.sessionId }.distinct().size != sessions.size) malformed("sessions")
        return AgentsCatalog(sessions)
    }

    fun parseAgentsUpdate(body: JsonObject): AgentsUpdate = AgentsUpdate(
        sessionId = SessionId(body.requiredUuid("sessionId")),
        agent = parseAgent(body.requiredObject("agent")),
    )

    private fun parseAgent(agent: JsonObject): Agent {
        val agentId = agent.requiredString("agentId", 128)
        if (!wireOpaqueIdPattern.matches(agentId)) malformed("agentId")
        val parentAgentId = agent.optionalString("parentAgentId")?.also {
            if (!wireOpaqueIdPattern.matches(it)) malformed("parentAgentId")
        }
        val rawDescription = (agent["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: malformed("description")
        if (rawDescription.isEmpty() ||
            rawDescription.length > MAX_AGENT_DESCRIPTION_CHARS ||
            rawDescription.encodeToByteArray().size > MAX_AGENT_DESCRIPTION_BYTES
        ) {
            malformed("description")
        }
        val toolUses = agent.optionalInt("toolUses")?.also { if (it < 0) malformed("toolUses") }
        return Agent(
            agentId = agentId,
            parentAgentId = parentAgentId,
            description = rawDescription,
            agentType = agent.requiredString("agentType", MAX_AGENT_TYPE_CHARS),
            status = AgentStatus.fromWire(agent.requiredString("status", 16)),
            startedAt = parseDateTime(agent.requiredString("startedAt", 64)),
            endedAt = agent.optionalString("endedAt")?.let(::parseDateTime),
            toolUses = toolUses,
            model = agent.optionalString("model")?.also {
                if (it.isEmpty() || it.length > MAX_AGENT_MODEL_CHARS) malformed("model")
            },
        )
    }

    data class SnapshotBegin(
        val sessionId: SessionId,
        val streamEpoch: StreamEpoch,
        val messageCount: Uint64Decimal,
        val lastAppendId: AppendId?,
    )

    fun parseSnapshotBegin(body: JsonObject): SnapshotBegin = SnapshotBegin(
        sessionId = SessionId(body.requiredUuid("sessionId")),
        streamEpoch = StreamEpoch(body.requiredUuid("streamEpoch")),
        messageCount = body.requiredUint64("messageCount"),
        lastAppendId = body.optionalUint64("lastAppendId")?.let { AppendId(it.text) },
    )

    data class SnapshotEnd(
        val sessionId: SessionId,
        val streamEpoch: StreamEpoch,
        val messageCount: Uint64Decimal,
        val lastAppendId: AppendId?,
        val leafId: LeafId?,
    )

    fun parseSnapshotEnd(body: JsonObject): SnapshotEnd {
        if (!body.requiredBoolean("validated")) malformed("validated")
        return SnapshotEnd(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            streamEpoch = StreamEpoch(body.requiredUuid("streamEpoch")),
            messageCount = body.requiredUint64("messageCount"),
            lastAppendId = body.optionalUint64("lastAppendId")?.let { AppendId(it.text) },
            leafId = body.optionalLeaf("leafId"),
        )
    }

    data class VoiceAudio(
        val sessionId: SessionId,
        val chunkSequence: Uint64Decimal,
        val final: Boolean,
    )

    fun parseVoiceAudio(body: JsonObject): VoiceAudio {
        requireBodyBytes(body, MAX_VOICE_BODY_BYTES)
        if ("audio" in body) malformed("audio")
        return VoiceAudio(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            chunkSequence = body.requiredUint64("chunkSequence"),
            final = body.requiredBoolean("final"),
        )
    }

    data class VoicePartial(
        val sessionId: SessionId,
        val chunkSequence: Uint64Decimal,
        val revision: Uint64Decimal,
        val text: String,
    )

    fun parseVoicePartial(body: JsonObject): VoicePartial {
        requireBodyBytes(body, MAX_VOICE_BODY_BYTES)
        val text = body.requiredString("text")
        if (text.length > MAX_VOICE_TEXT_CHARS) malformed("text")
        return VoicePartial(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            chunkSequence = body.requiredUint64("chunkSequence"),
            revision = body.requiredUint64("revision"),
            text = text,
        )
    }

    data class VoiceFinish(
        val sessionId: SessionId,
        val chunkSequence: Uint64Decimal,
        val text: String,
    )

    fun parseVoiceFinish(body: JsonObject): VoiceFinish {
        requireBodyBytes(body, MAX_VOICE_BODY_BYTES)
        val text = body.requiredString("text")
        if (text.length > MAX_VOICE_TEXT_CHARS) malformed("text")
        return VoiceFinish(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            chunkSequence = body.requiredUint64("chunkSequence"),
            text = text,
        )
    }

    data class TerminalHistoryRequest(
        val sessionId: SessionId,
        val terminalGeneration: Uint64Decimal,
        val maxLines: Int,
        val maxBytes: Int,
    )

    fun parseTerminalHistoryRequest(body: JsonObject): TerminalHistoryRequest {
        val maxLines = body.requiredInt("maxLines")
        val maxBytes = body.requiredInt("maxBytes")
        if (maxLines !in 1..MAX_TERMINAL_HISTORY_ENTRIES || maxBytes !in 1..MAX_TERMINAL_HISTORY_BODY_BYTES) malformed("terminalHistoryBounds")
        return TerminalHistoryRequest(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            terminalGeneration = body.requiredUint64("terminalGeneration"),
            maxLines = maxLines,
            maxBytes = maxBytes,
        )
    }

    fun encodeTerminalHistoryRequest(request: TerminalHistoryRequest): JsonObject {
        if (request.maxLines !in 1..MAX_TERMINAL_HISTORY_ENTRIES || request.maxBytes !in 1..MAX_TERMINAL_HISTORY_BODY_BYTES) malformed("terminalHistoryBounds")
        return JsonObject(
            mapOf(
                "sessionId" to JsonPrimitive(request.sessionId.value),
                "terminalGeneration" to JsonPrimitive(request.terminalGeneration.text),
                "maxLines" to JsonPrimitive(request.maxLines),
                "maxBytes" to JsonPrimitive(request.maxBytes),
            ),
        )
    }

    data class TerminalHistoryResponse(
        val sessionId: SessionId,
        val terminalGeneration: Uint64Decimal,
        val capturedAt: Instant,
        val text: String,
        val truncatedLines: Boolean,
        val truncatedBytes: Boolean,
    )

    fun parseTerminalHistoryResponse(body: JsonObject): TerminalHistoryResponse {
        requireBodyBytes(body, MAX_TERMINAL_HISTORY_BODY_BYTES)
        val text = body.requiredText("text", MAX_TERMINAL_HISTORY_BODY_BYTES)
        val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
        if (lineCount > MAX_TERMINAL_HISTORY_ENTRIES || text.encodeToByteArray().size > MAX_TERMINAL_HISTORY_BODY_BYTES) malformed("text")
        return TerminalHistoryResponse(
            sessionId = SessionId(body.requiredUuid("sessionId")),
            terminalGeneration = body.requiredUint64("terminalGeneration"),
            capturedAt = parseDateTime(body.requiredString("capturedAt")),
            text = text,
            truncatedLines = body.requiredBoolean("truncatedLines"),
            truncatedBytes = body.requiredBoolean("truncatedBytes"),
        )
    }

    private fun requireBodyBytes(body: JsonObject, maxBytes: Int) {
        if (StrictJson.canonicalize(body).size > maxBytes) malformed("body")
    }

    private fun parseDateTime(value: String): Instant = try {
        Instant.parse(value)
    } catch (_: Exception) {
        malformed("dateTime")
    }

    private fun JsonObject.requiredString(name: String, maxChars: Int = 2_048): String {
        val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed(name)
        if (value.isEmpty() || value.length > maxChars || value.encodeToByteArray().size > maxChars) malformed(name)
        return value
    }

    private fun JsonObject.requiredText(name: String, maxBytes: Int): String {
        val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed(name)
        if (value.length > maxBytes || value.encodeToByteArray().size > maxBytes) malformed(name)
        return value
    }

    private fun JsonObject.optionalString(name: String): String? =
        this[name]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: malformed(name) }

    private fun JsonObject.requiredUuid(name: String): String =
        requiredString(name, 36).also { if (!uuidV4Pattern.matches(it)) malformed(name) }

    private fun JsonObject.optionalUuid(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            if (!value.isString) malformed(name)
            value.content.also { if (!uuidV4Pattern.matches(it)) malformed(name) }
        }

        else -> malformed(name)
    }

    private fun JsonObject.requiredUint64(name: String): Uint64Decimal {
        val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed(name)
        if (!Uint64Decimal.isCanonical(value)) malformed(name)
        return Uint64Decimal(value)
    }

    private fun JsonObject.optionalUint64(name: String): Uint64Decimal? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            if (!value.isString || !Uint64Decimal.isCanonical(value.content)) malformed(name)
            Uint64Decimal(value.content)
        }

        else -> malformed(name)
    }

    private fun JsonObject.optionalLeaf(name: String): LeafId? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            if (!value.isString) malformed(name)
            try {
                LeafId(value.content)
            } catch (_: IllegalArgumentException) {
                malformed(name)
            }
        }

        else -> malformed(name)
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = this[name] as? JsonPrimitive ?: malformed(name)
        if (value.isString) malformed(name)
        return when (value.content) {
            "true" -> true
            "false" -> false
            else -> malformed(name)
        }
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val value = this[name] as? JsonPrimitive ?: malformed(name)
        if (value.isString) malformed(name)
        return value.content.toIntOrNull() ?: malformed(name)
    }

    private fun JsonObject.requiredArray(name: String): JsonArray = this[name] as? JsonArray ?: malformed(name)

    private fun JsonObject.requiredObject(name: String): JsonObject = this[name] as? JsonObject ?: malformed(name)

    private fun JsonObject.optionalInt(name: String): Int? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            if (value.isString) malformed(name)
            value.content.toIntOrNull() ?: malformed(name)
        }

        else -> malformed(name)
    }

    private fun JsonObject.requiredBoundedPath(name: String): String = requiredString(name, MAX_CATALOG_SESSION_BYTES)

    private fun JsonObject.optionalBoundedPath(name: String): String? = when (val value = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> {
            if (!value.isString) malformed(name)
            value.content.also {
                if (it.isEmpty() || it.encodeToByteArray().size > MAX_CATALOG_SESSION_BYTES) malformed(name)
            }
        }

        else -> malformed(name)
    }

    private fun JsonElement.asObject(name: String): JsonObject = this as? JsonObject ?: malformed(name)

    private fun JsonElement.asString(name: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed(name)

    private fun malformed(field: String): Nothing =
        throw NetworkException(NetworkError.MALFORMED_JSON, "wire body field $field does not match the frozen shape")
}
