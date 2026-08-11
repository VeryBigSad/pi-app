package io.github.verybigsad.pimobile.terminal

import android.os.Build
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalRuntimeInstrumentedTest {
    @Test
    fun api29WebViewCanaryAndArrayBufferInputPass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val canary = AtomicReference<TerminalCanaryResult>()
        val input = AtomicReference<TerminalInput>()
        val canaryLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        val inputLatch = CountDownLatch(1)
        lateinit var runtime: TerminalRuntime

        instrumentation.runOnMainSync {
            runtime = TerminalRuntime(context) { event ->
                when (event) {
                    is TerminalEvent.Canary -> {
                        canary.set(event.result)
                        canaryLatch.countDown()
                    }
                    TerminalEvent.PageReady -> readyLatch.countDown()
                    is TerminalEvent.Input -> {
                        input.set(event.value)
                        inputLatch.countDown()
                    }
                    else -> Unit
                }
            }
            runtime.createWebView()
        }

        assertTrue("terminal canary timed out", canaryLatch.await(20, TimeUnit.SECONDS))
        assertTrue("terminal incompatible: ${canary.get()}", canary.get().compatible)
        assertTrue("terminal page timed out", readyLatch.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            runtime.startGeneration(ULong.MAX_VALUE)
            assertTrue(runtime.sendKey("A\u0000界"))
        }
        assertTrue("binary terminal input timed out", inputLatch.await(20, TimeUnit.SECONDS))
        val actualCanary = canary.get()
        val actualInput = input.get()
        assertNotNull(actualCanary)
        assertTrue("terminal incompatible: $actualCanary", actualCanary.compatible)
        assertEquals(81, actualCanary.columns)
        assertEquals(25, actualCanary.rows)
        assertEquals(ULong.MAX_VALUE, actualInput.terminalGeneration)
        assertEquals(0uL, actualInput.sequence)
        assertArrayEquals("A\u0000界".toByteArray(Charsets.UTF_8), actualInput.bytes)
        assertTrue(Build.VERSION.SDK_INT >= 29)
        instrumentation.runOnMainSync {
            assertEquals(
                TerminalWriteResult.POSTED_TO_WEBVIEW,
                runtime.writeOutput(ULong.MAX_VALUE, 0u, "\r\nnative output ✓".toByteArray(Charsets.UTF_8)),
            )
        }
        Thread.sleep(250)
        instrumentation.runOnMainSync {
            val state = runtime.saveState()
            assertTrue(state.wasConnected)
            assertFalse(state.screenRestorable)
            assertFalse(state.scrollbackRestorable)
            assertTrue(state.requiresReconnect)
            runtime.destroy()
        }
    }

    @Test
    fun forcedCanaryFailureBlocksReadyAndConnection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val canary = AtomicReference<TerminalCanaryResult>()
        val canaryLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        val inputLatch = CountDownLatch(1)
        lateinit var runtime: TerminalRuntime

        instrumentation.runOnMainSync {
            runtime = TerminalRuntime(context, forceCanaryFailure = true) { event ->
                when (event) {
                    is TerminalEvent.Canary -> {
                        canary.set(event.result)
                        canaryLatch.countDown()
                    }
                    TerminalEvent.PageReady -> readyLatch.countDown()
                    is TerminalEvent.Input -> inputLatch.countDown()
                    else -> Unit
                }
            }
            runtime.createWebView()
        }

        assertTrue("terminal canary timed out", canaryLatch.await(20, TimeUnit.SECONDS))
        val actualCanary = canary.get()
        assertNotNull(actualCanary)
        assertFalse("forced canary must fail: $actualCanary", actualCanary.compatible)
        assertTrue("forced canary reason: ${actualCanary.reason}", actualCanary.reason.orEmpty().contains("FORCED"))
        assertFalse("page became ready despite a failed canary", readyLatch.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            runtime.startGeneration(11u)
            assertFalse(runtime.sendKey("A"))
            assertFalse(runtime.paste("paste"))
        }
        assertFalse("input flowed despite a failed canary", inputLatch.await(2, TimeUnit.SECONDS))
        instrumentation.runOnMainSync(runtime::destroy)
    }

    @Test
    fun historyIsSeparateAndRemoteOriginHasNoBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ready = CountDownLatch(1)
        val historyEvaluated = CountDownLatch(1)
        val remoteEvaluated = CountDownLatch(1)
        val historyText = AtomicReference<String>()
        val bridgeType = AtomicReference<String>()
        lateinit var runtime: TerminalRuntime
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            runtime = TerminalRuntime(context) { event ->
                if (event === TerminalEvent.PageReady) ready.countDown()
            }
            webView = runtime.createWebView()
        }
        assertTrue("terminal page timed out", ready.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            runtime.startGeneration(7u)
            assertTrue(
                runtime.showHistory(
                    TerminalHistorySnapshot(
                        terminalGeneration = 7u,
                        capturedAt = "2026-08-09T00:00:00Z",
                        text = "server history only",
                        truncatedLines = true,
                        truncatedBytes = true,
                    ),
                ),
            )
        }
        Thread.sleep(250)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "document.getElementById('terminal-history-meta').textContent + '|' + document.getElementById('terminal-history-content').textContent",
            ) { value ->
                historyText.set(value)
                historyEvaluated.countDown()
            }
        }
        assertTrue("history probe timed out", historyEvaluated.await(10, TimeUnit.SECONDS))
        assertTrue(historyText.get().contains("Earlier history lines were truncated"))
        assertTrue(historyText.get().contains("History was truncated at the byte limit"))
        assertTrue(historyText.get().contains("server history only"))
        instrumentation.runOnMainSync {
            webView.loadDataWithBaseURL(
                "https://example.invalid/",
                "<!doctype html><title>remote</title>",
                "text/html",
                "UTF-8",
                null,
            )
        }
        Thread.sleep(500)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript("typeof window.pimobile") { value ->
                bridgeType.set(value)
                remoteEvaluated.countDown()
            }
        }

        assertTrue("remote-origin probe timed out", remoteEvaluated.await(10, TimeUnit.SECONDS))
        assertEquals("\"undefined\"", bridgeType.get())
        instrumentation.runOnMainSync(runtime::destroy)
    }
}
