package io.github.verybigsad.pimobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.verybigsad.pimobile.MainViewModel
import io.github.verybigsad.pimobile.PiMobileApplication
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.state.AppIntent
import io.github.verybigsad.pimobile.terminal.TerminalPhase

@Composable
fun TerminalScreenDestination(viewModel: MainViewModel, sessionId: SessionId) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as PiMobileApplication
    val controller = application.container.terminalController
    if (controller == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "Terminal is not connected.",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { viewModel.submit(AppIntent.CloseTerminal) }) { Text("Back") }
        }
        return
    }
    val terminalState by controller.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        when (terminalState.phase) {
            TerminalPhase.CONNECTING -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Opening terminal on the Mac…",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            TerminalPhase.READY, TerminalPhase.RESETTING -> {
                if (terminalState.phase == TerminalPhase.RESETTING) {
                    Text(
                        "Terminal stream reset; waiting for a fresh redraw from the Mac.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (terminalState.historyOpen) {
                                controller.closeHistoryDrawer()
                            } else {
                                controller.requestHistory()
                            }
                        },
                        enabled = terminalState.phase == TerminalPhase.READY,
                    ) {
                        Text(if (terminalState.historyOpen) "Close history" else "History")
                    }
                    terminalState.historyError?.let { error ->
                        Text(
                            "History unavailable ($error)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { controller.runtime.createWebView() },
                )
            }

            TerminalPhase.FAILED -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Terminal unavailable", style = MaterialTheme.typography.titleMedium)
                    Text(
                        terminalState.errorCode ?: "TERMINAL_FAILED",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { viewModel.submit(AppIntent.CloseTerminal) }) { Text("Back") }
                }
            }

            TerminalPhase.CLOSED -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Terminal closed.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { viewModel.submit(AppIntent.CloseTerminal) }) { Text("Back") }
            }
        }
    }
}
