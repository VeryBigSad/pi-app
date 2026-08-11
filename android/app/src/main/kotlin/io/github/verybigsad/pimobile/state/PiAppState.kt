package io.github.verybigsad.pimobile.state

import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.SessionState
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.session.ApprovalOfferUiState
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

enum class AppPasskeyAvailability {
    CHECKING,
    AVAILABLE,
    UNAVAILABLE,
}

sealed interface PairingUiState {
    data object AwaitingScan : PairingUiState

    data object Connecting : PairingUiState

    data class PasskeyRequired(val registration: Boolean, val ceremonyId: String) : PairingUiState

    /** The short code is rendered from the Mac's pair.confirm messages; Android never confirms for the Mac. */
    data class AwaitingMacConfirmation(val shortCode: String?) : PairingUiState

    data object IssuingCertificate : PairingUiState

    data class Failed(val code: String) : PairingUiState
}

/**
 * Host-reported catalog entry for one session (session.catalog). Any null field renders as
 * hidden/unavailable in the UI; nothing falls back to placeholder or "unknown" strings.
 */
data class SessionCatalogEntry(
    val provider: String?,
    val modelName: String?,
    val thinkingLevel: String?,
)

enum class LockReason {
    BACKGROUND_TIMEOUT,
    DEVICE_LOCKED,
    AUTH_EXPIRED,
    HOST_LOCK,
    REVOKED,
}

data class PiAppState(
    val hydrated: Boolean = false,
    val trust: TrustState = TrustState.Unpaired,
    val connection: ConnectionState = ConnectionState.Disconnected(DisconnectReason.NEVER_CONNECTED),
    /** In-memory only passkey authentication; never persisted, never survives process death. */
    val authentication: PasskeyAuthentication? = null,
    val passkeyProvider: AppPasskeyAvailability = AppPasskeyAvailability.CHECKING,
    val sessions: PersistentMap<SessionId, SessionState> = persistentMapOf(),
    val pairing: PairingUiState? = null,
    /** A cache reset signal is outstanding; canonical content must be re-fetched before it is shown. */
    val resyncPending: Boolean = false,
    /** A canonical sync round is in flight on the current connection. */
    val syncing: Boolean = false,
    /** Opaque pending destination from a notification/deep link; consumed only when content is visible. */
    val pendingDeepLinkSessionId: SessionId? = null,
    val selectedSessionId: SessionId? = null,
    val terminalSessionId: SessionId? = null,
    /** Settings destination visible; state-driven like every other destination. */
    val settingsOpen: Boolean = false,
    /** Agents insight destination visible. */
    val agentsOpen: Boolean = false,
    /** Assisted-update sheet visible (deep link pimobile://update or settings entry). */
    val updateSheetOpen: Boolean = false,
    val approval: ApprovalOfferUiState? = null,
    /** Host-provided session catalog; null until session.catalog arrives, UI stays hidden. */
    val catalog: Map<SessionId, SessionCatalogEntry>? = null,
    val lastError: String? = null,
) {
    val locked: Boolean get() = authentication == null

    val showPairingFlow: Boolean
        get() = trust is TrustState.Unpaired || pairing != null
}
