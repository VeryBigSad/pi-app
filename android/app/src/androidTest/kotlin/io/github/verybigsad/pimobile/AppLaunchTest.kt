package io.github.verybigsad.pimobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesIntoPairingLandingWhenUnpaired() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                rule.onNodeWithText("Your Mac stays in control").assertIsDisplayed()
                rule.onNodeWithText("Pair a Mac").assertIsDisplayed()
            }.isSuccess
        }
        rule.onNodeWithText("Pair a Mac").assertIsDisplayed()
    }

    @Test
    fun pairingLandingExplainsTrustBoundary() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                rule.onNodeWithText("Passkey unlock").assertIsDisplayed()
            }.isSuccess
        }
        rule.onNodeWithText("End-to-end TLS").assertIsDisplayed()
        rule.onNodeWithText("Mac execution").assertIsDisplayed()
    }
}
