package io.github.verybigsad.pimobile.wire

import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.LeafId
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.Uint64Decimal
import io.github.verybigsad.pimobile.network.CertificateIdentity
import io.github.verybigsad.pimobile.network.CertificateRole
import io.github.verybigsad.pimobile.network.DuplexByteChannel
import io.github.verybigsad.pimobile.network.NetworkError
import io.github.verybigsad.pimobile.network.NetworkException
import io.github.verybigsad.pimobile.network.PathAttempt
import io.github.verybigsad.pimobile.network.PathConnection
import io.github.verybigsad.pimobile.network.PathRaceCoordinator
import io.github.verybigsad.pimobile.network.PimbStreamTransport
import io.github.verybigsad.pimobile.network.RelayAudience
import io.github.verybigsad.pimobile.network.RelayProofCodec
import io.github.verybigsad.pimobile.network.StreamByteChannel
import io.github.verybigsad.pimobile.network.WebSocketBinaryByteChannel
import io.github.verybigsad.pimobile.network.asRelayProofSigner
import io.github.verybigsad.pimobile.network.TlsContexts
import io.github.verybigsad.pimobile.network.TransportPathKind
import io.github.verybigsad.pimobile.protocol.FrameKind
import io.github.verybigsad.pimobile.protocol.PimbCodec
import io.github.verybigsad.pimobile.protocol.StreamPayload
import io.github.verybigsad.pimobile.protocol.TerminalPayload
import io.github.verybigsad.pimobile.security.DeviceKeys
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.storage.AuthoritativeFinalMetadata
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.FinalMetadataSource
import io.github.verybigsad.pimobile.storage.FinalizedMessageState
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.StoredMessageRole
import io.github.verybigsad.pimobile.voice.MacVoiceError
import io.github.verybigsad.pimobile.wire.WireMessages.string
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Direct-vs-relay racing host connector over PIMB/TLS 1.3 mTLS.
 *
 * The relay attempt mirrors the proven pairing path (`RelayPairingPath`): it opens a WSS
 * device-data tunnel to `wss://relay-host/v1/routes/{route}/data` with a self-issued
 * `device-data` proof (`X-Relay-Proof`) signed by the Keystore route key via
 * `DeviceKeys.routePayloadSigner`, the relay notifies the Mac which attaches `mac-data` and
 * splices, then the device runs inner mTLS 1.3 (paired CA trust, macId SAN) over the tunnel.
 *
 * Adapter gaps:
 * - Snapshot entries carry no append metadata on the wire; appendOrder falls back to a
 *   positional ordinal, so a later live event can invalidate canonical state and force a
 *   fresh snapshot (honest degradation, never fake content).
 */
private class SnapshotAssembly(
    val epoch: StreamEpoch,
    val sequence: Uint64Decimal,
) {
    val entries = ArrayList<JsonObject>()
}

