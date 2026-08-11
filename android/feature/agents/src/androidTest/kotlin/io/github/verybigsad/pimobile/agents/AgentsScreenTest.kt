package io.github.verybigsad.pimobile.agents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AgentsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L

    private fun agent(
        id: String,
        status: AgentStatus,
        depth: Int = 0,
        model: String? = "k3-large",
    ): AgentUiState = AgentUiState(
        agentId = id,
        parentAgentId = null,
        description = "Agent $id description",
        agentType = "explore",
        status = status,
        startedAtEpochMillis = now - 300_000,
        endedAtEpochMillis = if (status == AgentStatus.COMPLETED) now - 60_000 else null,
        toolUses = 3,
        model = model,
        depth = depth,
    )

    private fun setContent(state: AgentsUiState, onToggle: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { AgentsScreen(state = state, nowEpochMillis = now, onToggleSession = onToggle) }
        }
    }

    @Test
    fun rendersEveryAgentStatus() {
        val agents = AgentStatus.entries.map { status -> agent(status.name.lowercase(), status) }
        setContent(AgentsUiState(listOf(AgentSessionUiState("session-1", agents))))

        AgentStatus.entries.forEach { status ->
            compose.onNodeWithContentDescription("Status ${status.label}").assertIsDisplayed()
        }
    }

    @Test
    fun rendersDescriptionTimingToolUsesAndModelBadge() {
        setContent(AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.RUNNING))))))

        compose.onNodeWithText("Agent a1 description").assertIsDisplayed()
        compose.onNodeWithText("explore · started 5 min ago · 3 tool uses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Model k3-large").assertIsDisplayed()
    }

    @Test
    fun completedAgentShowsEndedTime() {
        setContent(AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.COMPLETED))))))

        compose.onNodeWithText("explore · started 5 min ago · ended 1 min ago · 3 tool uses").assertIsDisplayed()
    }

    @Test
    fun expandedSessionHeaderClickToggles() {
        var toggled: String? = null
        setContent(
            AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.RUNNING))))),
            onToggle = { toggled = it },
        )

        compose.onNode(hasStateDescription("Expanded")).assertIsDisplayed()
        compose.onNodeWithText("Agent a1 description").assertIsDisplayed()
        compose.onNode(hasStateDescription("Expanded")).performClick()
        assert(toggled == "session-1")
    }

    @Test
    fun collapsedSessionHidesAgents() {
        setContent(
            AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.RUNNING)), expanded = false))),
        )

        compose.onNode(hasStateDescription("Collapsed")).assertIsDisplayed()
        compose.onNodeWithText("Agent a1 description").assertDoesNotExist()
    }

    @Test
    fun runningChipHasPulseSemanticsAndMergedRowDescription() {
        setContent(AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.RUNNING, depth = 1))))))

        compose.onNode(
            hasContentDescription("Agent a1 description, type explore, status Running, started 5 min ago, 3 tool uses, model k3-large, nested level 1"),
        ).assertIsDisplayed()
    }

    @Test
    fun rendersEmptyState() {
        setContent(AgentsUiState(emptyList()))

        compose.onNodeWithText("No agents").assertIsDisplayed()
        compose.onNodeWithText("Agent activity appears here when a session starts agents.").assertIsDisplayed()
    }

    @Test
    fun rendersOfflineBannerAndEmptyOfflineCopy() {
        setContent(AgentsUiState(emptyList(), isOffline = true))

        compose.onNodeWithText("Offline — showing last known agent state").assertIsDisplayed()
        compose.onNodeWithText("Reconnect to see live agent activity.").assertIsDisplayed()
    }

    @Test
    fun offlineBannerCoexistsWithAgents() {
        setContent(
            AgentsUiState(
                listOf(AgentSessionUiState("session-1", listOf(agent("a1", AgentStatus.RUNNING)))),
                isOffline = true,
            ),
        )

        compose.onNodeWithText("Offline — showing last known agent state").assertIsDisplayed()
        compose.onNodeWithText("Agent a1 description").assertIsDisplayed()
        compose.onNodeWithText("No agents").assertDoesNotExist()
    }

    @Test
    fun childAgentIsIndentedUnderParent() {
        val parent = agent("parent", AgentStatus.RUNNING, depth = 0)
        val child = agent("child", AgentStatus.WAITING, depth = 1)
        setContent(AgentsUiState(listOf(AgentSessionUiState("session-1", listOf(parent, child)))))

        compose.onNode(
            hasContentDescription("Agent child description, type explore, status Waiting, started 5 min ago, 3 tool uses, model k3-large, nested level 1"),
        ).assertIsDisplayed()
    }
}
