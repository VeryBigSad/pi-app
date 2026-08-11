package io.github.verybigsad.pimobile.terminal

data class TerminalCanaryResult(
    val compatible: Boolean,
    val webViewVersion: String?,
    val reason: String?,
    val columns: Int?,
    val rows: Int?,
)

data class TerminalInput(
    val terminalGeneration: ULong,
    val sequence: ULong,
    val bytes: ByteArray,
)

data class TerminalHistorySnapshot(
    val terminalGeneration: ULong,
    val capturedAt: String,
    val text: String,
    val truncatedLines: Boolean,
    val truncatedBytes: Boolean,
)

data class TerminalSavedState(
    val lastGeneration: ULong?,
    val columns: Int?,
    val rows: Int?,
    val wasConnected: Boolean,
) {
    val screenRestorable: Boolean = false
    val scrollbackRestorable: Boolean = false
    val requiresReconnect: Boolean = true
}

enum class TerminalWriteResult {
    POSTED_TO_WEBVIEW,
    QUEUED_FOR_PAGE,
    REJECTED_NOT_CONNECTED,
    REJECTED_GENERATION,
    REJECTED_SEQUENCE,
    REJECTED_TOO_LARGE,
    REJECTED_BRIDGE,
}

enum class TerminalResetReason {
    INPUT_GENERATION_MISMATCH,
    INPUT_SEQUENCE_GAP,
    INPUT_SEQUENCE_EXHAUSTED,
    OUTPUT_GENERATION_MISMATCH,
    OUTPUT_SEQUENCE_GAP,
    OUTPUT_SEQUENCE_EXHAUSTED,
    WEB_RUNTIME_SEQUENCE_GAP,
    RENDERER_GONE,
}

sealed interface TerminalEvent {
    data object PageReady : TerminalEvent

    data class Canary(val result: TerminalCanaryResult) : TerminalEvent

    data class Input(val value: TerminalInput) : TerminalEvent

    data class Resize(val columns: Int, val rows: Int) : TerminalEvent

    data class FocusChanged(val focused: Boolean) : TerminalEvent

    data class CompositionChanged(val composing: Boolean) : TerminalEvent

    data class ResetRequired(val reason: TerminalResetReason) : TerminalEvent

    data object HistoryClosed : TerminalEvent

    data class RendererGone(val didCrash: Boolean) : TerminalEvent

    data class Failure(val code: String) : TerminalEvent
}
