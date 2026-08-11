package io.github.verybigsad.pimobile.agents

import androidx.compose.runtime.Immutable
import io.github.verybigsad.pimobile.network.WireBodies

/**
 * Presentation state for the agents insight screen. Sessions are expandable sections; agents
 * render in insertion order with a parent/child indentation depth derived from `parentAgentId`.
 */
@Immutable
data class AgentsUiState(
    val sessions: List<AgentSessionUiState>,
    val isOffline: Boolean = false,
) {
    val isEmpty: Boolean get() = sessions.all { it.agents.isEmpty() }
}

@Immutable
data class AgentSessionUiState(
    val sessionId: String,
    val agents: List<AgentUiState>,
    val expanded: Boolean = true,
) {
    init {
        require(sessionId.isNotBlank())
        require(agents.size <= AgentsReducer.MAX_AGENTS_PER_SESSION)
    }
}

@Immutable
data class AgentUiState(
    val agentId: String,
    val parentAgentId: String?,
    val description: String,
    val agentType: String,
    val status: AgentStatus,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val toolUses: Int,
    val model: String?,
    val depth: Int,
) {
    init {
        require(agentId.isNotBlank())
        require(description.isNotBlank())
        require(description.length <= AgentsReducer.MAX_AGENT_DESCRIPTION_CHARS)
        require(agentType.isNotBlank())
        require(toolUses >= 0)
        require(depth in 0..AgentsReducer.MAX_DEPTH)
    }

    val isTerminal: Boolean
        get() = status == AgentStatus.COMPLETED || status == AgentStatus.FAILED || status == AgentStatus.STOPPED
}

enum class AgentStatus(val label: String) {
    RUNNING("Running"),
    WAITING("Waiting"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    STOPPED("Stopped"),
    ;

    companion object {
        fun fromWire(status: WireBodies.AgentStatus): AgentStatus = when (status) {
            WireBodies.AgentStatus.RUNNING -> RUNNING
            WireBodies.AgentStatus.WAITING -> WAITING
            WireBodies.AgentStatus.COMPLETED -> COMPLETED
            WireBodies.AgentStatus.FAILED -> FAILED
            WireBodies.AgentStatus.STOPPED -> STOPPED
        }
    }
}
