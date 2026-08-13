package io.github.verybigsad.pimobile

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.pairing.PairingPasskeyPort
import io.github.verybigsad.pimobile.session.VoiceCaptureUiState
import io.github.verybigsad.pimobile.pairing.PairingRunner
import io.github.verybigsad.pimobile.push.EndpointUploadResult
import io.github.verybigsad.pimobile.push.LockedWakeNotifier
import io.github.verybigsad.pimobile.push.PushNotificationChannels
import io.github.verybigsad.pimobile.push.PushRuntimeInitializer
import io.github.verybigsad.pimobile.push.UnifiedPushClient
import io.github.verybigsad.pimobile.push.UnifiedPushEndpoint
import io.github.verybigsad.pimobile.push.UnifiedPushEndpointUploader
import io.github.verybigsad.pimobile.push.UnifiedPushProviderState
import io.github.verybigsad.pimobile.push.UnifiedPushRuntime
import io.github.verybigsad.pimobile.push.WakeReconnectResult
import io.github.verybigsad.pimobile.security.DeviceKeys
import io.github.verybigsad.pimobile.security.PairedProfileStore
import io.github.verybigsad.pimobile.state.AppClock
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.state.AppPasskeyAvailability
import io.github.verybigsad.pimobile.state.CachePort
import io.github.verybigsad.pimobile.state.OlderMessagesPage
import io.github.verybigsad.pimobile.state.PiAppCoordinator
import io.github.verybigsad.pimobile.state.PushDrainPort
import io.github.verybigsad.pimobile.state.SystemAppClock
import io.github.verybigsad.pimobile.state.UnpairPort
import io.github.verybigsad.pimobile.state.VoicePort
import io.github.verybigsad.pimobile.state.WakeNotificationPort
import io.github.verybigsad.pimobile.storage.CanonicalAppendCursor
import io.github.verybigsad.pimobile.storage.CanonicalResyncSignal
import io.github.verybigsad.pimobile.storage.DraftEntity
import io.github.verybigsad.pimobile.storage.DurableEndpointQueue
import io.github.verybigsad.pimobile.storage.EncryptedCache
import io.github.verybigsad.pimobile.storage.MessageEntity
import io.github.verybigsad.pimobile.storage.OpenedCache
import io.github.verybigsad.pimobile.storage.PiMobileDao
import io.github.verybigsad.pimobile.storage.SessionEntity
import io.github.verybigsad.pimobile.storage.TrustStateEntity
import io.github.verybigsad.pimobile.terminal.TerminalSessionController
import io.github.verybigsad.pimobile.updatewiring.UpdateIntegration
import io.github.verybigsad.pimobile.voice.AndroidAudioRecordSourceFactory
import io.github.verybigsad.pimobile.voice.AndroidMicrophonePermissionSource
import io.github.verybigsad.pimobile.voice.GatewayMacVoiceTransport
import io.github.verybigsad.pimobile.voice.VoiceCaptureController
import io.github.verybigsad.pimobile.voice.VoiceFrontendState
import io.github.verybigsad.pimobile.voice.VoiceStartResult
import io.github.verybigsad.pimobile.voice.toVoiceCaptureUiState
import io.github.verybigsad.pimobile.voice.VoiceTranscriptSink
import io.github.verybigsad.pimobile.voice.VoiceTranscriptGate
import io.github.verybigsad.pimobile.wire.HostConnectionEvent
import io.github.verybigsad.pimobile.wire.HostConnectionRunner
import io.github.verybigsad.pimobile.wire.HostConnector
import io.github.verybigsad.pimobile.wire.PimbHostConnector
import io.github.verybigsad.pimobile.wire.WireMessages
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RoomCachePort(
    private val context: Context,
    opened: OpenedCache,
) : CachePort {
    private val dao: PiMobileDao = opened.database.dao()

    override suspend fun loadTrustState(macId: String): TrustStateEntity? = dao.trustState(macId)

    override suspend fun loadSessions(): List<SessionEntity> = dao.observeSessions().first()

    override suspend fun loadSession(sessionId: String): SessionEntity? = dao.session(sessionId)

    override suspend fun upsertSession(session: SessionEntity) = dao.upsertSession(session)

    override suspend fun loadRecentMessages(sessionId: String, limit: Int): List<MessageEntity> =
        dao.observeRecentMessages(sessionId, limit).first()

    override suspend fun messageCount(sessionId: String): Int = dao.messageCount(sessionId)

    override suspend fun loadOlderMessages(sessionId: String, beforeAppendOrder: String, limit: Int): OlderMessagesPage {
        val page = dao.messagesOlderPage(sessionId, beforeAppendOrder, limit).asReversed()
        val hasMore = if (page.size < limit) {
            false
        } else {
            dao.messageCountBefore(sessionId, page.first().appendOrder) > 0
        }
        return OlderMessagesPage(page, hasMore)
    }

    override suspend fun loadDrafts(): List<DraftEntity> =
        loadSessions().mapNotNull { dao.draft(it.sessionId) }

    override suspend fun upsertDraft(draft: DraftEntity) = dao.upsertDraft(draft)

    override suspend fun upsertTrustState(trustState: TrustStateEntity) = dao.upsertTrustState(trustState)

    override suspend fun deleteTrustState(macId: String) = dao.deleteTrustState(macId)

    override suspend fun markCanonicalUnavailable() = dao.clearAll()

    override suspend fun commitCanonicalEvent(session: SessionEntity, finalized: MessageEntity?) =
        dao.commitCanonicalEvent(session, finalized)

    override suspend fun replaceSessionSnapshot(session: SessionEntity, messages: List<MessageEntity>) =
        dao.replaceSessionSnapshot(session, messages)

    override suspend fun resetSessionContent(session: SessionEntity) =
        dao.replaceSessionSnapshot(session, emptyList())

    override suspend fun revokeAndPurge(macId: String, revokedAtEpochMs: Long, reasonCode: String) =
        dao.revokeAndPurge(macId, revokedAtEpochMs, reasonCode)

    override suspend fun committedCursors(): List<Pair<String, CanonicalAppendCursor>> =
        loadSessions().mapNotNull { entity -> entity.canonicalCursor?.let { entity.sessionId to it } }

    override suspend fun acknowledgeCanonicalResync(signal: CanonicalResyncSignal): Boolean =
        EncryptedCache.acknowledgeCanonicalResync(context, signal)
}

