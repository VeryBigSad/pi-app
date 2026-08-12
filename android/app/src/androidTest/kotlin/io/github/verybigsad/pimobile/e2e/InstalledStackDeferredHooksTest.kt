package io.github.verybigsad.pimobile.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.verybigsad.pimobile.model.ConnectionState
import io.github.verybigsad.pimobile.model.SessionId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledStackDeferredHooksTest {
    @Test
    fun pushWakeHook() = selectedHook("push") { bridge ->
        await("E2E_PUSH_HOOK_PREREQUISITE_MISSING") {
            bridge.state.value.connection is ConnectionState.Ready &&
                bridge.state.value.authentication != null
        }
        val syncingObserved = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bridge.state.collect { state ->
                if (state.syncing) syncingObserved.set(true)
            }
        }
        try {
            bridge.injectOpaqueWake(OPAQUE_WAKE)
            await("E2E_PUSH_RECONNECT_NOT_OBSERVED") { syncingObserved.get() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun voiceDictationHook() = selectedHook("voice") { bridge ->
        val sessionId = selectedSessionId()
        await("E2E_VOICE_HOOK_PREREQUISITE_MISSING") {
            bridge.state.value.connection is ConnectionState.Ready &&
                bridge.state.value.sessions.containsKey(SessionId(sessionId))
        }
        val before = bridge.state.value.sessions.getValue(SessionId(sessionId)).conversation.finalizedMessages.size
        bridge.injectSyntheticGroqTranscript(sessionId, SYNTHETIC_GROQ_TEXT)
        await("E2E_VOICE_TRANSCRIPT_NOT_DELIVERED") {
            bridge.state.value.sessions[SessionId(sessionId)]?.draft?.transcriptionText == SYNTHETIC_GROQ_TEXT
        }
        val after = bridge.state.value.sessions.getValue(SessionId(sessionId)).conversation.finalizedMessages.size
        assertEquals("E2E_VOICE_AUTO_SENT", before, after)
    }

    @Test
    fun externalDistributorGate() = selectedHook("external-push") {
        fail("E2E_EXTERNAL_PUSH_DISTRIBUTOR_REQUIRED")
    }

    private fun bridge(): InstalledStackE2eBridge = InstalledStackE2eBridge.from(
        InstrumentationRegistry.getInstrumentation().targetContext,
    )

    private fun requireSelected(hook: String) {
        val selected = InstrumentationRegistry.getArguments().getString(HOOKS_ARGUMENT)
            ?.split(',')
            ?.filter(String::isNotBlank)
            ?.toSet()
            ?: emptySet()
        assumeTrue("SKIP[E2E_HOOK_NOT_SELECTED_$hook]", hook in selected)
    }

    private fun selectedHook(hook: String, block: (InstalledStackE2eBridge) -> Unit) {
        requireSelected(hook)
        val bridge = bridge()
        try {
            block(bridge)
            bridge.recordHookResult(hook, passed = true)
        } catch (error: Throwable) {
            bridge.recordHookResult(hook, passed = false, failureCode = hookFailureCode(error))
            throw error
        }
    }

    private fun hookFailureCode(error: Throwable): String = error.message
        ?.takeIf { E2E_CODE.matches(it) }
        ?: "E2E_HOOK_FAILED"

    private fun selectedSessionId(): String {
        val sessionId = InstrumentationRegistry.getArguments().getString(SESSION_ID_ARGUMENT)
        assertTrue("E2E_HOOK_SESSION_INVALID", sessionId != null && UUID_V4.matches(sessionId))
        return requireNotNull(sessionId)
    }

    private fun await(code: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + HOOK_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(25)
        }
        fail(code)
    }

    private fun fail(code: String): Nothing = throw AssertionError(code)

    private companion object {
        const val HOOKS_ARGUMENT = "e2eHooks"
        const val SESSION_ID_ARGUMENT = "e2eSessionId"
        const val HOOK_TIMEOUT_MILLIS = 15_000L
        const val OPAQUE_WAKE = "ABCDEFGHIJKLMNOPQRSTUV"
        const val SYNTHETIC_GROQ_TEXT = "PI_E2E_GROQ_SYNTHETIC"
        val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val E2E_CODE = Regex("^E2E_[A-Z0-9_]{1,96}$")
    }
}
