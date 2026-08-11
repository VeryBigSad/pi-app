package io.github.verybigsad.pimobile.agents

import io.github.verybigsad.pimobile.network.WireBodies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live store behind the agents insight screen. Wire handlers ([applyCatalog] / [applyUpdate])
 * funnel parsed protocol bodies through [AgentsReducer]; UI interactions toggle expansion.
 */
class AgentsStore(initial: AgentsUiState = AgentsUiState(emptyList())) {
    private val mutableState = MutableStateFlow(initial)

    val state: StateFlow<AgentsUiState> = mutableState.asStateFlow()

    fun applyCatalog(catalog: WireBodies.AgentsCatalog) {
        mutableState.update { AgentsReducer.reduceCatalog(it, catalog) }
    }

    fun applyUpdate(update: WireBodies.AgentsUpdate) {
        mutableState.update { AgentsReducer.reduceUpdate(it, update) }
    }

    fun toggleSession(sessionId: String) {
        mutableState.update { AgentsReducer.toggleSession(it, sessionId) }
    }

    fun setOffline(offline: Boolean) {
        mutableState.update { AgentsReducer.setOffline(it, offline) }
    }
}