class PimbHostConnector(
    private val profile: PairedProfile,
    private val deviceKeys: DeviceKeys,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val onEvent: (HostConnectionEvent) -> Unit,
) : HostConnectionRunner {
    override suspend fun run(): HostConnector {
        onEvent(HostConnectionEvent.Connecting(TransportPath.DIRECT, 1))
        val trustManagers = pairedTrustManagers(profile)
        val identity = CertificateIdentity(CertificateRole.MAC_SERVER, profile.macId)
        val keyManagers = arrayOf<KeyManager>(deviceKeys.tlsKeyManager(profile.deviceId))

        val race = PathRaceCoordinator()
        val attempts = buildList<PathAttempt> {
            profile.directCandidates.forEach { candidate ->
                add(
                    PathAttempt { generation ->
                        val tcp = StreamByteChannel.connect(candidate.host, candidate.port)
                        val context = TlsContexts.mutual(keyManagers, trustManagers, identity)
                        val tls = io.github.verybigsad.pimobile.network.TlsByteChannel.connect(
                            tcp,
                            context,
                            candidate.host,
                            candidate.port,
                        )
                        object : PathConnection {
                            override val kind: TransportPathKind = TransportPathKind.DIRECT
                            override val generation: Long = generation
                            override val channel: DuplexByteChannel = tls
                            override suspend fun authenticate() = Unit
                            override suspend fun close() = tls.close()
                        }
                    },
                )
            }
            add(
                PathAttempt { generation ->
                    connectRelay(generation, keyManagers, trustManagers, identity)
                },
            )
        }
        val connection = try {
            race.connect(attempts)
        } catch (error: Exception) {
            android.util.Log.w("PimbHostConnector", "path race failed: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
        val path = when (connection.kind) {
            TransportPathKind.DIRECT -> TransportPath.DIRECT
            TransportPathKind.RELAY -> TransportPath.RELAY
        }
        val transport = PimbStreamTransport(connection.channel)
        val connector = Connector(transport, path)
        transport.send(
            FrameKind.Json,
            WireMessages.encode(
                "client.hello",
                WireMessages.clientHello(profile.deviceId, appVersion, listOf("terminal", "voice", "push", "agents")),
            ),
        )
        onEvent(
            HostConnectionEvent.DeviceAuthenticated(
                path,
                certificateSerialFromPeer(connection) ?: profile.certificateSerial,
            ),
        )
        scope.launch(Dispatchers.IO) { readerLoop(transport) }
        return connector
    }

    private suspend fun connectRelay(
        generation: Long,
        keyManagers: Array<KeyManager>,
        trustManagers: Array<TrustManager>,
        identity: CertificateIdentity,
    ): PathConnection {
        val relay = try {
            URI(profile.relayWssUrl)
        } catch (error: Exception) {
            throw NetworkException(NetworkError.MALFORMED_URI, "Paired relay URL is malformed", error)
        }
        if (relay.scheme != "wss" || relay.host == null) {
            throw NetworkException(NetworkError.MALFORMED_URI, "Paired relay URL is not a wss:// endpoint")
        }
        val proof = RelayProofCodec().encodeSelfIssuedProof(
            RelayAudience.DEVICE_DATA,
            profile.routeId,
            profile.deviceRouteKeyId,
            deviceKeys.routePayloadSigner().asRelayProofSigner(),
        )
        val basePath = (relay.rawPath ?: "").trimEnd('/')
        val tunnelUri = URI("wss", null, relay.host, relay.port, "$basePath/v1/routes/${profile.routeId}/data", null, null)
        val tunnel = try {
            WebSocketBinaryByteChannel.connect(tunnelUri, mapOf("X-Relay-Proof" to proof.toString(Charsets.UTF_8)))
        } catch (error: NetworkException) {
            android.util.Log.w("PimbHostConnector", "relay tunnel failed code=${error.code} msg=${error.message}")
            throw error
        } catch (error: Exception) {
            android.util.Log.w("PimbHostConnector", "relay tunnel unreachable: ${error.javaClass.simpleName}: ${error.message}")
            throw NetworkException(NetworkError.RELAY_PAIRING_UNAVAILABLE, "Relay data tunnel is unreachable", error)
        }
        val context = TlsContexts.mutual(keyManagers, trustManagers, identity)
        val tls = io.github.verybigsad.pimobile.network.TlsByteChannel.connect(
            tunnel,
            context,
            requireNotNull(tunnelUri.host),
            if (tunnelUri.port == -1) 443 else tunnelUri.port,
        )
        return object : PathConnection {
            override val kind: TransportPathKind = TransportPathKind.RELAY
            override val generation: Long = generation
            override val channel: DuplexByteChannel = tls
            override suspend fun authenticate() = Unit
            override suspend fun close() = tls.close()
        }
    }

    private fun certificateSerialFromPeer(connection: PathConnection): String? = null

    private suspend fun readerLoop(transport: PimbStreamTransport) {
        val snapshots = HashMap<String, SnapshotAssembly>()
        val replays = HashMap<String, Uint64Decimal>()
        val mapper = EventProjectionMapper { System.currentTimeMillis() }
        try {
            while (true) {
                val frame = transport.receive()
                when (frame.kind) {
                    FrameKind.TerminalBytes -> {
                        val payload = runCatching { PimbCodec.decodeTerminalPayload(frame.payload) }.getOrNull() ?: continue
                        onEvent(HostConnectionEvent.TerminalOutput(payload.terminalGeneration, payload.sequence, payload.data))
                    }

                    FrameKind.Json -> {
                        val envelope = WireMessages.parseEnvelope(frame.payload) ?: continue
                        handleEnvelope(envelope, snapshots, replays, mapper)
                    }

                    else -> Unit
                }
            }
        } catch (_: Exception) {
            onEvent(HostConnectionEvent.Disconnected("TRANSPORT_CLOSED"))
        }
    }

    private suspend fun handleEnvelope(
        envelope: WireMessages.Envelope,
        snapshots: MutableMap<String, SnapshotAssembly>,
        replays: MutableMap<String, Uint64Decimal>,
        mapper: EventProjectionMapper,
    ) {
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

    private inner class Connector(
        private val transport: PimbStreamTransport,
        override val path: TransportPath,
    ) : HostConnector {
        override suspend fun send(type: String, body: JsonObject, replyTo: String?) {
            transport.send(FrameKind.Json, WireMessages.encode(type, body, replyTo))
        }

        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) {
            var offset = 0
            var chunkSequence = sequence
            while (offset < bytes.size) {
                val end = minOf(bytes.size, offset + io.github.verybigsad.pimobile.protocol.ProtocolConstants.maxBinaryDataBytes)
                transport.send(
                    FrameKind.TerminalBytes,
                    PimbCodec.encodeTerminalPayload(TerminalPayload(terminalGeneration, chunkSequence, bytes.copyOfRange(offset, end))),
                )
                chunkSequence += 1u
                offset = end
            }
        }

        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) {
            transport.send(
                FrameKind.AudioPcm,
                PimbCodec.encodeStreamPayload(StreamPayload(streamId, sequence, offset, bytes)),
            )
        }

        override suspend fun close() = transport.close()
    }

    companion object {
        fun pairedTrustManagers(profile: PairedProfile): Array<TrustManager> {
            val factory = CertificateFactory.getInstance("X.509")
            val certificates = PEM_CERTIFICATE.findAll(profile.caCertificatePem).map { match ->
                val der = java.util.Base64.getMimeDecoder().decode(match.groupValues[1].filterNot(Char::isWhitespace))
                factory.generateCertificate(ByteArrayInputStream(der)) as java.security.cert.X509Certificate
            }.toList()
            require(certificates.isNotEmpty()) { "paired profile has no CA certificates" }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
            certificates.forEachIndexed { index, certificate ->
                store.setCertificateEntry("paired-ca-$index", certificate)
            }
            val trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustFactory.init(store)
            return trustFactory.trustManagers
        }

        private val PEM_CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----")
    }
}

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { primitive ->
        runCatching { primitive.content.toBooleanStrict() }.getOrNull()
    }
