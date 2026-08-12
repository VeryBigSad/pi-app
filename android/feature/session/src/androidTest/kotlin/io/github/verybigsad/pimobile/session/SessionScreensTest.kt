package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.DisconnectReason
import io.github.verybigsad.pimobile.model.MutualTlsAuthentication
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.model.TrustState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@Suppress("DEPRECATION")
class SessionScreensTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun streamingComposerSeparatesSteeringQueueAndStop() {
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(previewDetailState(), onEvent = {})
            }
        }

        compose.onNodeWithText("Steer now").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithText("Queue follow-up").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithText("Stop").assertHasClickAction().assertIsEnabled()
        compose.onNodeWithText("Voice transcription draft").assertExists()
        compose.onNodeWithText("Nothing is auto-sent.", substring = true).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Approve", substring = false).fetchSemanticsNodes().size)
    }

    @Test
    fun terminalModeEntryIsExplicitAccessibleAndOpensOnLiveTrustedConnection() {
        val opens = mutableListOf<String>()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = previewDetailState(),
                    onEvent = {},
                    onOpenTerminal = { opens += "opened" },
                )
            }
        }

        compose.onNodeWithText("Terminal compatibility mode").assertIsDisplayed()
        compose.onNodeWithText("Terminal ready · direct").assertIsDisplayed()
        compose.onNodeWithText("Terminal input is ephemeral and never replayed", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Open terminal compatibility mode on MacBook Pro over direct trusted, passkey-authenticated connection",
        ).assertHasClickAction().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("opened"), opens)
    }

    @Test
    fun terminalModeFailsClosedForOfflineCachedSession() {
        val opens = mutableListOf<String>()
        val detail = previewDetailState()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = detail.copy(
                        session = detail.session.copy(
                            connection = ConnectionState.Disconnected(DisconnectReason.NETWORK_LOST),
                        ),
                    ),
                    onEvent = {},
                    onOpenTerminal = { opens += "opened" },
                )
            }
        }

        compose.onNodeWithText("Terminal unavailable").assertIsDisplayed()
        compose.onNodeWithText("Cached offline session data cannot open a terminal.", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Terminal compatibility mode unavailable while showing cached offline session data",
        ).assertIsNotEnabled()

        assertEquals(emptyList<String>(), opens)
    }

    @Test
    fun idleVoiceControlStartsDictation() {
        val events = mutableListOf<SessionDetailEvent>()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(previewDetailState(), onEvent = events::add)
            }
        }

        compose.onNodeWithContentDescription("Start voice dictation")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(SessionDetailEvent.StartVoice), events)
    }

    @Test
    fun deniedMicrophonePermissionOffersAnExplicitRetry() {
        val events = mutableListOf<SessionDetailEvent>()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = previewDetailState().copy(voicePermission = VoicePermissionUiState.Denied),
                    onEvent = events::add,
                )
            }
        }

        compose.onNodeWithText("Microphone access was denied.", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask Android for microphone access again")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(SessionDetailEvent.StartVoice), events)
    }

    @Test
    fun permanentlyDeniedMicrophonePermissionGuidesToSettings() {
        val events = mutableListOf<SessionDetailEvent>()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = previewDetailState().copy(voicePermission = VoicePermissionUiState.PermanentlyDenied),
                    onEvent = events::add,
                )
            }
        }

        compose.onNodeWithText("blocked in Android settings", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Open Android app settings to enable microphone access")
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(SessionDetailEvent.OpenVoicePermissionSettings), events)
    }

    @Test
    fun capturingVoiceShowsBacklogAndAccessibleStopCancelControls() {
        val events = mutableListOf<SessionDetailEvent>()
        val detail = previewDetailState()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = detail.copy(
                        voice = VoiceCaptureUiState(
                            targetSessionId = detail.session.metadata.id,
                            phase = VoiceCaptureUiPhase.CAPTURING,
                            queueDepth = 2,
                            queuedAudioMilliseconds = 1_200,
                        ),
                    ),
                    onEvent = events::add,
                )
            }
        }

        compose.onNodeWithText("Listening").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Audio backlog: 2 chunks; 1200 ms queued.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop voice dictation and request a final transcription")
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription("Cancel voice dictation and discard captured audio")
            .performScrollTo()
            .performClick()

        assertEquals(
            listOf(SessionDetailEvent.StopVoice, SessionDetailEvent.CancelVoice),
            events,
        )
    }

    @Test
    fun finalTranscriptIsEditableAndNeverSendsOnItsOwn() {
        val events = mutableListOf<SessionDetailEvent>()
        val detail = previewDetailState()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = detail.copy(
                        session = detail.session.copy(
                            draft = detail.session.draft.copy(
                                typedText = "Keep this manual message",
                                transcriptionText = "spoken final",
                            ),
                        ),
                        voice = VoiceCaptureUiState(
                            targetSessionId = detail.session.metadata.id,
                            phase = VoiceCaptureUiPhase.PROCESSING,
                            finalTranscriptReady = true,
                        ),
                    ),
                    onEvent = events::add,
                )
            }
        }

        compose.onNodeWithText("Final transcription ready").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Editable final transcription draft")
            .performScrollTo()
            .performTextReplacement("edited final")
        compose.onNodeWithContentDescription("Insert transcription into typed message draft without sending it")
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription("Typed message draft")
            .performScrollTo()
            .assertTextContains("Keep this manual message")

        assertEquals(1, events.count { it == SessionDetailEvent.UpdateTranscription("edited final") })
        assertEquals(1, events.count { it == SessionDetailEvent.InsertTranscription })
        assertEquals(0, events.count { it is SessionDetailEvent.UpdateTypedText || it is SessionDetailEvent.Send })
    }

    @Test
    fun voiceErrorSurfacesHostQuotaTelemetryWithoutAnInventedBalance() {
        val detail = previewDetailState()
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = detail.copy(
                        voice = VoiceCaptureUiState(
                            targetSessionId = detail.session.metadata.id,
                            phase = VoiceCaptureUiPhase.FAILED,
                            error = VoiceCaptureErrorUiState(
                                title = "Voice limit reached",
                                detail = "Daily voice budget",
                                retryAfterMilliseconds = 60_000,
                            ),
                        ),
                    ),
                    onEvent = {},
                )
            }
        }

        compose.onNodeWithText("Voice limit reached").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Daily voice budget").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry after 1 minute.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Balance", substring = true).assertCountEquals(0)
    }

    @Test
    fun passkeyProviderUnavailableKeepsSessionListLocked() {
        val detail = previewDetailState()
        val state = SessionListUiState(
            trust = detail.session.trust,
            connection = ConnectionState.DeviceAuthenticated(
                path = TransportPath.RELAY,
                macId = detail.session.metadata.macId,
                deviceAuthentication = MutualTlsAuthentication(
                    (detail.session.trust as TrustState.Trusted).certificateSerial,
                    detail.nowEpochMillis,
                ),
            ),
            passkeyProvider = PasskeyProviderAvailability.Unavailable("Enable a compatible provider."),
            retainedAuthentication = null,
            nowEpochMillis = detail.nowEpochMillis,
            sessions = listOf(
                SessionListItemUiState(
                    metadata = detail.session.metadata,
                    runState = detail.session.conversation.runState,
                    bucket = SessionBucket.WORKING,
                    latestActivity = "Sensitive cached activity",
                    lastActiveLabel = "Now",
                ),
            ),
            lastSyncedLabel = null,
        )
        compose.setContent {
            SessionTheme {
                SessionListScreen(state, onEvent = {})
            }
        }

        compose.onNodeWithText("No passkey provider").assertIsDisplayed()
        compose.onNodeWithText("Session data stays locked.", substring = true).assertExists()
        compose.onNodeWithText("Sensitive cached activity").assertDoesNotExist()
    }

    @Test
    fun approvalSheetShowsExactBindingAndOnlyDecisionLanguage() {
        val events = mutableListOf<SessionDetailEvent>()
        val offer = ApprovalOfferUiState(
            offerId = "offer-exact",
            operationId = "operation-exact",
            operationName = "bash",
            normalizedArguments = "rm -rf -- build/output",
            targetLabel = "Working directory",
            targetValue = "/Users/example/worktree",
            reasons = listOf("Recursive deletion", "Protected path"),
            policyVersion = "policy-v7",
            argumentHash = "hash-exact",
            expiresAtLabel = "12:30:00",
            remainingSeconds = 44,
        )
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = previewDetailState().copy(approvalOffer = offer),
                    onEvent = events::add,
                )
            }
        }

        compose.onNode(hasContentDescription("Pre-execution approval offer for bash"), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("rm -rf -- build/output").assertExists()
        compose.onNodeWithText("/Users/example/worktree").assertExists()
        compose.onNodeWithText("Recursive deletion", substring = true).assertExists()
        compose.onNodeWithText("44 seconds remaining", substring = true).assertExists()
        compose.onNodeWithContentDescription("Deny this exact operation")
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        assertEquals(
            SessionDetailEvent.DecideApproval(
                binding = ApprovalBinding("offer-exact", "operation-exact", "hash-exact"),
                decision = ApprovalDecision.DENY,
            ),
            events.single(),
        )
    }

    @Test
    fun expiredApprovalDisablesBothDecisionsAndShowsDenyDefault() {
        val expiredOffer = ApprovalOfferUiState(
            offerId = "expired-offer",
            operationId = "expired-operation",
            operationName = "bash",
            normalizedArguments = "rm -rf -- build/output",
            targetLabel = "Working directory",
            targetValue = "/Users/example/worktree",
            reasons = listOf("Recursive deletion"),
            policyVersion = "policy-v7",
            argumentHash = "expired-hash",
            expiresAtLabel = "12:30:00",
            remainingSeconds = 0,
        )
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(
                    state = previewDetailState().copy(approvalOffer = expiredOffer),
                    onEvent = {},
                )
            }
        }

        compose.onNodeWithText("Approval expired").assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Offer expired. Denied by default.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Offer expired. Denied by default.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Deny this exact operation").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Allow this exact operation once").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Copy exact normalized arguments").performScrollTo().assertHasClickAction()
    }

    @Test
    fun narrowLargeTextKeepsActionsAddressable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SessionTheme {
                    Box(modifier = Modifier.width(300.dp).height(900.dp)) {
                        SessionDetailScreen(previewDetailState(), onEvent = {})
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Return to session list").assertHasClickAction()
        compose.onNode(hasText("Steer now") and hasContentDescription("Send this text as steering during the current run"))
            .assertExists()
        compose.onNode(hasText("Queue follow-up") and hasContentDescription("Queue this text after the current run"))
            .assertExists()
    }

    @Test
    fun finalizedAndProvisionalMessagesAreLabeledWithoutClaimingCompletion() {
        compose.setContent {
            SessionTheme {
                SessionDetailScreen(previewDetailState(), onEvent = {})
            }
        }

        compose.onNodeWithContentDescription("Session timeline")
            .performScrollToNode(hasText("Final message"))
        compose.onAllNodesWithText("Final message", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription("Session timeline")
            .performScrollToNode(hasText("Live · provisional · may be replaced"))
        compose.onNodeWithText("Live · provisional · may be replaced", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Streaming").assertTextContains("Streaming")
        compose.onNodeWithText("Completed").assertDoesNotExist()
    }
}
