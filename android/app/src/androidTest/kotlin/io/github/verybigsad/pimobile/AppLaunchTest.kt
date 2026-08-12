package io.github.verybigsad.pimobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.verybigsad.pimobile.testing.AppLaunchTestBridge
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
