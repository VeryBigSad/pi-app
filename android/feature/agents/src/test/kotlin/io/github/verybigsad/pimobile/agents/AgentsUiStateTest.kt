package io.github.verybigsad.pimobile.agents

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.network.WireBodies
import java.time.Instant
import org.junit.Test

class AgentsUiStateTest {
    @Test
    fun statusesExposeExpectedLabels() {
        assertThat(AgentStatus.entries.map(AgentStatus::label)).containsExactly(
            "Running",
            "Waiting",
            "Completed",
            "Failed",
            "Stopped",
        ).inOrder()
    }

    @Test
    fun terminalStatusesAreTerminal() {
        assertThat(agent(status = AgentStatus.COMPLETED).isTerminal).isTrue()
        assertThat(agent(status = AgentStatus.FAILED).isTerminal).isTrue()
        assertThat(agent(status = AgentStatus.STOPPED).isTerminal).isTrue()
        assertThat(agent(status = AgentStatus.RUNNING).isTerminal).isFalse()
        assertThat(agent(status = AgentStatus.WAITING).isTerminal).isFalse()
    }

    @Test
    fun relativeTimeFormatsCoarseBuckets() {
        val now = Instant.parse("2026-08-11T05:10:00Z").toEpochMilli()
        assertThat(RelativeTime.relative(now - 10_000, now)).isEqualTo("just now")
        assertThat(RelativeTime.relative(now - 300_000, now)).isEqualTo("5 min ago")
        assertThat(RelativeTime.relative(now - 7_200_000, now)).isEqualTo("2 hr ago")
        assertThat(RelativeTime.relative(now - 172_800_000, now)).isEqualTo("2 days ago")
        assertThat(RelativeTime.relative(now + 60_000, now)).isEqualTo("just now")
        assertThat(RelativeTime.startedLabel(now - 300_000, now)).isEqualTo("started 5 min ago")
    }

    @Test
    fun catalogReplaceBuildsSessionsWithDepths() {
        val state = AgentsReducer.reduceCatalog(
            AgentsUiState(emptyList()),
            catalog(
                "session-1" to listOf(
                    wireAgent("root", null),
                    wireAgent("child", "root"),
                    wireAgent("grandchild", "child"),
                ),
            ),
        )
        val session = state.sessions.single()
        assertThat(session.expanded).isTrue()
        assertThat(session.agents.map { it.depth }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun catalogReplacePreservesExpansionForSurvivingSessions() {
        val initial = AgentsReducer.reduceCatalog(
            AgentsUiState(emptyList()),
            catalog("session-1" to listOf(wireAgent("a", null))),
        )
        val collapsed = AgentsReducer.toggleSession(initial, "session-1")
        val replaced = AgentsReducer.reduceCatalog(collapsed, catalog("session-1" to emptyList()))
        assertThat(replaced.sessions.single().expanded).isFalse()
    }

    @Test
    fun updateUpsertByAgentIdIsIdempotent() {
        val catalogState = AgentsReducer.reduceCatalog(
            AgentsUiState(emptyList()),
            catalog("session-1" to listOf(wireAgent("a", null), wireAgent("b", null))),
        )
        val completed = wireAgent("a", null, status = WireBodies.AgentStatus.COMPLETED, toolUses = 9)
        val once = AgentsReducer.reduceUpdate(catalogState, update("session-1", completed))
        val twice = AgentsReducer.reduceUpdate(once, update("session-1", completed))
        assertThat(twice).isEqualTo(once)
        val a = once.sessions.single().agents.first { it.agentId == "a" }
        assertThat(a.status).isEqualTo(AgentStatus.COMPLETED)
        assertThat(a.toolUses).isEqualTo(9)
        assertThat(once.sessions.single().agents.map { it.agentId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun updateForUnknownSessionCreatesSession() {
        val state = AgentsReducer.reduceUpdate(
            AgentsUiState(emptyList()),
            update("session-9", wireAgent("a", null)),
        )
        assertThat(state.sessions.single().sessionId).isEqualTo("session-9")
        assertThat(state.sessions.single().agents.single().agentId).isEqualTo("a")
    }

    @Test
    fun updateUpsertIsBoundedPerSession() {
        val agents = (1..AgentsReducer.MAX_AGENTS_PER_SESSION).map { wireAgent("agent-$it", null) }
        var state = AgentsReducer.reduceCatalog(AgentsUiState(emptyList()), catalog("session-1" to agents))
        state = AgentsReducer.reduceUpdate(state, update("session-1", wireAgent("overflow", null)))
        assertThat(state.sessions.single().agents).hasSize(AgentsReducer.MAX_AGENTS_PER_SESSION)
        assertThat(state.sessions.single().agents.map { it.agentId }).doesNotContain("overflow")
        // Existing agents still update past the bound.
        state = AgentsReducer.reduceUpdate(
            state,
            update("session-1", wireAgent("agent-1", null, status = WireBodies.AgentStatus.FAILED)),
        )
        assertThat(state.sessions.single().agents.first().status).isEqualTo(AgentStatus.FAILED)
    }

    @Test
    fun depthIsCappedAndCycleSafe() {
        val state = AgentsReducer.reduceCatalog(
            AgentsUiState(emptyList()),
            catalog(
                "session-1" to listOf(
                    wireAgent("a", "b"),
                    wireAgent("b", "a"),
                ),
            ),
        )
        assertThat(state.sessions.single().agents.map { it.depth })
            .containsExactly(1, 1)
    }

    @Test
    fun storePublishesLiveUpdates() = kotlinx.coroutines.test.runTest {
        val store = AgentsStore()
        store.applyCatalog(catalog("session-1" to listOf(wireAgent("a", null))))
        assertThat(store.state.value.sessions).hasSize(1)
        store.applyUpdate(update("session-1", wireAgent("a", null, status = WireBodies.AgentStatus.STOPPED)))
        assertThat(store.state.value.sessions.single().agents.single().status).isEqualTo(AgentStatus.STOPPED)
        store.toggleSession("session-1")
        assertThat(store.state.value.sessions.single().expanded).isFalse()
        store.setOffline(true)
        assertThat(store.state.value.isOffline).isTrue()
    }

    private fun agent(
        status: AgentStatus,
        id: String = "a",
        depth: Int = 0,
    ): AgentUiState = AgentUiState(
        agentId = id,
        parentAgentId = null,
        description = "desc",
        agentType = "explore",
        status = status,
        startedAtEpochMillis = 0,
        endedAtEpochMillis = null,
        toolUses = 0,
        model = null,
        depth = depth,
    )

    private fun wireAgent(
        agentId: String,
        parentAgentId: String?,
        status: WireBodies.AgentStatus = WireBodies.AgentStatus.RUNNING,
        toolUses: Int = 1,
    ): WireBodies.Agent = WireBodies.Agent(
        agentId = agentId,
        parentAgentId = parentAgentId,
        description = "desc-$agentId",
        agentType = "explore",
        status = status,
        startedAt = Instant.parse("2026-08-11T05:00:00Z"),
        endedAt = null,
        toolUses = toolUses,
        model = "k3-large",
    )

    private fun catalog(vararg sessions: Pair<String, List<WireBodies.Agent>>): WireBodies.AgentsCatalog =
        WireBodies.AgentsCatalog(
            sessions.map { (id, agents) ->
                WireBodies.AgentsCatalogSession(SessionId(id), agents)
            },
        )

    private fun update(sessionId: String, agent: WireBodies.Agent): WireBodies.AgentsUpdate =
        WireBodies.AgentsUpdate(SessionId(sessionId), agent)
}
