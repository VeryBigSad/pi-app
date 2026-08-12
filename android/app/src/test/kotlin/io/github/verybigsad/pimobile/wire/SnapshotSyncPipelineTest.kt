package io.github.verybigsad.pimobile.wire

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.TimelineEntry
import io.github.verybigsad.pimobile.session.buildTimelineEntries
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.security.ProfileStore
import io.github.verybigsad.pimobile.state.AppClock
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.state.AppPasskeyAvailability
import io.github.verybigsad.pimobile.state.CachePort
import io.github.verybigsad.pimobile.state.OlderMessagesPage
import io.github.verybigsad.pimobile.state.PiAppCoordinator
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.CanonicalResyncSignal
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.TrustStateEntity
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

private const val MAC_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
private const val DEVICE_ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
private const val SESSION_EMPTY = "11111111-1111-4111-8111-111111111111"
private const val SESSION_CONTENT = "22222222-2222-4222-8222-222222222222"
private const val EPOCH = "33333333-3333-4333-8333-333333333333"

/**
 * Drives real wire envelopes through [HostInboundRouter] into [PiAppCoordinator]: the exact
 * frame sequences the Mac gateway publishes during `sync.resume` (sync.reset, snapshot.begin,
 * snapshot.page*, snapshot.end, sync.complete) plus live `message.append` appends.
 */
