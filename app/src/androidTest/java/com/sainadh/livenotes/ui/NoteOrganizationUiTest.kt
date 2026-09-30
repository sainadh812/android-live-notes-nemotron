package com.sainadh.livenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesTheme
import com.sainadh.livenotes.data.NoteCategory
import com.sainadh.livenotes.data.NoteOrganization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteOrganizationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSaveKeepsPastedSummaryAndRetryUsesTheSameText() {
        val editor = NoteEditorState().apply { open("recording:test", "Original recording", null) }
        var attempts = 0
        var savedSummary: String? = null
        compose.setContent {
            LiveNotesTheme {
                NoteEditDialog(editor, emptyList(), onSave = { _, _, _, summary ->
                    attempts++
                    savedSummary = summary
                    if (attempts == 1) Result.failure(IllegalStateException("Storage unavailable")) else Result.success(Unit)
                }, onCreateCategory = { Result.success(NoteCategory("new", it)) })
            }
        }
        val summary = "Summary pasted from another app.\n\nFollow up with the team."
        compose.onNodeWithText("My summary").performScrollTo().performTextReplacement(summary)
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Storage unavailable").assertIsDisplayed()
        compose.runOnIdle { assertEquals(summary, editor.draft?.userSummary) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(summary, savedSummary); assertEquals(2, attempts); assertNull(editor.draft) }
    }

    @Test fun cancelAsksBeforeDiscardingAnEditedName() {
        val editor = NoteEditorState().apply { open("daily:2026-09-30", "September 30", NoteOrganization("daily:2026-09-30")) }
        compose.setContent {
            LiveNotesTheme {
                NoteEditDialog(editor, emptyList(), onSave = { _, _, _, _ -> Result.success(Unit) },
                    onCreateCategory = { Result.success(NoteCategory("new", it)) })
            }
        }
        compose.onNodeWithText("Note name").performTextReplacement("Team decisions")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.runOnIdle { assertEquals("Team decisions", editor.draft?.title) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertNull(editor.draft) }
    }
}
