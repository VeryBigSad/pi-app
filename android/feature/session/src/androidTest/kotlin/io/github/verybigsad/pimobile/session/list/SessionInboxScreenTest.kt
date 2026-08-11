package io.github.verybigsad.pimobile.session.list

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.SessionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionInboxScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sessionRowExposesTrustActivityUnreadSettlementAndCatalogMetadata() {
        compose.setContent {
            SessionTheme { SessionInboxScreen(inboxState(), onEvent = {}) }
        }

        compose.onNodeWithText("Trusted").assertIsDisplayed()
        compose.onNodeWithText("Streaming").assertIsDisplayed()
        compose.onNodeWithText("3 unread").assertIsDisplayed()
        compose.onNodeWithText("Settled").assertIsDisplayed()
        compose.onNodeWithText("OpenAI · gpt-5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Alpha, Streaming, Trusted, updated 2m ago, 3 unread, settled, OpenAI · gpt-5. Recent output")
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Open actions for Alpha").assertHasClickAction()
    }

    @Test
    fun refreshRequestsResyncAndOfflineStateIsAnnounced() {
        val events = mutableListOf<SessionInboxEvent>()
        compose.setContent {
            SessionTheme {
                SessionInboxScreen(
                    inboxState(isOffline = true, offlineLabel = "Synced 4 minutes ago"),
                    onEvent = events::add,
                )
            }
        }

        compose.onNodeWithText("Offline · Synced 4 minutes ago").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resync session inbox from Mac").performClick()
        assertEquals(listOf(SessionInboxEvent.RequestResync), events)
    }

    @Test
    fun loadingAndErrorStatesHaveClearRecoveryCopy() {
        compose.setContent {
            SessionTheme {
                SessionInboxScreen(inboxState(loadState = SessionInboxLoadState.Error("Mac is unreachable.")), onEvent = {})
            }
        }
        compose.onNodeWithText("Couldn't load sessions").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertHasClickAction()
    }

    private fun inboxState(
        loadState: SessionInboxLoadState = SessionInboxLoadState.Ready,
        isOffline: Boolean = false,
        offlineLabel: String? = null,
    ): SessionInboxUiState = SessionInboxUiState(
        items = listOf(
            SessionInboxItemUiState(
                id = SessionId("alpha"),
                title = "Alpha",
                repositoryPath = "/repo",
                worktreePath = "/worktree",
                preview = "Recent output",
                updatedAtEpochMillis = 1_000,
                relativeTimestamp = "2m ago",
                trustBadge = SessionTrustBadge.TRUSTED,
                activity = SessionActivityIndicator.STREAMING,
                unreadCount = 3,
                hasSettlement = true,
                provider = "OpenAI",
                model = "gpt-5",
                treeDepth = 0,
            ),
        ),
        loadState = loadState,
        isRefreshing = false,
        isOffline = isOffline,
        offlineLabel = offlineLabel,
    )
}
