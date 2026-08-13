package io.github.verybigsad.pimobile.session

import androidx.compose.runtime.Immutable
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.ProvisionalMessage
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState

@Immutable
sealed interface PasskeyProviderAvailability {
    data object Checking : PasskeyProviderAvailability

    data class Available(val providerName: String) : PasskeyProviderAvailability {
        init {
            require(providerName.isNotBlank())
        }
    }

    data class Candidate(val guidance: String) : PasskeyProviderAvailability {
        init {
            require(guidance.isNotBlank())
        }
    }

    data class Unavailable(val guidance: String) : PasskeyProviderAvailability {
        init {
            require(guidance.isNotBlank())
        }
    }

    data class Failed(val code: String, val guidance: String) : PasskeyProviderAvailability {
        init {
            require(code.isNotBlank())
            require(guidance.isNotBlank())
        }
    }
}

enum class SessionBucket(val label: String) {
    NEEDS_YOU("Needs you"),
    INDETERMINATE("Indeterminate"),
    WORKING("Working"),
    READY_TO_REVIEW("Ready to review"),
    DONE("Done"),
}

@Immutable
data class SessionListItemUiState(
    val metadata: SessionMetadata,
    val runState: SessionRunState,
    val bucket: SessionBucket,
    val latestActivity: String,
    val lastActiveLabel: String,
    val blockerCount: Int = 0,
    val treeDepth: Int = 0,
    val parentSessionLabel: String? = null,
) {
    init {
        require(latestActivity.isNotBlank())
        require(lastActiveLabel.isNotBlank())
        require(blockerCount >= 0)
        require(treeDepth in 0..8)
        require(parentSessionLabel == null || parentSessionLabel.isNotBlank())
    }
}

@Immutable
data class SessionListUiState(
    val trust: TrustState,
    val connection: ConnectionState,
    val passkeyProvider: PasskeyProviderAvailability,
    val retainedAuthentication: PasskeyAuthentication?,
    val nowEpochMillis: Long,
    val sessions: List<SessionListItemUiState>,
    val lastSyncedLabel: String?,
    val isRefreshing: Boolean = false,
) {
    init {
        require(nowEpochMillis >= 0)
    }

    val access: SessionContentAccess
        get() = resolveSessionContentAccess(
            trust = trust,
            connection = connection,
            passkeyProvider = passkeyProvider,
            retainedAuthentication = retainedAuthentication,
            nowEpochMillis = nowEpochMillis,
            lastSyncedLabel = lastSyncedLabel,
        )
}

@Immutable
sealed interface VoicePermissionUiState {
    data object Denied : VoicePermissionUiState

    data object PermanentlyDenied : VoicePermissionUiState
}

enum class VoiceCaptureUiPhase {
    IDLE,
    REQUESTING_PERMISSION,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    STARTING,
    CAPTURING,
    PROCESSING,
    CANCELING,
    CANCELED,
    FAILED,
    CLOSED,
}

@Immutable
data class VoiceCaptureErrorUiState(
    val title: String,
    val detail: String? = null,
    val retryAfterMilliseconds: Long? = null,
    val resetAtEpochMilliseconds: Long? = null,
) {
    init {
        require(title.isNotBlank())
        require(detail == null || detail.isNotBlank())
        require(retryAfterMilliseconds == null || retryAfterMilliseconds >= 0)
        require(resetAtEpochMilliseconds == null || resetAtEpochMilliseconds >= 0)
    }
}

@Immutable
data class VoiceCaptureUiState(
    val targetSessionId: SessionId,
    val phase: VoiceCaptureUiPhase,
    val queueDepth: Int = 0,
    val queuedAudioMilliseconds: Int = 0,
    val error: VoiceCaptureErrorUiState? = null,
    val finalTranscriptReady: Boolean = false,
    val canOpenPermissionSettings: Boolean = false,
) {
    init {
        require(queueDepth >= 0)
        require(queuedAudioMilliseconds >= 0)
    }
}

