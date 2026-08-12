package io.github.verybigsad.pimobile.protocol

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val sha256Pattern = Regex("^[0-9a-f]{64}$")
private val leafPattern = Regex("^[0-9a-f]{8}$")

class ContiguousStream(
    val streamId: String,
    private val limit: ULong,
    private val expectedLength: ULong? = null,
    private val expectedSha256: String? = null,
) {
    private var nextSequence = 0L
    private var nextOffset = 0uL
    private val digest = MessageDigest.getInstance("SHA-256")
    private var ended = false

    init {
        if (expectedLength != null && expectedLength > limit) fail()
        if (expectedSha256 != null && !sha256Pattern.matches(expectedSha256)) fail()
    }

    fun accept(streamId: String, sequence: Long, offset: ULong, data: ByteArray) {
        if (
            ended || streamId != this.streamId || sequence != nextSequence || offset != nextOffset ||
            sequence !in 0..0xffff_ffffL || data.size > ProtocolConstants.maxBinaryDataBytes ||
            data.size.toULong() > limit || nextOffset > limit - data.size.toULong()
        ) fail()
        digest.update(data)
        nextSequence += 1
        nextOffset += data.size.toULong()
    }

    fun close(length: ULong, sha256: String) {
        if (ended || length != nextOffset || expectedLength != null && length != expectedLength || !sha256Pattern.matches(sha256)) fail()
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != sha256 || expectedSha256 != null && actual != expectedSha256) fail()
        ended = true
    }

    val length: ULong get() = nextOffset

    private fun fail(): Nothing {
        ended = true
        throw ProtocolException(ProtocolErrorCode.STREAM_INVALID, "Stream ordering, bound, or digest is invalid")
    }
}

enum class CursorResult { APPLIED, DUPLICATE }

class RecoveryCursor(
    val streamEpoch: String,
    sequence: String,
) {
    private var sequence = parseUint64(sequence)

    fun accept(streamEpoch: String, sequence: String): CursorResult {
        if (streamEpoch != this.streamEpoch) throw ProtocolException(ProtocolErrorCode.SYNC_REQUIRED, "Stream epoch changed")
        val incoming = parseUint64(sequence)
        if (incoming <= this.sequence) return CursorResult.DUPLICATE
        if (incoming != this.sequence + 1uL) throw ProtocolException(ProtocolErrorCode.SEQUENCE_GAP, "Event sequence is not contiguous")
        this.sequence = incoming
        return CursorResult.APPLIED
    }

    fun snapshot(leafId: String?): RecoverySnapshot {
        if (leafId != null && !leafPattern.matches(leafId)) protocolViolation("Leaf ID is invalid")
        return RecoverySnapshot(streamEpoch, sequence.toString(), leafId)
    }
}

data class RecoverySnapshot(val streamEpoch: String, val sequence: String, val leafId: String?)

class SnapshotAttempt(
    val sessionId: String,
    val streamEpoch: String,
    val frozenSequence: String,
    val leafId: String?,
    val lastAppendId: String?,
) {
    private val postFence = RecoveryCursor(streamEpoch, frozenSequence)
    private var validated = false
    private var published = false

    init {
        parseUint64(frozenSequence)
        if (leafId != null && !leafPattern.matches(leafId)) {
            protocolViolation("Snapshot leaf or append cursor is invalid")
        }
        if (lastAppendId != null) parseUint64(lastAppendId)
    }

    fun acceptAdjunct(streamEpoch: String, sequence: String) {
        parseUint64(sequence)
        if (streamEpoch != this.streamEpoch || sequence != frozenSequence) {
            throw ProtocolException(ProtocolErrorCode.SYNC_REQUIRED, "Snapshot adjunct is not tagged with the frozen cursor")
        }
    }

    fun validate(newAppendEntries: Int, leafId: String?) {
        if (newAppendEntries < 0 || leafId != null && !leafPattern.matches(leafId)) protocolViolation("Snapshot validation result is invalid")
        if (newAppendEntries != 0 || leafId != this.leafId) {
            throw ProtocolException(ProtocolErrorCode.SNAPSHOT_LEAF_CHANGED, "Snapshot changed during validation")
        }
        validated = true
    }

    fun publish() {
        if (!validated) throw ProtocolException(ProtocolErrorCode.SYNC_REQUIRED, "Snapshot cannot publish before validation")
        published = true
    }

    fun acceptPostFence(streamEpoch: String, sequence: String): CursorResult {
        if (!published) throw ProtocolException(ProtocolErrorCode.SYNC_REQUIRED, "Post-fence replay cannot start before publication")
        return postFence.accept(streamEpoch, sequence)
    }
}

