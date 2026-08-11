package io.github.verybigsad.pimobile

import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.verybigsad.pimobile.terminal.TerminalCanary
import io.github.verybigsad.pimobile.terminal.TerminalCanaryResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalCanaryTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bundledTerminalBootsInInstalledWebView() {
        val result = AtomicReference<TerminalCanaryResult>()
        val latch = CountDownLatch(1)
        rule.activityRule.scenario.onActivity { activity ->
            val webView = TerminalCanary(activity).createWebView {
                result.set(it)
                latch.countDown()
            }
            activity.addContentView(
                webView,
                ViewGroup.LayoutParams(1, 1),
            )
        }

        assertTrue("terminal canary timed out", latch.await(15, TimeUnit.SECONDS))
        val actual = result.get()
        assertNotNull(actual)
        if (actual.compatible) {
            assertEquals(81, actual.columns)
            assertEquals(25, actual.rows)
        } else {
            // CI API29 images may ship an outdated WebView; the canary must then
            // honestly report WEBVIEW_UPDATE_REQUIRED instead of booting.
            assertEquals("WEBVIEW_UPDATE_REQUIRED", actual.reason)
        }
    }
}