sealed interface CommandNoticeUiState {
    data object Sending : CommandNoticeUiState

    data object AwaitingHost : CommandNoticeUiState

    data object Acknowledged : CommandNoticeUiState

    data class Failed(val code: String, val retryable: Boolean) : CommandNoticeUiState {
        init {
            require(code.isNotBlank())
        }
    }
}

@Immutable
data class SessionDetailUiState(
    val session: SessionState,
    val passkeyProvider: PasskeyProviderAvailability,
    val retainedAuthentication: PasskeyAuthentication?,
    val nowEpochMillis: Long,
    val macDisplayName: String,
    val modelName: String?,
    val thinkingLevel: String?,
    val elapsedLabel: String?,
    val lastSyncedLabel: String?,
    val expandedContentIds: Set<String> = emptySet(),
    val approvalOffer: ApprovalOfferUiState? = null,
    val approvalNotice: ApprovalNoticeUiState? = null,
    val commandNotice: CommandNoticeUiState? = null,
    val voicePermission: VoicePermissionUiState? = null,
    val voice: VoiceCaptureUiState? = null,
) {
    init {
        require(nowEpochMillis >= 0)
        require(macDisplayName.isNotBlank())
    }

    val access: SessionContentAccess
        get() = resolveSessionContentAccess(
            trust = session.trust,
            connection = session.connection,
            passkeyProvider = passkeyProvider,
            retainedAuthentication = retainedAuthentication,
            nowEpochMillis = nowEpochMillis,
            lastSyncedLabel = lastSyncedLabel,
        )

    val canMutate: Boolean
        get() = access is SessionContentAccess.Online &&
            session.conversation.availability is CanonicalAvailability.Current

    val terminalModeAvailability: TerminalModeAvailability
        get() = when (val currentAccess = access) {
            is SessionContentAccess.Online -> TerminalModeAvailability.Available(currentAccess.path)
            is SessionContentAccess.Offline -> TerminalModeAvailability.Unavailable(
                TerminalModeUnavailableReason.OFFLINE_CACHE,
            )
            is SessionContentAccess.Locked -> TerminalModeAvailability.Unavailable(
                TerminalModeUnavailableReason.AUTHENTICATED_CONNECTION_REQUIRED,
            )
        }
}

@Immutable
sealed interface TerminalModeAvailability {
    data class Available(val path: TransportPath) : TerminalModeAvailability

    data class Unavailable(val reason: TerminalModeUnavailableReason) : TerminalModeAvailability
}

enum class TerminalModeUnavailableReason {
    OFFLINE_CACHE,
    AUTHENTICATED_CONNECTION_REQUIRED,
}

@Immutable
sealed interface SessionContentAccess {
    data class Online(val path: TransportPath) : SessionContentAccess

    data class Offline(
        val reason: DisconnectReason,
        val lastSyncedLabel: String?,
    ) : SessionContentAccess

    data class Locked(
        val title: String,
        val explanation: String,
        val action: LockAction?,
        val code: String? = null,
    ) : SessionContentAccess
}

enum class LockAction {
    PAIR_MAC,
    AUTHENTICATE,
    RETRY_CONNECTION,
}

@Immutable
data class ApprovalOfferUiState(
    val offerId: String,
    val operationId: String,
    val operationName: String,
    val normalizedArguments: String,
    val targetLabel: String,
    val targetValue: String,
    val reasons: List<String>,
    val policyVersion: String,
    val argumentHash: String,
    val expiresAtLabel: String,
    val remainingSeconds: Int,
    val pendingDecision: ApprovalDecision? = null,
) {
    init {
        require(offerId.isNotBlank())
        require(operationId.isNotBlank())
        require(operationName.isNotBlank())
        require(normalizedArguments.isNotBlank())
        require(targetLabel.isNotBlank())
        require(targetValue.isNotBlank())
        require(reasons.isNotEmpty())
        require(reasons.none(String::isBlank))
        require(policyVersion.isNotBlank())
        require(argumentHash.isNotBlank())
        require(expiresAtLabel.isNotBlank())
        require(remainingSeconds >= 0)
    }

    val binding: ApprovalBinding
        get() = ApprovalBinding(offerId, operationId, argumentHash)
}

