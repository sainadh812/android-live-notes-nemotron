package com.sainadh.livenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real application, ViewModel, database, and navigation without a model or API key. */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recorderAndLibraryOpenWithoutCloudCredentials() {
        compose.onNodeWithText("Start recording").assertIsDisplayed()
        saveTestScreenshot(compose.onRoot().captureToImage().asAndroidBitmap(), "recorder.png")
        compose.onNodeWithText("Notes").performClick()
        compose.onNodeWithText("Your library").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Start recording").assertIsDisplayed()
    }
}