data class PairingBinding(
    val ceremonyKind: String,
    val invitationId: String,
    val sessionBinding: String,
    val csrSha256: String,
    val rpId: String,
    val origin: String,
    val challenge: String,
    val expiresAt: String,
)

fun assertPairingBinding(expected: PairingBinding, actual: PairingBinding) {
    if (
        expected != actual || actual.ceremonyKind !in setOf("registration", "assertion") ||
        !Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(actual.invitationId) ||
        !sha256Pattern.matches(actual.sessionBinding) || !sha256Pattern.matches(actual.csrSha256) ||
        !Regex("^[A-Za-z0-9_-]{43}$").matches(actual.challenge) || actual.rpId.isEmpty() || actual.origin.isEmpty() || actual.expiresAt.isEmpty()
    ) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "Pairing binding mismatch")
    }
}

data class UnlockBinding(
    val ceremonyKind: String,
    val deviceId: String,
    val rpId: String,
    val origin: String,
    val challenge: String,
    val expiresAt: String,
)

fun assertUnlockBinding(expected: UnlockBinding, actual: UnlockBinding) {
    if (
        expected != actual || actual.ceremonyKind != "assertion" ||
        !Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(actual.deviceId) ||
        !Regex("^[A-Za-z0-9_-]{43}$").matches(actual.challenge) || actual.rpId.isEmpty() || actual.origin.isEmpty() || actual.expiresAt.isEmpty()
    ) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "Unlock binding mismatch")
    }
}

fun assertPairingToken(pairingToken: String, sessionBinding: String) {
    if (!Regex("^[A-Za-z0-9_-]{43}$").matches(pairingToken) || !sha256Pattern.matches(sessionBinding)) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "Pairing token is malformed")
    }
    val tokenBytes = try {
        java.util.Base64.getUrlDecoder().decode(pairingToken)
    } catch (_: IllegalArgumentException) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "Pairing token is malformed")
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(tokenBytes).joinToString("") { "%02x".format(it) }
    if (tokenBytes.size != 32 || digest != sessionBinding) {
        throw ProtocolException(ProtocolErrorCode.AUTH_FAILED, "Pairing token does not match the session binding")
    }
}

data class ApprovalBinding(val offerId: String, val operationId: String, val argumentHash: String)

fun assertApprovalBinding(expected: ApprovalBinding, actual: ApprovalBinding) {
    if (expected != actual || !sha256Pattern.matches(actual.argumentHash)) {
        throw ProtocolException(ProtocolErrorCode.APPROVAL_DENIED, "Approval tuple is stale or mismatched")
    }
}

class ApprovalOffer(
    val binding: ApprovalBinding,
    val expiresAtEpochMilliseconds: Long,
) {
    private var consumed = false

    init {
        if (expiresAtEpochMilliseconds < 0) protocolViolation("Approval expiry is invalid")
    }

    fun decide(binding: ApprovalBinding, nowEpochMilliseconds: Long) {
        if (consumed || nowEpochMilliseconds >= expiresAtEpochMilliseconds) {
            consumed = true
            throw ProtocolException(ProtocolErrorCode.APPROVAL_EXPIRED, "Approval is expired or already consumed")
        }
        consumed = true
        assertApprovalBinding(this.binding, binding)
    }

    fun expire() {
        consumed = true
    }
}

data class ReadyBlob(
    val blobId: String,
    val ownerDeviceId: String,
    val size: String,
    val sha256: String,
    val mimeType: String,
    val expiresAtEpochMilliseconds: Long,
    val ready: Boolean,
    val referenced: Boolean,
)

data class ImageRef(val blobId: String, val size: String, val sha256: String, val mimeType: String)