@Immutable
data class ApprovalBinding(
    val offerId: String,
    val operationId: String,
    val argumentHash: String,
)

enum class ApprovalDecision {
    DENY,
    ALLOW_ONCE,
}

@Immutable
sealed interface ApprovalNoticeUiState {
    data class Expired(val operationName: String) : ApprovalNoticeUiState

    data class Blocked(
        val operationName: String,
        val code: String,
        val canRetry: Boolean,
    ) : ApprovalNoticeUiState
}

sealed interface SessionListEvent {
    data class OpenSession(val sessionId: SessionId) : SessionListEvent
    data class OpenSessionActions(val sessionId: SessionId) : SessionListEvent
    data object PairMac : SessionListEvent
    data object Authenticate : SessionListEvent
    data object RetryConnection : SessionListEvent
    data object Refresh : SessionListEvent
}

sealed interface SessionDetailEvent {
    data object NavigateBack : SessionDetailEvent
    data object PairMac : SessionDetailEvent
    data object Authenticate : SessionDetailEvent
    data object RetryConnection : SessionDetailEvent
    data object Stop : SessionDetailEvent
    data object Send : SessionDetailEvent
    data object SteerNow : SessionDetailEvent
    data object QueueFollowUp : SessionDetailEvent
    data object Attach : SessionDetailEvent
    data object StartVoice : SessionDetailEvent
    data object StopVoice : SessionDetailEvent
    data object CancelVoice : SessionDetailEvent
    data object OpenVoicePermissionSettings : SessionDetailEvent
    data object InsertTranscription : SessionDetailEvent
    data object DiscardTranscription : SessionDetailEvent
    data object LoadOlder : SessionDetailEvent
    data object RetryApprovalService : SessionDetailEvent
    data object DismissApprovalNotice : SessionDetailEvent
    data class UpdateTypedText(val text: String) : SessionDetailEvent
    data class UpdateTranscription(val text: String) : SessionDetailEvent
    data class UseQuickReply(val text: String) : SessionDetailEvent
    data class ToggleContent(val contentId: String) : SessionDetailEvent
    data class InspectRaw(val messageId: MessageId, val contentStableId: String?) : SessionDetailEvent
    data class DecideApproval(
        val binding: ApprovalBinding,
        val decision: ApprovalDecision,
    ) : SessionDetailEvent
}

@Immutable
sealed interface TimelineEntry {
    val stableKey: String
    val contentType: String

    data class Finalized(val message: FinalizedMessage) : TimelineEntry {
        override val stableKey: String = "message:${message.id.value}"
        override val contentType: String = "message"
    }

    data class Provisional(val message: ProvisionalMessage) : TimelineEntry {
        override val stableKey: String = "message:${message.id.value}"
        override val contentType: String = "message"
    }

    data class CanonicalUnavailable(val explanation: String) : TimelineEntry {
        override val stableKey: String = "canonical_unavailable"
        override val contentType: String = "canonical_unavailable"
    }
}

fun buildTimelineEntries(state: SessionState): List<TimelineEntry> {
    val conversation = state.conversation
    if (conversation.availability is CanonicalAvailability.Unavailable) {
        return listOf(
            TimelineEntry.CanonicalUnavailable(
                "Canonical data is unavailable. Live provisional content is hidden until an idle snapshot commits.",
            ),
        )
    }
    return buildList {
        addAll(conversation.finalizedMessages.map(TimelineEntry::Finalized))
        addAll(
            conversation.provisionalMessages.values
                .sortedWith(compareBy(ProvisionalMessage::startedAtEpochMillis, { it.id.value }))
                .map(TimelineEntry::Provisional),
        )
    }
}

