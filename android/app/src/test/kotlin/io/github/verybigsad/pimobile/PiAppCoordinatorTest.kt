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
import io.github.verybigsad.pimobile.session.SessionDetailEvent
import io.github.verybigsad.pimobile.session.SessionListEvent
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
import io.github.verybigsad.pimobile.wire.HostConnectionEvent
import io.github.verybigsad.pimobile.wire.HostConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

private const val MAC_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
private const val DEVICE_ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
private const val SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e"

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
    var failCommits = false

    override suspend fun loadTrustState(macId: String): TrustStateEntity? = trust[macId]
    override suspend fun loadSessions(): List<SessionEntity> = sessions.values.toList()
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

    override suspend fun commitFinalizedMessage(session: SessionEntity, message: MessageEntity) {
        if (failCommits) throw IllegalStateException("disk full")
        sessions[session.sessionId] = session
    }

    override suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) {
        if (failCommits) throw IllegalStateException("disk full")
        sessions[session.sessionId] = session
    }

    override suspend fun resetSessionContent(session: SessionEntity) {
        sessions[session.sessionId] = session
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
        val lockedWakes: MutableList<Int> = ArrayList(),
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
                    override suspend fun start(): String? = null
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
    fun `unpair clears trust and sessions`() = runHarnessTest(profiles = FakeProfileStore(trustedProfile())) { harness ->
        harness.cache.sessions[SESSION] = sessionEntity()
        harness.coordinator.start()
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.UnpairRequested)
        advanceUntilIdle()
        val state = harness.coordinator.state.value
        assertThat(state.trust).isEqualTo(TrustState.Unpaired)
        assertThat(state.sessions).isEmpty()
        assertThat(harness.profiles.profile).isNull()
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