@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotSyncPipelineTest {
    private fun envelope(type: String, body: JsonObject): WireMessages.Envelope =
        WireMessages.parseEnvelope(WireMessages.encode(type, body))!!

    private fun sha256(text: String): String = io.github.verybigsad.pimobile.protocol.sha256Hex(text.encodeToByteArray())

    private fun metaEntry(id: String): JsonObject {
        val raw = "{\"id\":\"$id\"}"
        return buildJsonObject {
            put("id", id)
            put("type", "extension_ui_request")
            put("rawJson", raw)
            put("rawSize", raw.encodeToByteArray().size.toString())
            put("rawSha256", sha256(raw))
        }
    }

    private fun messageEntry(id: String, role: String, text: String): JsonObject = buildJsonObject {
        val raw = "{\"type\":\"message_end\"}"
        put("id", id)
        put("messageId", id)
        put("type", "message_end")
        put("role", role)
        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
        put("rawJson", raw)
        put("rawSize", raw.encodeToByteArray().size.toString())
        put("rawSha256", sha256(raw))
    }

    private fun snapshotFrames(
        router: HostInboundRouter,
        sessionId: String,
        sequence: String,
        entries: List<JsonObject>,
    ) {
        router.handle(envelope("sync.reset", buildJsonObject { put("sessionId", sessionId); put("reason", "canonical_snapshot") }))
        router.handle(
            envelope(
                "snapshot.begin",
                buildJsonObject {
                    put("sessionId", sessionId)
                    put("streamEpoch", EPOCH)
                    put("sequence", sequence)
                },
            ),
        )
        entries.forEach { entry ->
            router.handle(
                envelope(
                    "snapshot.page",
                    buildJsonObject {
                        put("sessionId", sessionId)
                        put("streamEpoch", EPOCH)
                        put("sequence", sequence)
                        put("entries", JsonArray(listOf(entry)))
                    },
                ),
            )
        }
        router.handle(envelope("snapshot.end", buildJsonObject { put("sessionId", sessionId); put("streamEpoch", EPOCH); put("sequence", sequence) }))
    }

    @Test
    fun `router emits SnapshotReady for a snapshot with zero content entries`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        snapshotFrames(router, SESSION_EMPTY, sequence = "3", entries = listOf(metaEntry("meta-1"), metaEntry("meta-2")))
        val ready = events.filterIsInstance<HostConnectionEvent.SnapshotReady>()
        assertThat(ready).hasSize(1)
        assertThat(ready.single().sessionId).isEqualTo(SessionId(SESSION_EMPTY))
        assertThat(ready.single().messages).isEmpty()
        assertThat(ready.single().cursor.sequence.text).isEqualTo("3")
    }

    @Test
    fun `router still emits a snapshot outcome when a content entry cannot be mapped`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add)
        val broken = buildJsonObject {
            put("id", "broken-1")
            put("messageId", "broken-1")
            put("role", "assistant")
            put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "x") })))
            // no rawJson/rawRef: violates the authenticity contract
        }
        snapshotFrames(router, SESSION_CONTENT, sequence = "4", entries = listOf(broken))
        // The host blocks the whole sync queue on the per-session ack; an unmapped snapshot
        // must still surface an outcome the coordinator can ack (without committing content).
        assertThat(events.any { it is HostConnectionEvent.SnapshotRejected || it is HostConnectionEvent.SnapshotReady }).isTrue()
    }

    @Test
    fun `router maps live message append into a canonical event`() {
        val events = ArrayList<HostConnectionEvent>()
        val router = HostInboundRouter(events::add, nowEpochMillis = { 1_000L })
        val raw = "{\"type\":\"message_end\"}"
        router.handle(
            envelope(
                "message.append",
                buildJsonObject {
                    put("sessionId", SESSION_CONTENT)
                    put("streamEpoch", EPOCH)
                    put("sequence", "9")
                    put("appendId", "append-9")
                    put("piType", "message_end")
                    put("rawJson", raw)
                    put("rawSize", raw.encodeToByteArray().size.toString())
                    put("rawSha256", sha256(raw))
                    put(
                        "projection",
                        buildJsonObject {
                            put("type", "message_end")
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", "assistant")
                                    put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "PONG") })))
                                },
                            )
                        },
                    )
                },
            ),
        )
        val canonical = events.filterIsInstance<HostConnectionEvent.CanonicalEvent>()
        assertThat(canonical).hasSize(1)
        assertThat(canonical.single().sessionId).isEqualTo(SessionId(SESSION_CONTENT))
        assertThat(canonical.single().finalized).isNotNull()
        assertThat(canonical.single().finalized!!.contentJson).contains("PONG")
    }

    @Test
    fun `two session snapshots commit and ack even when one is empty and one was cached`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val cache = FakeCache()
        // Suspect (b): SESSION_CONTENT is already cached with a stale cursor AHEAD of the
        // snapshot fence (7 > 3); the snapshot must still commit its content.
        cache.sessions[SESSION_CONTENT] = SessionEntity(
            sessionId = SESSION_CONTENT,
            cwd = "/Users/test/content",
            displayName = "Content session",
            provider = "unknown",
            modelId = "unknown",
            thinkingLevel = "unknown",
            canonicalCursor = CanonicalAppendCursor(EPOCH, "7", null, null),
            updatedAtEpochMs = 500L,
        )
        val sent = ArrayList<Pair<String, String>>()
        val coordinator = PiAppCoordinator(
            scope = scope,
            cache = cache,
            profiles = FakeProfiles(trustedProfile()),
            connectorFactory = { _, _ ->
                HostConnectionRunner {
                    object : HostConnector {
                        override val path = io.github.verybigsad.pimobile.model.TransportPath.DIRECT

                        override suspend fun send(type: String, body: JsonObject, replyTo: String?) {
                            sent += type to body.toString()
                        }

                        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = Unit
                        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) = Unit
                        override suspend fun close() = Unit
                    }
                }
            },
            pairingRunnerFactory = { _, _, _ -> suspend { } },
            passkeyBridge = object : io.github.verybigsad.pimobile.state.PasskeyBridgePort {
                override suspend fun performAssertion(ceremonyId: String, optionsJson: String): Pair<String?, String?> = null to "unused"
            },
            voicePort = object : io.github.verybigsad.pimobile.state.VoicePort {
                override suspend fun setForeground(foreground: Boolean) = Unit
                override suspend fun start(): String? = null
                override suspend fun stop() = Unit
                override suspend fun cancel() = Unit
                override suspend fun onMacError(sessionId: String, error: io.github.verybigsad.pimobile.voice.MacVoiceError) = Unit
            },
            terminalPort = { null },
            wakeNotifier = object : io.github.verybigsad.pimobile.state.WakeNotificationPort {
                override fun notifyLockedWake() = Unit
            },
            pushDrain = object : io.github.verybigsad.pimobile.state.PushDrainPort {
                override suspend fun drain(connector: HostConnector) = Unit
            },
            passkeyAvailability = { AppPasskeyAvailability.AVAILABLE },
            pendingResyncSignal = { null },
            clock = object : AppClock {
                override fun nowEpochMillis(): Long = 1_000L
                override fun nowMonotonicMillis(): Long = 1_000L
            },
        )
        try {
            coordinator.start()
            scope.advanceUntilIdle()
            // hydrate -> connect() is connection generation 1.
            coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.DeviceAuthenticated(io.github.verybigsad.pimobile.model.TransportPath.DIRECT, "aa".repeat(32))))
            scope.advanceUntilIdle()
            coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.AuthResult("ceremony-1", true)))
            scope.advanceUntilIdle()
            assertThat(sent.map { it.first }).contains("sync.resume")

            val router = HostInboundRouter({ event -> coordinator.submit(AppIntent.ConnectionEvent(1, event)) }, nowEpochMillis = { 1_000L })
            // Session 1: snapshot with only meta records (zero content entries).
            snapshotFrames(router, SESSION_EMPTY, sequence = "5", entries = listOf(metaEntry("meta-1")))
            // Session 2: snapshot with message-shaped entries over a stale cached cursor.
            snapshotFrames(
                router,
                SESSION_CONTENT,
                sequence = "3",
                entries = listOf(
                    metaEntry("meta-2"),
                    messageEntry("msg-$EPOCH-1", "user", "hello pi"),
                    messageEntry("msg-$EPOCH-2", "assistant", "hello human"),
                ),
            )
            router.handle(envelope("sync.complete", buildJsonObject { }))
            scope.advanceUntilIdle()

            // Suspect (a): both sessions must commit and ack, including the empty one; the
            // host waits per-session and a missing ack stalls every later session.
            val acks = sent.filter { it.first == "event.ack" }.map { it.second }
            assertThat(acks.any { it.contains(SESSION_EMPTY) && it.contains("\"sequence\":\"5\"") }).isTrue()
            assertThat(acks.any { it.contains(SESSION_CONTENT) && it.contains("\"sequence\":\"3\"") }).isTrue()

            val state = coordinator.state.value
            assertThat(state.syncing).isFalse()
            val empty = state.sessions[SessionId(SESSION_EMPTY)]!!
            assertThat(empty.conversation.availability).isEqualTo(io.github.verybigsad.pimobile.model.CanonicalAvailability.Current)
            assertThat(empty.conversation.finalizedMessages).isEmpty()
            val content = state.sessions[SessionId(SESSION_CONTENT)]!!
            assertThat(content.conversation.availability).isEqualTo(io.github.verybigsad.pimobile.model.CanonicalAvailability.Current)
            assertThat(content.conversation.cursor!!.sequence.text).isEqualTo("3")
            assertThat(content.conversation.finalizedMessages.map { it.id.value })
                .containsExactly("msg-$EPOCH-1", "msg-$EPOCH-2")
                .inOrder()

            // Suspect (c): the timeline state for the selected session carries the text parts.
            val timeline = buildTimelineEntries(content)
            val finalized = timeline.filterIsInstance<TimelineEntry.Finalized>()
            assertThat(finalized).hasSize(2)
            val textParts = finalized.flatMap { it.message.content }
                .filter { it.kind == io.github.verybigsad.pimobile.model.MessageContentKind.TEXT }
            assertThat(textParts.map { it.projection }.toString()).contains("hello pi")
            assertThat(textParts.map { it.projection }.toString()).contains("hello human")
        } finally {
            coordinator.close()
        }
    }

    private fun trustedProfile() = PairedProfile(
        deviceId = DEVICE_ID,
        macId = MAC_ID,
        macDisplayName = "Test Mac",
        relayWssUrl = "wss://relay.example.com",
        routeId = "route-1",
        deviceRouteKeyId = "device-route-$DEVICE_ID",
        directCandidates = listOf(io.github.verybigsad.pimobile.security.DirectCandidate("192.168.1.10", 8443)),
        caCertificatePem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
        certificateSerial = "aa".repeat(32),
        certificateNotAfterEpochMillis = 10_000_000L,
        endpointId = null,
    )

    private class FakeProfiles(var profile: PairedProfile?) : ProfileStore {
        override fun load(): PairedProfile? = profile
        override fun save(profile: PairedProfile) {
            this.profile = profile
        }

        override fun delete() {
            profile = null
        }
    }

    private class FakeCache : CachePort {
        val sessions = HashMap<String, SessionEntity>()
        val messages = HashMap<String, MutableList<MessageEntity>>()

        override suspend fun loadTrustState(macId: String): TrustStateEntity? = null
        override suspend fun loadSessions(): List<SessionEntity> = sessions.values.toList()
        override suspend fun loadRecentMessages(sessionId: String, limit: Int): List<MessageEntity> =
            messages[sessionId].orEmpty().takeLast(limit)

        override suspend fun messageCount(sessionId: String): Int = messages[sessionId]?.size ?: 0
        override suspend fun loadOlderMessages(sessionId: String, beforeAppendOrder: String, limit: Int): OlderMessagesPage =
            OlderMessagesPage(emptyList(), hasMore = false)

        override suspend fun loadDrafts(): List<DraftEntity> = emptyList()
        override suspend fun upsertDraft(draft: DraftEntity) = Unit
        override suspend fun upsertTrustState(trustState: TrustStateEntity) = Unit
        override suspend fun deleteTrustState(macId: String) = Unit
        override suspend fun markCanonicalUnavailable() = Unit

        override suspend fun commitFinalizedMessage(session: SessionEntity, message: MessageEntity) {
            sessions[session.sessionId] = session
            messages.getOrPut(message.sessionId) { ArrayList() }.add(message)
        }

        override suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) {
            sessions[session.sessionId] = session
            this.messages[session.sessionId] = messages.toMutableList()
        }

        override suspend fun resetSessionContent(session: SessionEntity) {
            sessions[session.sessionId] = session
            messages.remove(session.sessionId)
        }

        override suspend fun committedCursors(): List<Pair<String, CanonicalAppendCursor>> =
            sessions.values.mapNotNull { entity -> entity.canonicalCursor?.let { entity.sessionId to it } }

        override suspend fun acknowledgeCanonicalResync(signal: CanonicalResyncSignal): Boolean = true
    }
}
