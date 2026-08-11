package io.github.verybigsad.pimobile

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.network.WireBodies
import io.github.verybigsad.pimobile.state.AgentsEventSink
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
import io.github.verybigsad.pimobile.wire.HostConnectionEvent
import io.github.verybigsad.pimobile.wire.HostConnector
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class WiringClock : AppClock {
    override fun nowEpochMillis(): Long = 1_000L
    override fun nowMonotonicMillis(): Long = 1_000L
}

private class WiringCache : CachePort {
    override suspend fun loadTrustState(macId: String): TrustStateEntity? = null
    override suspend fun loadSessions(): List<SessionEntity> = emptyList()
    override suspend fun loadRecentMessages(sessionId: String, limit: Int): List<MessageEntity> = emptyList()
    override suspend fun messageCount(sessionId: String): Int = 0
    override suspend fun loadOlderMessages(sessionId: String, beforeAppendOrder: String, limit: Int): OlderMessagesPage =
        OlderMessagesPage(emptyList(), hasMore = false)
    override suspend fun loadDrafts(): List<DraftEntity> = emptyList()
    override suspend fun upsertDraft(draft: DraftEntity) = Unit
    override suspend fun upsertTrustState(trustState: TrustStateEntity) = Unit
    override suspend fun deleteTrustState(macId: String) = Unit
    override suspend fun markCanonicalUnavailable() = Unit
    override suspend fun commitFinalizedMessage(session: SessionEntity, message: MessageEntity) = Unit
    override suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) = Unit
    override suspend fun resetSessionContent(session: SessionEntity) = Unit
    override suspend fun committedCursors(): List<Pair<String, CanonicalAppendCursor>> = emptyList()
    override suspend fun acknowledgeCanonicalResync(signal: CanonicalResyncSignal): Boolean = true
}

private class RecordingAgentsSink : AgentsEventSink {
    val catalogs = ArrayList<WireBodies.AgentsCatalog>()
    val updates = ArrayList<WireBodies.AgentsUpdate>()
    val offlineEvents = ArrayList<Boolean>()

    override fun onCatalog(catalog: WireBodies.AgentsCatalog) {
        catalogs += catalog
    }

    override fun onUpdate(update: WireBodies.AgentsUpdate) {
        updates += update
    }

    override fun onOffline(offline: Boolean) {
        offlineEvents += offline
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppWiringTest {
    private class Harness(
        scope: TestScope,
        val agentsSink: RecordingAgentsSink = RecordingAgentsSink(),
    ) {
        val coordinator: PiAppCoordinator = PiAppCoordinator(
            scope = scope,
            cache = WiringCache(),
            profiles = object : io.github.verybigsad.pimobile.security.ProfileStore {
                override fun load(): io.github.verybigsad.pimobile.security.PairedProfile? = null
                override fun save(profile: io.github.verybigsad.pimobile.security.PairedProfile) = Unit
                override fun delete() = Unit
            },
            connectorFactory = { _, _ ->
                io.github.verybigsad.pimobile.wire.HostConnectionRunner {
                    object : HostConnector {
                        override val path = io.github.verybigsad.pimobile.model.TransportPath.DIRECT
                        override suspend fun send(
                            type: String,
                            body: kotlinx.serialization.json.JsonObject,
                            replyTo: String?,
                        ) = Unit

                        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = Unit
                        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) = Unit
                        override suspend fun close() = Unit
                    }
                }
            },
            pairingRunnerFactory = { _, _, _ -> suspend { } },
            passkeyBridge = object : io.github.verybigsad.pimobile.state.PasskeyBridgePort {
                override suspend fun performAssertion(ceremonyId: String, optionsJson: String): Pair<String?, String?> =
                    null to "UNAVAILABLE"
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
            clock = WiringClock(),
            agentsSink = agentsSink,
        )

        init {
            coordinator.start()
        }
    }

    private fun runHarness(block: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness(this)
        try {
            block(harness)
        } finally {
            harness.coordinator.close()
        }
    }

    @Test
    fun settingsDestinationOpensAndCloses() = runHarness { harness ->
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.OpenSettings)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.settingsOpen).isTrue()
        harness.coordinator.submit(AppIntent.CloseSettings)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.settingsOpen).isFalse()
    }

    @Test
    fun agentsDestinationOpensAndCloses() = runHarness { harness ->
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.OpenAgents)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.agentsOpen).isTrue()
        harness.coordinator.submit(AppIntent.CloseAgents)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.agentsOpen).isFalse()
    }

    @Test
    fun updateDeepLinkOpensSheet() = runHarness { harness ->
        advanceUntilIdle()
        // pimobile://update lands as OpenUpdateSheet (MainActivity maps the VIEW intent).
        harness.coordinator.submit(AppIntent.OpenUpdateSheet)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.updateSheetOpen).isTrue()
        harness.coordinator.submit(AppIntent.CloseUpdateSheet)
        advanceUntilIdle()
        assertThat(harness.coordinator.state.value.updateSheetOpen).isFalse()
    }

    @Test
    fun agentsCatalogEventFeedsSink() = runHarness { harness ->
        advanceUntilIdle()
        val catalog = WireBodies.AgentsCatalog(
            sessions = listOf(
                WireBodies.AgentsCatalogSession(
                    sessionId = SessionId("0f8fad5b-d9cb-469f-a165-70867728950e"),
                    agents = listOf(
                        WireBodies.Agent(
                            agentId = "agent-1",
                            parentAgentId = null,
                            description = "Explore code",
                            agentType = "explore",
                            status = WireBodies.AgentStatus.RUNNING,
                            startedAt = Instant.ofEpochMilli(1_000L),
                            endedAt = null,
                            toolUses = 3,
                            model = "k3-large",
                        ),
                    ),
                ),
            ),
        )
        harness.coordinator.submit(AppIntent.ConnectionEvent(0L, HostConnectionEvent.AgentsCatalogReceived(catalog)))
        advanceUntilIdle()
        assertThat(harness.agentsSink.catalogs).containsExactly(catalog)
    }

    @Test
    fun agentsUpdateEventFeedsSink() = runHarness { harness ->
        advanceUntilIdle()
        val update = WireBodies.AgentsUpdate(
            sessionId = SessionId("0f8fad5b-d9cb-469f-a165-70867728950e"),
            agent = WireBodies.Agent(
                agentId = "agent-2",
                parentAgentId = "agent-1",
                description = "Run tests",
                agentType = "general",
                status = WireBodies.AgentStatus.COMPLETED,
                startedAt = Instant.ofEpochMilli(1_000L),
                endedAt = Instant.ofEpochMilli(2_000L),
                toolUses = 5,
                model = null,
            ),
        )
        harness.coordinator.submit(AppIntent.ConnectionEvent(0L, HostConnectionEvent.AgentsUpdateReceived(update)))
        advanceUntilIdle()
        assertThat(harness.agentsSink.updates).containsExactly(update)
    }

    @Test
    fun disconnectMarksAgentsOffline() = runHarness { harness ->
        advanceUntilIdle()
        harness.coordinator.submit(AppIntent.ConnectionEvent(0L, HostConnectionEvent.Disconnected("NETWORK_LOST")))
        advanceUntilIdle()
        assertThat(harness.agentsSink.offlineEvents).contains(true)
    }
}