class AppContainer(
    private val application: PiMobileApplication,
    val clock: AppClock = SystemAppClock,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val openedCache: OpenedCache = EncryptedCache.open(application)
    val profiles = PairedProfileStore(application)
    val deviceKeys = DeviceKeys()
    val endpointQueue = DurableEndpointQueue(application)
    private val notifier = LockedWakeNotifier(application)
    lateinit var pushClient: UnifiedPushClient
        private set
    val updateIntegration = UpdateIntegration(application, BuildConfig.VERSION_CODE.toLong())
    val agentsStore = io.github.verybigsad.pimobile.agents.AgentsStore()
    val notificationPermission = io.github.verybigsad.pimobile.notifications.NotificationPermissionController(application)

    lateinit var settingsProjection: io.github.verybigsad.pimobile.settingswiring.SettingsProjection
        private set

    @Volatile
    private var pairedRelayUrl: String? = null

    @Volatile
    var activeConnector: HostConnector? = null
        private set

    @Volatile
    var terminalController: TerminalSessionController? = null
        private set

    private val voiceTransport = GatewayMacVoiceTransport { activeConnector }
    private val mutableVoiceUiState = MutableStateFlow<VoiceCaptureUiState?>(null)
    val voiceUiState: StateFlow<VoiceCaptureUiState?> = mutableVoiceUiState.asStateFlow()
    private val coordinatorVoicePort = CoordinatorVoicePort()

    lateinit var coordinator: PiAppCoordinator
        private set

    private var transcriptGate: VoiceTranscriptGate? = null
    private val voiceConnectionGeneration = AtomicLong()

    fun start() {
        PushNotificationChannels.create(application)
        installPush()
        coordinator = PiAppCoordinator(
            scope = scope,
            cache = RoomCachePort(application, openedCache),
            profiles = profiles,
            connectorFactory = { profile, onEvent ->
                val voiceGeneration = voiceConnectionGeneration.incrementAndGet()
                transcriptGate?.reset(voiceGeneration)
                wrappingRunner(
                    PimbHostConnector(profile, deviceKeys, BuildConfig.VERSION_NAME, scope) { event ->
                        if (event is HostConnectionEvent.Disconnected) {
                            activeConnector = null
                            transcriptGate?.reset(voiceConnectionGeneration.incrementAndGet())
                            scope.launch { coordinatorVoicePort.cancel() }
                        }
                        if (event is HostConnectionEvent.VoiceTranscript) {
                            transcriptGate?.accept(voiceGeneration, event.sessionId, event.type, event.body)
                        }
                        onEvent(event)
                    },
                    onEvent,
                )
            },
            pairingRunnerFactory = { invitation, deviceId, onEvent ->
                val runner = PairingRunner(
                    invitation = invitation,
                    deviceKeys = deviceKeys,
                    deviceId = deviceId,
                    passkey = pairingPasskeyPort(),
                    onEvent = onEvent,
                )
                suspend { runner.run() }
            },
            passkeyBridge = application.passkeyBridge,
            voicePort = coordinatorVoicePort,
            terminalPort = { terminalController },
            wakeNotifier = object : WakeNotificationPort {
                override fun notifyLockedWake() = notifier.notifyActivityPending()
            },
            pushDrain = PushDrain(),
            passkeyAvailability = { passkeyAvailability() },
            pendingResyncSignal = { openedCache.canonicalResync },
            clock = clock,
            unpairPort = object : UnpairPort {
                override suspend fun unregister() {
                    runCatching { pushClient.unregister() }
                    endpointQueue.clear()
                    PushRuntimeInitializer.forgetRegistration(application)
                }
            },
            agentsSink = object : io.github.verybigsad.pimobile.state.AgentsEventSink {
                override fun onCatalog(catalog: io.github.verybigsad.pimobile.network.WireBodies.AgentsCatalog) =
                    agentsStore.applyCatalog(catalog)

                override fun onUpdate(update: io.github.verybigsad.pimobile.network.WireBodies.AgentsUpdate) =
                    agentsStore.applyUpdate(update)

                override fun onOffline(offline: Boolean) = agentsStore.setOffline(offline)
            },
        )
        coordinator.start()
        pairedRelayUrl = runBlocking { profiles.load() }?.relayWssUrl
        updateIntegration.start(application)
        settingsProjection = io.github.verybigsad.pimobile.settingswiring.SettingsProjection(
            context = application,
            appState = coordinator.state,
            pushState = UnifiedPushRuntime.state,
            updateState = updateIntegration.state,
            permissionState = notificationPermission.status,
            updateAdapter = io.github.verybigsad.pimobile.settings.UpdateIntegrationUiAdapter(
                currentVersionName = BuildConfig.VERSION_NAME,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                lastCheckedEpochMillis = updateIntegration.lastCheckedEpochMillis,
            ),
            autoLockMinutes = AUTO_LOCK_MINUTES,
            relayUrl = { pairedRelayUrl },
            piVersion = BuildConfig.VERSION_NAME,
            providers = { pushClient.availableProviderChoices() },
            scope = scope,
        )
        observeProcessLifecycle()
        observeDeviceLock()
    }

    private fun wrappingRunner(
        delegate: HostConnectionRunner,
        onEvent: (HostConnectionEvent) -> Unit,
    ): HostConnectionRunner = HostConnectionRunner {
        val connector = delegate.run()
        activeConnector = connector
        connector
    }

    fun openTerminal(sessionId: SessionId) {
        val controller = TerminalSessionController(
            context = application,
            sessionId = sessionId,
            connector = { activeConnector },
            onClosed = { terminalController = null },
        )
        terminalController = controller
        controller.start()
        coordinator.submit(AppIntent.OpenTerminal(sessionId))
    }

    fun voiceTranscriptSink(): VoiceTranscriptGate {
        transcriptGate?.let { return it }
        val sink = object : VoiceTranscriptSink {
            override fun onPartialDraft(
                targetSessionId: String,
                transcript: io.github.verybigsad.pimobile.voice.VoiceTranscript,
            ) {
                coordinator.submit(AppIntent.VoiceTranscriptReceived(SessionId(targetSessionId), transcript))
            }

            override fun onFinalDraft(
                targetSessionId: String,
                transcript: io.github.verybigsad.pimobile.voice.VoiceTranscript,
            ) {
                coordinatorVoicePort.onFinalTranscript(SessionId(targetSessionId), transcript.sessionId)
                coordinator.submit(AppIntent.VoiceTranscriptReceived(SessionId(targetSessionId), transcript))
            }
        }
        val gate = VoiceTranscriptGate(sink).also { it.reset(voiceConnectionGeneration.get()) }
        transcriptGate = gate
        voiceTransport.attachTranscriptSink(sink)
        return gate
    }

    private fun pairingPasskeyPort(): PairingPasskeyPort = PairingPasskeyPort { registration, optionsJson, onFailure ->
        val bridge = application.passkeyBridge
        val (credential, failure) = if (registration) {
            bridge.performRegistration(optionsJson)
        } else {
            bridge.performAssertion("pairing", optionsJson)
        }
        if (credential == null) onFailure(failure ?: "PASSKEY_FAILED")
        credential
    }

    /** Re-probes the passkey provider once an Activity is attached and publishes the result. */
    fun refreshPasskeyAvailability() {
        coordinator.submit(AppIntent.PasskeyAvailabilityChanged(passkeyAvailability()))
    }

    private fun passkeyAvailability(): AppPasskeyAvailability {
        // Debug builds run ceremonies through the installed debug passkey executor regardless
        // of provider probing; core availability() still reports Locked on the debug package
        // identity, so surface the executor as the provider here.
        if (BuildConfig.DEBUG && io.github.verybigsad.pimobile.security.PasskeyDebugHooks.executor != null) {
            return AppPasskeyAvailability.AVAILABLE
        }
        return when (application.passkeyBridge.availability()) {
            null -> AppPasskeyAvailability.CHECKING
            is io.github.verybigsad.pimobile.security.PasskeyAvailability.Available -> AppPasskeyAvailability.AVAILABLE
            is io.github.verybigsad.pimobile.security.PasskeyAvailability.CandidateAvailable -> AppPasskeyAvailability.CANDIDATE_AVAILABLE
            is io.github.verybigsad.pimobile.security.PasskeyAvailability.Locked -> AppPasskeyAvailability.UNAVAILABLE
        }
    }

    private fun installPush() {
        pushClient = PushRuntimeInitializer.install(
            context = application,
            endpointUploader = object : UnifiedPushEndpointUploader {
                override suspend fun upload(endpoint: UnifiedPushEndpoint): EndpointUploadResult = runCatching {
                    endpointQueue.enqueue(
                        DurableEndpointQueue.Operation(
                            endpointId = stableEndpointId(endpoint.instance),
                            distributor = currentDistributor(),
                            endpoint = endpoint.url,
                            wakePublicKey = endpoint.publicKey,
                            revoke = false,
                        ),
                    )
                    EndpointUploadResult.UPLOADED
                }.getOrDefault(EndpointUploadResult.RETRY_REQUIRED)

                override suspend fun remove(instance: String): EndpointUploadResult = runCatching {
                    endpointQueue.enqueue(
                        DurableEndpointQueue.Operation(
                            endpointId = stableEndpointId(instance),
                            distributor = currentDistributor(),
                            endpoint = "",
                            wakePublicKey = null,
                            revoke = true,
                        ),
                    )
                    EndpointUploadResult.UPLOADED
                }.getOrDefault(EndpointUploadResult.RETRY_REQUIRED)
            },
            wakeReconnector = {
                if (!::coordinator.isInitialized) {
                    WakeReconnectResult.RETRY
                } else {
                    coordinator.submit(AppIntent.WakeReceived)
                    WakeReconnectResult.COMPLETED
                }
            },
        )
        scope.launch {
            runCatching { pushClient.requestRegistration() }
        }
    }

    fun selectPushProvider(packageName: String) {
        scope.launch {
            val selected = pushClient.selectProvider(packageName)
            if (selected is UnifiedPushProviderState.ProviderSelected) {
                pushClient.requestRegistration()
            }
        }
    }

    fun requestPushRegistration() {
        scope.launch { pushClient.requestRegistration() }
    }

    fun unregisterPush() {
        scope.launch { pushClient.unregister() }
    }

    private fun currentDistributor(): String =
        (UnifiedPushRuntime.state.value.provider as? UnifiedPushProviderState.ProviderSelected)?.packageName
            ?: "unifiedpush"

    private fun stableEndpointId(instance: String): String =
        UUID.nameUUIDFromBytes("pi-mobile-push:$instance".encodeToByteArray()).toString()

    companion object {
        private const val AUTO_LOCK_MINUTES = 5
    }

    private fun observeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    coordinator.submit(AppIntent.ForegroundChanged(true))
                }

                override fun onStop(owner: LifecycleOwner) {
                    coordinator.submit(AppIntent.ForegroundChanged(false))
                }
            },
        )
    }

    private fun observeDeviceLock() {
        val keyguard = application.getSystemService(KeyguardManager::class.java)
        application.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (keyguard?.isDeviceLocked == true) {
                        coordinator.submit(AppIntent.DeviceLockDetected)
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )
    }

    private inner class CoordinatorVoicePort : VoicePort {
        private var controller: VoiceCaptureController? = null

        @Volatile
        private var targetSessionId: SessionId? = null

        @Volatile
        private var pendingStartTargetSessionId: SessionId? = null

        @Volatile
        private var finalTranscriptStreamId: String? = null

        private fun controller(): VoiceCaptureController = controller ?: VoiceCaptureController(
            permissionSource = AndroidMicrophonePermissionSource(application),
            audioSourceFactory = AndroidAudioRecordSourceFactory(application),
            transport = voiceTransport,
            parentScope = scope,
        ).also { active ->
            voiceTranscriptSink()
            controller = active
            scope.launch {
                active.state.collect { state -> publish(state) }
            }
        }

        override suspend fun setForeground(foreground: Boolean) {
            val active = controller
            val streamId = active?.state?.value?.sessionId
            if (!foreground && streamId != null) {
                transcriptGate?.cancel(streamId, voiceConnectionGeneration.get())
            }
            active?.setForeground(foreground)
            active?.state?.value?.let(::publish)
        }

        override suspend fun start(targetSessionId: SessionId): String? {
            val active = controller()
            if (this.targetSessionId == null) {
                pendingStartTargetSessionId = targetSessionId
            }
            active.setForeground(true)
            publish(active.state.value)
            val result = active.start()
            if (result != VoiceStartResult.ALREADY_ACTIVE) {
                this.targetSessionId = targetSessionId
                finalTranscriptStreamId = null
            }
            pendingStartTargetSessionId = null
            publish(active.state.value)
            val generation = voiceConnectionGeneration.get()
            return when (result) {
                VoiceStartResult.STARTED -> {
                    val streamId = active.state.value.sessionId
                    if (
                        streamId == null ||
                        voiceTranscriptSink().begin(streamId, targetSessionId.value, generation) != null
                    ) {
                        active.cancel()
                        publish(active.state.value)
                        "VOICE_CONNECTION_CHANGED"
                    } else {
                        null
                    }
                }

                VoiceStartResult.PERMISSION_REQUIRED -> "VOICE_PERMISSION_REQUIRED"
                VoiceStartResult.PERMISSION_DENIED -> "VOICE_PERMISSION_DENIED"
                VoiceStartResult.NOT_FOREGROUND -> "VOICE_NOT_FOREGROUND"
                else -> "VOICE_START_FAILED"
            }
        }

        override suspend fun stop() {
            val active = controller ?: return
            active.stop()
            publish(active.state.value)
        }

        override suspend fun cancel() {
            val active = controller ?: return
            active.state.value.sessionId?.let { streamId ->
                transcriptGate?.cancel(streamId, voiceConnectionGeneration.get())
            }
            active.cancel()
            publish(active.state.value)
        }

        override suspend fun onMacError(sessionId: String, error: io.github.verybigsad.pimobile.voice.MacVoiceError) {
            transcriptGate?.cancel(sessionId, voiceConnectionGeneration.get())
            controller?.onMacError(sessionId, error)
            controller?.state?.value?.let(::publish)
        }

        fun onFinalTranscript(targetSessionId: SessionId, streamId: String) {
            val active = controller ?: return
            if (this.targetSessionId != targetSessionId || active.state.value.sessionId != streamId) return
            finalTranscriptStreamId = streamId
            publish(active.state.value)
        }

        private fun publish(state: VoiceFrontendState) {
            val target = targetSessionId ?: pendingStartTargetSessionId ?: return
            mutableVoiceUiState.value = state.toVoiceCaptureUiState(
                targetSessionId = target,
                finalTranscriptReady = finalTranscriptStreamId == state.sessionId,
            )
        }
    }

    private inner class PushDrain : PushDrainPort {
        override suspend fun drain(connector: HostConnector) {
            for (operation in endpointQueue.all()) {
                val sent = runCatching {
                    if (operation.revoke) {
                        connector.send("push.endpoint.revoke", WireMessages.pushEndpointRevoke(operation.endpointId))
                    } else {
                        connector.send(
                            "push.endpoint",
                            WireMessages.pushEndpoint(
                                operation.endpointId,
                                operation.distributor,
                                operation.endpoint,
                                operation.wakePublicKey,
                            ),
                        )
                    }
                }.isSuccess
                if (sent) endpointQueue.remove(operation.endpointId)
            }
        }
    }
}
