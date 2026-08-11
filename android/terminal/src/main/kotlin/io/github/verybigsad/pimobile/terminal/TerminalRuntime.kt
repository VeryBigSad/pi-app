package io.github.verybigsad.pimobile.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Base64
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import org.json.JSONObject

internal const val TerminalAssetOrigin = "https://appassets.androidplatform.net"
internal const val TerminalAssetUrl = "$TerminalAssetOrigin/assets/terminal/index.html"
internal const val MinimumTerminalWebViewMajor = 91
private val TerminalAssetUri = TerminalAssetOrigin.toUri()
private const val MaximumControlMessageCharacters = 1_500_000
private const val MaximumSyntheticInputBytes = 1_048_576
private val CanonicalUint64Pattern = Regex("^(0|[1-9][0-9]{0,19})$")
private val CanonicalBase64Pattern = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")

class TerminalRuntime(
    private val context: Context,
    private val forceCanaryFailure: Boolean = false,
    private val onEvent: (TerminalEvent) -> Unit,
) {
    private val sequenceState = TerminalSequenceState()
    private val pendingOutput = ArrayDeque<ByteArray>()
    private var pendingOutputBytes = 0
    private var pageReady = false
    private var canaryPassed = false
    private var webView: WebView? = null
    private var columns: Int? = null
    private var rows: Int? = null
    private var lastGenerationMetadata: ULong? = null

    fun webViewVersion(): String? = WebViewCompat.getCurrentWebViewPackage(context)?.versionName

    fun engineCompatible(): Boolean = terminalEngineCompatible(
        version = webViewVersion(),
        hasMessageListener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
        hasPostMessage = WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE),
    )

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun createWebView(): WebView {
        checkMainThread()
        check(webView == null)
        val version = webViewVersion()
        if (!engineCompatible()) {
            onEvent(
                TerminalEvent.Canary(
                    TerminalCanaryResult(false, version, "WEBVIEW_UPDATE_REQUIRED", null, null),
                ),
            )
            return createRefusedWebView().also { webView = it }
        }

        WebView.setWebContentsDebuggingEnabled(false)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        val view = WebView(context)
        webView = view
        view.contentDescription = "Pi terminal"
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.removeJavascriptInterface("searchBoxJavaBridge_")
        view.removeJavascriptInterface("accessibility")
        view.removeJavascriptInterface("accessibilityTraversal")
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
            setGeolocationEnabled(false)
        }
        view.setDownloadListener { _, _, _, _, _ -> onEvent(TerminalEvent.Failure("TERMINAL_DOWNLOAD_BLOCKED")) }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                if (uri.scheme == "https" &&
                    uri.host == "appassets.androidplatform.net" &&
                    uri.path?.startsWith("/assets/terminal/") == true
                ) {
                    return loader.shouldInterceptRequest(uri) ?: blockedResponse(404)
                }
                return blockedResponse(403)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) onEvent(TerminalEvent.Failure("TERMINAL_ASSET_LOAD_FAILED"))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                pageReady = false
                canaryPassed = false
                sequenceState.disconnect()
                clearPendingOutput()
                onEvent(TerminalEvent.ResetRequired(TerminalResetReason.RENDERER_GONE))
                onEvent(TerminalEvent.RendererGone(detail.didCrash()))
                this@TerminalRuntime.webView = null
                view.post(view::destroy)
                return true
            }
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            onEvent(TerminalEvent.Failure("TERMINAL_MESSAGE_FEATURE_UNAVAILABLE"))
            return view
        }
        WebViewCompat.addWebMessageListener(
            view,
            "pimobile",
            setOf(TerminalAssetOrigin),
        ) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || sourceOrigin != TerminalAssetUri) {
                onEvent(TerminalEvent.Failure("TERMINAL_MESSAGE_ORIGIN_REJECTED"))
                return@addWebMessageListener
            }
            when (message.type) {
                WebMessageCompat.TYPE_ARRAY_BUFFER -> handleInputPacket(message.arrayBuffer)
                WebMessageCompat.TYPE_STRING -> handleControlMessage(message.data)
                else -> onEvent(TerminalEvent.Failure("TERMINAL_MESSAGE_INVALID"))
            }
        }
        view.loadUrl(if (forceCanaryFailure) "$TerminalAssetUrl?forceCanaryFailure=1" else TerminalAssetUrl)
        return view
    }

    fun startGeneration(generation: ULong) {
        checkMainThread()
        require(lastGenerationMetadata != generation)
        clearPendingOutput()
        sequenceState.begin(generation)
        lastGenerationMetadata = generation
        if (pageReady) {
            postControl(generationCommand(generation, connected = true))
        }
    }

    fun writeOutput(generation: ULong, sequence: ULong, bytes: ByteArray): TerminalWriteResult {
        checkMainThread()
        if (bytes.size > MaximumTerminalDataBytes) return TerminalWriteResult.REJECTED_TOO_LARGE
        if (!sequenceState.connected) return TerminalWriteResult.REJECTED_NOT_CONNECTED
        if (sequenceState.generation != generation) {
            rejectSequence(TerminalResetReason.OUTPUT_GENERATION_MISMATCH)
            return TerminalWriteResult.REJECTED_GENERATION
        }
        val packet = TerminalBridgePacket.encode(generation, sequence, bytes)
        if (!pageReady && pendingOutputBytes + packet.size > MaximumPendingTerminalBytes) {
            return TerminalWriteResult.REJECTED_TOO_LARGE
        }
        when (sequenceState.acceptOutput(generation, sequence)) {
            SequenceDecision.ACCEPTED -> Unit
            SequenceDecision.EXHAUSTED -> {
                rejectSequence(TerminalResetReason.OUTPUT_SEQUENCE_EXHAUSTED)
                return TerminalWriteResult.REJECTED_SEQUENCE
            }
            else -> {
                rejectSequence(TerminalResetReason.OUTPUT_SEQUENCE_GAP)
                return TerminalWriteResult.REJECTED_SEQUENCE
            }
        }
        if (!pageReady) {
            pendingOutput += packet
            pendingOutputBytes += packet.size
            return TerminalWriteResult.QUEUED_FOR_PAGE
        }
        return if (postBinary(packet)) {
            TerminalWriteResult.POSTED_TO_WEBVIEW
        } else {
            sequenceState.disconnect()
            TerminalWriteResult.REJECTED_BRIDGE
        }
    }

    fun disconnect() {
        checkMainThread()
        sequenceState.disconnect()
        clearPendingOutput()
        if (pageReady) {
            postControl(JSONObject().put("type", "terminal.connection").put("connected", false))
        }
    }

    fun paste(text: String): Boolean {
        checkMainThread()
        if (!sequenceState.connected || text.toByteArray(Charsets.UTF_8).size > MaximumSyntheticInputBytes) return false
        return postControl(JSONObject().put("type", "terminal.paste").put("text", text))
    }

    fun sendKey(data: String): Boolean {
        checkMainThread()
        if (!sequenceState.connected || data.toByteArray(Charsets.UTF_8).size > 4_096) return false
        return postControl(JSONObject().put("type", "terminal.key").put("data", data))
    }

    fun focus(): Boolean {
        checkMainThread()
        return pageReady && postControl(JSONObject().put("type", "terminal.focus"))
    }

    fun showHistory(snapshot: TerminalHistorySnapshot): Boolean {
        checkMainThread()
        snapshot.validate()
        if (!sequenceState.connected || sequenceState.generation != snapshot.terminalGeneration) return false
        return postControl(
            JSONObject()
                .put("type", "terminal.history")
                .put("generation", snapshot.terminalGeneration.toString())
                .put("capturedAt", snapshot.capturedAt)
                .put("text", snapshot.text)
                .put("truncatedLines", snapshot.truncatedLines)
                .put("truncatedBytes", snapshot.truncatedBytes),
        )
    }

    fun closeHistory(): Boolean {
        checkMainThread()
        return pageReady && postControl(JSONObject().put("type", "terminal.history.close"))
    }

    fun saveState(): TerminalSavedState = TerminalSavedState(
        lastGeneration = sequenceState.generation ?: lastGenerationMetadata,
        columns = columns,
        rows = rows,
        wasConnected = sequenceState.connected,
    )

    fun restoreState(savedState: TerminalSavedState) {
        checkMainThread()
        sequenceState.clear()
        clearPendingOutput()
        lastGenerationMetadata = savedState.lastGeneration
        columns = savedState.columns
        rows = savedState.rows
        if (pageReady) {
            postControl(
                JSONObject()
                    .put("type", "terminal.restored")
                    .put("requiresReconnect", true)
                    .put("screenRestored", false)
                    .put("scrollbackRestored", false),
            )
        }
    }

    fun destroy() {
        checkMainThread()
        val view = webView ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(view, "pimobile")
        }
        pageReady = false
        sequenceState.clear()
        clearPendingOutput()
        webView = null
        canaryPassed = false
        view.destroy()
    }

    private fun handleInputPacket(packet: ByteArray) {
        val input = runCatching { TerminalBridgePacket.decode(packet) }.getOrElse {
            onEvent(TerminalEvent.Failure("TERMINAL_BINARY_MESSAGE_INVALID"))
            rejectSequence(TerminalResetReason.INPUT_SEQUENCE_GAP)
            return
        }
        when (sequenceState.acceptInput(input.terminalGeneration, input.sequence)) {
            SequenceDecision.ACCEPTED -> onEvent(TerminalEvent.Input(input))
            SequenceDecision.GENERATION_MISMATCH, SequenceDecision.NO_GENERATION ->
                rejectSequence(TerminalResetReason.INPUT_GENERATION_MISMATCH)
            SequenceDecision.EXHAUSTED ->
                rejectSequence(TerminalResetReason.INPUT_SEQUENCE_EXHAUSTED)
            SequenceDecision.DUPLICATE, SequenceDecision.GAP ->
                rejectSequence(TerminalResetReason.INPUT_SEQUENCE_GAP)
        }
    }

    private fun handleControlMessage(data: String?) {
        if (data == null || data.length > MaximumControlMessageCharacters) {
            onEvent(TerminalEvent.Failure("TERMINAL_CONTROL_MESSAGE_INVALID"))
            return
        }
        val value = runCatching { JSONObject(data) }.getOrNull()
        when (value?.optString("type")) {
            "terminal.ready" -> {
                if (pageReady) return
                if (!value.optBoolean("canaryOk", false) || !canaryPassed) {
                    onEvent(TerminalEvent.Failure("TERMINAL_READY_WITHOUT_CANARY"))
                    return
                }
                pageReady = true
                sequenceState.generation?.let { generation ->
                    postControl(generationCommand(generation, sequenceState.connected))
                }
                drainPendingOutput()
                onEvent(TerminalEvent.PageReady)
            }
            "terminal.input" -> handleBase64Input(value)
            "terminal.canary" -> {
                val result = TerminalCanaryResult(
                    compatible = value.optBoolean("ok", false),
                    webViewVersion = webViewVersion(),
                    reason = value.optString("error").ifBlank { null },
                    columns = value.optInt("cols").takeIf { it > 0 },
                    rows = value.optInt("rows").takeIf { it > 0 },
                )
                canaryPassed = result.compatible
                onEvent(TerminalEvent.Canary(result))
            }
            "terminal.resize" -> {
                val nextColumns = value.optInt("cols")
                val nextRows = value.optInt("rows")
                if (nextColumns !in 2..1_000 || nextRows !in 1..1_000) {
                    onEvent(TerminalEvent.Failure("TERMINAL_RESIZE_INVALID"))
                } else {
                    columns = nextColumns
                    rows = nextRows
                    onEvent(TerminalEvent.Resize(nextColumns, nextRows))
                }
            }
            "terminal.focus" -> onEvent(TerminalEvent.FocusChanged(value.optBoolean("focused")))
            "terminal.composition" -> onEvent(TerminalEvent.CompositionChanged(value.optBoolean("composing")))
            "terminal.history.closed" -> onEvent(TerminalEvent.HistoryClosed)
            "terminal.resetRequired" -> rejectSequence(TerminalResetReason.WEB_RUNTIME_SEQUENCE_GAP)
            else -> onEvent(TerminalEvent.Failure("TERMINAL_CONTROL_MESSAGE_INVALID"))
        }
    }

    private fun drainPendingOutput() {
        while (pendingOutput.isNotEmpty()) {
            val packet = pendingOutput.removeFirst()
            pendingOutputBytes -= packet.size
            if (!postBinary(packet)) {
                sequenceState.disconnect()
                clearPendingOutput()
                return
            }
        }
    }

    private fun postBinary(packet: ByteArray): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return false
        val view = webView ?: return false
        val message = if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            WebMessageCompat(packet)
        } else {
            val decoded = TerminalBridgePacket.decode(packet)
            WebMessageCompat(
                JSONObject()
                    .put("type", "terminal.output")
                    .put("generation", decoded.terminalGeneration.toString())
                    .put("sequence", decoded.sequence.toString())
                    .put("bytes", Base64.encodeToString(decoded.bytes, Base64.NO_WRAP))
                    .toString(),
            )
        }
        return runCatching {
            WebViewCompat.postWebMessage(view, message, TerminalAssetUri)
        }.onFailure {
            onEvent(TerminalEvent.Failure("TERMINAL_BRIDGE_POST_FAILED"))
        }.isSuccess
    }

    private fun handleBase64Input(value: JSONObject) {
        val generationText = value.optString("generation")
        val sequenceText = value.optString("sequence")
        val generation = generationText.takeIf(CanonicalUint64Pattern::matches)?.toULongOrNull()
        val sequence = sequenceText.takeIf(CanonicalUint64Pattern::matches)?.toULongOrNull()
        val encoded = value.optString("bytes")
        if (generation == null || sequence == null ||
            encoded.length > 1_398_080 || encoded.length % 4 != 0 ||
            !CanonicalBase64Pattern.matches(encoded)
        ) {
            onEvent(TerminalEvent.Failure("TERMINAL_BINARY_MESSAGE_INVALID"))
            rejectSequence(TerminalResetReason.INPUT_SEQUENCE_GAP)
            return
        }
        val bytes = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
        if (bytes == null || bytes.size > MaximumTerminalDataBytes) {
            onEvent(TerminalEvent.Failure("TERMINAL_BINARY_MESSAGE_INVALID"))
            rejectSequence(TerminalResetReason.INPUT_SEQUENCE_GAP)
            return
        }
        handleInputPacket(TerminalBridgePacket.encode(generation, sequence, bytes))
    }

    private fun generationCommand(generation: ULong, connected: Boolean): JSONObject = JSONObject()
        .put("type", "terminal.generation")
        .put("generation", generation.toString())
        .put("connected", connected)
        .put(
            "arrayBufferBridge",
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER),
        )

    private fun postControl(value: JSONObject): Boolean {
        if (!pageReady && value.optString("type") != "terminal.generation") return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE)) return false
        val view = webView ?: return false
        return runCatching {
            WebViewCompat.postWebMessage(view, WebMessageCompat(value.toString()), TerminalAssetUri)
        }.onFailure {
            onEvent(TerminalEvent.Failure("TERMINAL_BRIDGE_POST_FAILED"))
        }.isSuccess
    }

    private fun rejectSequence(reason: TerminalResetReason) {
        sequenceState.disconnect()
        clearPendingOutput()
        if (pageReady) postControl(JSONObject().put("type", "terminal.connection").put("connected", false))
        onEvent(TerminalEvent.ResetRequired(reason))
    }

    private fun clearPendingOutput() {
        pendingOutput.clear()
        pendingOutputBytes = 0
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper())
    }

    private fun createRefusedWebView(): WebView = WebView(context).apply {
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                blockedResponse(403)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
    }

    private fun blockedResponse(statusCode: Int): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        statusCode,
        if (statusCode == 404) "Not Found" else "Forbidden",
        mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'none'",
        ),
        ByteArrayInputStream(ByteArray(0)),
    )
}