fun assertPromptImageRef(blob: ReadyBlob, ref: ImageRef, deviceId: String, nowEpochMilliseconds: Long) {
    val size = try {
        parseUint64(ref.size)
    } catch (_: ProtocolException) {
        throw ProtocolException(ProtocolErrorCode.BLOB_INVALID, "Prompt image size is invalid")
    }
    if (!blob.ready || blob.referenced || blob.ownerDeviceId != deviceId || blob.expiresAtEpochMilliseconds <= nowEpochMilliseconds) {
        throw ProtocolException(ProtocolErrorCode.BLOB_NOT_READY, "Prompt image is unavailable")
    }
    if (
        size > ProtocolConstants.maxPromptImageBytes.toULong() || ref.blobId != blob.blobId || ref.size != blob.size ||
        ref.sha256 != blob.sha256 || ref.mimeType != blob.mimeType || !sha256Pattern.matches(ref.sha256) ||
        ref.mimeType !in setOf("image/jpeg", "image/png", "image/webp")
    ) throw ProtocolException(ProtocolErrorCode.BLOB_INVALID, "Prompt image reference is invalid")
}

fun assertTerminalHistory(text: String, maxLines: Int, maxBytes: Int) {
    if (maxLines !in 1..ProtocolConstants.maxTerminalHistoryLines || maxBytes !in 1..ProtocolConstants.maxTerminalHistoryBytes) {
        protocolViolation("Terminal history request is out of bounds")
    }
    val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    if (lines > maxLines || text.encodeToByteArray().size > maxBytes) frameTooLarge("Terminal history result exceeds its request")
}

private val uuidV4Pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val opaqueIdPattern = Regex("^[A-Za-z0-9._:-]+$")
private val errorCodePattern = Regex("^[A-Z][A-Z0-9_]+$")
private val base64urlPattern = Regex("^[A-Za-z0-9_-]+$")
private const val MAX_CATALOG_SESSIONS = 512
private const val MAX_SYNC_CURSORS = 512

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.requireUuidV4(key: String) {
    if (this[key].stringOrNull()?.let(uuidV4Pattern::matches) != true) protocolViolation("$key must be a lowercase UUIDv4")
}

private fun JsonObject.requireUint64(key: String) {
    parseUint64(this[key].stringOrNull() ?: protocolViolation("$key must be canonical uint64 decimal text"))
}

private fun JsonObject.requireUint64OrNull(key: String) {
    val value = this[key] ?: protocolViolation("$key is required")
    if (value !is JsonNull) parseUint64(value.stringOrNull() ?: protocolViolation("$key must be null or canonical uint64 decimal text"))
}

private fun JsonObject.requireLeaf(key: String) {
    val value = this[key] ?: return
    if (value !is JsonNull && !leafPattern.matches(value.stringOrNull() ?: protocolViolation("$key must be null or eight lowercase hex characters"))) {
        protocolViolation("$key must be null or eight lowercase hex characters")
    }
}

private fun JsonObject.requireBoundedString(key: String, maxLength: Int) {
    val value = this[key].stringOrNull() ?: protocolViolation("$key must be a string")
    if (value.isEmpty() || value.length > maxLength) protocolViolation("$key exceeds its length bound")
}

private fun requireDateTime(value: String?) {
    val text = value ?: protocolViolation("date-time must be a string")
    try {
        java.time.OffsetDateTime.parse(text)
    } catch (_: Exception) {
        protocolViolation("date-time is invalid")
    }
}

private fun assertVoiceBody(body: JsonObject) {
    if (body.toString().encodeToByteArray().size > ProtocolConstants.maxVoiceBodyBytes) {
        frameTooLarge("Voice message body exceeds its bound")
    }
}

private fun assertVoiceText(value: JsonElement?) {
    val text = value.stringOrNull() ?: protocolViolation("Voice text must be a string")
    if (text.length > ProtocolConstants.maxVoiceTextChars) frameTooLarge("Voice transcript text exceeds its character bound")
}

private fun assertSyncCursor(value: JsonElement?) {
    val cursor = value as? JsonObject ?: protocolViolation("Sync cursor must be an object")
    cursor.requireUuidV4("sessionId")
    cursor.requireUuidV4("streamEpoch")
    cursor.requireUint64("sequence")
    if (!cursor.containsKey("leafId")) protocolViolation("Sync cursor requires leafId")
    cursor.requireLeaf("leafId")
}

