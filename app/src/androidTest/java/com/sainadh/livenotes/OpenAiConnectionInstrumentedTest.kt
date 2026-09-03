package com.sainadh.livenotes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiConnectionInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun embeddedOpenAiKey_connectsFromInsideTheApp() {
        composeRule.onNodeWithText("Show").performClick()
        composeRule.onNodeWithText("Test AI connection").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 90000) {
            composeRule.onAllNodesWithText("Connected to OpenAI", substring = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Connection failed:", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        val successNodes = composeRule.onAllNodesWithText("Connected to OpenAI", substring = true)
            .fetchSemanticsNodes()
        assertTrue("Expected in-app OpenAI connection success message", successNodes.isNotEmpty())
    }
}
