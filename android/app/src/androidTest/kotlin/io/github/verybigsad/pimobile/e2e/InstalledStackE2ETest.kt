package io.github.verybigsad.pimobile.e2e

import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.verybigsad.pimobile.MainActivity
import io.github.verybigsad.pimobile.model.CanonicalAvailability
import io.github.verybigsad.pimobile.model.FinalizedMessage
import io.github.verybigsad.pimobile.model.MessageContentKind
import io.github.verybigsad.pimobile.model.MessageRole
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.terminal.TerminalPhase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.hamcrest.Matchers.containsString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class InstalledStackE2ETest {
    private val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(ExplicitInstalledStackInvocationRule())
        .around(rule)

    @Test
    fun freshInstallPairsUnlocksSyncsAndExercisesInstalledStack() {
        val bridge = InstalledStackE2eBridge.from(rule.activity)
        try {
            awaitState("E2E_PAIRING_LANDING_TIMEOUT") { bridge.state.value.hydrated }
            requireUi("E2E_PAIRING_LANDING_MISSING") {
                rule.onNodeWithText("Your Mac stays in control").assertIsDisplayed()
                rule.onNodeWithText("Pair a Mac").assertIsDisplayed()
            }
            captureNode("pairing-title.png", "Your Mac stays in control")

            val config = readOneUseConfig()
            bridge.acceptOneUseInvitation(config.invitationUri)
            awaitState("E2E_PAIRING_TIMEOUT", config.pairingTimeoutMillis) {
                bridge.state.value.trust is TrustState.Trusted && bridge.state.value.pairing == null
            }
            awaitState("E2E_UNLOCK_NOT_READY", config.syncTimeoutMillis) {
                bridge.state.value.connection is ConnectionState.DeviceAuthenticated
            }
            val unlockClicked = runCatching {
                rule.onNodeWithText("Unlock with passkey").performClick()
            }.isSuccess
            if (!unlockClicked) bridge.requestAuthentication()
            awaitState("E2E_SYNC_TIMEOUT", config.syncTimeoutMillis) {
                val state = bridge.state.value
                state.connection is ConnectionState.Ready &&
                    state.authentication != null &&
                    !state.syncing &&
                    !state.resyncPending &&
                    state.sessions.keys.map { it.value }.toSet() == config.expectedSessionIds.toSet() &&
                    state.sessions.values.all { it.conversation.availability is CanonicalAvailability.Current }
            }
            assertCanonicalState(bridge, config)

            val knownSession = requireNotNull(bridge.state.value.sessions[SessionId(config.knownSessionId)])
            requireUi("E2E_SESSION_ROW_MISSING") {
                rule.onNode(sessionRow(knownSession.metadata.displayName)).performClick()
            }
            awaitState("E2E_SESSION_OPEN_TIMEOUT") {
                bridge.state.value.selectedSessionId?.value == config.knownSessionId
            }
            assertTrue("E2E_KNOWN_CONTENT_STATE_MISSING", knownSessionContains(bridge, config.knownSessionId, config.knownContent))
            assertTimelineTextEventually(config.knownContent, "E2E_KNOWN_CONTENT_UI_MISSING")

            val before = requireNotNull(bridge.state.value.sessions[SessionId(config.knownSessionId)])
            val baselineOrdinal = before.conversation.finalizedMessages.maxOfOrNull { it.appendOrdinal } ?: -1L
            val baselineCursor = requireNotNull(before.conversation.cursor)
            val replyWatch = bridge.beginFinalReplyWatch(config.knownSessionId)
            val observedReplyFailure = AtomicReference<String?>()
            val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
                bridge.state.collect { state ->
                    replyWatch.failureCode(state)?.let { observedReplyFailure.compareAndSet(null, it) }
                }
            }
            try {
                requireUi("E2E_COMPOSER_INPUT_FAILED") {
                    rule.onNodeWithContentDescription("Typed message draft").performTextInput(config.prompt)
                    rule.onNodeWithText("Send").performClick()
                }
                awaitFinalReply(bridge, replyWatch, observedReplyFailure, baselineOrdinal, config)
            } finally {
                observerScope.cancel()
            }
            val after = requireNotNull(bridge.state.value.sessions[SessionId(config.knownSessionId)])
            assertTrue("E2E_CANONICAL_STATE_LOST", after.conversation.availability is CanonicalAvailability.Current)
            assertTrue("E2E_CURSOR_DID_NOT_ADVANCE", requireNotNull(after.conversation.cursor).sequence > baselineCursor.sequence)
            assertFinalReplyCollision(after.conversation.finalizedMessages, baselineOrdinal, config)
            assertTimelineTextEventually(
                config.expectedReply,
                "E2E_FINAL_REPLY_UI_MISSING",
                "Pi. Final message. Long press for message actions",
            )

            bridge.openTerminal(config.knownSessionId)
            awaitState("E2E_TERMINAL_OPEN_TIMEOUT", config.terminalTimeoutMillis) {
                bridge.state.value.terminalSessionId?.value == config.knownSessionId &&
                    bridge.terminalController?.state?.value?.phase == TerminalPhase.READY
            }
            awaitUi("E2E_TERMINAL_VIEW_MISSING", config.terminalTimeoutMillis) {
                onView(withContentDescription("Pi terminal")).check(matches(isDisplayed()))
            }
            awaitState("E2E_TERMINAL_CANARY_TIMEOUT", config.terminalTimeoutMillis) {
                var focused = false
                rule.activity.runOnUiThread {
                    focused = bridge.terminalController?.runtime?.focus() == true
                }
                focused
            }
            var inputAccepted = false
            rule.activity.runOnUiThread {
                inputAccepted = bridge.terminalController?.runtime?.paste(config.terminalCanary) == true
            }
            assertTrue("E2E_TERMINAL_INPUT_REJECTED", inputAccepted)
            Thread.sleep(1_500)
            rule.activity.runOnUiThread { bridge.terminalController?.requestHistory() }
            awaitState("E2E_TERMINAL_HISTORY_TIMEOUT", config.terminalTimeoutMillis) {
                bridge.terminalController?.state?.value?.historyOpen == true
            }
            awaitWebText(config.terminalCanary, config.terminalTimeoutMillis)

            bridge.closeTerminal()
            awaitState("E2E_TERMINAL_CLOSE_TIMEOUT") { bridge.state.value.terminalSessionId == null }
            requireUi("E2E_AGENTS_ACTION_MISSING") {
                rule.onNodeWithContentDescription("Open agents insight").performClick()
            }
            awaitState("E2E_AGENTS_OPEN_TIMEOUT") { bridge.state.value.agentsOpen }
            assertFalse("E2E_AGENTS_OFFLINE", bridge.agentsState.value.isOffline)
            requireUi("E2E_AGENTS_STATE_MISSING") {
                rule.onNodeWithTag("agents-list").assertIsDisplayed()
                rule.onNodeWithText("Agents").assertIsDisplayed()
            }
            captureNode("agents-title.png", "Agents")
        } finally {
            bridge.closeTerminal()
        }
    }

    private fun assertCanonicalState(bridge: InstalledStackE2eBridge, config: InstalledStackConfig) {
        val state = bridge.state.value
        assertTrue("E2E_UNLOCK_FAILED", state.authentication != null)
        assertFalse("E2E_REFRESH_STILL_RUNNING", state.syncing)
        assertFalse("E2E_RESYNC_STILL_PENDING", state.resyncPending)
        assertEquals("E2E_SESSION_SET_MISMATCH", config.expectedSessionIds.toSet(), state.sessions.keys.map { it.value }.toSet())
        assertTrue("E2E_CANONICAL_SESSION_MISSING", state.sessions.values.all {
            it.conversation.availability is CanonicalAvailability.Current
        })
    }

    private fun knownSessionContains(bridge: InstalledStackE2eBridge, sessionId: String, text: String): Boolean =
        bridge.state.value.sessions[SessionId(sessionId)]?.conversation?.finalizedMessages?.any {
            messageText(it).contains(text)
        } == true

    private fun messageText(message: FinalizedMessage): String = message.content
        .filter { it.kind == MessageContentKind.TEXT }
        .joinToString("\n") { content ->
            runCatching { JSONObject(content.projection).optString("text", content.projection) }
                .getOrDefault(content.projection)
        }

    private fun sessionRow(displayName: String): SemanticsMatcher = SemanticsMatcher("installed-stack session row") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.startsWith("$displayName.") } == true
    }

    private fun awaitState(code: String, timeoutMillis: Long = 30_000, predicate: () -> Boolean) {
        val success = runCatching { rule.waitUntil(timeoutMillis = timeoutMillis, condition = predicate) }.isSuccess
        if (!success) e2eFail(code)
    }

    private fun assertFinalReplyCollision(
        messages: List<FinalizedMessage>,
        baselineOrdinal: Long,
        config: InstalledStackConfig,
    ) {
        val newMessages = messages.filter { it.appendOrdinal > baselineOrdinal }
        assertTrue("E2E_FINAL_REPLY_USER_PROMPT_MISSING", newMessages.any {
            it.role == MessageRole.USER && messageText(it).contains(config.prompt)
        })
        assertTrue("E2E_FINAL_REPLY_ASSISTANT_MISMATCH", newMessages.any {
            it.role == MessageRole.ASSISTANT && messageText(it) == config.expectedReply
        })
    }

    private fun awaitFinalReply(
        bridge: InstalledStackE2eBridge,
        watch: InstalledStackReplyWatch,
        observedFailure: AtomicReference<String?>,
        baselineOrdinal: Long,
        config: InstalledStackConfig,
    ) {
        val deadline = System.currentTimeMillis() + config.replyTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            observedFailure.get()?.let(::e2eFail)
            bridge.finalReplyFailureCode(watch)?.let(::e2eFail)
            val conversation = bridge.state.value.sessions[SessionId(config.knownSessionId)]?.conversation
            if (conversation != null && conversation.finalizedMessages.any {
                    it.role == MessageRole.ASSISTANT &&
                        it.appendOrdinal > baselineOrdinal &&
                        messageText(it).contains(config.expectedReply)
                }
            ) return
            Thread.sleep(25)
        }
        observedFailure.get()?.let(::e2eFail)
        bridge.finalReplyFailureCode(watch)?.let(::e2eFail)
        e2eFail("E2E_FINAL_REPLY_TIMEOUT")
    }

    private fun assertTimelineTextEventually(text: String, code: String, contentDescription: String? = null) {
        val matcher = hasText(text, substring = true).let { textMatcher ->
            contentDescription?.let { textMatcher and hasContentDescription(it) } ?: textMatcher
        }
        val deadline = System.currentTimeMillis() + TIMELINE_TEXT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching {
                    rule.onNodeWithContentDescription("Session timeline").performScrollToNode(matcher)
                    rule.onNode(matcher).assertIsDisplayed()
                }.isSuccess
            ) return
            Thread.sleep(TIMELINE_TEXT_RETRY_MILLIS)
        }
        e2eFail(code)
    }

    private fun requireUi(code: String, operation: () -> Unit) {
        if (runCatching(operation).isFailure) e2eFail(code)
    }

    private fun awaitUi(code: String, timeoutMillis: Long, operation: () -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(operation).isSuccess) return
            Thread.sleep(TIMELINE_TEXT_RETRY_MILLIS)
        }
        e2eFail(code)
    }

    private fun awaitWebText(text: String, timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching {
                onWebView(withContentDescription("Pi terminal"))
                    .withElement(findElement(Locator.ID, "terminal-history-content"))
                    .check(webMatches(getText(), containsString(text)))
            }.isSuccess
            if (found) return
            Thread.sleep(250)
        }
        e2eFail("E2E_TERMINAL_CANARY_OUTPUT_MISSING")
    }

    private fun captureNode(fileName: String, text: String) {
        val bitmap = runCatching { rule.onNodeWithText(text).captureToImage().asAndroidBitmap() }.getOrNull()
            ?: e2eFail("E2E_SCREENSHOT_CAPTURE_FAILED")
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "e2e")
        assertTrue("E2E_SCREENSHOT_DIRECTORY_FAILED", directory.mkdirs() || directory.isDirectory)
        File(directory, fileName).outputStream().use { stream ->
            assertTrue("E2E_SCREENSHOT_WRITE_FAILED", bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }

    private fun readOneUseConfig(): InstalledStackConfig {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val token = arguments.getString(RUN_TOKEN_ARGUMENT)
            ?.takeIf { RUN_TOKEN.matches(it) }
            ?: e2eFail("E2E_CHANNEL_TOKEN_INVALID")
        val expectedPath = "/data/local/tmp/pi-mobile-e2e-$token/config"
        val path = arguments.getString(CHANNEL_PATH_ARGUMENT)
            ?.takeIf { it == expectedPath && ONE_USE_PATH.matches(it) }
            ?: e2eFail("E2E_CHANNEL_PATH_INVALID")
        val consumed = "$path.consumed"
        shellReadBounded(instrumentation, "/system/bin/mv $path $consumed", MAX_SHELL_CONTROL_BYTES)
        val sourceAfterMove = shellReadBounded(instrumentation, "/system/bin/ls $path", MAX_SHELL_CONTROL_BYTES)
        val consumedAfterMove = shellReadBounded(instrumentation, "/system/bin/ls $consumed", MAX_SHELL_CONTROL_BYTES)
        if (sourceAfterMove.isNotEmpty() || consumedAfterMove.decodeToString().trim() != consumed) {
            shellReadBounded(instrumentation, "/system/bin/rm -f $path", MAX_SHELL_CONTROL_BYTES)
            shellReadBounded(instrumentation, "/system/bin/rm -f $consumed", MAX_SHELL_CONTROL_BYTES)
            e2eFail("E2E_CHANNEL_CONSUME_FAILED")
        }
        val rawBytes = try {
            shellReadBounded(instrumentation, "/system/bin/cat $consumed", MAX_CONFIG_BYTES)
        } finally {
            shellReadBounded(instrumentation, "/system/bin/rm -f $consumed", MAX_SHELL_CONTROL_BYTES)
            shellReadBounded(instrumentation, "/system/bin/rm -f $path", MAX_SHELL_CONTROL_BYTES)
        }
        val residue = shellReadBounded(instrumentation, "/system/bin/ls $path $consumed", MAX_SHELL_CONTROL_BYTES)
        if (residue.isNotEmpty()) e2eFail("E2E_CHANNEL_DELETE_FAILED")
        if (rawBytes.isEmpty()) e2eFail("E2E_CHANNEL_EMPTY")
        val raw = runCatching { rawBytes.decodeToString(throwOnInvalidSequence = true) }
            .getOrElse { e2eFail("E2E_CHANNEL_UTF8_INVALID") }
        return InstalledStackConfig.parse(raw)
    }
}

private class ExplicitInstalledStackInvocationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val arguments = InstrumentationRegistry.getArguments()
            val path = arguments.getString(CHANNEL_PATH_ARGUMENT)?.takeIf(String::isNotBlank)
            val token = arguments.getString(RUN_TOKEN_ARGUMENT)?.takeIf(String::isNotBlank)
            assumeTrue("SKIP[E2E_EXPLICIT_INVOCATION_REQUIRED]", path != null && token != null)
            val requiredPath = requireNotNull(path)
            val requiredToken = requireNotNull(token)
            if (requiredPath != "/data/local/tmp/pi-mobile-e2e-$requiredToken/config" || !RUN_TOKEN.matches(requiredToken)) {
                e2eFail("E2E_INVOCATION_ARGS_INVALID")
            }
            base.evaluate()
        }
    }

}

private const val CHANNEL_PATH_ARGUMENT = "e2eChannelPath"
private const val RUN_TOKEN_ARGUMENT = "e2eRunToken"
private const val MAX_CONFIG_BYTES = 64 * 1024
private const val MAX_SHELL_CONTROL_BYTES = 4 * 1024
private const val TIMELINE_TEXT_TIMEOUT_MILLIS = 10_000L
private const val TIMELINE_TEXT_RETRY_MILLIS = 100L
private val RUN_TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
private val ONE_USE_PATH = Regex("^/data/local/tmp/pi-mobile-e2e-[A-Za-z0-9_-]{43}/config$")

