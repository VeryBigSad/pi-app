package io.github.verybigsad.pimobile

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.pairing.PairingProgressEvent
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.security.ProfileStore
import io.github.verybigsad.pimobile.session.CommandNoticeUiState
import io.github.verybigsad.pimobile.session.SessionDetailEvent
import io.github.verybigsad.pimobile.session.SessionListEvent
import io.github.verybigsad.pimobile.session.VoicePermissionUiState
import io.github.verybigsad.pimobile.state.AppClock
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.state.AppPasskeyAvailability
import io.github.verybigsad.pimobile.state.CachePort
import io.github.verybigsad.pimobile.state.PairingUiState
import io.github.verybigsad.pimobile.state.PiAppCoordinator
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.CanonicalResyncSignal
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.TrustStateEntity
import io.github.verybigsad.pimobile.voice.VoiceTranscript
import io.github.verybigsad.pimobile.voice.VoiceTranscriptKind
import io.github.verybigsad.pimobile.wire.HostConnectionEvent
import io.github.verybigsad.pimobile.wire.HostConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val MAC_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
private const val DEVICE_ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
private const val SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e"
private const val SESSION_B = "0f8fad5b-d9cb-469f-a165-70867728950f"

private class FakeClock(var epoch: Long = 1_000L) : AppClock {
    override fun nowEpochMillis(): Long = epoch
    override fun nowMonotonicMillis(): Long = epoch
}

private class FakeProfileStore(var profile: PairedProfile? = null) : ProfileStore {
    override fun load(): PairedProfile? = profile
    override fun save(profile: PairedProfile) {
        this.profile = profile
    }

    override fun delete() {
        profile = null
    }
}

private class FakeCachePort : CachePort {
    val trust = HashMap<String, TrustStateEntity>()
    val sessions = HashMap<String, SessionEntity>()
    val drafts = HashMap<String, DraftEntity>()
    val acknowledged = ArrayList<CanonicalResyncSignal>()
    val canonicalCommits = ArrayList<Pair<SessionEntity, MessageEntity?>>()
    var failCommits = false

    override suspend fun loadTrustState(macId: String): TrustStateEntity? = trust[macId]
    override suspend fun loadSessions(): List<SessionEntity> = sessions.values.toList()
    override suspend fun loadSession(sessionId: String): SessionEntity? = sessions[sessionId]
    override suspend fun upsertSession(session: SessionEntity) {
        sessions[session.sessionId] = session
    }
    val recentMessages = HashMap<String, List<MessageEntity>>()
    val messageCounts = HashMap<String, Int>()
    var olderPage = io.github.verybigsad.pimobile.state.OlderMessagesPage(emptyList(), hasMore = false)

    override suspend fun loadRecentMessages(sessionId: String, limit: Int): List<MessageEntity> =
        recentMessages[sessionId].orEmpty().takeLast(limit)

    override suspend fun messageCount(sessionId: String): Int = messageCounts[sessionId] ?: recentMessages[sessionId]?.size ?: 0

    override suspend fun loadOlderMessages(sessionId: String, beforeAppendOrder: String, limit: Int): io.github.verybigsad.pimobile.state.OlderMessagesPage =
        olderPage
    override suspend fun loadDrafts(): List<DraftEntity> = drafts.values.toList()
    override suspend fun upsertDraft(draft: DraftEntity) {
        drafts[draft.sessionId] = draft
    }

    override suspend fun upsertTrustState(trustState: TrustStateEntity) {
        trust[trustState.macId] = trustState
    }

    override suspend fun deleteTrustState(macId: String) {
        trust.remove(macId)
    }

    override suspend fun markCanonicalUnavailable() {
        sessions.clear()
    }

    override suspend fun commitCanonicalEvent(session: SessionEntity, finalized: MessageEntity?) {
        if (failCommits) throw IllegalStateException("disk full")
        sessions[session.sessionId] = session
        canonicalCommits += session to finalized
        if (finalized != null) {
            recentMessages[session.sessionId] = recentMessages[session.sessionId].orEmpty() + finalized
        }
    }

