package io.github.verybigsad.pimobile.session

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.CanonicalResetReason
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.ConversationState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.DraftState
import io.github.verybigsad.pimobile.model.EventCursor
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MessageContent
import io.github.verybigsad.pimobile.model.MessageContentKind
import io.github.verybigsad.pimobile.model.MessageId
import io.github.verybigsad.pimobile.model.MessageRole
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.ProvisionalMessage
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionMetadata
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.StreamEpoch
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Test

class SessionUiStateTest {
    private val now = 10_000L
    private val macId = MacId("mac-test")
    private val sessionId = SessionId("session-test")
    private val trust = TrustState.Trusted(macId, "Test Mac", "cert", 90_000L)

    @Test
    fun deviceAuthenticatedWithoutProviderStaysLocked() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.DeviceAuthenticated(
                path = TransportPath.RELAY,
                macId = macId,
                deviceAuthentication = MutualTlsAuthentication("cert", now),
            ),
            passkeyProvider = PasskeyProviderAvailability.Unavailable("Enable a provider."),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
        access as SessionContentAccess.Locked
        assertThat(access.title).isEqualTo("No passkey provider")
        assertThat(access.action).isNull()
        assertThat(access.code).isEqualTo("PASSKEY_PROVIDER_UNAVAILABLE")
    }

    @Test
    fun candidateProviderDelegatesResolutionAndKeepsSessionLocked() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.DeviceAuthenticated(
                path = TransportPath.RELAY,
                macId = macId,
                deviceAuthentication = MutualTlsAuthentication("cert", now),
            ),
            passkeyProvider = PasskeyProviderAvailability.Candidate("Android Credential Manager will resolve it."),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isEqualTo(
            SessionContentAccess.Locked(
                title = "Unlock with passkey",
                explanation = "The Mac recognized this device, but user authentication is still required. Android Credential Manager will resolve it. If Android reports that no provider is configured, enable one in system settings. Session data stays locked.",
                action = LockAction.AUTHENTICATE,
                code = "PASSKEY_PROVIDER_CANDIDATE",
            ),
        )
    }

    @Test
    fun provisionalPairingNeverExposesSessionData() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.PairingProvisional(TransportPath.DIRECT, "invitation"),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = PasskeyAuthentication("retained", now - 1, now + 1_000),
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
        access as SessionContentAccess.Locked
        assertThat(access.title).isEqualTo("Pairing is provisional")
        assertThat(access.explanation).contains("cannot carry session data")
    }

    @Test
    fun mismatchedAuthenticatedMacIdentityFailsClosed() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.Ready(
                path = TransportPath.DIRECT,
                macId = MacId("different-mac"),
                userAuthentication = PasskeyAuthentication("assertion", now - 1, now + 1_000),
                deviceAuthentication = MutualTlsAuthentication("cert", now - 1),
            ),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
        access as SessionContentAccess.Locked
        assertThat(access.code).isEqualTo("MAC_IDENTITY_MISMATCH")
    }

    @Test
    fun mismatchedDeviceCertificateFailsClosed() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.Ready(
                path = TransportPath.DIRECT,
                macId = macId,
                userAuthentication = PasskeyAuthentication("assertion", now - 1, now + 1_000),
                deviceAuthentication = MutualTlsAuthentication("different-cert", now - 1),
            ),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
        access as SessionContentAccess.Locked
        assertThat(access.code).isEqualTo("DEVICE_CERTIFICATE_MISMATCH")
    }

    @Test
    fun expiredDeviceCertificateFailsClosedBeforeReadyState() {
        val access = resolveSessionContentAccess(
            trust = trust.copy(certificateNotAfterEpochMillis = now),
            connection = ready(expiresAt = now + 1_000),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
        access as SessionContentAccess.Locked
        assertThat(access.code).isEqualTo("DEVICE_CERTIFICATE_EXPIRED")
    }

    @Test
    fun expiredReadyAuthenticationDoesNotExposeContent() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ready(expiresAt = now),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = null,
            nowEpochMillis = now,
        )

        assertThat(access).isEqualTo(
            SessionContentAccess.Locked(
                title = "Unlock with passkey",
                explanation = "Your passkey session expired. Continue with Provider. There is no password or biometric-only fallback.",
                action = LockAction.AUTHENTICATE,
            ),
        )
    }

    @Test
    fun currentRetainedAuthenticationAllowsExplicitOfflineCache() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.Disconnected(DisconnectReason.NETWORK_LOST),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = PasskeyAuthentication("retained", now - 1, now + 1),
            nowEpochMillis = now,
            lastSyncedLabel = "Synced 3 minutes ago",
        )

        assertThat(access).isEqualTo(
            SessionContentAccess.Offline(
                reason = DisconnectReason.NETWORK_LOST,
                lastSyncedLabel = "Synced 3 minutes ago",
            ),
        )
    }

    @Test
    fun authRequiredNeverUsesRetainedAuthenticationForOfflineAccess() {
        val access = resolveSessionContentAccess(
            trust = trust,
            connection = ConnectionState.Disconnected(DisconnectReason.AUTH_REQUIRED),
            passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
            retainedAuthentication = PasskeyAuthentication("retained", now - 1, now + 1),
            nowEpochMillis = now,
        )

        assertThat(access).isInstanceOf(SessionContentAccess.Locked::class.java)
    }

    @Test
    fun terminalModeFailsClosedWithoutLiveReadyAuthentication() {
        val online = detailState(ready(now + 1_000))
        val deviceAuthenticated = detailState(
            ConnectionState.DeviceAuthenticated(
                path = TransportPath.DIRECT,
                macId = macId,
                deviceAuthentication = MutualTlsAuthentication("cert", now - 1),
            ),
        )
        val offline = detailState(
            connection = ConnectionState.Disconnected(DisconnectReason.NETWORK_LOST),
            retainedAuthentication = PasskeyAuthentication("retained", now - 1, now + 1_000),
        )

        assertThat(online.terminalModeAvailability).isEqualTo(
            TerminalModeAvailability.Available(TransportPath.DIRECT),
        )
        assertThat(deviceAuthenticated.terminalModeAvailability).isEqualTo(
            TerminalModeAvailability.Unavailable(
                TerminalModeUnavailableReason.AUTHENTICATED_CONNECTION_REQUIRED,
            ),
        )
        assertThat(offline.terminalModeAvailability).isEqualTo(
            TerminalModeAvailability.Unavailable(TerminalModeUnavailableReason.OFFLINE_CACHE),
        )
    }

    @Test
    fun unavailableCanonicalStateHidesFinalAndProvisionalRows() {
        val conversation = conversation().copy(
            availability = CanonicalAvailability.Unavailable(
                reason = CanonicalResetReason.SEQUENCE_GAP,
                lastCommittedCursor = EventCursor(StreamEpoch("epoch"), 2, null),
                observedCursor = EventCursor(StreamEpoch("epoch"), 5, null),
            ),
            provisionalMessages = persistentMapOf(),
            runState = SessionRunState.WAITING_FOR_CANONICAL,
        )
        val entries = buildTimelineEntries(sessionState(conversation))

        assertThat(entries).hasSize(1)
        assertThat(entries.single()).isInstanceOf(TimelineEntry.CanonicalUnavailable::class.java)
    }

    @Test
    fun currentTimelineKeepsFinalOrderAndAppendsProvisionalByStartTime() {
        val entries = buildTimelineEntries(sessionState(conversation()))

        assertThat(entries.map(TimelineEntry::stableKey)).containsExactly(
            "message:final-message",
            "message:provisional-early",
            "message:provisional-late",
        ).inOrder()
    }

    @Test
    fun lifecycleCopyNeverCallsRetryOrCompactionComplete() {
        assertThat(SessionRunState.RETRYING.displayLabel()).contains("still working")
        assertThat(SessionRunState.RETRYING.supportingCopy()).contains("no completion")
        assertThat(SessionRunState.COMPACTING.displayLabel()).contains("still working")
        assertThat(SessionRunState.SETTLED.supportingCopy()).contains("durable agent settlement")
    }

    @Test
    fun sanitizerRemovesAnsiAndUnsafeControlsButKeepsTextLayout() {
        val value = "ok\u001B[31mred\u001B[0m\nnext\u0000\tcolumn"

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("okred\nnext\tcolumn")
    }

    @Test
    fun approvalDecisionBindingCarriesExactOfferOperationAndHash() {
        val offer = ApprovalOfferUiState(
            offerId = "offer-id",
            operationId = "operation-id",
            operationName = "bash",
            normalizedArguments = "rm -rf -- build",
            targetLabel = "Working directory",
            targetValue = "/worktree",
            reasons = listOf("Recursive deletion"),
            policyVersion = "policy-v1",
            argumentHash = "exact-hash",
            expiresAtLabel = "12:00:00",
            remainingSeconds = 120,
        )

        assertThat(offer.binding).isEqualTo(ApprovalBinding("offer-id", "operation-id", "exact-hash"))
    }

    private fun detailState(
        connection: ConnectionState,
        retainedAuthentication: PasskeyAuthentication? = null,
    ): SessionDetailUiState = SessionDetailUiState(
        session = sessionState(conversation()).copy(connection = connection),
        passkeyProvider = PasskeyProviderAvailability.Available("Provider"),
        retainedAuthentication = retainedAuthentication,
        nowEpochMillis = now,
        macDisplayName = "Test Mac",
        modelName = null,
        thinkingLevel = null,
        elapsedLabel = null,
        lastSyncedLabel = null,
    )

    private fun ready(expiresAt: Long): ConnectionState.Ready = ConnectionState.Ready(
        path = TransportPath.DIRECT,
        macId = macId,
        userAuthentication = PasskeyAuthentication("assertion", now - 1, expiresAt),
        deviceAuthentication = MutualTlsAuthentication("cert", now - 1),
    )

    private fun metadata(): SessionMetadata = SessionMetadata(
        id = sessionId,
        macId = macId,
        displayName = "Test session",
        repositoryPath = "/repository",
        worktreePath = "/worktree",
        parentSessionId = null,
        updatedAtEpochMillis = now,
    )

    private fun sessionState(conversation: ConversationState): SessionState = SessionState(
        metadata = metadata(),
        conversation = conversation,
        draft = DraftState.empty(sessionId),
        trust = trust,
        connection = ready(now + 1_000),
    )

    private fun conversation(): ConversationState = ConversationState(
        sessionId = sessionId,
        finalizedMessages = persistentListOf(
            FinalizedMessage(
                id = MessageId("final-message"),
                role = MessageRole.USER,
                content = persistentListOf(MessageContent("text", MessageContentKind.TEXT, 1, "Final")),
                appendOrdinal = 1,
                createdAtEpochMillis = 1,
                finalizedAtEpochMillis = 2,
            ),
        ),
        provisionalMessages = persistentMapOf(
            MessageId("provisional-late") to ProvisionalMessage(
                id = MessageId("provisional-late"),
                role = MessageRole.ASSISTANT,
                content = persistentListOf(MessageContent("late", MessageContentKind.TEXT, 2, "Late")),
                revision = 2,
                startedAtEpochMillis = 20,
            ),
            MessageId("provisional-early") to ProvisionalMessage(
                id = MessageId("provisional-early"),
                role = MessageRole.ASSISTANT,
                content = persistentListOf(MessageContent("early", MessageContentKind.TEXT, 1, "Early")),
                revision = 1,
                startedAtEpochMillis = 10,
            ),
        ),
        cursor = EventCursor(StreamEpoch("epoch"), 2, null),
        availability = CanonicalAvailability.Current,
        runState = SessionRunState.STREAMING,
        lastAppendId = null,
        lastSettlementId = null,
        hasOlderMessages = false,
    )
}
