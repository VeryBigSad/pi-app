package io.github.verybigsad.pimobile.agents

import io.github.verybigsad.pimobile.network.WireBodies

/**
 * Pure reducer for agents wire traffic. `agents.catalog` replaces the full snapshot (preserving
 * expansion flags for surviving sessions); `agents.update` upserts a single agent by agentId.
 * Upserts are idempotent (reapplying the same update yields an equal state) and bounded to
 * [MAX_AGENTS_PER_SESSION] agents per session — new agents past the bound are dropped.
 */
object AgentsReducer {
    const val MAX_AGENT_SESSIONS = 512
    const val MAX_AGENTS_PER_SESSION = 256
    const val MAX_AGENT_DESCRIPTION_CHARS = 256
    const val MAX_DEPTH = 8

    fun reduceCatalog(state: AgentsUiState, catalog: WireBodies.AgentsCatalog): AgentsUiState {
        val expandedById = state.sessions.associate { it.sessionId to it.expanded }
        val sessions = catalog.sessions.take(MAX_AGENT_SESSIONS).map { session ->
            AgentSessionUiState(
                sessionId = session.sessionId.value,
                agents = session.agents.take(MAX_AGENTS_PER_SESSION).map(::toUi).withDepths(),
                expanded = expandedById[session.sessionId.value] ?: true,
            )
        }
        return state.copy(sessions = sessions)
    }

    fun reduceUpdate(state: AgentsUiState, update: WireBodies.AgentsUpdate): AgentsUiState {
        val sessionId = update.sessionId.value
        val agent = toUi(update.agent)
        val sessions = state.sessions.toMutableList()
        val index = sessions.indexOfFirst { it.sessionId == sessionId }
        if (index < 0) {
            sessions += AgentSessionUiState(sessionId, listOf(agent).withDepths())
            return state.copy(sessions = sessions)
        }
        val session = sessions[index]
        val agents = session.agents.toMutableList()
        val existing = agents.indexOfFirst { it.agentId == agent.agentId }
        if (existing >= 0) {
            agents[existing] = agent
        } else if (agents.size < MAX_AGENTS_PER_SESSION) {
            agents += agent
        } else {
            return state
        }
        sessions[index] = session.copy(agents = agents.withDepths())
        return state.copy(sessions = sessions)
    }

    fun toggleSession(state: AgentsUiState, sessionId: String): AgentsUiState = state.copy(
        sessions = state.sessions.map { session ->
            if (session.sessionId == sessionId) session.copy(expanded = !session.expanded) else session
        },
    )

    fun setOffline(state: AgentsUiState, offline: Boolean): AgentsUiState = state.copy(isOffline = offline)

    private fun toUi(agent: WireBodies.Agent): AgentUiState = AgentUiState(
        agentId = agent.agentId,
        parentAgentId = agent.parentAgentId,
        description = agent.description,
        agentType = agent.agentType,
        status = AgentStatus.fromWire(agent.status),
        startedAtEpochMillis = agent.startedAt.toEpochMilli(),
        endedAtEpochMillis = agent.endedAt?.toEpochMilli(),
        toolUses = agent.toolUses ?: 0,
        model = agent.model,
        depth = 0,
    )

    /** Assigns indentation depth from parent/child links, with a cycle guard and [MAX_DEPTH] cap. */
    private fun List<AgentUiState>.withDepths(): List<AgentUiState> {
        val byId = associateBy(AgentUiState::agentId)
        fun depthOf(agent: AgentUiState): Int {
            var depth = 0
            var current = agent.parentAgentId
            val seen = mutableSetOf(agent.agentId)
            while (current != null && depth < MAX_DEPTH && seen.add(current)) {
                val parent = byId[current] ?: break
                depth++
                current = parent.parentAgentId
            }
            return depth
        }
        return map { it.copy(depth = depthOf(it)) }
    }
}
