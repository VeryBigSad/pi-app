package io.github.verybigsad.pimobile.session.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.TrustState
import kotlin.math.max

@Immutable
data class SessionStatusState(
    val trust: TrustState,
    val connection: ConnectionState,
    val passkeyAuthentication: PasskeyAuthentication?,
    val passkeyProviderPresent: Boolean,
    val nowEpochMillis: Long,
    val sessions: List<SessionStatusSession>,
    val lastSeenLabel: String? = null,
    val provisionalCertificateFingerprint: String? = null,
    val lock: SessionLockState? = null,
    val error: SessionStatusError? = null,
) {
    init {
        require(nowEpochMillis >= 0)
        require(lastSeenLabel == null || lastSeenLabel.isNotBlank())
        require(provisionalCertificateFingerprint == null || provisionalCertificateFingerprint.isNotBlank())
    }
}

@Immutable
data class SessionStatusSession(val runState: SessionRunState)

sealed interface SessionLockState {
    data object LockedByTimeout : SessionLockState
    data object DeviceLocked : SessionLockState
    data object ProviderAbsent : SessionLockState
}

enum class SessionStatusAction {
    RETRY,
    UNLOCK,
    PAIR,
}

enum class SessionStatusError {
    NETWORK,
    TIMEOUT,
    PROTOCOL,
    AUTHENTICATION,
    UNKNOWN,
    ;

    companion object {
        fun from(error: Throwable): SessionStatusError = when (error) {
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            -> NETWORK
            is java.net.SocketTimeoutException -> TIMEOUT
            is SecurityException -> AUTHENTICATION
            is IllegalArgumentException,
            is IllegalStateException,
            -> PROTOCOL
            else -> UNKNOWN
        }
    }
}

@Composable
fun SessionStatusSurfaces(
    state: SessionStatusState,
    onAction: (SessionStatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedStatusBanner(connectionNotice(state), onAction)
        AnimatedStatusBanner(canonicalNotice(state.sessions), onAction)
        AnimatedStatusBanner(authenticationNotice(state), onAction)
        AnimatedStatusBanner(provisionalNotice(state), onAction)
        AnimatedStatusBanner(errorNotice(state.error), onAction)
    }
}

@Composable
fun SessionLockOverlay(
    state: SessionStatusState,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) = SessionLockOverlay(
    lock = state.lock ?: inferredLock(state),
    onUnlock = onUnlock,
    modifier = modifier,
)

@Composable
fun SessionLockOverlay(
    lock: SessionLockState?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastLock = remember { mutableStateOf<SessionLockState?>(null) }
    if (lock != null) lastLock.value = lock
    AnimatedVisibility(
        visible = lock != null,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier.fillMaxSize(),
    ) {
        val notice = requireNotNull(lockNotice(requireNotNull(lastLock.value)))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .testTag("session_lock_overlay"),
            contentAlignment = Alignment.Center,
        ) {
            StatusCard(notice = notice, onAction = { onUnlock() }, modifier = Modifier.padding(24.dp))
        }
    }
}

@Composable
private fun AnimatedStatusBanner(notice: StatusNotice?, onAction: (SessionStatusAction) -> Unit) {
    val lastNotice = remember { mutableStateOf<StatusNotice?>(null) }
    if (notice != null) lastNotice.value = notice
    AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        StatusCard(notice = requireNotNull(lastNotice.value), onAction = onAction)
    }
}

@Composable
private fun StatusCard(
    notice: StatusNotice,
    onAction: (SessionStatusAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = when (notice.severity) {
        StatusSeverity.INFO -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        StatusSeverity.WARNING -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        StatusSeverity.ERROR -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = if (notice.severity == StatusSeverity.ERROR) LiveRegionMode.Assertive else LiveRegionMode.Polite
                stateDescription = notice.accessibilityText
            }
            .testTag(notice.tag),
        colors = colors,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(notice.title, style = MaterialTheme.typography.titleSmall)
            Text(notice.message, style = MaterialTheme.typography.bodyMedium)
            notice.action?.let { action ->
                Button(onClick = { onAction(action) }) {
                    Text(notice.actionLabel)
                }
            }
        }
    }
}

