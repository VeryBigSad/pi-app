package io.github.verybigsad.pimobile.terminal

import android.content.Context
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.wire.HostConnector
import io.github.verybigsad.pimobile.wire.WireMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TerminalPhase {
    /** No terminal.open has been acknowledged; nothing is rendered as live. */
    CONNECTING,
    READY,
    RESETTING,
    CLOSED,
    FAILED,
}

data class TerminalUiState(
    val phase: TerminalPhase = TerminalPhase.CONNECTING,
    val errorCode: String? = null,
    /** A history drawer capture is displayed inside the WebView drawer. */
    val historyOpen: Boolean = false,
    /** Non-null when the last terminal.history.request could not be honored. */
    val historyError: String? = null,
)

/**
 * Owns one TerminalRuntime for one session. Input is ephemeral: it is forwarded only while
 * the runtime reports a connected generation and is never replayed after uncertain delivery.
 */
class TerminalSessionController(
    context: Context,
    private val sessionId: SessionId,
    private val connector: () -> HostConnector?,
    private val onClosed: () -> Unit,
) : io.github.verybigsad.pimobile.state.TerminalPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(TerminalUiState())

    val state: StateFlow<TerminalUiState> = mutableState.asStateFlow()

    val runtime: TerminalRuntime = TerminalRuntime(context.applicationContext, onEvent = ::onTerminalEvent)

    private var inputSequence = 0uL
    private var outputSequence = 0uL
    private var generation: ULong? = null

    fun start(columns: Int = 80, rows: Int = 24) {
        val host = connector()
        if (host == null) {
            mutableState.value = TerminalUiState(TerminalPhase.FAILED, "TERMINAL_NOT_CONNECTED")
            return
        }
        scope.launch {
            runCatching { host.send("terminal.open", WireMessages.terminalOpen(sessionId, columns, rows)) }
                .onFailure { mutableState.value = TerminalUiState(TerminalPhase.FAILED, "TERMINAL_OPEN_FAILED") }
        }
    }

    override fun onReady(terminalGeneration: ULong, columns: Int, rows: Int) = onTerminalReady(terminalGeneration, columns, rows)
    override fun onOutput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = onTerminalOutput(terminalGeneration, sequence, bytes)
    override fun onReset() = onResetRequired()

    /**
     * Sends terminal.history.request for the current generation. The capture is read-only,
     * bounded (protocol v1: 5,000 lines / 1 MiB), and renders in the WebView drawer only;
     * it never feeds xterm, Pi stdin, or replay state.
     */
    fun requestHistory(maxLines: Int = HISTORY_MAX_LINES, maxBytes: Int = HISTORY_MAX_BYTES) {
        val current = generation ?: run {
            mutableState.value = mutableState.value.copy(historyError = "TERMINAL_HISTORY_NOT_READY")
            return
        }
        val host = connector() ?: run {
            mutableState.value = mutableState.value.copy(historyError = "TERMINAL_HISTORY_NOT_CONNECTED")
            return
        }
        mutableState.value = mutableState.value.copy(historyError = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                host.send("terminal.history.request", WireMessages.terminalHistoryRequest(current, maxLines, maxBytes))
            }.onFailure {
                mutableState.value = mutableState.value.copy(historyError = "TERMINAL_HISTORY_REQUEST_FAILED")
            }
        }
    }

    override fun onHistoryResult(result: io.github.verybigsad.pimobile.wire.HostConnectionEvent.TerminalHistoryResult) {
        if (result.terminalGeneration != generation) return
        val text = result.text
        if (text == null) {
            mutableState.value = mutableState.value.copy(historyError = "TERMINAL_HISTORY_REF_UNSUPPORTED")
            return
        }
        scope.launch(Dispatchers.Main) {
            val snapshot = runCatching {
                io.github.verybigsad.pimobile.terminal.TerminalHistorySnapshot(
                    terminalGeneration = result.terminalGeneration,
                    capturedAt = result.capturedAt,
                    text = text,
                    truncatedLines = result.truncatedLines,
                    truncatedBytes = result.truncatedBytes,
                )
            }.getOrNull()
            val shown = snapshot != null && runtime.showHistory(snapshot)
            mutableState.value = if (shown) {
                mutableState.value.copy(historyOpen = true, historyError = null)
            } else {
                mutableState.value.copy(historyError = "TERMINAL_HISTORY_RENDER_FAILED")
            }
        }
    }

    fun closeHistoryDrawer() {
        scope.launch(Dispatchers.Main) { runtime.closeHistory() }
        mutableState.value = mutableState.value.copy(historyOpen = false)
    }

    fun onTerminalReady(terminalGeneration: ULong, columns: Int, rows: Int) {
        generation = terminalGeneration
        inputSequence = 0uL
        outputSequence = 0uL
        scope.launch(Dispatchers.Main) {
            runtime.startGeneration(terminalGeneration)
            mutableState.value = TerminalUiState(TerminalPhase.READY)
        }
    }

    private companion object {
        const val HISTORY_MAX_LINES = 5_000
        const val HISTORY_MAX_BYTES = 1_048_576
    }

    fun onTerminalOutput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) {
        scope.launch(Dispatchers.Main) {
            when (runtime.writeOutput(terminalGeneration, sequence, bytes)) {
                TerminalWriteResult.POSTED_TO_WEBVIEW, TerminalWriteResult.QUEUED_FOR_PAGE -> Unit
                else -> mutableState.value = TerminalUiState(TerminalPhase.RESETTING)
            }
        }
    }

    fun onResetRequired() {
        generation = null
        mutableState.value = TerminalUiState(TerminalPhase.RESETTING)
        scope.launch {
            connector()?.send("terminal.reset", WireMessages.sessionRef(sessionId))
        }
    }

    private fun onTerminalEvent(event: TerminalEvent) {
        when (event) {
            is TerminalEvent.Input -> {
                val host = connector() ?: return
                val current = generation ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        host.sendTerminalInput(current, event.value.sequence, event.value.bytes)
                    }.onFailure {
                        mutableState.value = TerminalUiState(TerminalPhase.RESETTING)
                    }
                }
            }

            is TerminalEvent.Resize -> {
                val host = connector() ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching { host.send("terminal.resize", WireMessages.terminalResize(sessionId, event.columns, event.rows)) }
                }
            }

            is TerminalEvent.ResetRequired -> onResetRequired()
            TerminalEvent.HistoryClosed -> mutableState.value = mutableState.value.copy(historyOpen = false)
            is TerminalEvent.RendererGone -> mutableState.value = TerminalUiState(TerminalPhase.RESETTING, "TERMINAL_RENDERER_GONE")
            is TerminalEvent.Failure -> mutableState.value = TerminalUiState(TerminalPhase.FAILED, event.code)
            is TerminalEvent.Canary -> {
                if (!event.result.compatible) {
                    mutableState.value = TerminalUiState(TerminalPhase.FAILED, event.result.reason ?: "TERMINAL_WEBVIEW_INCOMPATIBLE")
                }
            }

            else -> Unit
        }
    }

    override fun close() {
        val host = connector()
        scope.launch(Dispatchers.IO) {
            runCatching { host?.send("terminal.close", WireMessages.sessionRef(sessionId)) }
        }
        scope.launch(Dispatchers.Main) {
            runtime.destroy()
            mutableState.value = TerminalUiState(TerminalPhase.CLOSED)
            onClosed()
            scope.cancel()
        }
    }
}
