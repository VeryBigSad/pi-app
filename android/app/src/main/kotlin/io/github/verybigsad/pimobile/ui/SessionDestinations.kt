package io.github.verybigsad.pimobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.verybigsad.pimobile.MainViewModel
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.session.SessionDetailScreen
import io.github.verybigsad.pimobile.session.SessionListScreen
import io.github.verybigsad.pimobile.session.TerminalModeAvailability
import io.github.verybigsad.pimobile.state.AppIntent

@Composable
fun SessionListDestination(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val uiState = viewModel.listUiState(state, System.currentTimeMillis())
    SessionListScreen(
        state = uiState,
        onEvent = { event -> viewModel.submit(AppIntent.ListEvent(event)) },
    )
}

@Composable
fun SessionDetailDestination(
    viewModel: MainViewModel,
    sessionId: SessionId,
    activityActions: AppActivityActions = AppActivityActions(),
) {
    val state by viewModel.state.collectAsState()
    val voice by viewModel.voiceUiState.collectAsState()
    val uiState = viewModel.detailUiState(state, sessionId, System.currentTimeMillis(), voice)
    if (uiState == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "This session is not in the canonical cache.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    SessionDetailScreen(
        state = uiState,
        onEvent = { event ->
            if (event == io.github.verybigsad.pimobile.session.SessionDetailEvent.OpenVoicePermissionSettings) {
                activityActions.onOpenVoicePermissionSettings()
            } else {
                viewModel.submit(AppIntent.DetailEvent(sessionId, event))
            }
        },
        onOpenTerminal = {
            val latest = viewModel.detailUiState(viewModel.state.value, sessionId, System.currentTimeMillis())
            if (latest?.terminalModeAvailability is TerminalModeAvailability.Available) {
                viewModel.openTerminal(sessionId)
            }
        },
    )
}