private fun assertAgent(value: JsonElement?) {
    val agent = value as? JsonObject ?: protocolViolation("Agent must be an object")
    val agentId = agent["agentId"].stringOrNull() ?: protocolViolation("agentId must be an opaque id")
    if (agentId.isEmpty() || agentId.length > 128 || !opaqueIdPattern.matches(agentId)) protocolViolation("agentId must be an opaque id")
    agent["parentAgentId"]?.let {
        val parentAgentId = it.stringOrNull() ?: protocolViolation("parentAgentId must be an opaque id")
        if (parentAgentId.isEmpty() || parentAgentId.length > 128 || !opaqueIdPattern.matches(parentAgentId)) protocolViolation("parentAgentId must be an opaque id")
    }
    agent.requireBoundedString("description", 256)
    agent.requireBoundedString("agentType", 128)
    if (agent["status"].stringOrNull() !in setOf("running", "waiting", "completed", "failed", "stopped")) protocolViolation("agent status is invalid")
    requireDateTime(agent["startedAt"].stringOrNull())
    agent["endedAt"]?.let { requireDateTime(it.stringOrNull()) }
    agent["toolUses"]?.let {
        val toolUses = (it as? JsonPrimitive)?.takeIf { primitive -> !primitive.isString }?.content?.toLongOrNull()
            ?: protocolViolation("toolUses must be a nonnegative integer")
        if (toolUses !in 0..2_147_483_647L) protocolViolation("toolUses is out of bounds")
    }
    agent["model"]?.let {
        val model = it.stringOrNull() ?: protocolViolation("model must be a string")
        if (model.isEmpty() || model.length > 128) protocolViolation("model exceeds its length bound")
    }
}

private fun assertAgentsCatalogSession(value: JsonElement?) {
    val session = value as? JsonObject ?: protocolViolation("agents.catalog session must be an object")
    session.requireUuidV4("sessionId")
    val agents = session["agents"] as? JsonArray ?: protocolViolation("agents.catalog session requires agents")
    if (agents.size > ProtocolConstants.maxAgents) protocolViolation("agents.catalog agents exceed their bound")
    agents.forEach(::assertAgent)
}

private fun assertSessionCatalogEntry(value: JsonElement?) {
    val entry = value as? JsonObject ?: protocolViolation("Session catalog entry must be an object")
    entry.requireUuidV4("sessionId")
    entry.requireBoundedString("provider", 64)
    entry.requireBoundedString("model", 128)
    entry.requireBoundedString("thinkingLevel", 32)
    entry.requireBoundedString("repo", 4096)
    entry.requireBoundedString("cwd", 4096)
    val worktree = entry["worktree"] ?: protocolViolation("worktree is required")
    if (worktree !is JsonNull) {
        val text = worktree.stringOrNull() ?: protocolViolation("worktree must be null or a string")
        if (text.isEmpty() || text.length > 4096) protocolViolation("worktree exceeds its length bound")
    }
    val parentId = entry["parentId"] ?: protocolViolation("parentId is required")
    if (parentId !is JsonNull && parentId.stringOrNull()?.let(uuidV4Pattern::matches) != true) protocolViolation("parentId must be null or a UUIDv4")
    requireDateTime(entry["createdAt"].stringOrNull())
    requireDateTime(entry["updatedAt"].stringOrNull())
}