private fun shellReadBounded(
    instrumentation: android.app.Instrumentation,
    command: String,
    limit: Int,
): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(
    instrumentation.uiAutomation.executeShellCommand(command),
).use { readBounded(it, limit) }

private fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val chunk = ByteArray(4_096)
    while (true) {
        val count = input.read(chunk)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) e2eFail("E2E_CHANNEL_PAYLOAD_TOO_LARGE")
        output.write(chunk, 0, count)
    }
}

private fun e2eFail(code: String): Nothing = throw AssertionError(code)

private data class InstalledStackConfig(
    val invitationUri: String,
    val expectedSessionIds: List<String>,
    val knownSessionId: String,
    val knownContent: String,
    val prompt: String,
    val expectedReply: String,
    val terminalCanary: String,
    val pairingTimeoutMillis: Long,
    val syncTimeoutMillis: Long,
    val replyTimeoutMillis: Long,
    val terminalTimeoutMillis: Long,
) {
    companion object {
        fun parse(raw: String): InstalledStackConfig {
            val value = runCatching { JSONObject(raw) }.getOrNull() ?: e2eFail("E2E_CHANNEL_JSON_INVALID")
            val sessions = value.optJSONArray("expectedSessionIds") ?: e2eFail("E2E_CHANNEL_SESSIONS_INVALID")
            if (sessions.length() !in 1..1_024) e2eFail("E2E_CHANNEL_SESSIONS_INVALID")
            val sessionIds = (0 until sessions.length()).map { index ->
                (sessions.opt(index) as? String)
                    ?.takeIf { it.isNotBlank() && it.length <= 512 }
                    ?: e2eFail("E2E_CHANNEL_SESSIONS_INVALID")
            }
            return InstalledStackConfig(
                invitationUri = value.requiredString("invitationUri"),
                expectedSessionIds = sessionIds,
                knownSessionId = value.requiredString("knownSessionId"),
                knownContent = value.requiredString("knownContent"),
                prompt = value.requiredString("prompt"),
                expectedReply = value.requiredString("expectedReply"),
                terminalCanary = value.requiredString("terminalCanary"),
                pairingTimeoutMillis = value.timeout("pairingTimeoutMillis", 180_000),
                syncTimeoutMillis = value.timeout("syncTimeoutMillis", 120_000),
                replyTimeoutMillis = value.timeout("replyTimeoutMillis", 180_000),
                terminalTimeoutMillis = value.timeout("terminalTimeoutMillis", 60_000),
            ).also {
                if (it.knownSessionId !in it.expectedSessionIds) e2eFail("E2E_CHANNEL_KNOWN_SESSION_INVALID")
            }
        }

        private fun JSONObject.requiredString(key: String): String =
            (opt(key) as? String)
                ?.takeIf { it.isNotBlank() && it.length <= 8_192 }
                ?: e2eFail("E2E_CHANNEL_FIELD_INVALID")

        private fun JSONObject.timeout(key: String, fallback: Long): Long {
            val raw = if (has(key)) opt(key) as? Number ?: e2eFail("E2E_CHANNEL_TIMEOUT_INVALID") else fallback
            val timeout = raw.toLong()
            if (raw.toDouble() != timeout.toDouble() || timeout !in 1_000..600_000) {
                e2eFail("E2E_CHANNEL_TIMEOUT_INVALID")
            }
            return timeout
        }
    }
}
