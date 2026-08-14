package io.github.verybigsad.pimobile

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.verybigsad.pimobile.model.MacId
import io.github.verybigsad.pimobile.model.TrustState
import io.github.verybigsad.pimobile.testing.AppLaunchTestBridge
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(AppLaunchIsolationRule())
        .around(composeRule)

    @Test
    fun appLaunchesIntoPairingLandingWhenUnpaired() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Your Mac stays in control").assertIsDisplayed()
                composeRule.onNodeWithText("Pair a Mac").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("Pair a Mac").assertIsDisplayed()
    }

    @Test
    fun systemScreenshotsRemainEnabledForTrustedState() {
        composeRule.runOnUiThread {
            val window = composeRule.activity.window
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            ScreenCapturePolicy.apply(
                window,
                TrustState.Trusted(
                    macId = MacId("00000000-0000-4000-8000-000000000001"),
                    macDisplayName = "Test Mac",
                    certificateSerial = "01",
                    certificateNotAfterEpochMillis = Long.MAX_VALUE,
                ),
            )
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test
    fun pairingLandingExplainsTrustBoundary() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Passkey unlock").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithText("End-to-end TLS").assertIsDisplayed()
        composeRule.onNodeWithText("Mac execution").assertIsDisplayed()
    }
}

private class AppLaunchIsolationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
            AppLaunchTestBridge.begin(targetContext).use { base.evaluate() }
        }
    }
}