/** Validates frozen v1 wire-message bodies shared verbatim between the Kotlin and TypeScript conformance suites. */
fun assertWireMessage(type: String, body: JsonObject) {
    when (type) {
        "auth.result" -> {
            if ((body["success"] as? JsonPrimitive)?.booleanOrNull == null) protocolViolation("auth.result requires a boolean success")
            body["error"]?.let {
                val code = it.stringOrNull() ?: protocolViolation("auth.result error must be a code string")
                if (code.length > 64 || !errorCodePattern.matches(code)) protocolViolation("auth.result error must be an upper snake case code")
            }
            body["expiresAt"]?.let { requireDateTime(it.stringOrNull()) }
        }
        "sync.complete" -> {
            if (body.isNotEmpty()) protocolViolation("sync.complete requires an empty body")
        }
        "sync.resume" -> {
            val cursors = (body["cursors"] as? JsonArray) ?: protocolViolation("sync.resume requires a cursors array")
            if (cursors.size > MAX_SYNC_CURSORS) protocolViolation("sync.resume cursors exceed their bound")
            cursors.forEach(::assertSyncCursor)
        }
        "message.append" -> {
            body.requireUuidV4("sessionId")
            body.requireUuidV4("streamEpoch")
            body.requireUint64("appendId")
            body.requireLeaf("leafId")
        }
        "session.settled" -> {
            body.requireUuidV4("sessionId")
            val settlementId = body["settlementId"].stringOrNull() ?: protocolViolation("session.settled requires a settlementId")
            if (settlementId.isEmpty() || settlementId.length > 128 || !opaqueIdPattern.matches(settlementId)) {
                protocolViolation("session.settled settlementId must be an opaque id")
            }
        }
        "session.catalog" -> {
            val sessions = (body["sessions"] as? JsonArray) ?: protocolViolation("session.catalog requires a sessions array")
            if (sessions.size > MAX_CATALOG_SESSIONS) protocolViolation("session.catalog sessions exceed their bound")
            sessions.forEach(::assertSessionCatalogEntry)
        }
        "agents.catalog" -> {
            val sessions = (body["sessions"] as? JsonArray) ?: protocolViolation("agents.catalog requires a sessions array")
            if (sessions.size > MAX_CATALOG_SESSIONS) protocolViolation("agents.catalog sessions exceed their bound")
            sessions.forEach(::assertAgentsCatalogSession)
        }
        "agents.update" -> {
            body.requireUuidV4("sessionId")
            assertAgent(body["agent"])
        }
        "snapshot.begin" -> {
            body.requireUuidV4("sessionId")
            body.requireUuidV4("streamEpoch")
            body.requireUint64("messageCount")
            body.requireUint64OrNull("lastAppendId")
        }
        "snapshot.end" -> {
            body.requireUuidV4("sessionId")
            body.requireUuidV4("streamEpoch")
            body.requireUint64("messageCount")
            body.requireUint64OrNull("lastAppendId")
            if (!body.containsKey("leafId")) protocolViolation("snapshot.end requires leafId")
            body.requireLeaf("leafId")
            if ((body["validated"] as? JsonPrimitive)?.booleanOrNull != true) protocolViolation("snapshot.end requires validated true")
        }
        "voice.audio" -> {
            body.requireUuidV4("sessionId")
            body.requireUint64("chunkSequence")
            if ((body["final"] as? JsonPrimitive)?.booleanOrNull == null) protocolViolation("voice.audio requires a boolean final")
            assertVoiceBody(body)
        }
        "voice.partial" -> {
            body.requireUuidV4("sessionId")
            body.requireUint64("chunkSequence")
            body.requireUint64("revision")
            assertVoiceText(body["text"])
            assertVoiceBody(body)
        }
        "voice.finish" -> {
            body.requireUuidV4("sessionId")
            body.requireUint64("chunkSequence")
            assertVoiceText(body["text"])
            assertVoiceBody(body)
        }
        "push.endpoint" -> {
            body.requireUuidV4("endpointId")
            body.requireBoundedString("distributor", 128)
            body.requireBoundedString("endpoint", 4096)
            body["wakePublicKey"]?.let {
                val key = it.stringOrNull() ?: protocolViolation("push.endpoint wakePublicKey must be base64url when present")
                if (!base64urlPattern.matches(key)) protocolViolation("push.endpoint wakePublicKey must be base64url when present")
            }
        }
        "terminal.history.request" -> {
            body.requireUuidV4("sessionId")
            body.requireUint64OrNull("beforeSequence")
            val limit = (body["limit"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
                ?: protocolViolation("terminal.history.request requires an integer limit")
            if (limit !in 1..ProtocolConstants.maxTerminalHistoryLines) protocolViolation("terminal.history.request limit is out of bounds")
        }
        "terminal.history.response" -> {
            body.requireUuidV4("sessionId")
            val entries = (body["entries"] as? JsonArray) ?: protocolViolation("terminal.history.response requires entries")
            if (entries.size > ProtocolConstants.maxTerminalHistoryLines) protocolViolation("terminal.history.response entries exceed their bound")
            if (entries.any { it.stringOrNull() == null }) protocolViolation("terminal.history.response entries must be strings")
            if ((body["truncated"] as? JsonPrimitive)?.booleanOrNull == null) protocolViolation("terminal.history.response requires a boolean truncated")
            if (body.toString().encodeToByteArray().size > ProtocolConstants.maxTerminalHistoryBytes) {
                frameTooLarge("terminal.history.response exceeds its byte bound")
            }
        }
        else -> protocolViolation("No shared wire validator for $type")
    }
}

private data class AssistantBlock(val kind: String, var open: Boolean, var value: JsonElement)

class AssistantMessageAssembler {
    private var blocks: MutableList<AssistantBlock>? = null
    private var committedValue: JsonObject? = null
    private var recoveryNeeded = false

    fun apply(record: JsonObject) {
        val type = record.stringField("type")
        if (type == "message_start") {
            if (blocks != null) fault()
            val content = record.objectField("message")["content"] as? JsonArray ?: fault()
            if (content.isNotEmpty()) fault()
            blocks = mutableListOf()
            return
        }
        if (type == "message_end") {
            if (blocks == null || blocks!!.any { it.open }) fault()
            committedValue = record.objectField("message")
            blocks = null
            return
        }
        if (type in setOf("tool_execution_start", "tool_execution_update", "tool_execution_end")) {
            val toolCallId = record.stringField("toolCallId")
            if (blocks == null || blocks!!.none { it.kind == "toolCall" && (it.value as? JsonObject)?.get("id")?.stringContent() == toolCallId }) fault()
            return
        }
        if (type != "message_update" || blocks == null) fault()
        val event = record.objectField("assistantMessageEvent")
        val eventType = event.stringField("type")
        val index = event.integerField("contentIndex")
        if (eventType.endsWith("_start")) {
            if (index != blocks!!.size) fault()
            when (eventType) {
                "text_start", "thinking_start" -> blocks!!.add(AssistantBlock(if (eventType == "text_start") "text" else "thinking", true, JsonPrimitive(event.stringField("content"))))
                "toolcall_start" -> blocks!!.add(AssistantBlock("toolCall", true, event.objectField("toolCall")))
                else -> fault()
            }
            return
        }
        val block = blocks!!.getOrNull(index) ?: fault()
        if (!block.open) fault()
        when (eventType) {
            "text_delta", "thinking_delta" -> {
                val kind = if (eventType == "text_delta") "text" else "thinking"
                if (block.kind != kind) fault()
                block.value = JsonPrimitive(block.value.stringContent() + event.stringField("delta"))
            }
            "toolcall_delta" -> {
                if (block.kind != "toolCall") fault()
                block.value = event.objectField("toolCall")
            }
            "text_end", "thinking_end" -> {
                val kind = if (eventType == "text_end") "text" else "thinking"
                if (block.kind != kind) fault()
                block.value = JsonPrimitive(event.stringField("content"))
                block.open = false
            }
            "toolcall_end" -> {
                if (block.kind != "toolCall") fault()
                block.value = event.objectField("toolCall")
                block.open = false
            }
            else -> fault()
        }
    }

    fun provisional(): List<JsonElement>? = blocks?.map { it.value }
    fun committed(): JsonObject? = committedValue
    fun needsRecovery(): Boolean = recoveryNeeded
    fun transportFault(): Nothing = fault()

    private fun fault(): Nothing {
        blocks = null
        recoveryNeeded = true
        throw ProtocolException(ProtocolErrorCode.SYNC_REQUIRED, "Assistant delta transition is invalid")
    }
}

private fun JsonObject.stringField(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: protocolViolation("$key must be a string")

private fun JsonObject.integerField(key: String): Int = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()?.takeIf { it >= 0 }
    ?: protocolViolation("$key must be a nonnegative integer")

private fun JsonObject.objectField(key: String): JsonObject = this[key] as? JsonObject ?: protocolViolation("$key must be an object")
private fun JsonElement.stringContent(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: protocolViolation("Value must be a string")
