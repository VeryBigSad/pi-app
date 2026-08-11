package io.github.verybigsad.pimobile.terminal

import android.content.Context
import android.webkit.WebView

class TerminalCanary(private val context: Context) {
    fun webViewVersion(): String? = TerminalRuntime(context) {}.webViewVersion()

    fun engineCompatible(): Boolean = TerminalRuntime(context) {}.engineCompatible()

    fun createWebView(onResult: (TerminalCanaryResult) -> Unit): WebView {
        val runtime = TerminalRuntime(context) { event ->
            if (event is TerminalEvent.Canary) onResult(event.result)
        }
        return runtime.createWebView()
    }
}
