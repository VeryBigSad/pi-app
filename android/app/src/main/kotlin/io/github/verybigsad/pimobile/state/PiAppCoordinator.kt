package io.github.verybigsad.pimobile.state

import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.CanonicalResetReason
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.ConversationAction
import io.github.verybigsad.pimobile.model.ConversationState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.DraftAction
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionAction
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionReducer
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.pairing.PairingProgressEvent
import io.github.verybigsad.pimobile.security.PairedProfile
import io.github.verybigsad.pimobile.security.ProfileStore
import io.github.verybigsad.pimobile.session.ApprovalDecision
import io.github.verybigsad.pimobile.session.ApprovalOfferUiState
import io.github.verybigsad.pimobile.session.SessionDetailEvent
import io.github.verybigsad.pimobile.session.SessionListEvent
import io.github.verybigsad.pimobile.storage.CanonicalResyncSignal
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.StoredTrustStatus
import io.github.verybigsad.pimobile.storage.TrustStateEntity
import io.github.verybigsad.pimobile.voice.VoiceTranscript
import io.github.verybigsad.pimobile.voice.VoiceTranscriptKind
import io.github.verybigsad.pimobile.wire.HostConnectionEvent
import io.github.verybigsad.pimobile.wire.HostConnector
import io.github.verybigsad.pimobile.wire.WireMessages
import java.util.UUID
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface AppIntent {
    data object Hydrate : AppIntent
    data class ForegroundChanged(val foreground: Boolean) : AppIntent
    data object DeviceLockDetected : AppIntent
    data object BackgroundLockElapsed : AppIntent
    data object StartPairing : AppIntent
    data class PairingUriScanned(val uri: String) : AppIntent
    data class PairingEvent(val generation: Long, val event: PairingProgressEvent) : AppIntent
    data object PairingCancelled : AppIntent
    data object UnpairRequested : AppIntent
    data object AuthenticateRequested : AppIntent
    data class PasskeyCeremonyResult(val ceremonyId: String, val credentialJson: String?, val failureCode: String?) : AppIntent
    data object RetryConnection : AppIntent
    data class ConnectionEvent(val generation: Long, val event: HostConnectionEvent) : AppIntent
    data class ListEvent(val event: SessionListEvent) : AppIntent
    data class DetailEvent(val sessionId: SessionId, val event: SessionDetailEvent) : AppIntent
    data class DeepLink(val sessionId: SessionId?) : AppIntent
    data object WakeReceived : AppIntent
    data object NavigateBack : AppIntent
    data class OpenTerminal(val sessionId: SessionId) : AppIntent
    data object CloseTerminal : AppIntent
    data object VoiceStop : AppIntent
    data object OpenSettings : AppIntent
    data object CloseSettings : AppIntent
    data object OpenAgents : AppIntent
    data object CloseAgents : AppIntent
    data object OpenUpdateSheet : AppIntent
    data object CloseUpdateSheet : AppIntent
    data class VoiceTranscriptReceived(val transcript: VoiceTranscript) : AppIntent
    data class PasskeyAvailabilityChanged(val availability: AppPasskeyAvailability) : AppIntent
}

/** Passkey ceremonies needing an Activity; implemented by the activity bridge. */
interface PasskeyBridgePort {
    suspend fun performAssertion(ceremonyId: String, optionsJson: String): Pair<String?, String?>
}

interface VoicePort {
    suspend fun setForeground(foreground: Boolean)
    suspend fun start(): String?
    suspend fun stop()
    suspend fun cancel()
    suspend fun onMacError(sessionId: String, error: io.github.verybigsad.pimobile.voice.MacVoiceError)
}

interface TerminalPort {
    fun onReady(terminalGeneration: ULong, columns: Int, rows: Int)
    fun onOutput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray)
    fun onReset()
    fun onHistoryResult(result: HostConnectionEvent.TerminalHistoryResult)
    fun close()
}

interface WakeNotificationPort {
    fun notifyLockedWake()
}

interface PushDrainPort {
    suspend fun drain(connector: HostConnector)
}

/** Receives parsed agents host events; app composes an AgentsStore behind it. */
interface AgentsEventSink {
    fun onCatalog(catalog: io.github.verybigsad.pimobile.network.WireBodies.AgentsCatalog)
    fun onUpdate(update: io.github.verybigsad.pimobile.network.WireBodies.AgentsUpdate)
    fun onOffline(offline: Boolean)

    companion object {
        val NOOP: AgentsEventSink = object : AgentsEventSink {
            override fun onCatalog(catalog: io.github.verybigsad.pimobile.network.WireBodies.AgentsCatalog) = Unit
            override fun onUpdate(update: io.github.verybigsad.pimobile.network.WireBodies.AgentsUpdate) = Unit
            override fun onOffline(offline: Boolean) = Unit
        }
    }
}

/**
 * Application-scoped actor: one serialized intent channel, one immutable StateFlow, one
 * active transport generation, one inbound pipeline. All authoritative inbound processing
 * follows decode -> pure reducer -> Room transaction -> publish -> acknowledge; a storage
 * failure closes the generation instead of acknowledging.
 */
