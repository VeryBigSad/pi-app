package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.Uint64Decimal
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

internal class SnapshotAssembly(
    val epoch: StreamEpoch,
    val sequence: Uint64Decimal,
) {
    val entries = ArrayList<JsonObject>()
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
    private val replays = HashMap<String, Uint64Decimal>()
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
                val sessionId = body.string("sessionId") ?: return
                val through = body.string("throughSequence")?.takeIf { Uint64Decimal.isCanonical(it) } ?: return
                replays[sessionId] = Uint64Decimal(through)
            }

            // Empty sync round: sync.resume had no per-session work; end syncing state.
            "sync.complete" -> onEvent(HostConnectionEvent.SyncComplete)

            "snapshot.begin" -> {
                val sessionId = body.string("sessionId") ?: return
                val epoch = body.string("streamEpoch") ?: return
                val sequence = body.string("sequence")?.takeIf { Uint64Decimal.isCanonical(it) } ?: return
                snapshots[sessionId] = SnapshotAssembly(StreamEpoch(epoch), Uint64Decimal(sequence))
            }

            "snapshot.page" -> {
                val sessionId = body.string("sessionId") ?: return
                val assembly = snapshots[sessionId] ?: return
                val entries = body["entries"] as? JsonArray ?: return
                assembly.entries += entries.filterIsInstance<JsonObject>()
            }

            "snapshot.end" -> {
                val sessionId = body.string("sessionId") ?: return
                val assembly = snapshots.remove(sessionId) ?: return
                val leafId = body.string("leafId")?.takeIf { Regex("^[0-9a-f]{8}$").matches(it) }
                    ?.let(::LeafId)
                val cursor = EventCursor(assembly.epoch, assembly.sequence, leafId)
                val messages = assembly.entries.mapIndexedNotNull { index, entry ->
                    snapshotEntry(sessionId, entry, index)
                }
                if (messages.size != assembly.entries.size) {
                    onEvent(HostConnectionEvent.HostError("SNAPSHOT_ENTRY_INVALID", false))
                    return
                }
                onEvent(
                    HostConnectionEvent.SnapshotReady(
                        sessionId = SessionId(sessionId),
                        cursor = cursor,
                        session = SessionEntity(
                            sessionId = sessionId,
                            cwd = assembly.entries.firstNotNullOfOrNull { it.string("cwd") } ?: "/",
                            displayName = assembly.entries.firstNotNullOfOrNull { it.string("displayName") },
                            provider = "unknown",
                            modelId = "unknown",
                            thinkingLevel = "unknown",
                            canonicalCursor = CanonicalAppendCursor(
                                streamEpoch = cursor.streamEpoch.value,
                                sequence = cursor.sequence.text,
                                leafId = cursor.leafId?.value,
                                lastAppendId = null,
                            ),
                            updatedAtEpochMs = System.currentTimeMillis(),
                        ),
                        messages = messages,
                        runState = null,
                    ),
                )
            }

            "event.batch" -> {
                val events = body["events"] as? JsonArray ?: return
                for (element in events.filterIsInstance<JsonObject>()) {
                    val sessionText = element.string("sessionId") ?: continue
                    val mapped = mapper.map(SessionId(sessionText), element) ?: continue
                    onEvent(HostConnectionEvent.CanonicalEvent(SessionId(sessionText), mapped.cursor, mapped.conversationEvent, mapped.finalized))
                    val through = replays[sessionText]
                    if (through != null && mapped.cursor.sequence == through) {
                        replays.remove(sessionText)
                        onEvent(HostConnectionEvent.SyncComplete)
                    }
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

            "voice.partial", "voice.finish" -> {
                val sessionId = body.string("sessionId") ?: return
                onEvent(HostConnectionEvent.VoiceTranscript(sessionId, envelope.type, envelope.raw))
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
            // terminal.history.response is the pre-schema name kept for tolerance.
            "terminal.history.result", "terminal.history.response" -> {
                val generation = body.string("terminalGeneration")?.toULongOrNull()
                    ?: return emitError("TERMINAL_HISTORY_INVALID")
                val capturedAt = body.string("capturedAt") ?: return emitError("TERMINAL_HISTORY_INVALID")
                onEvent(
                    HostConnectionEvent.TerminalHistoryResult(
                        terminalGeneration = generation,
                        capturedAt = capturedAt,
                        text = body.string("text"),
                        truncatedLines = body.booleanField("truncatedLines") ?: false,
                        truncatedBytes = body.booleanField("truncatedBytes") ?: false,
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

    private fun snapshotEntry(sessionId: String, entry: JsonObject, index: Int): MessageEntity? {
        val id = entry.string("messageId") ?: entry.string("id") ?: return null
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
            appendOrder = (index + 1).toString(),
            appendId = entry.string("appendId") ?: "snapshot-$sessionId-$index",
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

    private fun emitError(code: String) {
        onEvent(HostConnectionEvent.HostError(code, retryable = false))
    }
}

internal fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { primitive ->
        runCatching { primitive.content.toBooleanStrict() }.getOrNull()
    }
