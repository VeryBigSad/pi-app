package io.github.verybigsad.pimobile.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.verybigsad.pimobile.model.SessionId
import io.github.verybigsad.pimobile.model.TransportPath
import io.github.verybigsad.pimobile.wire.HostConnector
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSessionControllerInstrumentedTest {
    @Test
    fun canaryFailureAfterActivationClosesExactlyOnceWithoutReopen() {
        assertStartupFailure { context, onEvent ->
            TerminalRuntime(context, forceCanaryFailure = true, onEvent = onEvent)
        }
    }

    @Test
    fun readinessFailureAfterActivationClosesExactlyOnceWithoutReopen() {
        assertStartupFailure { context, onEvent ->
            TerminalRuntime(context, forceReadyWithoutCanary = true, onEvent = onEvent)
        }
    }

    private fun assertStartupFailure(
        runtimeFactory: (android.content.Context, (TerminalEvent) -> Unit) -> TerminalRuntime,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = RecordingHost()
        lateinit var controller: TerminalSessionController
        instrumentation.runOnMainSync {
            controller = TerminalSessionController(
                context = instrumentation.targetContext,
                sessionId = SessionId("0f8fad5b-d9cb-469f-a165-70867728950e"),
                connector = { host },
                onClosed = {},
                runtimeFactory = runtimeFactory,
            )
            controller.onTerminalReady(7u, 80, 24)
            controller.runtime.createWebView()
        }

        assertTrue("terminal close timed out", host.closeLatch.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            controller.onResetRequired()
            controller.onTerminalReady(8u, 80, 24)
        }
        instrumentation.waitForIdleSync()

        assertEquals(TerminalPhase.FAILED, controller.state.value.phase)
        assertEquals(listOf(7uL), host.activated)
        assertEquals(listOf(7uL), host.deactivated)
        assertEquals(1, host.messages.count { it.type == "terminal.close" })
        assertTrue(host.messages.none { it.type == "terminal.open" })
        val close = host.messages.single { it.type == "terminal.close" }
        assertEquals("7", close.body["terminalGeneration"]?.jsonPrimitive?.content)
        assertEquals("runtime_failure", close.body["reason"]?.jsonPrimitive?.content)

        instrumentation.runOnMainSync(controller::close)
        instrumentation.waitForIdleSync()
        assertEquals(1, host.messages.count { it.type == "terminal.close" })
    }

    private class RecordingHost : HostConnector {
        override val path = TransportPath.DIRECT
        val activated = CopyOnWriteArrayList<ULong>()
        val deactivated = CopyOnWriteArrayList<ULong>()
        val messages = CopyOnWriteArrayList<SentMessage>()
        val closeLatch = CountDownLatch(1)

        override fun activateTerminalInput(terminalGeneration: ULong) {
            activated += terminalGeneration
        }

        override fun deactivateTerminalInput(terminalGeneration: ULong) {
            deactivated += terminalGeneration
        }

        override suspend fun send(type: String, body: JsonObject, replyTo: String?) {
            messages += SentMessage(type, body)
            if (type == "terminal.close") closeLatch.countDown()
        }

        override suspend fun sendTerminalInput(terminalGeneration: ULong, sequence: ULong, bytes: ByteArray) = Unit

        override suspend fun sendVoicePcm(streamId: String, sequence: Long, offset: ULong, bytes: ByteArray) = Unit

        override suspend fun close() = Unit
    }

    private data class SentMessage(val type: String, val body: JsonObject)
}
