package io.github.verybigsad.pimobile.session.composer

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.verybigsad.pimobile.model.SessionRunState
import io.github.verybigsad.pimobile.session.SessionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MessageComposerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun streamingShowsSteeringQueueAndAccessibleAnnouncements() {
        compose.setContent {
            SessionTheme {
                MessageComposer(
                    state = MessageComposerState(
                        text = "Continue with tests",
                        runState = SessionRunState.STREAMING,
                        enabled = true,
                        restoredFromCache = true,
                        queuedSteering = listOf(QueuedSteeringItem("queued-1", "Then summarize")),
                        voiceState = VoiceState.Partial("partial transcript"),
                    ),
                    onIntent = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Draft restored from cache").assertExists()
        compose.onNodeWithContentDescription("Steer running response").assertHasClickAction()
        compose.onNodeWithContentDescription("Queue steering message").assertHasClickAction()
        compose.onNodeWithContentDescription("Queued steering messages").assertExists()
        compose.onNodeWithText("Transcript: partial transcript").assertIsDisplayed()
    }

    @Test
    fun idleImeAndSendEmitOnlyTypedIntent() {
        val intents = mutableListOf<MessageComposerIntent>()
        compose.setContent {
            SessionTheme {
                MessageComposer(
                    state = MessageComposerState(
                        text = "hello",
                        runState = SessionRunState.IDLE,
                        enabled = true,
                    ),
                    onIntent = intents::add,
                )
            }
        }

        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitForIdle()

        assertEquals(listOf(MessageComposerIntent.Send), intents)
    }
}