    override suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) {
        if (failCommits) throw IllegalStateException("disk full")
        sessions[session.sessionId] = session
    }

    override suspend fun resetSessionContent(session: SessionEntity) {
        sessions[session.sessionId] = session
    }

    override suspend fun revokeAndPurge(macId: String, revokedAtEpochMs: Long, reasonCode: String) {
        sessions.clear()
        recentMessages.clear()
        messageCounts.clear()
        drafts.clear()
        trust[macId] = TrustStateEntity(
            macId = macId,
            status = io.github.verybigsad.pimobile.storage.StoredTrustStatus.REVOKED,
            displayName = null,
            certificateSerial = null,
            certificateNotAfterEpochMs = null,
            revokedAtEpochMs = revokedAtEpochMs,
            revocationReasonCode = reasonCode,
            updatedAtEpochMs = revokedAtEpochMs,
        )
    }

    override suspend fun committedCursors(): List<Pair<String, CanonicalAppendCursor>> =
        sessions.values.mapNotNull { it.canonicalCursor?.let { cursor -> it.sessionId to cursor } }

    override suspend fun acknowledgeCanonicalResync(signal: CanonicalResyncSignal): Boolean {
        acknowledged += signal
        return true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PiAppCoordinatorTest {
    private fun runHarnessTest(
        profiles: FakeProfileStore = FakeProfileStore(),
        resyncSignal: CanonicalResyncSignal? = null,
        block: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val harness = Harness(this, profiles = profiles, resyncSignal = resyncSignal)
        try {
            block(harness)
        } finally {
            harness.coordinator.close()
        }
    }

    private class Harness(
        scope: TestScope,
        val cache: FakeCachePort = FakeCachePort(),
        val profiles: FakeProfileStore = FakeProfileStore(),
        val clock: FakeClock = FakeClock(),
        resyncSignal: CanonicalResyncSignal? = null,
        val sentMessages: MutableList<Pair<String, String>> = ArrayList(),
        val voiceCanceled: MutableList<Boolean> = ArrayList(),
        val voiceStarts: MutableList<SessionId> = ArrayList(),
        val lockedWakes: MutableList<Int> = ArrayList(),
        var voiceStartResult: String? = null,
        var failCommandSends: Boolean = false,
        pairingRunner: ((PairingProgressEvent) -> Unit) -> Unit = {},
    ) {
        val coordinator: PiAppCoordinator
        private var pairingCallback: () -> ((PairingProgressEvent) -> Unit)?

        init {
            var capturedPairingCallback: ((PairingProgressEvent) -> Unit)? = null
            coordinator = PiAppCoordinator(
                scope = scope,
                cache = cache,
                profiles = profiles,
                connectorFactory = { _, onEvent ->
                    io.github.verybigsad.pimobile.wire.HostConnectionRunner {
                        object : HostConnector {
                            override val path = io.github.verybigsad.pimobile.model.TransportPath.DIRECT

                            override suspend fun send(
                                type: String,
                                body: kotlinx.serialization.json.JsonObject,
                                replyTo: String?,
                            ) {
                                if (failCommandSends && type == "command.submit") error("send failed")
                                sentMessages += type to body.toString()
                            }

                            override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = Unit
                            override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) = Unit
                            override suspend fun close() = Unit
                        }
                    }
                },
                pairingRunnerFactory = { _, _, onEvent ->
                    capturedPairingCallback = onEvent
                    suspend { pairingRunner(onEvent) }
                },
                passkeyBridge = object : io.github.verybigsad.pimobile.state.PasskeyBridgePort {
                    override suspend fun performAssertion(ceremonyId: String, optionsJson: String): Pair<String?, String?> =
                        "{\"id\":\"cred\"}" to null
                },
                voicePort = object : io.github.verybigsad.pimobile.state.VoicePort {
                    override suspend fun setForeground(foreground: Boolean) = Unit
                    override suspend fun start(targetSessionId: SessionId): String? {
                        voiceStarts += targetSessionId
                        return voiceStartResult
                    }
                    override suspend fun stop() = Unit
                    override suspend fun cancel() {
                        voiceCanceled += true
                    }

                    override suspend fun onMacError(sessionId: String, error: io.github.verybigsad.pimobile.voice.MacVoiceError) = Unit
                },
                terminalPort = { null },
                wakeNotifier = object : io.github.verybigsad.pimobile.state.WakeNotificationPort {
                    override fun notifyLockedWake() {
                        lockedWakes += 1
                    }
                },
                pushDrain = object : io.github.verybigsad.pimobile.state.PushDrainPort {
                    override suspend fun drain(connector: HostConnector) = Unit
                },
                passkeyAvailability = { AppPasskeyAvailability.AVAILABLE },
                pendingResyncSignal = { resyncSignal },
                clock = clock,
                backgroundLockMillis = 5 * 60 * 1_000L,
                reconnectBaseDelayMillis = 10_000L,
            )
            pairingCallback = { capturedPairingCallback }
        }

        fun emitPairing(event: PairingProgressEvent) {
            pairingCallback()?.invoke(event)
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

    private fun messageEntity(sessionId: String = SESSION, messageId: String, appendOrder: String): MessageEntity {
        val raw = "{\"messageId\":\"$messageId\"}"
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return MessageEntity(
            sessionId = sessionId,
            messageId = messageId,
            parentId = null,
            appendOrder = appendOrder,
            appendId = "append-$messageId",
            role = io.github.verybigsad.pimobile.storage.StoredMessageRole.USER,
            state = io.github.verybigsad.pimobile.storage.FinalizedMessageState.FINALIZED,
            contentJson = "[{\"id\":\"c-$messageId\",\"type\":\"text\",\"text\":\"body $messageId\"}]",
            authoritativeFinal = io.github.verybigsad.pimobile.storage.AuthoritativeFinalMetadata(
                source = io.github.verybigsad.pimobile.storage.FinalMetadataSource.AUTHORITATIVE,
                rawJson = raw,
                rawRef = null,
                rawSizeBytes = raw.encodeToByteArray().size.toLong(),
                rawSha256 = sha256,
                projectionJson = "{}",
                signature = null,
                redacted = false,
                createdAtEpochMs = 100L,
                finalizedAtEpochMs = 100L,
            ),
        )
    }

    private fun sessionEntity(sessionId: String = SESSION, cursor: CanonicalAppendCursor? = null) = SessionEntity(
        sessionId = sessionId,
        cwd = "/Users/test/project",
        displayName = "Test session",
        provider = "anthropic",
        modelId = "claude",
        thinkingLevel = "high",
        canonicalCursor = cursor,
        updatedAtEpochMs = 500L,
    )

    private suspend fun TestScope.readyForCommand(harness: Harness) {
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, null),
        )
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.DeviceAuthenticated(
                    io.github.verybigsad.pimobile.model.TransportPath.DIRECT,
                    "cert",
                ),
            ),
        )
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.AuthResult("ceremony", true)))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.connection).isInstanceOf(ConnectionState.Ready::class.java)
    }

    private fun commandId(harness: Harness): String = Regex("\\\"commandId\\\":\\\"([^\\\"]+)\\\"")
        .find(harness.sentMessages.last { it.first == "command.submit" }.second)
        ?.groupValues?.get(1)
        ?: error("command id missing")

    @Test
    fun `hydration without profile lands unpaired with pairing landing`() = runHarnessTest { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        assertThat(state.hydrated).isTrue()
        assertThat(state.trust).isEqualTo(TrustState.Unpaired)
        assertThat(state.locked).isTrue()
        assertThat(state.pairing).isNull()
        harness.coordinator.submit(AppIntent.StartPairing)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.pairing).isEqualTo(PairingUiState.AwaitingScan)
    }

    @Test
    fun `hydration with profile stays locked after process death and connects`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, null),
        )
        harness.cache.drafts[SESSION] = DraftEntity(SESSION, "hello draft", null, 3, 100L)
        harness.coordinator.start()
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        assertThat(state.trust).isInstanceOf(TrustState.Trusted::class.java)
        assertThat(state.locked).isTrue()
        assertThat(state.connection).isEqualTo(ConnectionState.Disconnected(DisconnectReason.PROCESS_DEATH))
        val session = state.sessions[SessionId(SESSION)]!!
        assertThat(session.draft.typedText).isEqualTo("hello draft")
        assertThat(session.draft.revision).isEqualTo(3)
        assertThat(session.conversation.availability).isEqualTo(CanonicalAvailability.Current)
    }

    @Test
    fun `voice stream stays bound across navigation and never changes typed text or sends`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity()
        harness.cache.sessions[SESSION_B] = sessionEntity(SESSION_B)
        harness.cache.drafts[SESSION] = DraftEntity(SESSION, "typed A", null, 0, 100L)
        harness.cache.drafts[SESSION_B] = DraftEntity(SESSION_B, "typed B", null, 0, 100L)
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        harness.coordinator.submit(
            AppIntent.VoiceTranscriptReceived(
                SessionId(SESSION),
                VoiceTranscript(SESSION, 0u, 1u, VoiceTranscriptKind.PARTIAL, "spoken partial"),
            ),
        )
        advanceUntilIdle()
        var draftA = harness.coordinator.state.value.sessions[SessionId(SESSION)]!!.draft
        assertThat(draftA.typedText).isEqualTo("typed A")
        assertThat(draftA.transcriptionText).isEqualTo("spoken partial")

        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION_B))))
        harness.coordinator.submit(
            AppIntent.VoiceTranscriptReceived(
                SessionId(SESSION),
                VoiceTranscript(SESSION, 1u, 0u, VoiceTranscriptKind.FINAL, "spoken final"),
            ),
        )
        advanceUntilIdle()

        draftA = harness.coordinator.state.value.sessions[SessionId(SESSION)]!!.draft
        val draftB = harness.coordinator.state.value.sessions[SessionId(SESSION_B)]!!.draft
        assertThat(harness.coordinator.state.value.selectedSessionId).isEqualTo(SessionId(SESSION_B))
        assertThat(draftA.typedText).isEqualTo("typed A")
        assertThat(draftA.transcriptionText).isEqualTo("spoken final")
        assertThat(draftB.typedText).isEqualTo("typed B")
        assertThat(draftB.transcriptionText).isNull()
        assertThat(harness.sentMessages).isEmpty()
    }

    @Test
    fun `cache reset signal keeps canonical content unavailable until snapshots commit`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
        resyncSignal = CanonicalResyncSignal("gen-1", io.github.verybigsad.pimobile.storage.CacheResetReason.KEY_UNAVAILABLE),
    ) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity()
        harness.coordinator.start()
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        assertThat(state.resyncPending).isTrue()
        assertThat(state.sessions[SessionId(SESSION)]!!.conversation.availability)
            .isInstanceOf(CanonicalAvailability.Unavailable::class.java)
        assertThat(harness.cache.acknowledged).isEmpty()
    }

    @Test
    fun `background for five minutes locks and device lock locks immediately`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        advanceUntilIdle()

        harness.coordinator.submit(AppIntent.ForegroundChanged(false))
        advanceTimeBy(4 * 60 * 1_000L)
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ForegroundChanged(false))
        advanceTimeBy(5 * 60 * 1_000L + 1)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.connection)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.AUTH_REQUIRED))

        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.DeviceLockDetected)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.connection)
            .isEqualTo(ConnectionState.Disconnected(DisconnectReason.AUTH_REQUIRED))
        assertThat(harness.voiceCanceled).isNotEmpty()
    }

    @Test
    fun `pairing completion persists trust and requires serial and expiry`() = runHarnessTest { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.PairingUriScanned("pimobile://pair?v=1&d=invalid"))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.pairing)
            .isEqualTo(PairingUiState.Failed("PAIRING_INVITATION_INVALID"))

        harness.coordinator.submit(AppIntent.StartPairing)
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.PairingEvent(0, PairingProgressEvent.Completed(trustedProfile())))
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        val trust = state.trust as TrustState.Trusted
        assertThat(trust.macId.value).isEqualTo(MAC_ID)
        assertThat(trust.certificateSerial).isEqualTo("aa".repeat(32))
        assertThat(trust.certificateNotAfterEpochMillis).isEqualTo(10_000_000L)
        assertThat(state.pairing).isNull()
        assertThat(harness.profiles.profile).isNotNull()
        assertThat(harness.cache.trust[MAC_ID]).isNotNull()
    }

    @Test
    fun `stale pairing generation events are ignored`() = runHarnessTest { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.StartPairing)
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.PairingUriScanned("pimobile://pair?v=1&d=invalid"))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.PairingCancelled)
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.PairingEvent(0, PairingProgressEvent.Completed(trustedProfile())))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.trust).isEqualTo(TrustState.Unpaired)
        assertThat(harness.profiles.profile).isNull()
    }

    @Test
    fun `draft edits persist through the cache port with reducer revisions`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity()
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("fix the flake")))
        advanceUntilIdle()
        val draft = harness.cache.drafts[SESSION]!!
        assertThat(draft.typedText).isEqualTo("fix the flake")
        assertThat(draft.revision).isEqualTo(1)
        assertThat(harness.coordinator.state.value.sessions[SessionId(SESSION)]!!.draft.typedText)
            .isEqualTo("fix the flake")
    }

    @Test
    fun `deep link stays pending while locked and is consumed after auth and sync`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, null),
        )
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.DeepLink(SessionId(SESSION)))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.selectedSessionId).isNull()
        assertThat(harness.coordinator.state.value.pendingDeepLinkSessionId).isEqualTo(SessionId(SESSION))
    }

    @Test
    fun `wake while locked posts generic notification and never touches credentials`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.WakeReceived)
        advanceUntilIdle()
        assertThat(harness.lockedWakes).hasSize(1)
        assertThat(harness.sentMessages).isEmpty()
    }

    @Test
    fun `sync complete ends syncing and leaves the session list online`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, null),
        )
        harness.coordinator.start()
        advanceUntilIdle()
        // hydrate -> connect() exactly once, so the live connection generation is 1.
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(1, HostConnectionEvent.DeviceAuthenticated(io.github.verybigsad.pimobile.model.TransportPath.DIRECT, "aa".repeat(32))),
        )
        advanceUntilIdle()
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(1, HostConnectionEvent.AuthResult("ceremony-1", true)),
        )
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.syncing).isTrue()
        assertThat(harness.coordinator.state.value.connection).isInstanceOf(ConnectionState.Ready::class.java)
        assertThat(harness.sentMessages.map { it.first }).contains("sync.resume")

        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.SyncComplete))
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        assertThat(state.syncing).isFalse()
        assertThat(state.connection).isInstanceOf(ConnectionState.Ready::class.java)
        val session = state.sessions[SessionId(SESSION)]!!
        assertThat(session.conversation.availability).isEqualTo(CanonicalAvailability.Current)
        assertThat(session.conversation.finalizedMessages).isEmpty()
    }

    @Test
    fun `first post-snapshot no-op and next live event remain canonical`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        val epoch = io.github.verybigsad.pimobile.model.StreamEpoch("00000000-0000-4000-8000-000000000001")
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor(epoch.value, "5", null, null),
        )
        harness.coordinator.start()
        advanceUntilIdle()

        val noOpCursor = io.github.verybigsad.pimobile.model.EventCursor(
            epoch,
            io.github.verybigsad.pimobile.model.Uint64Decimal("6"),
            null,
        )
        val liveCursor = io.github.verybigsad.pimobile.model.EventCursor(
            epoch,
            io.github.verybigsad.pimobile.model.Uint64Decimal("7"),
            null,
        )
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.CanonicalEvent(SessionId(SESSION), noOpCursor, null, null),
            ),
        )
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.CanonicalEvent(
                    SessionId(SESSION),
                    liveCursor,
                    io.github.verybigsad.pimobile.model.ConversationEvent.RunStateChanged(
                        liveCursor,
                        io.github.verybigsad.pimobile.model.SessionRunState.STREAMING,
                    ),
                    null,
                ),
            ),
        )
        advanceUntilIdle()

        val conversation = harness.coordinator.state.value.sessions.getValue(SessionId(SESSION)).conversation
        assertThat(conversation.availability).isEqualTo(CanonicalAvailability.Current)
        assertThat(conversation.cursor).isEqualTo(liveCursor)
        assertThat(conversation.runState).isEqualTo(io.github.verybigsad.pimobile.model.SessionRunState.STREAMING)
        assertThat(harness.cache.canonicalCommits.map { it.first.canonicalCursor?.sequence })
            .containsExactly("6", "7").inOrder()
    }

    @Test
    fun `canonical metadata start update final advances cursor and retains rendered final`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        val epoch = io.github.verybigsad.pimobile.model.StreamEpoch("00000000-0000-4000-8000-000000000001")
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor(epoch.value, "0", null, null),
        )
        harness.coordinator.start()
        advanceUntilIdle()
        fun cursor(sequence: String) = io.github.verybigsad.pimobile.model.EventCursor(
            epoch,
            io.github.verybigsad.pimobile.model.Uint64Decimal(sequence),
            null,
        )
        val final = io.github.verybigsad.pimobile.state.StorageMappers
            .finalizedMessage(messageEntity(messageId = "m-final", appendOrder = "1"))!!
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.CanonicalEvent(
                    SessionId(SESSION),
                    cursor("1"),
                    io.github.verybigsad.pimobile.model.ConversationEvent.RunStateChanged(
                        cursor("1"),
                        io.github.verybigsad.pimobile.model.SessionRunState.STREAMING,
                    ),
                    null,
                ),
            ),
        )
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.CanonicalEvent(SessionId(SESSION), cursor("2"), null, null)))
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.CanonicalEvent(
                    SessionId(SESSION),
                    cursor("3"),
                    io.github.verybigsad.pimobile.model.ConversationEvent.MessageFinalized(
                        cursor("3"),
                        io.github.verybigsad.pimobile.model.AppendId("1"),
                        final,
                    ),
                    messageEntity(messageId = "m-final", appendOrder = "1"),
                ),
            ),
        )
        advanceUntilIdle()

        val conversation = harness.coordinator.state.value.sessions.getValue(SessionId(SESSION)).conversation
        assertThat(conversation.cursor).isEqualTo(cursor("3"))
        assertThat(conversation.availability).isEqualTo(CanonicalAvailability.Current)
        assertThat(conversation.finalizedMessages.single().toString()).contains("body m-final")
        assertThat(harness.cache.canonicalCommits.map { it.first.canonicalCursor?.sequence }).containsExactly("1", "2", "3").inOrder()
    }

    @Test
    fun `rejected snapshot keeps six placeholders and never acknowledges uncommitted data`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        harness.coordinator.start()
        advanceUntilIdle()
        val ids = (0 until 6).map { index -> SessionId("550e8400-e29b-41d4-a716-44665544004$index") }
        val catalog = io.github.verybigsad.pimobile.network.WireBodies.SessionCatalog(
            ids.map { id ->
                io.github.verybigsad.pimobile.model.SessionCatalogEntry(
                    id = id,
                    provider = "anthropic",
                    model = "claude",
                    thinkingLevel = "high",
                    repositoryPath = "/tmp/pi-app",
                    worktreePath = null,
                    workingDirectory = "/tmp/pi-app/${id.value}",
                    parentSessionId = null,
                    createdAtEpochMillis = 1_000L,
                    updatedAtEpochMillis = 2_000L,
                )
            },
        )
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.SessionCatalogReceived(catalog)))
        val rejectedCursor = io.github.verybigsad.pimobile.model.EventCursor(
            io.github.verybigsad.pimobile.model.StreamEpoch("650e8400-e29b-41d4-a716-446655440040"),
            io.github.verybigsad.pimobile.model.Uint64Decimal("9"),
            null,
        )
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(1, HostConnectionEvent.SnapshotRejected(ids.first(), rejectedCursor)),
        )
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.keys).containsExactlyElementsIn(ids)
        assertThat(state.catalog!!.keys).containsExactlyElementsIn(ids)
        assertThat(harness.cache.sessions.keys).containsExactlyElementsIn(ids.map(SessionId::value))
        ids.forEach { id ->
            val persisted = harness.cache.sessions.getValue(id.value)
            assertThat(persisted.repositoryPath).isEqualTo("/tmp/pi-app")
            assertThat(persisted.worktreePath).isEqualTo("/tmp/pi-app/${id.value}")
            assertThat(persisted.parentSessionId).isNull()
        }
        state.sessions.values.forEach { session ->
            assertThat(session.conversation.availability).isInstanceOf(CanonicalAvailability.Unavailable::class.java)
        }
        assertThat(harness.sentMessages.filter { it.first == "event.ack" }).isEmpty()
        assertThat(state.lastError).isEqualTo("SNAPSHOT_REJECTED")
    }

    @Test
    fun `snapshot preserves catalog provider model thinking metadata`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        val cursor = io.github.verybigsad.pimobile.model.EventCursor(
            io.github.verybigsad.pimobile.model.StreamEpoch("00000000-0000-4000-8000-000000000001"),
            io.github.verybigsad.pimobile.model.Uint64Decimal("3"),
            null,
        )
        val catalog = io.github.verybigsad.pimobile.network.WireBodies.SessionCatalog(
            listOf(
                io.github.verybigsad.pimobile.model.SessionCatalogEntry(
                    id = SessionId(SESSION),
                    provider = "anthropic",
                    model = "claude-3-7-sonnet",
                    thinkingLevel = "high",
                    repositoryPath = "/work/project",
                    worktreePath = "/work/project/.worktrees/child",
                    workingDirectory = "/work/project/.worktrees/child",
                    parentSessionId = SessionId(SESSION_B),
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 2L,
                ),
            ),
        )
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.SessionCatalogReceived(catalog)))
        harness.coordinator.submit(
            AppIntent.ConnectionEvent(
                1,
                HostConnectionEvent.SnapshotReady(
                    sessionId = SessionId(SESSION),
                    cursor = cursor,
                    lastAppendId = null,
                    session = sessionEntity(cursor = CanonicalAppendCursor(cursor.streamEpoch.value, "3", null, null)).copy(
                        provider = "unknown",
                        modelId = "unknown",
                        thinkingLevel = "unknown",
                    ),
                    messages = emptyList(),
                    runState = null,
                ),
            ),
        )
        advanceUntilIdle()
        val persisted = harness.cache.sessions.getValue(SESSION)
        assertThat(persisted.provider).isEqualTo("anthropic")
        assertThat(persisted.modelId).isEqualTo("claude-3-7-sonnet")
        assertThat(persisted.thinkingLevel).isEqualTo("high")
        assertThat(persisted.repositoryPath).isEqualTo("/work/project")
        assertThat(persisted.worktreePath).isEqualTo("/work/project/.worktrees/child")
        assertThat(persisted.parentSessionId).isEqualTo(SESSION_B)
    }

    @Test
    fun `unpair durably purges old host data before re-pair cold hydrate`() = runTest {
        val first = Harness(this, profiles = FakeProfileStore(trustedProfile()))
        try {
            first.cache.sessions[SESSION] = sessionEntity(
                cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, "77"),
            )
            first.cache.recentMessages[SESSION] = listOf(messageEntity(messageId = "old", appendOrder = "7"))
            first.cache.drafts[SESSION] = DraftEntity(SESSION, "old draft", "old voice", 2, 100L)
            first.coordinator.start()
            advanceUntilIdle()
            first.coordinator.submit(AppIntent.UnpairRequested)
            advanceUntilIdle()
            assertThat(first.coordinator.state.value.trust).isEqualTo(TrustState.Unpaired)
            assertThat(first.cache.sessions).isEmpty()
            assertThat(first.cache.recentMessages).isEmpty()
            assertThat(first.cache.drafts).isEmpty()
            assertThat(first.cache.trust[MAC_ID]?.status)
                .isEqualTo(io.github.verybigsad.pimobile.storage.StoredTrustStatus.REVOKED)
            assertThat(first.profiles.profile).isNull()

            val newProfile = trustedProfile().copy(
                macId = "3fa85f64-5717-4562-b3fc-2c963f66afaa",
                macDisplayName = "New Mac",
            )
            first.coordinator.submit(AppIntent.PairingEvent(1, PairingProgressEvent.Completed(newProfile)))
            advanceUntilIdle()
            first.coordinator.close()

            val cold = Harness(this, cache = first.cache, profiles = first.profiles)
            try {
                cold.coordinator.start()
                advanceUntilIdle()
                assertThat(cold.coordinator.state.value.trust).isInstanceOf(TrustState.Trusted::class.java)
                assertThat(cold.coordinator.state.value.sessions).isEmpty()
                assertThat(cold.cache.committedCursors()).isEmpty()
            } finally {
                cold.coordinator.close()
            }
        } finally {
            first.coordinator.close()
        }
    }

    @Test
    fun `load older prepends retained page and reports storage-exhausted honestly`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity(
            cursor = CanonicalAppendCursor("00000000-0000-4000-8000-000000000001", "7", null, null),
        )
        harness.cache.recentMessages[SESSION] = listOf(messageEntity(messageId = "m-2", appendOrder = "2"))
        harness.cache.messageCounts[SESSION] = 2
        harness.cache.olderPage = io.github.verybigsad.pimobile.state.OlderMessagesPage(
            listOf(messageEntity(messageId = "m-1", appendOrder = "1")),
            hasMore = false,
        )
        harness.coordinator.start()
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.sessions[SessionId(SESSION)]!!.conversation.hasOlderMessages).isTrue()
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.LoadOlder))
        advanceUntilIdle()
        val conversation = harness.coordinator.state.value.sessions[SessionId(SESSION)]!!.conversation
        assertThat(conversation.finalizedMessages.map { it.id.value }).containsExactly("m-1", "m-2").inOrder()
        assertThat(conversation.hasOlderMessages).isFalse()
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.LoadOlder))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.lastError).isNull()
    }

    @Test
    fun `command send failure preserves the draft and reports a retryable failure`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("retry me")))
        advanceUntilIdle()
        harness.failCommandSends = true
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.Send))
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.getValue(SessionId(SESSION)).draft.typedText).isEqualTo("retry me")
        assertThat(state.commandNotices[SessionId(SESSION)]).isEqualTo(CommandNoticeUiState.Failed("COMMAND_SEND_FAILED", true))
    }

    @Test
    fun `command rejection preserves the draft and routes host status by command id`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("retry me")))
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.Send))
        advanceUntilIdle()
        val commandId = commandId(harness)
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.CommandStatus(commandId, "REJECTED", "PI_RPC_REJECTED")))
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.getValue(SessionId(SESSION)).draft.typedText).isEqualTo("retry me")
        assertThat(state.commandNotices[SessionId(SESSION)]).isEqualTo(CommandNoticeUiState.Failed("PI_RPC_REJECTED", true))
    }

    @Test
    fun `command indeterminate status preserves the draft`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("retry me")))
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.Send))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.CommandStatus(commandId(harness), "INDETERMINATE", "DISPATCH_OUTCOME_UNKNOWN")))
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.getValue(SessionId(SESSION)).draft.typedText).isEqualTo("retry me")
        assertThat(state.commandNotices[SessionId(SESSION)]).isEqualTo(CommandNoticeUiState.Failed("DISPATCH_OUTCOME_UNKNOWN", true))
    }

    @Test
    fun `disconnect after submission leaves the draft retryable and indeterminate`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("retry me")))
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.Send))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.Disconnected("TRANSPORT_CLOSED")))
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.getValue(SessionId(SESSION)).draft.typedText).isEqualTo("retry me")
        assertThat(state.commandNotices[SessionId(SESSION)]).isEqualTo(CommandNoticeUiState.Failed("COMMAND_INDETERMINATE", true))
    }

    @Test
    fun `acknowledged command clears the unchanged submitted draft`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.UpdateTypedText("send me")))
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.Send))
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ConnectionEvent(1, HostConnectionEvent.CommandStatus(commandId(harness), "ACKED", null)))
        advanceUntilIdle()

        val state = harness.coordinator.state.value
        assertThat(state.sessions.getValue(SessionId(SESSION)).draft.typedText).isEmpty()
        assertThat(state.commandNotices[SessionId(SESSION)]).isEqualTo(CommandNoticeUiState.Acknowledged)
    }

    @Test
    fun `voice permission grant retries the selected session exactly once`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        harness.voiceStartResult = "VOICE_PERMISSION_REQUIRED"
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.StartVoice))
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.StartVoice))
        runCurrent()

        val request = requireNotNull(harness.coordinator.state.value.voicePermissionRequest)
        assertThat(request.targetSessionId).isEqualTo(SessionId(SESSION))
        assertThat(harness.voiceStarts).containsExactly(SessionId(SESSION))

        harness.voiceStartResult = null
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = true, permanentlyDenied = false))
        runCurrent()
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = true, permanentlyDenied = false))
        runCurrent()

        val state = harness.coordinator.state.value
        assertThat(harness.voiceStarts).containsExactly(SessionId(SESSION), SessionId(SESSION)).inOrder()
        assertThat(state.voicePermissionRequest).isNull()
        assertThat(state.voicePermissionNotices[SessionId(SESSION)]).isNull()
    }

    @Test
    fun `voice permission denial is stable and never retries by itself`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        harness.voiceStartResult = "VOICE_PERMISSION_REQUIRED"
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.StartVoice))
        runCurrent()

        val request = requireNotNull(harness.coordinator.state.value.voicePermissionRequest)
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = false, permanentlyDenied = false))
        runCurrent()
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = false, permanentlyDenied = true))
        runCurrent()

        val state = harness.coordinator.state.value
        assertThat(harness.voiceStarts).containsExactly(SessionId(SESSION))
        assertThat(state.voicePermissionRequest).isNull()
        assertThat(state.voicePermissionNotices[SessionId(SESSION)]).isEqualTo(VoicePermissionUiState.Denied)
    }

    @Test
    fun `voice permission grant after background cannot start capture`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        harness.voiceStartResult = "VOICE_PERMISSION_REQUIRED"
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.StartVoice))
        runCurrent()

        val request = requireNotNull(harness.coordinator.state.value.voicePermissionRequest)
        harness.voiceStartResult = null
        harness.coordinator.submit(AppIntent.ForegroundChanged(false))
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = true, permanentlyDenied = false))
        runCurrent()

        assertThat(harness.voiceStarts).containsExactly(SessionId(SESSION))
        assertThat(harness.coordinator.state.value.voicePermissionRequest).isNull()
    }

    @Test
    fun `voice permission grant for a stale session cannot start capture`() = runHarnessTest(
        profiles = FakeProfileStore(trustedProfile()),
    ) { harness ->
        harness.cache.sessions[SESSION_B] = sessionEntity(SESSION_B)
        readyForCommand(harness)
        harness.coordinator.submit(AppIntent.ForegroundChanged(true))
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        harness.voiceStartResult = "VOICE_PERMISSION_REQUIRED"
        harness.coordinator.submit(AppIntent.DetailEvent(SessionId(SESSION), SessionDetailEvent.StartVoice))
        runCurrent()

        val request = requireNotNull(harness.coordinator.state.value.voicePermissionRequest)
        harness.voiceStartResult = null
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION_B))))
        harness.coordinator.submit(AppIntent.VoicePermissionResult(request.requestId, granted = true, permanentlyDenied = false))
        runCurrent()

        assertThat(harness.coordinator.state.value.selectedSessionId).isEqualTo(SessionId(SESSION_B))
        assertThat(harness.voiceStarts).containsExactly(SessionId(SESSION))
        assertThat(harness.coordinator.state.value.voicePermissionRequest).isNull()
    }

    @Test
    fun `session list open session selects detail destination`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity()
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ListEvent(SessionListEvent.OpenSession(SessionId(SESSION))))
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.selectedSessionId).isEqualTo(SessionId(SESSION))
        harness.coordinator.submit(AppIntent.NavigateBack)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.selectedSessionId).isNull()
    }
}
