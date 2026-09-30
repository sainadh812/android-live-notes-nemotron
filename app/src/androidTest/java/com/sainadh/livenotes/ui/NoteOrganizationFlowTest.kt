package com.sainadh.livenotes.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.MainActivity
import com.sainadh.livenotes.data.recordingNoteKey
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Covers the real Notes screen, retained ViewModel, Room storage and existing transcript. */
@RunWith(AndroidJUnit4::class)
class NoteOrganizationFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun organizeAnExistingRecordingAndReopenItWithoutLosingItsTranscript() {
        val application = ApplicationProvider.getApplicationContext<LiveNotesApplication>()
        val repository = application.appContainer.repository
        val recordingId = UUID.randomUUID().toString()
        val suffix = recordingId.take(8)
        val originalTitle = "Original meeting $suffix"
        val editedTitle = "Project decisions $suffix"
        val transcript = "Original meeting words remain unchanged. Reference $suffix."
        val pastedSummary = "Summary pasted from another app.\n\nSend the prototype to Maya and review it on Friday."
        val category = runBlocking {
            application.appContainer.recordingRecovery.await()
            val now = System.currentTimeMillis()
            repository.beginRecording(recordingId, now)
            repository.updateRecordingTitle(recordingId, originalTitle)
            repository.saveTranscript(recordingId, TranscriptUpdate(0, transcript, TranscriptStatus.FINAL), now)
            repository.finishRecording(recordingId, null, 45_000)
            repository.createNoteCategory("Project $suffix")
        }

        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithText("Notes").performClick()
            compose.onNodeWithText("Search notes").performTextReplacement(originalTitle)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Recordings · 1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Edit details"))
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Edit details") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Edit details").performClick()
            compose.onNodeWithText("Note name").performTextReplacement(editedTitle)
            compose.onNodeWithText("Category: Uncategorized").performClick()
            compose.onNode(hasText(category.name) and hasClickAction()).performClick()
            compose.onNodeWithText("My summary").performScrollTo().performTextReplacement(pastedSummary)
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Edit note details").fetchSemanticsNodes().isEmpty()
            }

            // The renamed note no longer matches the old title. Find it using its new name.
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Search notes"))
            compose.onNodeWithText("Search notes").performTextReplacement(editedTitle)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Recordings · 1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Bookmark note"))
            compose.onNode(hasContentDescription("Bookmark note")).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Bookmarked ✓").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Bookmarked"))
            compose.onNodeWithText("Bookmarked").performClick()
            compose.onNodeWithText("All categories").performClick()
            compose.onNode(hasText(category.name) and hasClickAction()).performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(editedTitle))
            compose.onNodeWithText(editedTitle).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open recording"))
            compose.onNodeWithText("Open recording").performClick()

            activity.recreate()
            compose.onNodeWithText(editedTitle).assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(pastedSummary))
            compose.onNodeWithText(pastedSummary).assertIsDisplayed()
            saveTestScreenshot(compose.onRoot().captureToImage().asAndroidBitmap(), "note-organization.png")
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(transcript))
            compose.onNodeWithText(transcript).assertIsDisplayed()

            runBlocking {
                val organization = repository.observeNoteOrganizations().first()
                    .single { it.noteKey == recordingNoteKey(recordingId) }
                assertEquals(editedTitle, organization.title)
                assertTrue(organization.isBookmarked)
                assertEquals(category.id, organization.categoryId)
                assertEquals(pastedSummary, organization.userSummary)
                val original = repository.observeSavedRecordings().first().single { it.recordingId == recordingId }
                assertEquals(originalTitle, original.title)
                assertEquals(transcript, original.text)
            }
        }
    }
}