class PiAppCoordinator(
    scope: CoroutineScope,
    private val cache: CachePort,
    private val profiles: ProfileStore,
    private val connectorFactory: (PairedProfile, (HostConnectionEvent) -> Unit) -> io.github.verybigsad.pimobile.wire.HostConnectionRunner,
    private val pairingRunnerFactory: (io.github.verybigsad.pimobile.security.PairingInvitation, String, (PairingProgressEvent) -> Unit) -> suspend () -> Unit,
    private val passkeyBridge: PasskeyBridgePort,
    private val voicePort: VoicePort,
    private val terminalPort: () -> TerminalPort?,
    private val wakeNotifier: WakeNotificationPort,
    private val pushDrain: PushDrainPort,
    private val passkeyAvailability: () -> AppPasskeyAvailability,
    private val pendingResyncSignal: () -> CanonicalResyncSignal?,
    private val clock: AppClock,
    private val agentsSink: AgentsEventSink = AgentsEventSink.NOOP,
    private val backgroundLockMillis: Long = 5 * 60 * 1_000L,
    private val passkeySessionMillis: Long = 12 * 60 * 60 * 1_000L,
    private val reconnectBaseDelayMillis: Long = 1_000L,
) {
    private val actorJob = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
    private val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + actorJob)
    private val intents = Channel<AppIntent>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(PiAppState())
    val state: StateFlow<PiAppState> = mutableState.asStateFlow()

    private var foreground = false
    private var connectionGeneration = 0L
    private var pairingGeneration = 0L
    private var connector: HostConnector? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectFailures = 0
    private var lockJob: Job? = null
    private var leaseJob: Job? = null
    private var pendingAssertion: Pair<String, String>? = null
    private var ceremonyInFlight = false
    private var resyncSignal: CanonicalResyncSignal? = null
    private var pairingDeviceId: String? = null
    private val loadingOlder = mutableSetOf<SessionId>()

    fun submit(intent: AppIntent) {
        intents.trySend(intent)
    }

    fun close() {
        actorJob.cancel()
    }

    /** Applies a host session.catalog snapshot; null clears it back to unavailable/hidden. */
    fun applyCatalog(catalog: Map<SessionId, SessionCatalogEntry>?) {
        update { it.copy(catalog = catalog) }
    }

    fun start() {
        scope.launch {

            runCatching { handle(AppIntent.Hydrate) }.onFailure { it.printStackTrace() }
            for (intent in intents) {
                runCatching { handle(intent) }.onFailure { it.printStackTrace() }
            }
        }
    }

    private suspend fun handle(intent: AppIntent) {
        when (intent) {
            AppIntent.Hydrate -> hydrate()
            is AppIntent.ForegroundChanged -> onForeground(intent.foreground)
            AppIntent.DeviceLockDetected -> lock(LockReason.DEVICE_LOCKED)
            AppIntent.BackgroundLockElapsed -> if (!foreground) lock(LockReason.BACKGROUND_TIMEOUT)
            AppIntent.StartPairing -> update { it.copy(pairing = PairingUiState.AwaitingScan, lastError = null) }
            is AppIntent.PairingUriScanned -> startPairing(intent.uri)
            is AppIntent.PairingEvent -> onPairingEvent(intent.generation, intent.event)
            AppIntent.PairingCancelled -> cancelPairing()
            AppIntent.UnpairRequested -> unpair()
            AppIntent.AuthenticateRequested -> authenticate()
            is AppIntent.PasskeyCeremonyResult -> onPasskeyResult(intent)
            AppIntent.RetryConnection -> reconnectNow()
            is AppIntent.ConnectionEvent -> onConnectionEvent(intent.generation, intent.event)
            is AppIntent.ListEvent -> onListEvent(intent.event)
            is AppIntent.DetailEvent -> onDetailEvent(intent.sessionId, intent.event)
            is AppIntent.DeepLink -> onDeepLink(intent.sessionId)
            AppIntent.WakeReceived -> onWake()
            AppIntent.NavigateBack -> update { it.copy(selectedSessionId = null, terminalSessionId = null) }
            is AppIntent.OpenTerminal -> update { it.copy(terminalSessionId = intent.sessionId) }
            AppIntent.CloseTerminal -> {
                terminalPort()?.close()
                update { it.copy(terminalSessionId = null) }
            }

            AppIntent.VoiceStop -> voicePort.stop()
            AppIntent.OpenSettings -> update { it.copy(settingsOpen = true) }
            AppIntent.CloseSettings -> update { it.copy(settingsOpen = false) }
            AppIntent.OpenAgents -> update { it.copy(agentsOpen = true) }
            AppIntent.CloseAgents -> update { it.copy(agentsOpen = false) }
            AppIntent.OpenUpdateSheet -> update { it.copy(updateSheetOpen = true) }
            AppIntent.CloseUpdateSheet -> update { it.copy(updateSheetOpen = false) }
            is AppIntent.VoiceTranscriptReceived -> onVoiceTranscript(intent.transcript)
            is AppIntent.PasskeyAvailabilityChanged -> update { it.copy(passkeyProvider = intent.availability) }
        }
    }

    private suspend fun hydrate() {
        val profile = profiles.load()
        val signal = pendingResyncSignal()
        if (profile == null) {
            update {
                it.copy(
                    hydrated = true,
                    trust = TrustState.Unpaired,
                    passkeyProvider = passkeyAvailability(),
                    resyncPending = signal != null,
                )
            }
            resyncSignal = signal
            return
        }
        val storedTrust = cache.loadTrustState(profile.macId)
        val trust = storedTrust?.let(StorageMappers::trustState) ?: TrustState.Trusted(
            macId = MacId(profile.macId),
            macDisplayName = profile.macDisplayName,
            certificateSerial = profile.certificateSerial,
            certificateNotAfterEpochMillis = profile.certificateNotAfterEpochMillis,
        )
        if (storedTrust == null) {
            persistTrust(trust)
        }
        val drafts = cache.loadDrafts().associateBy(DraftEntity::sessionId)
        val sessions = cache.loadSessions().associate { entity ->
            val metadata = StorageMappers.sessionMetadata(entity, MacId(profile.macId))
            val restored = SessionState.initial(metadata)
            val withTrust = SessionReducer.reduce(restored, SessionAction.TrustChanged(trust), clock.nowEpochMillis())
            val conversation = if (signal != null) {
                ConversationState.awaitingCanonical(metadata.id)
            } else {
                val messages = cache.loadRecentMessages(metadata.id.value, ConversationState.MAX_IN_MEMORY_FINALIZED_MESSAGES)
                    .mapNotNull(StorageMappers::finalizedMessage)
                    .toPersistentList()
                val retainedCount = cache.messageCount(metadata.id.value)
                ConversationState.restored(
                    metadata.id,
                    messages,
                    entity.canonicalCursor?.let(StorageMappers::cursor),
                    hasOlderMessages = retainedCount > messages.size,
                )
            }
            val draft = drafts[metadata.id.value]
                ?.let { entity ->
                    DraftState(
                        sessionId = metadata.id,
                        typedText = entity.typedText,
                        transcriptionText = entity.transcriptionText,
                        revision = entity.revision,
                        updatedAtEpochMillis = entity.updatedAtEpochMs,
                    )
                }
                ?: DraftState.empty(metadata.id)
            metadata.id to withTrust.copy(conversation = conversation, draft = draft)
        }
        resyncSignal = signal
        update {
            it.copy(
                hydrated = true,
                trust = trust,
                sessions = sessions.toPersistentMap(),
                connection = ConnectionState.Disconnected(DisconnectReason.PROCESS_DEATH),
                authentication = null,
                passkeyProvider = passkeyAvailability(),
                resyncPending = signal != null,
            )
        }
        connect()
    }

    private suspend fun connect() {
        val profile = profiles.load() ?: return
        if (mutableState.value.trust !is TrustState.Trusted) return
        connectionJob?.cancel()
        reconnectJob?.cancel()
        val generation = ++connectionGeneration
        connectionJob = scope.launch {
            runCatching {
                val runner = connectorFactory(profile) { event -> submit(AppIntent.ConnectionEvent(generation, event)) }
                val connected = runner.run()
                connector = connected
                startLease()
            }.onFailure {
                submit(
                    AppIntent.ConnectionEvent(
                        generation,
                        HostConnectionEvent.Disconnected("CONNECT_FAILED"),
                    ),
                )
            }
        }
    }

    private fun reconnectNow() {
        val trust = mutableState.value.trust
        if (trust !is TrustState.Trusted) return
        scope.launch { connect() }
    }

    private suspend fun scheduleReconnect() {
        if (mutableState.value.trust !is TrustState.Trusted) return
        reconnectJob?.cancel()
        reconnectFailures += 1
        val delayMillis = (reconnectBaseDelayMillis shl minOf(reconnectFailures - 1, 5)).coerceAtMost(30_000L)
        reconnectJob = scope.launch {
            delay(delayMillis)
            connect()
        }
    }

    private suspend fun onConnectionEvent(generation: Long, event: HostConnectionEvent) {
        if (generation != connectionGeneration) return
        val now = clock.nowEpochMillis()
        when (event) {
            is HostConnectionEvent.Connecting -> applyConnection(ConnectionState.Connecting(event.path, event.attempt), now)

            is HostConnectionEvent.DeviceAuthenticated -> {
                applyConnection(
                    ConnectionState.DeviceAuthenticated(
                        event.path,
                        (mutableState.value.trust as? TrustState.Trusted)?.macId ?: MacId("unknown"),
                        MutualTlsAuthentication(event.certificateSerial, now),
                    ),
                    now,
                )
            }

            is HostConnectionEvent.AssertionOptions -> {
                pendingAssertion = event.ceremonyId to event.optionsJson
                pendingAssertionBinding = event.binding
            }

            is HostConnectionEvent.AuthResult -> {
                if (event.success) {
                    val authentication = PasskeyAuthentication(
                        assertionId = event.ceremonyId ?: UUID.randomUUID().toString(),
                        verifiedAtEpochMillis = now,
                        expiresAtEpochMillis = now + passkeySessionMillis,
                    )
                    val current = mutableState.value.connection
                    if (current is ConnectionState.DeviceAuthenticated) {
                        update { it.copy(authentication = authentication, syncing = true) }
                        applyConnection(
                            ConnectionState.Ready(current.path, current.macId, authentication, current.deviceAuthentication),
                            now,
                        )
                        sendSyncResume()
                        connector?.let { pushDrain.drain(it) }
                    } else {
                        update { it.copy(authentication = authentication) }
                    }
                } else {
                    update { it.copy(lastError = "PASSKEY_ASSERTION_REJECTED") }
                }
            }

            HostConnectionEvent.HostLocked -> lock(LockReason.HOST_LOCK)
            is HostConnectionEvent.SyncReset -> onSyncReset(event.sessionId, event.reason, now)
            is HostConnectionEvent.SnapshotReady -> onSnapshot(event, now)
            is HostConnectionEvent.CanonicalEvent -> onCanonicalEvent(event, now)
            HostConnectionEvent.SyncComplete -> onSyncComplete()
            is HostConnectionEvent.ApprovalOffer -> update {
                it.copy(
                    approval = ApprovalOfferUiState(
                        offerId = event.offerId,
                        operationId = event.operationId,
                        operationName = event.operationName,
                        normalizedArguments = event.normalizedArguments,
                        targetLabel = event.targetLabel,
                        targetValue = event.targetValue,
                        reasons = event.reasons,
                        policyVersion = event.policyVersion,
                        argumentHash = event.argumentHash,
                        expiresAtLabel = "expires at ${event.expiresAtEpochMillis}",
                        remainingSeconds = ((event.expiresAtEpochMillis - now) / 1_000).coerceAtLeast(0).toInt(),
                    ),
                )
            }

            is HostConnectionEvent.ApprovalExpired -> update { it.copy(approval = null) }
            is HostConnectionEvent.VoiceTranscript -> Unit
            is HostConnectionEvent.VoiceError -> voicePort.onMacError(event.streamId, event.error)
            is HostConnectionEvent.TerminalReady -> terminalPort()?.onReady(event.terminalGeneration, event.columns, event.rows)
            is HostConnectionEvent.TerminalOutput -> terminalPort()?.onOutput(event.terminalGeneration, event.sequence, event.bytes)
            HostConnectionEvent.TerminalReset -> terminalPort()?.onReset()
            is HostConnectionEvent.TerminalHistoryResult -> terminalPort()?.onHistoryResult(event)
            is HostConnectionEvent.AgentsCatalogReceived -> agentsSink.onCatalog(event.catalog)
            is HostConnectionEvent.SessionCatalogReceived -> applyCatalog(
                event.catalog.sessions.associate { entry ->
                    SessionId(entry.id.value) to SessionCatalogEntry(
                        provider = entry.provider,
                        modelName = entry.model,
                        thinkingLevel = entry.thinkingLevel,
                    )
                },
            )
            is HostConnectionEvent.AgentsUpdateReceived -> agentsSink.onUpdate(event.update)
            is HostConnectionEvent.HostError -> update { it.copy(lastError = event.code) }
            is HostConnectionEvent.Disconnected -> onDisconnected(event.reason, now)
        }
    }

    private suspend fun onSyncReset(sessionId: SessionId, reason: String, now: Long) {
        val state = mutableState.value.sessions[sessionId] ?: return
        val resetReason = if (reason == "active_gap") CanonicalResetReason.SEQUENCE_GAP else CanonicalResetReason.EXPLICIT_RESET
        val next = SessionReducer.reduce(
            state,
            SessionAction.Conversation(ConversationAction.SyncReset(resetReason, null)),
            now,
        )
        updateSession(next)
        runCatching {
            cache.resetSessionContent(sessionEntityFor(next, now))
        }.onFailure { closeGeneration() }
    }

    private suspend fun onSnapshot(event: HostConnectionEvent.SnapshotReady, now: Long) {
        val existing = mutableState.value.sessions[event.sessionId]
        val metadata = StorageMappers.sessionMetadata(event.session, (mutableState.value.trust as? TrustState.Trusted)?.macId ?: MacId("unknown"))
        val base = existing ?: SessionState.initial(metadata).let {
            SessionReducer.reduce(it, SessionAction.TrustChanged(mutableState.value.trust), now)
        }
        val snapshot = io.github.verybigsad.pimobile.model.CanonicalSnapshot(
            sessionId = event.sessionId,
            cursor = event.cursor,
            finalizedMessages = event.messages.mapNotNull(StorageMappers::finalizedMessage).toPersistentList(),
            lastAppendId = null,
            runState = io.github.verybigsad.pimobile.model.SessionRunState.IDLE,
            hasOlderMessages = false,
        )
        val next = SessionReducer.reduce(
            base,
            SessionAction.Conversation(ConversationAction.SnapshotCommitted(snapshot)),
            now,
        )
        updateSession(next)
        runCatching {
            cache.replaceSessionSnapshot(event.session, event.messages)
        }.onSuccess {
            acknowledge(event.sessionId, event.cursor)
            maybeAcknowledgeResync()
        }.onFailure {
            closeGeneration()
        }
    }

    private suspend fun onCanonicalEvent(event: HostConnectionEvent.CanonicalEvent, now: Long) {
        val state = mutableState.value.sessions[event.sessionId] ?: return
        if (event.conversationEvent == null) return
        val next = SessionReducer.reduce(
            state,
            SessionAction.Conversation(ConversationAction.EventReceived(event.conversationEvent)),
            now,
        )
        if (next.conversation.availability is CanonicalAvailability.Unavailable &&
            state.conversation.availability is CanonicalAvailability.Current
        ) {
            updateSession(next)
            return
        }
        updateSession(next)
        if (event.finalized != null) {
            runCatching {
                cache.commitFinalizedMessage(sessionEntityFor(next, now), event.finalized)
            }.onFailure {
                closeGeneration()
                return
            }
        }
        if (mutableState.value.syncing) {
            acknowledge(event.sessionId, event.cursor)
        }
    }

    private suspend fun onSyncComplete() {
        update { it.copy(syncing = false) }
        agentsSink.onOffline(false)
        maybeAcknowledgeResync()
        maybeConsumeDeepLink()
    }

    private suspend fun maybeAcknowledgeResync() {
        val signal = resyncSignal ?: return
        val state = mutableState.value
        if (state.sessions.isEmpty() || state.sessions.values.any {
                it.conversation.availability !is CanonicalAvailability.Current
            }
        ) {
            return
        }
        if (runCatching { cache.acknowledgeCanonicalResync(signal) }.getOrDefault(false)) {
            resyncSignal = null
            update { it.copy(resyncPending = false) }
        }
    }

    private suspend fun acknowledge(sessionId: SessionId, cursor: EventCursor) {
        runCatching { connector?.send("event.ack", WireMessages.eventAck(sessionId, cursor)) }
    }

    private suspend fun sendSyncResume() {
        val cursors = cache.committedCursors().map { (sessionId, cursor) ->
            SessionId(sessionId) to StorageMappers.cursor(cursor)
        }
        runCatching { connector?.send("sync.resume", WireMessages.syncResume(cursors)) }
    }

    private suspend fun onDisconnected(reason: String?, now: Long) {
        connector = null
        stopLease()
        agentsSink.onOffline(true)
        val current = mutableState.value.connection
        applyConnection(
            ConnectionState.Disconnected(
                when {
                    reason == "AUTH_REQUIRED" -> DisconnectReason.AUTH_REQUIRED
                    current is ConnectionState.Disconnected -> current.reason
                    else -> DisconnectReason.NETWORK_LOST
                },
            ),
            now,
        )
        update { it.copy(syncing = false) }
        scheduleReconnect()
    }

    private suspend fun closeGeneration() {
        connectionGeneration += 1
        runCatching { connector?.close() }
        connector = null
        stopLease()
        applyConnection(ConnectionState.Disconnected(DisconnectReason.NETWORK_LOST), clock.nowEpochMillis())
        scheduleReconnect()
    }

    private suspend fun applyConnection(connection: ConnectionState, now: Long) {
        val sessions = mutableState.value.sessions.mapValues { (_, session) ->
            SessionReducer.reduce(session, SessionAction.ConnectionChanged(connection), now)
        }.toPersistentMap()
        update { it.copy(connection = connection, sessions = sessions) }
        maybeConsumeDeepLink()
    }

    private fun applyTrust(trust: TrustState, now: Long) {
        val sessions = mutableState.value.sessions.mapValues { (_, session) ->
            SessionReducer.reduce(session, SessionAction.TrustChanged(trust), now)
        }.toPersistentMap()
        val connection = sessions.values.firstOrNull()?.connection ?: ConnectionState.Disconnected(DisconnectReason.TRUST_REQUIRED)
        update { it.copy(trust = trust, sessions = sessions, connection = connection) }
    }

    // Pairing

    private suspend fun startPairing(uri: String) {
        val invitation = runCatching { io.github.verybigsad.pimobile.security.PairingInvitation.parse(uri) }.getOrNull()
        if (invitation == null) {
            update { it.copy(pairing = PairingUiState.Failed("PAIRING_INVITATION_INVALID")) }
            return
        }
        val deviceId = pairingDeviceId ?: UUID.randomUUID().toString().also { pairingDeviceId = it }
        val generation = ++pairingGeneration
        update { it.copy(pairing = PairingUiState.Connecting) }
        val runner = pairingRunnerFactory(invitation, deviceId) { event ->
            submit(AppIntent.PairingEvent(generation, event))
        }
        scope.launch { runner() }
    }

    private suspend fun onPairingEvent(generation: Long, event: PairingProgressEvent) {
        if (generation != pairingGeneration) return
        when (event) {
            PairingProgressEvent.Connecting -> update { it.copy(pairing = PairingUiState.Connecting) }
            is PairingProgressEvent.PasskeyRequired -> update {
                it.copy(pairing = PairingUiState.PasskeyRequired(event.registration, event.ceremonyId))
            }

            is PairingProgressEvent.AwaitingConfirmation -> update {
                it.copy(pairing = PairingUiState.AwaitingMacConfirmation(event.shortCode))
            }

            PairingProgressEvent.IssuingCertificate -> update { it.copy(pairing = PairingUiState.IssuingCertificate) }
            is PairingProgressEvent.Completed -> onPairingCompleted(event.profile)
            is PairingProgressEvent.Failed -> update { it.copy(pairing = PairingUiState.Failed(event.code)) }
        }
    }

    private suspend fun onPairingCompleted(profile: PairedProfile) {
        profiles.save(profile)
        val trust = TrustState.Trusted(
            macId = MacId(profile.macId),
            macDisplayName = profile.macDisplayName,
            certificateSerial = profile.certificateSerial,
            certificateNotAfterEpochMillis = profile.certificateNotAfterEpochMillis,
        )
        persistTrust(trust)
        update { it.copy(pairing = null, pendingDeepLinkSessionId = null) }
        applyTrust(trust, clock.nowEpochMillis())
        connect()
    }

    private suspend fun cancelPairing() {
        pairingGeneration += 1
        update { it.copy(pairing = null) }
    }

    private suspend fun unpair() {
        pairingGeneration += 1
        closeGenerationNoReconnect()
        profiles.delete()
        update {
            it.copy(
                trust = TrustState.Unpaired,
                sessions = persistentMapOf(),
                authentication = null,
                pairing = null,
                selectedSessionId = null,
                terminalSessionId = null,
            )
        }
    }

    private suspend fun persistTrust(trust: TrustState) {
        val now = clock.nowEpochMillis()
        when (trust) {
            is TrustState.Trusted -> cache.upsertTrustState(
                TrustStateEntity(
                    macId = trust.macId.value,
                    status = StoredTrustStatus.TRUSTED,
                    displayName = trust.macDisplayName,
                    certificateSerial = trust.certificateSerial,
                    certificateNotAfterEpochMs = trust.certificateNotAfterEpochMillis,
                    revokedAtEpochMs = null,
                    revocationReasonCode = null,
                    updatedAtEpochMs = now,
                ),
            )

            is TrustState.Revoked -> cache.upsertTrustState(
                TrustStateEntity(
                    macId = trust.macId.value,
                    status = StoredTrustStatus.REVOKED,
                    displayName = null,
                    certificateSerial = null,
                    certificateNotAfterEpochMs = null,
                    revokedAtEpochMs = trust.revokedAtEpochMillis,
                    revocationReasonCode = trust.reasonCode,
                    updatedAtEpochMs = now,
                ),
            )

            TrustState.Unpaired -> Unit
        }
    }

    // Authentication and locking

    private fun authenticate() {
        val pending = pendingAssertion ?: return
        if (ceremonyInFlight) return
        ceremonyInFlight = true
        scope.launch {
            val (credential, failure) = passkeyBridge.performAssertion(pending.first, pending.second)
            submit(AppIntent.PasskeyCeremonyResult(pending.first, credential, failure))
        }
    }

    private suspend fun onPasskeyResult(result: AppIntent.PasskeyCeremonyResult) {
        ceremonyInFlight = false
        if (result.credentialJson == null) {
            update { it.copy(lastError = result.failureCode ?: "PASSKEY_FAILED") }
            return
        }
        val binding = pendingAssertionBinding ?: return
        runCatching {
            connector?.send(
                "auth.assertion.response",
                WireMessages.assertionResponse(result.ceremonyId, binding, result.credentialJson),
            )
        }.onFailure {
            update { it.copy(lastError = "PASSKEY_RESPONSE_SEND_FAILED") }
        }
    }

    private suspend fun onForeground(nowForeground: Boolean) {
        foreground = nowForeground
        voicePort.setForeground(nowForeground)
        if (nowForeground) {
            lockJob?.cancel()
            lockJob = null
            if (mutableState.value.connection is ConnectionState.Ready) startLease()
        } else {
            stopLease()
            lockJob?.cancel()
            lockJob = scope.launch {
                delay(backgroundLockMillis)
                submit(AppIntent.BackgroundLockElapsed)
            }
        }
    }

    private suspend fun lock(reason: LockReason) {
        lockJob?.cancel()
        lockJob = null
        val hadAuthentication = mutableState.value.authentication != null
        update { it.copy(authentication = null) }
        voicePort.cancel()
        terminalPort()?.close()
        if (mutableState.value.terminalSessionId != null) {
            update { it.copy(terminalSessionId = null) }
        }
        if (hadAuthentication) {
            runCatching { connector?.send("auth.lock", buildJsonObject { put("reason", reason.name) }) }
        }
        applyConnection(ConnectionState.Disconnected(DisconnectReason.AUTH_REQUIRED), clock.nowEpochMillis())
    }

    private fun startLease() {
        if (leaseJob != null || !foreground) return
        leaseJob = scope.launch {
            while (true) {
                delay(30_000)
                if (mutableState.value.connection is ConnectionState.Ready) {
                    runCatching { connector?.send("ping", WireMessages.foregroundPing(foregroundLease = true)) }
                }
            }
        }
    }

    private fun stopLease() {
        leaseJob?.cancel()
        leaseJob = null
    }

    // Session list / detail

    private suspend fun onListEvent(event: SessionListEvent) {
        when (event) {
            is SessionListEvent.OpenSession -> update { it.copy(selectedSessionId = event.sessionId) }
            is SessionListEvent.OpenSessionActions -> update { it.copy(selectedSessionId = event.sessionId) }
            SessionListEvent.PairMac -> handle(AppIntent.StartPairing)
            SessionListEvent.Authenticate -> authenticate()
            SessionListEvent.RetryConnection -> reconnectNow()
            SessionListEvent.Refresh -> if (mutableState.value.connection is ConnectionState.Ready) {
                update { it.copy(syncing = true) }
                sendSyncResume()
            }
        }
    }

    private suspend fun onDetailEvent(sessionId: SessionId, event: SessionDetailEvent) {
        val state = mutableState.value.sessions[sessionId] ?: return
        val now = clock.nowEpochMillis()
        when (event) {
            SessionDetailEvent.NavigateBack -> update { it.copy(selectedSessionId = null) }
            SessionDetailEvent.PairMac -> handle(AppIntent.StartPairing)
            SessionDetailEvent.Authenticate -> authenticate()
            SessionDetailEvent.RetryConnection -> reconnectNow()
            is SessionDetailEvent.UpdateTypedText -> mutateDraft(state) {
                DraftAction.ReplaceTypedText(event.text, it.revision, now)
            }

            is SessionDetailEvent.UpdateTranscription -> mutateDraft(state) {
                DraftAction.ReplaceTranscription(event.text.ifBlank { null }, it.revision, now)
            }

            SessionDetailEvent.InsertTranscription -> {
                val transcription = state.draft.transcriptionText ?: return
                mutateDraft(state) {
                    DraftAction.ReplaceTypedText(
                        (it.typedText + if (it.typedText.isBlank()) transcription else " $transcription"),
                        it.revision,
                        now,
                    )
                }
                mutateDraft(sessionId) {
                    DraftAction.ReplaceTranscription(null, it.revision, now)
                }
            }

            SessionDetailEvent.DiscardTranscription -> mutateDraft(state) {
                DraftAction.ReplaceTranscription(null, it.revision, now)
            }

            SessionDetailEvent.Send -> sendCommand(state, "prompt")
            SessionDetailEvent.SteerNow -> sendCommand(state, "steer")
            SessionDetailEvent.QueueFollowUp -> sendCommand(state, "follow_up")
            SessionDetailEvent.Stop -> sendCommand(state, "interrupt")
            SessionDetailEvent.Attach -> Unit
            SessionDetailEvent.StartVoice -> voicePort.start()?.let { code ->
                update { it.copy(lastError = code) }
            }

            SessionDetailEvent.LoadOlder -> loadOlder(state)
            SessionDetailEvent.RetryApprovalService -> reconnectNow()
            SessionDetailEvent.DismissApprovalNotice -> Unit
            is SessionDetailEvent.UseQuickReply -> mutateDraft(state) {
                DraftAction.ReplaceTypedText(event.text, it.revision, now)
            }

            is SessionDetailEvent.ToggleContent -> Unit
            is SessionDetailEvent.InspectRaw -> Unit
            is SessionDetailEvent.DecideApproval -> {
                runCatching {
                    connector?.send(
                        "approval.decision",
                        WireMessages.approvalDecision(
                            event.binding.offerId,
                            event.binding.operationId,
                            event.binding.argumentHash,
                            event.decision == ApprovalDecision.ALLOW_ONCE,
                        ),
                    )
                }
                update { it.copy(approval = null) }
            }
        }
    }

    private suspend fun mutateDraft(sessionId: SessionId, action: (DraftState) -> DraftAction) {
        val state = mutableState.value.sessions[sessionId] ?: return
        mutateDraft(state, action)
    }

    private suspend fun mutateDraft(state: SessionState, action: (DraftState) -> DraftAction) {
        val now = clock.nowEpochMillis()
        val next = SessionReducer.reduce(state, SessionAction.Draft(action(state.draft)), now)
        updateSession(next)
        runCatching {
            cache.upsertDraft(
                DraftEntity(
                    sessionId = next.metadata.id.value,
                    typedText = next.draft.typedText,
                    transcriptionText = next.draft.transcriptionText,
                    revision = next.draft.revision,
                    updatedAtEpochMs = next.draft.updatedAtEpochMillis,
                ),
            )
        }
    }

    /**
     * Loads one retained page of older messages from the encrypted cache into the detail
     * state. App-side adapter: core/model has no LoadOlder action yet, so the page is
     * prepended to the conversation copy with reducer invariants preserved (distinct ids,
     * strictly increasing append ordinals). hasOlderMessages reflects retained storage;
     * quota-evicted history is honestly absent.
     */
    private suspend fun loadOlder(state: SessionState) {
        val conversation = state.conversation
        if (conversation.availability !is CanonicalAvailability.Current) return
        if (!conversation.hasOlderMessages) return
        val sessionId = state.metadata.id
        if (!loadingOlder.add(sessionId)) return
        try {
            val earliestOrdinal = conversation.finalizedMessages.firstOrNull()?.appendOrdinal
            if (earliestOrdinal == null) {
                updateSession(state.copy(conversation = conversation.copy(hasOlderMessages = false)))
                return
            }
            val page = cache.loadOlderMessages(sessionId.value, earliestOrdinal.toString(), OLDER_PAGE_SIZE)
            val knownIds = conversation.finalizedMessages.mapTo(HashSet()) { it.id }
            val older = page.messages
                .mapNotNull(StorageMappers::finalizedMessage)
                .filter { it.appendOrdinal < earliestOrdinal && it.id !in knownIds }
                .sortedBy { it.appendOrdinal }
            if (older.isEmpty()) {
                updateSession(state.copy(conversation = conversation.copy(hasOlderMessages = false)))
                return
            }
            val merged = (older + conversation.finalizedMessages).toPersistentList()
            updateSession(
                state.copy(
                    conversation = conversation.copy(
                        finalizedMessages = merged,
                        hasOlderMessages = page.hasMore,
                    ),
                ),
            )
        } finally {
            loadingOlder.remove(sessionId)
        }
    }

    private suspend fun sendCommand(state: SessionState, operation: String) {
        if (mutableState.value.connection !is ConnectionState.Ready) return
        if (state.conversation.availability !is CanonicalAvailability.Current) return
        val text = state.draft.typedText
        if (operation != "interrupt" && text.isBlank()) return
        val payload = buildJsonObject { put("text", text) }
        val hash = io.github.verybigsad.pimobile.protocol.commandPayloadHash(
            state.metadata.id.value,
            operation,
            payload,
        )
        val commandId = UUID.randomUUID().toString()
        runCatching {
            connector?.send(
                "command.submit",
                WireMessages.commandSubmit(commandId, state.metadata.id, operation, payload, hash),
            )
        }.onSuccess {
            if (operation != "interrupt") {
                mutateDraft(state) { DraftAction.Clear(it.revision, clock.nowEpochMillis()) }
            }
        }.onFailure {
            update { it.copy(lastError = "COMMAND_SEND_FAILED") }
        }
    }

    private fun onVoiceTranscript(transcript: VoiceTranscript) {
        val sessionId = mutableState.value.selectedSessionId ?: return
        scope.launch {
            when (transcript.kind) {
                VoiceTranscriptKind.PARTIAL -> mutateDraft(sessionId) {
                    DraftAction.ReplaceTranscription(transcript.text, it.revision, clock.nowEpochMillis())
                }

                VoiceTranscriptKind.FINAL -> mutateDraft(sessionId) {
                    DraftAction.ReplaceTypedText(
                        (it.typedText + if (it.typedText.isBlank()) transcript.text else " ${transcript.text}"),
                        it.revision,
                        clock.nowEpochMillis(),
                    )
                }
            }
        }
    }

    private fun onDeepLink(sessionId: SessionId?) {
        update { it.copy(pendingDeepLinkSessionId = sessionId) }
        maybeConsumeDeepLink()
    }

    private fun maybeConsumeDeepLink() {
        val state = mutableState.value
        val pending = state.pendingDeepLinkSessionId ?: return
        if (state.authentication == null || state.syncing) return
        val session = state.sessions[pending] ?: return
        if (session.conversation.availability !is CanonicalAvailability.Current) return
        update { it.copy(selectedSessionId = pending, pendingDeepLinkSessionId = null) }
    }

    private suspend fun onWake() {
        val state = mutableState.value
        if (state.authentication != null && state.connection is ConnectionState.Ready) {
            update { it.copy(syncing = true) }
            sendSyncResume()
        } else {
            wakeNotifier.notifyLockedWake()
        }
    }

    // Helpers

    private fun updateSession(session: SessionState) {
        update {
            it.copy(sessions = (it.sessions.put(session.metadata.id, session)))
        }
    }

    private fun sessionEntityFor(session: SessionState, now: Long): io.github.verybigsad.pimobile.storage.SessionEntity =
        io.github.verybigsad.pimobile.storage.SessionEntity(
            sessionId = session.metadata.id.value,
            cwd = session.metadata.repositoryPath,
            displayName = session.metadata.displayName,
            provider = "unknown",
            modelId = "unknown",
            thinkingLevel = "unknown",
            canonicalCursor = session.conversation.cursor?.let(StorageMappers::cursor),
            updatedAtEpochMs = now,
        )

    private suspend fun closeGenerationNoReconnect() {
        connectionGeneration += 1
        reconnectJob?.cancel()
        runCatching { connector?.close() }
        connector = null
        stopLease()
    }

    private fun update(transform: (PiAppState) -> PiAppState) {
        mutableState.value = transform(mutableState.value)
    }

    private companion object {
        const val OLDER_PAGE_SIZE = 100
    }

    private var pendingAssertionBinding: kotlinx.serialization.json.JsonObject? = null
}
