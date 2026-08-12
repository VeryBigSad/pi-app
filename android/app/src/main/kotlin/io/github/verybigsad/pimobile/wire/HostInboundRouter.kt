package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.protocol.assertWireMessage
import io.github.verybigsad.pimobile.storage.AuthoritativeFinalMetadata
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.FinalMetadataSource
import io.github.verybigsad.pimobile.storage.FinalizedMessageState
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.StoredMessageRole
import io.github.verybigsad.pimobile.voice.MacVoiceError
import io.github.verybigsad.pimobile.wire.WireMessages.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class ReplayAssembly(
    val epoch: StreamEpoch,
    var sequence: Uint64Decimal,
    val through: Uint64Decimal,
)

internal class SnapshotAssembly(
    val sessionId: SessionId,
    val epoch: StreamEpoch,
    val sequence: Uint64Decimal,
    val messageCount: Int,
    val lastAppendId: String?,
) {
    val entries = ArrayList<JsonObject>()
    var nextPage = 0
}

/**
 * Maps host JSON envelopes to [HostConnectionEvent]s for one connection generation.
 * Owns snapshot assembly and replay-through tracking state.
 */
internal class HostInboundRouter(
    private val onEvent: (HostConnectionEvent) -> Unit,
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val snapshots = HashMap<String, SnapshotAssembly>()
    private val replays = HashMap<String, ReplayAssembly>()
    private val mapper = EventProjectionMapper(nowEpochMillis)

    fun handle(envelope: WireMessages.Envelope) {
        val body = envelope.body
        when (envelope.type) {
            "server.hello" -> Unit
            "auth.assertion.options" -> {
                val ceremonyId = body.string("ceremonyId") ?: return emitError("ASSERTION_OPTIONS_INVALID")
                val binding = body["binding"] as? JsonObject ?: return emitError("ASSERTION_OPTIONS_INVALID")
                val publicKey = body["publicKey"] as? JsonObject ?: return emitError("ASSERTION_OPTIONS_INVALID")
                onEvent(HostConnectionEvent.AssertionOptions(ceremonyId, binding, publicKey.toString()))
            }

            "auth.result" -> onEvent(
                HostConnectionEvent.AuthResult(
                    body.string("ceremonyId"),
                    body.booleanField("success") ?: body.booleanField("authenticated") ?: false,
                ),
            )

            "auth.lock" -> onEvent(HostConnectionEvent.HostLocked)
            "sync.reset" -> {
                val sessionId = body.string("sessionId")?.let(::SessionId) ?: return
                onEvent(HostConnectionEvent.SyncReset(sessionId, body.string("reason") ?: "explicit"))
            }

            "sync.replay" -> {
                val sessionId = body.string("sessionId")?.takeIf(SNAPSHOT_UUID::matches)
                    ?: return emitError("SYNC_REPLAY_INVALID")
                val epoch = body.string("streamEpoch")?.takeIf(SNAPSHOT_UUID::matches)
                    ?: return emitError("SYNC_REPLAY_INVALID")
                val from = body.string("fromSequence")?.takeIf { Uint64Decimal.isCanonical(it) }?.let(::Uint64Decimal)
                    ?: return emitError("SYNC_REPLAY_INVALID")
                val through = body.string("throughSequence")?.takeIf { Uint64Decimal.isCanonical(it) }?.let(::Uint64Decimal)
                    ?: return emitError("SYNC_REPLAY_INVALID")
                if (through < from) return emitError("SYNC_REPLAY_INVALID")
                replays[sessionId] = ReplayAssembly(StreamEpoch(epoch), from, through)
            }

            "sync.complete" -> {
                replays.clear()
                onEvent(HostConnectionEvent.SyncComplete)
            }

            "snapshot.begin" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("SNAPSHOT_BEGIN_INVALID")
                val sessionText = body.string("sessionId")?.takeIf(SNAPSHOT_UUID::matches)
                    ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                val epoch = body.string("streamEpoch")?.takeIf(SNAPSHOT_UUID::matches)
                    ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                val sequence = body.string("sequence")?.takeIf { Uint64Decimal.isCanonical(it) }
                    ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                val messageCount = body.string("messageCount")?.toULongOrNull()?.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()
                    ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                val lastAppendElement = body["lastAppendId"] ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                val lastAppendId = when {
                    lastAppendElement.toString() == "null" -> null
                    else -> (lastAppendElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { Uint64Decimal.isCanonical(it) }
                        ?: return emitError("SNAPSHOT_BEGIN_INVALID")
                }
                val sessionId = SessionId(sessionText)
                val assembly = SnapshotAssembly(
                    sessionId = sessionId,
                    epoch = StreamEpoch(epoch),
                    sequence = Uint64Decimal(sequence),
                    messageCount = messageCount,
                    lastAppendId = lastAppendId,
                )
                snapshots.put(sessionText, assembly)?.let { rejectSnapshot(it, "SNAPSHOT_OVERLAP") }
            }

            "snapshot.page" -> {
                val sessionText = body.string("sessionId") ?: return emitError("SNAPSHOT_PAGE_INVALID")
                val assembly = snapshots[sessionText] ?: return emitError("SNAPSHOT_PAGE_UNEXPECTED")
                val validIdentity = body.string("streamEpoch") == assembly.epoch.value &&
                    body.string("sequence") == assembly.sequence.text &&
                    body.intField("page") == assembly.nextPage
                if (!validIdentity) return rejectSnapshot(assembly, "SNAPSHOT_PAGE_INVALID")
                val entries = body["entries"] as? JsonArray
                val adjunct = body["adjunct"] as? JsonObject
                if ((entries == null) == (adjunct == null) || entries?.any { it !is JsonObject } == true || (entries?.size ?: 0) > 500) {
                    return rejectSnapshot(assembly, "SNAPSHOT_PAGE_INVALID")
                }
                if (entries != null) {
                    if (assembly.entries.size + entries.size > assembly.messageCount) {
                        return rejectSnapshot(assembly, "SNAPSHOT_PAGE_INVALID")
                    }
                    assembly.entries += entries.filterIsInstance<JsonObject>()
                }
                assembly.nextPage += 1
            }

            "snapshot.end" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("SNAPSHOT_END_INVALID")
                val sessionText = body.string("sessionId") ?: return emitError("SNAPSHOT_END_INVALID")
                val assembly = snapshots.remove(sessionText) ?: return emitError("SNAPSHOT_END_UNEXPECTED")
                val messageCount = body.string("messageCount")?.toULongOrNull()?.takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()
                val pages = body.intField("pages")
                val lastAppendElement = body["lastAppendId"]
                val lastAppendId = when {
                    lastAppendElement?.toString() == "null" -> null
                    else -> (lastAppendElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                        ?.takeIf { Uint64Decimal.isCanonical(it) }
                }
                val lastAppendValid = lastAppendElement != null &&
                    (lastAppendElement.toString() == "null" || lastAppendId != null)
                val leafElement = body["leafId"]
                val leafText = body.string("leafId")
                val leafValid = leafElement?.toString() == "null" || (leafText != null && SNAPSHOT_LEAF.matches(leafText))
                val identityValid = body.string("streamEpoch") == assembly.epoch.value &&
                    body.string("sequence") == assembly.sequence.text
                val expectedLastAppendId = assembly.entries.lastOrNull()?.string("appendId")
                if (!identityValid || pages != assembly.nextPage || messageCount != assembly.messageCount ||
                    assembly.entries.size != assembly.messageCount || !lastAppendValid || lastAppendId != assembly.lastAppendId ||
                    expectedLastAppendId != assembly.lastAppendId || !leafValid
                ) {
                    return rejectSnapshot(assembly, "SNAPSHOT_END_INVALID")
                }
                val leafId = leafText?.let(::LeafId)
                val cursor = EventCursor(assembly.epoch, assembly.sequence, leafId)
                val messages = assembly.entries.mapNotNull { entry ->
                    snapshotEntry(sessionText, entry)
                }
                if (messages.size != assembly.entries.size) return rejectSnapshot(assembly, "SNAPSHOT_ENTRY_INVALID")
                onEvent(
                    HostConnectionEvent.SnapshotReady(
                        sessionId = assembly.sessionId,
                        cursor = cursor,
                        lastAppendId = assembly.lastAppendId,
                        session = SessionEntity(
                            sessionId = sessionText,
                            cwd = assembly.entries.firstNotNullOfOrNull { it.string("cwd") } ?: "/",
                            displayName = assembly.entries.firstNotNullOfOrNull { it.string("displayName") },
                            provider = "unknown",
                            modelId = "unknown",
                            thinkingLevel = "unknown",
                            canonicalCursor = CanonicalAppendCursor(
                                streamEpoch = cursor.streamEpoch.value,
                                sequence = cursor.sequence.text,
                                leafId = cursor.leafId?.value,
                                lastAppendId = assembly.lastAppendId,
                            ),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                        messages = messages,
                        runState = null,
                    ),
                )
            }

            // message.append is reserved for one finalized record; every other canonical
            // record is carried in event.batch so its sequence advances without inventing UI.
            "message.append" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("MESSAGE_APPEND_INVALID")
                if (body.string("piType") != "message_end") return emitError("MESSAGE_APPEND_INVALID")
                val sessionText = body.string("sessionId") ?: return emitError("MESSAGE_APPEND_INVALID")
                val mapped = mapper.map(SessionId(sessionText), body) ?: return emitError("MESSAGE_APPEND_INVALID")
                if (mapped.finalized == null) return emitError("MESSAGE_APPEND_INVALID")
                onEvent(HostConnectionEvent.CanonicalEvent(SessionId(sessionText), mapped.cursor, mapped.conversationEvent, mapped.finalized))
            }

            "event.batch" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("EVENT_BATCH_INVALID")
                val events = body["events"] as? JsonArray ?: return emitError("EVENT_BATCH_INVALID")
                for (element in events) {
                    val event = element as? JsonObject ?: return emitError("EVENT_BATCH_INVALID")
                    val sessionText = event.string("sessionId") ?: return emitError("EVENT_BATCH_INVALID")
                    val mapped = mapper.map(SessionId(sessionText), event) ?: return emitError("EVENT_BATCH_INVALID")
                    val replay = replays[sessionText]
                    if (replay != null) {
                        val expected = replay.sequence.incremented()
                        if (mapped.cursor.streamEpoch != replay.epoch || expected == null || mapped.cursor.sequence != expected || mapped.cursor.sequence > replay.through) {
                            return emitError("SYNC_REPLAY_GAP")
                        }
                        replay.sequence = mapped.cursor.sequence
                    }
                    val acknowledgeFence = replay != null && mapped.cursor.sequence == replay.through
                    if (acknowledgeFence) replays.remove(sessionText)
                    onEvent(
                        HostConnectionEvent.CanonicalEvent(
                            SessionId(sessionText),
                            mapped.cursor,
                            mapped.conversationEvent,
                            mapped.finalized,
                            acknowledgeSyncFence = acknowledgeFence,
                        ),
                    )
                }
            }

            "approval.offer" -> {
                val offer = parseApprovalOffer(body) ?: return emitError("APPROVAL_OFFER_INVALID")
                onEvent(offer)
            }

            "approval.expired" -> {
                val offerId = body.string("offerId") ?: return
                onEvent(HostConnectionEvent.ApprovalExpired(offerId, body.string("reason") ?: "deadline"))
            }

            "command.state", "command.result" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("COMMAND_STATUS_INVALID")
                val commandId = body.string("commandId") ?: return emitError("COMMAND_STATUS_INVALID")
                val state = body.string("state") ?: return emitError("COMMAND_STATUS_INVALID")
                val errorCode = body.string("errorCode")?.takeIf(STABLE_CODE::matches)
                onEvent(HostConnectionEvent.CommandStatus(commandId, state, errorCode))
            }

            "session.catalog" -> {
                val catalog = runCatching {
                    io.github.verybigsad.pimobile.network.WireBodies.parseSessionCatalog(body)
                }.getOrNull() ?: return emitError("SESSION_CATALOG_INVALID")
                onEvent(HostConnectionEvent.SessionCatalogReceived(catalog))
            }

            "agents.catalog" -> {
                val catalog = runCatching {
                    io.github.verybigsad.pimobile.network.WireBodies.parseAgentsCatalog(body)
                }.getOrNull() ?: return emitError("AGENTS_CATALOG_INVALID")
                onEvent(HostConnectionEvent.AgentsCatalogReceived(catalog))
            }

            "agents.update" -> {
                val update = runCatching {
                    io.github.verybigsad.pimobile.network.WireBodies.parseAgentsUpdate(body)
                }.getOrNull() ?: return emitError("AGENTS_UPDATE_INVALID")
                onEvent(HostConnectionEvent.AgentsUpdateReceived(update))
            }

            "session.settled" -> {
                val settled = runCatching {
                    io.github.verybigsad.pimobile.network.WireBodies.parseSessionSettled(body)
                }.getOrNull() ?: return emitError("SESSION_SETTLED_INVALID")
                onEvent(HostConnectionEvent.SessionSettledReceived(settled))
            }

            "voice.partial", "voice.finish" -> {
                val sessionId = body.string("sessionId") ?: return
                onEvent(HostConnectionEvent.VoiceTranscript(sessionId, envelope.type, body.toString().encodeToByteArray()))
            }

            "voice.error" -> {
                val streamId = body.string("streamId") ?: return
                onEvent(
                    HostConnectionEvent.VoiceError(
                        streamId,
                        MacVoiceError.fromWire(
                            body.string("code") ?: "VOICE_UNKNOWN",
                            body.string("detailCode"),
                            body.string("resetAtEpochMilliseconds")?.toLongOrNull(),
                            body.string("retryAfterMilliseconds")?.toLongOrNull(),
                        ),
                    ),
                )
            }

            "terminal.ready" -> {
                val generation = body.string("terminalGeneration")?.toULongOrNull()
                if (generation != null) {
                    onEvent(
                        HostConnectionEvent.TerminalReady(
                            generation,
                            body["columns"]?.jsonPrimitive?.intOrNull ?: 80,
                            body["rows"]?.jsonPrimitive?.intOrNull ?: 24,
                        ),
                    )
                }
            }

            "terminal.reset" -> onEvent(HostConnectionEvent.TerminalReset)
            "terminal.history.response" -> {
                if (runCatching { assertWireMessage(envelope.type, body) }.isFailure) return emitError("TERMINAL_HISTORY_INVALID")
                val sessionId = body.string("sessionId") ?: return emitError("TERMINAL_HISTORY_INVALID")
                val generation = body.string("terminalGeneration")?.toULongOrNull() ?: return emitError("TERMINAL_HISTORY_INVALID")
                val capturedAt = body.string("capturedAt") ?: return emitError("TERMINAL_HISTORY_INVALID")
                val text = body.string("text") ?: return emitError("TERMINAL_HISTORY_INVALID")
                onEvent(
                    HostConnectionEvent.TerminalHistoryResult(
                        sessionId = SessionId(sessionId),
                        terminalGeneration = generation,
                        capturedAt = capturedAt,
                        text = text,
                        truncatedLines = body.booleanField("truncatedLines") ?: return emitError("TERMINAL_HISTORY_INVALID"),
                        truncatedBytes = body.booleanField("truncatedBytes") ?: return emitError("TERMINAL_HISTORY_INVALID"),
                    ),
                )
            }
            "close" -> onEvent(HostConnectionEvent.Disconnected(body.string("code")))
            "error" -> onEvent(
                HostConnectionEvent.HostError(
                    body.string("code") ?: "UNKNOWN",
                    body.booleanField("retryable") ?: false,
                ),
            )

            else -> Unit
        }
    }

    private fun parseApprovalOffer(body: JsonObject): HostConnectionEvent.ApprovalOffer? {
        val offerId = body.string("offerId") ?: return null
        val operationId = body.string("operationId") ?: return null
        val argumentHash = body.string("argumentHash") ?: return null
        val reasons = (body["reasons"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }
            ?: return null
        val expiresAt = body.string("expiresAt")?.let { text ->
            runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
        } ?: return null
        val operationName = body.string("operationName") ?: body.string("operation") ?: operationId
        return HostConnectionEvent.ApprovalOffer(
            offerId = offerId,
            operationId = operationId,
            operationName = operationName,
            normalizedArguments = (body["arguments"] as? JsonPrimitive)?.contentOrNull ?: (body["arguments"]?.toString() ?: return null),
            targetLabel = if (body.string("resource") != null) "Resource" else "Working directory",
            targetValue = body.string("resource") ?: body.string("cwd") ?: return null,
            reasons = reasons,
            policyVersion = body.string("policyVersion") ?: return null,
            argumentHash = argumentHash,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun snapshotEntry(sessionId: String, entry: JsonObject): MessageEntity? {
        val id = entry.string("messageId") ?: entry.string("id") ?: return null
        val appendId = entry.string("appendId")?.takeIf(Uint64Decimal::isCanonical) ?: return null
        val appendOrder = entry.string("appendOrder")?.takeIf(Uint64Decimal::isCanonical) ?: appendId
        val content = entry["content"] ?: return null
        val rawJson = entry.string("rawJson")
        val rawRef = entry["rawRef"] as? JsonObject
        if (rawJson == null && rawRef == null) return null
        val rawSize = entry.string("rawSize")?.toULongOrNull()?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
            ?: rawJson?.encodeToByteArray()?.size?.toLong()
            ?: return null
        val rawSha256 = entry.string("rawSha256")
            ?: rawJson?.let { io.github.verybigsad.pimobile.protocol.sha256Hex(it.encodeToByteArray()) }
            ?: return null
        val role = when (entry.string("role")) {
            "user" -> StoredMessageRole.USER
            "assistant" -> StoredMessageRole.ASSISTANT
            "tool", "tool_result", "toolResult" -> StoredMessageRole.TOOL
            "system" -> StoredMessageRole.SYSTEM
            else -> StoredMessageRole.UNKNOWN
        }
        val now = System.currentTimeMillis()
        return MessageEntity(
            sessionId = sessionId,
            messageId = id,
            parentId = entry.string("parentId"),
            appendOrder = appendOrder,
            appendId = appendId,
            role = role,
            state = FinalizedMessageState.FINALIZED,
            contentJson = content.toString(),
            authoritativeFinal = AuthoritativeFinalMetadata(
                source = FinalMetadataSource.AUTHORITATIVE,
                rawJson = rawJson,
                rawRef = rawRef?.toString(),
                rawSizeBytes = rawSize,
                rawSha256 = rawSha256,
                projectionJson = (entry["projection"] as? JsonObject)?.toString() ?: "{}",
                signature = entry.string("signature"),
                redacted = false,
                createdAtEpochMs = entry.string("createdAt")?.toLongOrNull() ?: now,
                finalizedAtEpochMs = now,
            ),
        )
    }

    private fun rejectSnapshot(assembly: SnapshotAssembly, code: String) {
        snapshots.remove(assembly.sessionId.value)
        emitError(code)
        onEvent(
            HostConnectionEvent.SnapshotRejected(
                assembly.sessionId,
                EventCursor(assembly.epoch, assembly.sequence, null),
            ),
        )
    }

    private fun emitError(code: String) {
        onEvent(HostConnectionEvent.HostError(code, retryable = false))
    }

    private companion object {
        val SNAPSHOT_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val STABLE_CODE = Regex("^[A-Z][A-Z0-9_]{1,63}$")
        val SNAPSHOT_LEAF = Regex("^[0-9a-f]{8}$")
    }
}

private fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { primitive ->
        runCatching { primitive.content.toBooleanStrict() }.getOrNull()
    }
