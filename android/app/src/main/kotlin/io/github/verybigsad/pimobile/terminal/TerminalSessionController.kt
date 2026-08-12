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
    private val runtimeFactory: (Context, (TerminalEvent) -> Unit) -> TerminalRuntime = { runtimeContext, eventSink ->
        TerminalRuntime(runtimeContext, onEvent = eventSink)
    },
) : io.github.verybigsad.pimobile.state.TerminalPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableState = MutableStateFlow(TerminalUiState())

    val state: StateFlow<TerminalUiState> = mutableState.asStateFlow()

    val runtime: TerminalRuntime = runtimeFactory(context.applicationContext, ::onTerminalEvent)

    private var generation: ULong? = null
    private var activeTerminalHost: HostConnector? = null
    private var pageReady = false
    @Volatile
    private var terminalStartupFailed = false
    private var columns = 80
    private var rows = 24

    fun start(columns: Int = this.columns, rows: Int = this.rows) {
        if (terminalStartupFailed) return
        this.columns = columns
        this.rows = rows
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
                host.send("terminal.history.request", WireMessages.terminalHistoryRequest(sessionId, current, maxLines, maxBytes))
            }.onFailure {
                mutableState.value = mutableState.value.copy(historyError = "TERMINAL_HISTORY_REQUEST_FAILED")
            }
        }
    }

    override fun onHistoryResult(result: io.github.verybigsad.pimobile.wire.HostConnectionEvent.TerminalHistoryResult) {
        if (result.sessionId != sessionId || result.terminalGeneration != generation) return
        scope.launch(Dispatchers.Main) {
            val snapshot = runCatching {
                io.github.verybigsad.pimobile.terminal.TerminalHistorySnapshot(
                    terminalGeneration = result.terminalGeneration,
                    capturedAt = result.capturedAt,
                    text = result.text,
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
        if (terminalStartupFailed) return
        val host = connector()
        if (host == null || runCatching { host.activateTerminalInput(terminalGeneration) }.isFailure) {
            mutableState.value = TerminalUiState(TerminalPhase.FAILED, "TERMINAL_INPUT_ACTIVATION_FAILED")
            return
        }
        generation = terminalGeneration
        activeTerminalHost = host
        this.columns = columns
        this.rows = rows
        scope.launch(Dispatchers.Main) {
            if (terminalStartupFailed || generation != terminalGeneration) return@launch
            runtime.startGeneration(terminalGeneration)
            if (terminalStartupFailed || generation != terminalGeneration) return@launch
            mutableState.value = TerminalUiState(TerminalPhase.READY)
        }
    }

    private companion object {
        const val HISTORY_MAX_LINES = 5_000
        const val HISTORY_MAX_BYTES = 1_048_576
    }

    fun onTerminalOutput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) {
        scope.launch(Dispatchers.Main) {
            if (terminalStartupFailed) return@launch
            when (runtime.writeOutput(terminalGeneration, sequence, bytes)) {
                TerminalWriteResult.POSTED_TO_WEBVIEW, TerminalWriteResult.QUEUED_FOR_PAGE -> Unit
                else -> mutableState.value = TerminalUiState(TerminalPhase.RESETTING)
            }
        }
    }

    fun onResetRequired() {
        if (terminalStartupFailed) return
        val current = generation
        val activeHost = activeTerminalHost
        generation = null
        activeTerminalHost = null
        mutableState.value = TerminalUiState(TerminalPhase.RESETTING)
        if (current == null) return
        runCatching { activeHost?.deactivateTerminalInput(current) }
        scope.launch(Dispatchers.IO) {
            val host = activeHost ?: return@launch
            runCatching {
                host.send("terminal.close", WireMessages.terminalClose(current, "sequence_gap"))
                if (!terminalStartupFailed) host.send("terminal.open", WireMessages.terminalOpen(sessionId, columns, rows))
            }.onFailure {
                if (!terminalStartupFailed) {
                    mutableState.value = TerminalUiState(TerminalPhase.FAILED, "TERMINAL_REOPEN_FAILED")
                }
            }
        }
    }

    private fun failTerminalStartup(errorCode: String) {
        if (terminalStartupFailed) return
        terminalStartupFailed = true
        pageReady = false
        val current = generation
        val host = activeTerminalHost
        generation = null
        activeTerminalHost = null
        mutableState.value = TerminalUiState(TerminalPhase.FAILED, errorCode)
        scope.launch(Dispatchers.Main) { runtime.destroy() }
        if (current == null) return
        runCatching { host?.deactivateTerminalInput(current) }
        scope.launch(Dispatchers.IO) {
            if (host != null) {
                runCatching { host.send("terminal.close", WireMessages.terminalClose(current, "runtime_failure")) }
            }
        }
    }

    private fun onTerminalEvent(event: TerminalEvent) {
        if (terminalStartupFailed) return
        when (event) {
            TerminalEvent.PageReady -> pageReady = true
            is TerminalEvent.Input -> {
                val host = activeTerminalHost ?: return
                val current = generation ?: return
                if (event.value.terminalGeneration != current) {
                    onResetRequired()
                    return
                }
                val submission = runCatching {
                    host.submitTerminalInput(current, event.value.bytes)
                }.getOrElse {
                    onResetRequired()
                    return
                }
                scope.launch {
                    runCatching { submission.await() }.onFailure {
                        if (generation == current) onResetRequired()
                    }
                }
            }

            is TerminalEvent.Resize -> {
                val host = activeTerminalHost ?: return
                val current = generation ?: return
                columns = event.columns
                rows = event.rows
                scope.launch(Dispatchers.IO) {
                    runCatching { host.send("terminal.resize", WireMessages.terminalResize(current, event.columns, event.rows)) }
                }
            }

            is TerminalEvent.ResetRequired -> onResetRequired()
            TerminalEvent.HistoryClosed -> mutableState.value = mutableState.value.copy(historyOpen = false)
            is TerminalEvent.RendererGone -> mutableState.value = TerminalUiState(TerminalPhase.RESETTING, "TERMINAL_RENDERER_GONE")
            is TerminalEvent.Failure -> {
                if (!pageReady) failTerminalStartup(event.code)
                else mutableState.value = TerminalUiState(TerminalPhase.FAILED, event.code)
            }
            is TerminalEvent.Canary -> {
                if (!event.result.compatible) {
                    failTerminalStartup(event.result.reason ?: "TERMINAL_WEBVIEW_INCOMPATIBLE")
                }
            }

            else -> Unit
        }
    }

    override fun close() {
        val current = generation
        val host = activeTerminalHost
        generation = null
        activeTerminalHost = null
        if (current != null) runCatching { host?.deactivateTerminalInput(current) }
        scope.launch(Dispatchers.IO) {
            if (current != null && host != null) {
                runCatching { host.send("terminal.close", WireMessages.terminalClose(current, "client_closed")) }
            }
        }
        scope.launch(Dispatchers.Main) {
            runtime.destroy()
            mutableState.value = TerminalUiState(TerminalPhase.CLOSED)
            onClosed()
            scope.cancel()
        }
    }
}
