package io.github.verybigsad.pimobile.session.status

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.PasskeyAuthentication
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.session.SessionTheme
import org.junit.Rule
import org.junit.Test

class SessionStatusSurfacesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun offlineAndCanonicalBannersShowRetryAndLastSeen() {
        compose.setContent {
            SessionTheme {
                SessionStatusSurfaces(
                    state = state(connection = ConnectionState.Disconnected(DisconnectReason.NETWORK_LOST)),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithTag("connection_banner").assertIsDisplayed()
        compose.onNodeWithText("Network connection was lost. Last seen 3 minutes ago.").assertIsDisplayed()
        compose.onNodeWithTag("canonical_sync_banner").assertIsDisplayed()
        compose.onNodeWithText("2 of 3 sessions are waiting for canonical history.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertHasClickAction()
    }

    @Test
    fun reconnectAndExpiryCountdownAreExplicit() {
        val current = mutableStateOf(
            state(connection = ConnectionState.Connecting(TransportPath.RELAY, 3)),
        )
        compose.setContent {
            SessionTheme {
                SessionStatusSurfaces(
                    state = current.value,
                    onAction = {},
                )
            }
        }
        compose.onNodeWithText("Reconnecting").assertIsDisplayed()
        compose.onNodeWithText("Reconnecting to your Mac, attempt 3.").assertIsDisplayed()

        compose.runOnIdle {
            current.value = state(
                connection = ConnectionState.Ready(
                    TransportPath.DIRECT,
                    MacId("mac-1"),
                    PasskeyAuthentication("assertion", 1_000, 61_000),
                    MutualTlsAuthentication("serial-1", 1_000),
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("Passkey expires soon").assertIsDisplayed()
        compose.onNodeWithText("Passkey access expires in 1 minute.").assertIsDisplayed()
        compose.onNodeWithText("Refresh passkey").assertHasClickAction()
    }

    @Test
    fun provisionalPairingShowsFingerprintAndTofuWarning() {
        compose.setContent {
            SessionTheme {
                SessionStatusSurfaces(
                    state = state(
                        connection = ConnectionState.PairingProvisional(TransportPath.DIRECT, "invite-1"),
                        fingerprint = "AB:CD:EF",
                    ),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithTag("provisional_pairing_banner").assertIsDisplayed()
        compose.onNodeWithText("Compare certificate fingerprint AB:CD:EF on both devices.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Verify and pair").assertHasClickAction()
    }

    @Test
    fun deviceOnlyAndRevokedAuthenticationAreActionable() {
        val current = mutableStateOf(
            state(
                connection = ConnectionState.DeviceAuthenticated(
                    TransportPath.RELAY,
                    MacId("mac-1"),
                    MutualTlsAuthentication("serial-1", 1_000),
                ),
                providerPresent = false,
            ),
        )
        compose.setContent {
            SessionTheme {
                SessionStatusSurfaces(
                    state = current.value,
                    onAction = {},
                )
            }
        }
        compose.onNodeWithText("No passkey provider").assertIsDisplayed()
        compose.onNodeWithText("Set up passkey").assertHasClickAction()

        compose.runOnIdle {
            current.value = state(connection = ConnectionState.Revoked(MacId("mac-1"), 1_000))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Certificate revoked").assertIsDisplayed()
        compose.onNodeWithText("Pair again").assertHasClickAction()
    }

    @Test
    fun typedErrorShowsGenericRetryWithoutExceptionText() {
        compose.setContent {
            SessionTheme {
                SessionStatusSurfaces(
                    state = state(
                        connection = ConnectionState.Connecting(TransportPath.DIRECT, 1),
                    ).copy(error = SessionStatusError.NETWORK),
                    onAction = {},
                )
            }
        }

        compose.onNodeWithTag("error_banner").assertIsDisplayed()
        compose.onNodeWithText("Check your network connection and try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertHasClickAction()
    }

    @Test
    fun timeoutLockOverlayBlocksContentWithUnlockCta() {
        compose.setContent {
            SessionTheme {
                Box(Modifier.fillMaxSize()) {
                    SessionLockOverlay(
                        state = state(
                            connection = ConnectionState.Ready(
                                TransportPath.DIRECT,
                                MacId("mac-1"),
                                PasskeyAuthentication("assertion", 1_000, 2_000),
                                MutualTlsAuthentication("serial-1", 1_000),
                            ),
                            now = 2_000,
                        ),
                        onUnlock = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("session_lock_overlay").assertIsDisplayed()
        compose.onNodeWithText("Session locked").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertHasClickAction()
    }

    private fun state(
        connection: ConnectionState,
        now: Long = 1_000,
        providerPresent: Boolean = true,
        fingerprint: String? = null,
    ) = SessionStatusState(
        trust = TrustState.Trusted(MacId("mac-1"), "Mac", "serial-1", 10_000),
        connection = connection,
        passkeyAuthentication = null,
        passkeyProviderPresent = providerPresent,
        nowEpochMillis = now,
        sessions = listOf(
            SessionStatusSession(SessionRunState.WAITING_FOR_CANONICAL),
            SessionStatusSession(SessionRunState.WAITING_FOR_CANONICAL),
            SessionStatusSession(SessionRunState.IDLE),
        ),
        lastSeenLabel = "3 minutes ago",
        provisionalCertificateFingerprint = fingerprint,
    )
}