fun SessionRunState.displayLabel(): String = when (this) {
    SessionRunState.IDLE -> "Idle"
    SessionRunState.STREAMING -> "Streaming"
    SessionRunState.RETRYING -> "Retrying · still working"
    SessionRunState.COMPACTING -> "Compacting · still working"
    SessionRunState.WAITING_FOR_INPUT -> "Waiting for input"
    SessionRunState.SETTLED -> "Settled"
    SessionRunState.WAITING_FOR_CANONICAL -> "Waiting for canonical state"
    SessionRunState.FAULTED -> "Faulted"
}

fun SessionRunState.isAgentRunning(): Boolean = when (this) {
    SessionRunState.STREAMING,
    SessionRunState.RETRYING,
    SessionRunState.COMPACTING,
    -> true

    else -> false
}

fun SessionRunState.supportingCopy(): String = when (this) {
    SessionRunState.IDLE -> "Ready for a message."
    SessionRunState.STREAMING -> "A provisional response is arriving. It is not final yet."
    SessionRunState.RETRYING -> "The attempt is retrying. Pi is still working; no completion is claimed."
    SessionRunState.COMPACTING -> "Pi is compacting context and remains at work."
    SessionRunState.WAITING_FOR_INPUT -> "Pi is paused until you answer."
    SessionRunState.SETTLED -> "The host reported a durable agent settlement."
    SessionRunState.WAITING_FOR_CANONICAL -> "Partial live data is hidden until canonical state is available."
    SessionRunState.FAULTED -> "The session faulted. Inspect details before retrying."
}

internal fun resolveSessionContentAccess(
    trust: TrustState,
    connection: ConnectionState,
    passkeyProvider: PasskeyProviderAvailability,
    retainedAuthentication: PasskeyAuthentication?,
    nowEpochMillis: Long,
    lastSyncedLabel: String? = null,
): SessionContentAccess {
    if (trust is TrustState.Unpaired) {
        return SessionContentAccess.Locked(
            title = "Pair a Mac",
            explanation = "Session data stays locked until pairing issues a device certificate.",
            action = LockAction.PAIR_MAC,
        )
    }
    if (trust is TrustState.Revoked || connection is ConnectionState.Revoked) {
        val code = (trust as? TrustState.Revoked)?.reasonCode ?: "DEVICE_REVOKED"
        return SessionContentAccess.Locked(
            title = "Device access revoked",
            explanation = "This device can no longer access Mac sessions. Re-pair only after reviewing the revocation on the Mac.",
            action = null,
            code = code,
        )
    }
    trust as TrustState.Trusted
    if (trust.certificateNotAfterEpochMillis <= nowEpochMillis) {
        return SessionContentAccess.Locked(
            title = "Device certificate expired",
            explanation = "The paired device certificate is no longer valid. Re-pair before accessing session data.",
            action = LockAction.PAIR_MAC,
            code = "DEVICE_CERTIFICATE_EXPIRED",
        )
    }
    val authenticatedMacId = when (connection) {
        is ConnectionState.Ready -> connection.macId
        is ConnectionState.DeviceAuthenticated -> connection.macId
        else -> null
    }
    if (authenticatedMacId != null && authenticatedMacId != trust.macId) {
        return SessionContentAccess.Locked(
            title = "Mac identity changed",
            explanation = "The authenticated Mac does not match the paired identity. Session data remains locked.",
            action = LockAction.PAIR_MAC,
            code = "MAC_IDENTITY_MISMATCH",
        )
    }
    val deviceAuthentication = when (connection) {
        is ConnectionState.Ready -> connection.deviceAuthentication
        is ConnectionState.DeviceAuthenticated -> connection.deviceAuthentication
        else -> null
    }
    if (deviceAuthentication != null &&
        (deviceAuthentication.certificateSerial != trust.certificateSerial ||
            deviceAuthentication.verifiedAtEpochMillis > nowEpochMillis)
    ) {
        return SessionContentAccess.Locked(
            title = "Device certificate mismatch",
            explanation = "The authenticated device certificate does not match current paired trust. Session data remains locked.",
            action = LockAction.PAIR_MAC,
            code = "DEVICE_CERTIFICATE_MISMATCH",
        )
    }
    if (connection is ConnectionState.PairingProvisional) {
        return SessionContentAccess.Locked(
            title = "Pairing is provisional",
            explanation = "The pinned pairing channel cannot carry session data. Finish passkey and Mac confirmation, then reconnect with mTLS.",
            action = null,
        )
    }
    if (connection is ConnectionState.Ready) {
        return if (connection.userAuthentication.verifiedAtEpochMillis <= nowEpochMillis &&
            connection.userAuthentication.expiresAtEpochMillis > nowEpochMillis
        ) {            SessionContentAccess.Online(connection.path)
        } else {
            authenticationLock(passkeyProvider, "Your passkey session expired.")
        }
    }
    if (connection is ConnectionState.DeviceAuthenticated) {
        return authenticationLock(passkeyProvider, "The Mac recognized this device, but user authentication is still required.")
    }
    if (connection is ConnectionState.Connecting) {
        return SessionContentAccess.Locked(
            title = "Connecting to Mac",
            explanation = "Session data remains locked until the authenticated connection is ready.",
            action = null,
        )
    }
    if (connection is ConnectionState.Disconnected) {
        val canReadCache = retainedAuthentication?.let {
            it.verifiedAtEpochMillis <= nowEpochMillis && it.expiresAtEpochMillis > nowEpochMillis
        } == true && connection.reason !in setOf(DisconnectReason.AUTH_REQUIRED, DisconnectReason.TRUST_REQUIRED)
        if (canReadCache) {
            return SessionContentAccess.Offline(connection.reason, lastSyncedLabel)
        }
        return when (connection.reason) {
            DisconnectReason.TRUST_REQUIRED -> SessionContentAccess.Locked(
                title = "Trust required",
                explanation = "The current Mac identity is not trusted by this device.",
                action = LockAction.PAIR_MAC,
            )

            DisconnectReason.AUTH_REQUIRED -> authenticationLock(passkeyProvider, "Passkey authentication is required.")
            else -> SessionContentAccess.Locked(
                title = "Mac unavailable",
                explanation = "The Mac must be reachable for a fresh passkey assertion. A sleeping or offline Mac is a normal cause.",
                action = LockAction.RETRY_CONNECTION,
            )
        }
    }
    return SessionContentAccess.Locked(
        title = "Session locked",
        explanation = "The authenticated connection is not ready.",
        action = null,
    )
}

