package io.github.verybigsad.pimobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.verybigsad.pimobile.MainViewModel
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.session.SessionTheme
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.state.PairingUiState

/**
 * State-driven navigation: the coordinator owns the only source of truth, and every
 * destination is a pure projection of PiAppState. External intents never route directly;
 * they become pending opaque destinations consumed once trust, authentication, and
 * canonical sync allow it.
 */
@Composable
fun PiAppRoot(viewModel: MainViewModel, activityActions: AppActivityActions = AppActivityActions()) {
    val state by viewModel.state.collectAsState()
    SessionTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                !state.hydrated -> HydratingScreen()
                state.pairing != null -> PairingFlowScreen(
                    pairing = requireNotNull(state.pairing),
                    onScanned = { viewModel.submit(AppIntent.PairingUriScanned(it)) },
                    onCancel = { viewModel.submit(AppIntent.PairingCancelled) },
                    onRetry = { viewModel.submit(AppIntent.StartPairing) },
                )

                state.trust is TrustState.Unpaired -> PairingLandingScreen(
                    onPair = { viewModel.submit(AppIntent.StartPairing) },
                )

                state.terminalSessionId != null -> {
                    val sessionId = requireNotNull(state.terminalSessionId)
                    BackHandler { viewModel.submit(AppIntent.CloseTerminal) }
                    TerminalScreenDestination(viewModel, sessionId)
                }

                state.settingsOpen -> {
                    BackHandler { viewModel.submit(AppIntent.CloseSettings) }
                    SettingsDestination(viewModel, activityActions)
                }

                state.agentsOpen -> {
                    BackHandler { viewModel.submit(AppIntent.CloseAgents) }
                    AgentsDestination(viewModel)
                }

                state.selectedSessionId != null -> {
                    val sessionId = requireNotNull(state.selectedSessionId)
                    BackHandler { viewModel.submit(AppIntent.NavigateBack) }
                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                        SessionDetailDestination(viewModel, sessionId)
                        SessionDetailEntryPoints(
                            onOpenAgents = { viewModel.submit(AppIntent.OpenAgents) },
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd),
                        )
                    }
                }

                else -> SessionListWithEntryPoints(viewModel)
            }
            if (state.updateSheetOpen) {
                UpdateSheetDestination(viewModel, activityActions)
            }
        }
    }
}

@Composable
fun HydratingScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Opening encrypted cache…",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
