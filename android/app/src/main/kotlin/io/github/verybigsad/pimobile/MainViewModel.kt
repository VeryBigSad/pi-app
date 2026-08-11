package io.github.verybigsad.pimobile

import androidx.lifecycle.ViewModel
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.session.PasskeyProviderAvailability
import io.github.verybigsad.pimobile.session.SessionBucket
import io.github.verybigsad.pimobile.session.SessionDetailUiState
import io.github.verybigsad.pimobile.session.SessionListItemUiState
import io.github.verybigsad.pimobile.session.SessionListUiState
import io.github.verybigsad.pimobile.session.displayLabel
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.state.AppPasskeyAvailability
import io.github.verybigsad.pimobile.state.PiAppCoordinator
import io.github.verybigsad.pimobile.state.PiAppState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

/** Thin lifecycle facade over the application-scoped coordinator; holds no second truth. */
class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val coordinator: PiAppCoordinator get() = container.coordinator

    val state: StateFlow<PiAppState> = coordinator.state

    val settingsState: StateFlow<io.github.verybigsad.pimobile.settings.SettingsUiState>
        get() = container.settingsProjection.state

    val agentsStore: io.github.verybigsad.pimobile.agents.AgentsStore get() = container.agentsStore

    val updateState: StateFlow<io.github.verybigsad.pimobile.update.UpdateState>
        get() = container.updateIntegration.state

    val updateMeteredConfirmation: StateFlow<Long?> get() = container.updateIntegration.meteredConfirmationRequired

    val notificationPermissionStatus: StateFlow<io.github.verybigsad.pimobile.notifications.NotificationPermissionStatus>
        get() = container.notificationPermission.status

    val updateIntegration: io.github.verybigsad.pimobile.updatewiring.UpdateIntegration
        get() = container.updateIntegration

    fun submit(intent: AppIntent) = coordinator.submit(intent)

    fun openTerminal(sessionId: SessionId) = container.openTerminal(sessionId)

    fun listUiState(appState: PiAppState, now: Long): SessionListUiState = SessionListUiState(
        trust = appState.trust,
        connection = appState.connection,
        passkeyProvider = appState.passkeyProvider.toUi(),
        retainedAuthentication = appState.authentication,
        nowEpochMillis = now,
        sessions = appState.sessions.values
            .sortedByDescending { it.metadata.updatedAtEpochMillis }
            .map { session ->
                SessionListItemUiState(
                    metadata = session.metadata,
                    runState = session.conversation.runState,
                    bucket = bucket(session.conversation.runState),
                    latestActivity = session.conversation.runState.displayLabel(),
                    lastActiveLabel = timeLabel(session.metadata.updatedAtEpochMillis, now),
                    blockerCount = if (appState.approval != null) 1 else 0,
                    parentSessionLabel = session.metadata.parentSessionId?.value,
                )
            },
        lastSyncedLabel = appState.sessions.values
            .maxOfOrNull { it.metadata.updatedAtEpochMillis }
            ?.let { timeLabel(it, now) },
        isRefreshing = appState.syncing,
    )

    fun detailUiState(appState: PiAppState, sessionId: SessionId, now: Long): SessionDetailUiState? {
        val session = appState.sessions[sessionId] ?: return null
        val macName = (appState.trust as? io.github.verybigsad.pimobile.model.TrustState.Trusted)?.macDisplayName ?: "Mac"
        val catalogEntry = appState.catalog?.get(sessionId)
        return SessionDetailUiState(
            session = session,
            passkeyProvider = appState.passkeyProvider.toUi(),
            retainedAuthentication = appState.authentication,
            nowEpochMillis = now,
            macDisplayName = macName,
            modelName = catalogEntry?.modelName,
            thinkingLevel = catalogEntry?.thinkingLevel,
            elapsedLabel = null,
            lastSyncedLabel = timeLabel(session.metadata.updatedAtEpochMillis, now),
            approvalOffer = appState.approval,
        )
    }

    private fun bucket(runState: SessionRunState): SessionBucket = when (runState) {
        SessionRunState.WAITING_FOR_INPUT -> SessionBucket.NEEDS_YOU
        SessionRunState.STREAMING, SessionRunState.RETRYING, SessionRunState.COMPACTING -> SessionBucket.WORKING
        SessionRunState.SETTLED -> SessionBucket.READY_TO_REVIEW
        SessionRunState.IDLE -> SessionBucket.DONE
        SessionRunState.WAITING_FOR_CANONICAL -> SessionBucket.INDETERMINATE
        SessionRunState.FAULTED -> SessionBucket.INDETERMINATE
    }

    companion object {
        fun timeLabel(epochMillis: Long, now: Long): String {
            val delta = (now - epochMillis).coerceAtLeast(0)
            return when {
                delta < 60_000 -> "just now"
                delta < 3_600_000 -> "${delta / 60_000} min ago"
                delta < 86_400_000 -> "${delta / 3_600_000} h ago"
                else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
            }
        }
    }
}

private fun AppPasskeyAvailability.toUi(): PasskeyProviderAvailability = when (this) {
    AppPasskeyAvailability.CHECKING -> PasskeyProviderAvailability.Checking
    AppPasskeyAvailability.AVAILABLE -> PasskeyProviderAvailability.Available("the device passkey provider")
    AppPasskeyAvailability.UNAVAILABLE -> PasskeyProviderAvailability.Unavailable(
        "No compatible passkey provider is available on this device.",
    )
}

val PiAppState.connectedOnce: Boolean
    get() = connection !is ConnectionState.Disconnected