private enum class StatusSeverity { INFO, WARNING, ERROR }

@Immutable
private data class StatusNotice(
    val tag: String,
    val title: String,
    val message: String,
    val severity: StatusSeverity,
    val action: SessionStatusAction? = null,
    val actionLabel: String = "",
) {
    val accessibilityText: String get() = "$title. $message"
}

private fun connectionNotice(state: SessionStatusState): StatusNotice? = when (val connection = state.connection) {
    is ConnectionState.Connecting -> StatusNotice(
        tag = "connection_banner",
        title = if (connection.attempt == 1) "Connecting" else "Reconnecting",
        message = if (connection.attempt == 1) {
            "Connecting to your Mac."
        } else {
            "Reconnecting to your Mac, attempt ${connection.attempt}."
        },
        severity = StatusSeverity.INFO,
    )
    is ConnectionState.Disconnected -> StatusNotice(
        tag = "connection_banner",
        title = "Offline",
        message = offlineMessage(connection.reason, state.lastSeenLabel),
        severity = StatusSeverity.WARNING,
        action = SessionStatusAction.RETRY,
        actionLabel = "Retry",
    )
    else -> null
}

private fun canonicalNotice(sessions: List<SessionStatusSession>): StatusNotice? {
    val waiting = sessions.count { it.runState == SessionRunState.WAITING_FOR_CANONICAL }
    if (waiting == 0) return null
    val noun = if (waiting == 1) "session is" else "sessions are"
    return StatusNotice(
        tag = "canonical_sync_banner",
        title = "Syncing canonical history",
        message = "$waiting of ${sessions.size} $noun waiting for canonical history.",
        severity = StatusSeverity.INFO,
    )
}

private fun authenticationNotice(state: SessionStatusState): StatusNotice? = when (val connection = state.connection) {
    is ConnectionState.DeviceAuthenticated -> if (!state.passkeyProviderPresent) {
        StatusNotice(
            tag = "authentication_banner",
            title = "No passkey provider",
            message = "Set up a compatible passkey provider. Session content remains locked.",
            severity = StatusSeverity.ERROR,
            action = SessionStatusAction.UNLOCK,
            actionLabel = "Set up passkey",
        )
    } else {
        StatusNotice(
            tag = "authentication_banner",
            title = "Device authenticated",
            message = "This device is authenticated, but sessions are read-only until you unlock with a passkey.",
            severity = StatusSeverity.WARNING,
            action = SessionStatusAction.UNLOCK,
            actionLabel = "Unlock",
        )
    }
    is ConnectionState.Ready -> passkeyExpiryNotice(connection.userAuthentication, state.nowEpochMillis)
    is ConnectionState.Revoked -> revokedNotice()
    else -> if (state.trust is TrustState.Revoked) revokedNotice() else null
}

private fun passkeyExpiryNotice(authentication: PasskeyAuthentication, nowEpochMillis: Long): StatusNotice? {
    val remainingMillis = authentication.expiresAtEpochMillis - nowEpochMillis
    if (remainingMillis <= 0) {
        return StatusNotice(
            tag = "authentication_banner",
            title = "Passkey expired",
            message = "Unlock with a passkey to continue.",
            severity = StatusSeverity.ERROR,
            action = SessionStatusAction.UNLOCK,
            actionLabel = "Unlock",
        )
    }
    val remainingMinutes = max(1, (remainingMillis + 59_999) / 60_000)
    return StatusNotice(
        tag = "authentication_banner",
        title = "Passkey expires soon",
        message = "Passkey access expires in $remainingMinutes ${if (remainingMinutes == 1L) "minute" else "minutes"}.",
        severity = StatusSeverity.INFO,
        action = SessionStatusAction.UNLOCK,
        actionLabel = "Refresh passkey",
    )
}