private fun authenticationLock(
    provider: PasskeyProviderAvailability,
    prefix: String,
): SessionContentAccess.Locked = when (provider) {
    PasskeyProviderAvailability.Checking -> SessionContentAccess.Locked(
        title = "Checking passkey provider",
        explanation = "$prefix Provider discovery is still in progress.",
        action = null,
    )

    is PasskeyProviderAvailability.Available -> SessionContentAccess.Locked(
        title = "Unlock with passkey",
        explanation = "$prefix Continue with ${provider.providerName}. There is no password or biometric-only fallback.",
        action = LockAction.AUTHENTICATE,
    )

    is PasskeyProviderAvailability.Candidate -> SessionContentAccess.Locked(
        title = "Unlock with passkey",
        explanation = "$prefix ${provider.guidance} If Android reports that no provider is configured, enable one in system settings. Session data stays locked.",
        action = LockAction.AUTHENTICATE,
        code = "PASSKEY_PROVIDER_CANDIDATE",
    )

    is PasskeyProviderAvailability.Unavailable -> SessionContentAccess.Locked(
        title = "No passkey provider",
        explanation = "$prefix ${provider.guidance} Session data stays locked.",
        action = null,
        code = "PASSKEY_PROVIDER_UNAVAILABLE",
    )

    is PasskeyProviderAvailability.Failed -> SessionContentAccess.Locked(
        title = "Passkey provider unavailable",
        explanation = "$prefix ${provider.guidance} Session data stays locked.",
        action = null,
        code = provider.code,
    )
}

