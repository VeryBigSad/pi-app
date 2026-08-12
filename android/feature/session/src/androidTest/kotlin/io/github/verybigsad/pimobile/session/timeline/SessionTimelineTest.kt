package io.github.verybigsad.pimobile.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionTimelineTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun streamingTimelineUsesAccessibleProvisionalAndThinkingControls() {
        compose.setContent {
            SessionTheme {
                SessionTimeline(state = previewDetailState(), onEvent = {})
            }
        }

        compose.onNodeWithContentDescription("Session timeline").assertExists()
        compose.onNodeWithContentDescription("Session timeline").performScrollToNode(hasText("Live · provisional · may be replaced"))
        compose.onNodeWithText("Live · provisional · may be replaced", useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("Show Thinking"), useUnmergedTree = true).assertHasClickAction()
    }

    @Test
    fun timelineRendersWireJsonTextPartAsPlainMessageText() {
        // Finalized message whose TEXT part projection is the raw wire envelope, as committed
        // from a host snapshot/message.append; the bubble must render the text, not the JSON.
        val base = previewDetailState()
        val conversation = base.session.conversation.copy(
            finalizedMessages = kotlinx.collections.immutable.persistentListOf(
                io.github.verybigsad.pimobile.model.FinalizedMessage(
                    id = io.github.verybigsad.pimobile.model.MessageId("msg-wire-1"),
                    role = io.github.verybigsad.pimobile.model.MessageRole.ASSISTANT,
                    content = kotlinx.collections.immutable.persistentListOf(
                        io.github.verybigsad.pimobile.model.MessageContent(
                            "content-0",
                            io.github.verybigsad.pimobile.model.MessageContentKind.TEXT,
                            0,
                            "{\"text\":\"hello from the canonical log\",\"type\":\"text\"}",
                        ),
                    ),
                    appendOrdinal = 1,
                    createdAtEpochMillis = 1_000L,
                    finalizedAtEpochMillis = 1_001L,
                ),
            ),
            provisionalMessages = kotlinx.collections.immutable.persistentMapOf(),
            hasOlderMessages = false,
            lastSettlementId = null,
        )
        compose.setContent {
            SessionTheme {
                SessionTimeline(state = base.copy(session = base.session.copy(conversation = conversation)), onEvent = {})
            }
        }

        compose.onNodeWithText("hello from the canonical log", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("{\"text\":\"hello from the canonical log\",\"type\":\"text\"}", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun timelineUpdatesAVisibleProvisionalMessageToItsFinalReply() {
        val initial = previewDetailState()
        val messageId = io.github.verybigsad.pimobile.model.MessageId("message-live")
        var state by mutableStateOf(initial)
        compose.setContent {
            SessionTheme {
                SessionTimeline(state = state, onEvent = {})
            }
        }

        val provisional = requireNotNull(initial.session.conversation.provisionalMessages[messageId])
        val final = io.github.verybigsad.pimobile.model.FinalizedMessage(
            id = messageId,
            role = provisional.role,
            content = kotlinx.collections.immutable.persistentListOf(
                io.github.verybigsad.pimobile.model.MessageContent(
                    "text-final",
                    io.github.verybigsad.pimobile.model.MessageContentKind.TEXT,
                    9,
                    "final reply after recomposition",
                ),
            ),
            appendOrdinal = initial.session.conversation.finalizedMessages.last().appendOrdinal + 1,
            createdAtEpochMillis = provisional.startedAtEpochMillis,
            finalizedAtEpochMillis = provisional.startedAtEpochMillis + 1,
        )
        assertEquals(TimelineEntry.Provisional(provisional).stableKey, TimelineEntry.Finalized(final).stableKey)

        compose.runOnIdle {
            val conversation = state.session.conversation
            state = state.copy(
                session = state.session.copy(
                    conversation = conversation.copy(
                        finalizedMessages = conversation.finalizedMessages.adding(final),
                        provisionalMessages = kotlinx.collections.immutable.persistentMapOf(),
                    ),
                ),
            )
        }

        compose.waitForIdle()
        compose.onNodeWithText("final reply after recomposition", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Building the state-driven Compose surface…", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun timelineKeepsTextAndControlsAddressableAtTwoHundredPercentFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SessionTheme {
                    Box(Modifier.width(300.dp).height(700.dp)) {
                        SessionTimeline(state = previewDetailState(), onEvent = {})
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Session timeline").assertExists()
    }
}