private fun revokedNotice() = StatusNotice(
    tag = "authentication_banner",
    title = "Certificate revoked",
    message = "This device certificate was revoked. Session access is blocked; pair again to continue.",
    severity = StatusSeverity.ERROR,
    action = SessionStatusAction.PAIR,
    actionLabel = "Pair again",
)

private fun provisionalNotice(state: SessionStatusState): StatusNotice? {
    if (state.connection !is ConnectionState.PairingProvisional) return null
    val fingerprint = state.provisionalCertificateFingerprint ?: "Fingerprint unavailable"
    return StatusNotice(
        tag = "provisional_pairing_banner",
        title = "Verify this Mac before trusting it",
        message = "Compare certificate fingerprint $fingerprint on both devices. Trust on first use only after you verify the match.",
        severity = StatusSeverity.WARNING,
        action = SessionStatusAction.PAIR,
        actionLabel = "Verify and pair",
    )
}

private fun errorNotice(error: SessionStatusError?): StatusNotice? = error?.let {
    val (title, message) = when (it) {
        SessionStatusError.NETWORK -> "Connection problem" to "Check your network connection and try again."
        SessionStatusError.TIMEOUT -> "Connection timed out" to "Your Mac did not respond in time. Try again."
        SessionStatusError.PROTOCOL -> "Connection needs attention" to "The connection response was invalid. Try again."
        SessionStatusError.AUTHENTICATION -> "Authentication failed" to "Your credentials could not be verified. Unlock or pair again."
        SessionStatusError.UNKNOWN -> "Something went wrong" to "The operation could not be completed. Try again."
    }
    StatusNotice("error_banner", title, message, StatusSeverity.ERROR, SessionStatusAction.RETRY, "Retry")
}

private fun inferredLock(state: SessionStatusState): SessionLockState? = when {
    state.connection is ConnectionState.DeviceAuthenticated && !state.passkeyProviderPresent -> SessionLockState.ProviderAbsent
    state.connection is ConnectionState.DeviceAuthenticated -> SessionLockState.DeviceLocked
    state.connection is ConnectionState.Ready &&
        state.connection.userAuthentication.expiresAtEpochMillis <= state.nowEpochMillis -> SessionLockState.LockedByTimeout
    else -> null
}

private fun lockNotice(lock: SessionLockState): StatusNotice = when (lock) {
    SessionLockState.LockedByTimeout -> StatusNotice(
        "lock_timeout", "Session locked", "Your passkey access timed out. Unlock to view session content.", StatusSeverity.WARNING, SessionStatusAction.UNLOCK, "Unlock",
    )
    SessionLockState.DeviceLocked -> StatusNotice(
        "lock_device", "Device authentication required", "Unlock with a passkey to access session content on this device.", StatusSeverity.WARNING, SessionStatusAction.UNLOCK, "Unlock",
    )
    SessionLockState.ProviderAbsent -> StatusNotice(
        "lock_provider", "No passkey provider", "Set up a compatible passkey provider, then unlock to access sessions.", StatusSeverity.ERROR, SessionStatusAction.UNLOCK, "Set up passkey",
    )
}

internal fun offlineMessage(reason: DisconnectReason, lastSeenLabel: String?): String {
    val reasonText = when (reason) {
        DisconnectReason.NEVER_CONNECTED -> "Your Mac has not connected yet."
        DisconnectReason.PROCESS_DEATH -> "The connection was interrupted."
        DisconnectReason.NETWORK_LOST -> "Network connection was lost."
        DisconnectReason.HOST_UNAVAILABLE -> "Your Mac is unavailable."
        DisconnectReason.AUTH_REQUIRED -> "Authentication is required."
        DisconnectReason.TRUST_REQUIRED -> "Trusted pairing is required."
    }
    return if (lastSeenLabel == null) reasonText else "$reasonText Last seen $lastSeenLabel."
}
