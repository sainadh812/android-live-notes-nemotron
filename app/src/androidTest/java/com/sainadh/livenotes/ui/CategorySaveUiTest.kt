package com.sainadh.livenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesTheme
import com.sainadh.livenotes.data.NoteCategory
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategorySaveUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recreatingDialogDuringCategorySaveKeepsOneWriteAndDismissesTheRestoredDialog() {
        val completion = CompletableDeferred<Unit>()
        var writes = 0
        var savedName = ""
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LiveNotesTheme {
                ManageCategoriesDialog(emptyList(), onDismiss = {}, onCreate = { name ->
                    writes++
                    savedName = name
                    completion.await()
                    Result.success(NoteCategory("project", name))
                }, onRename = { _, _ -> Result.success(Unit) }, onDelete = { Result.success(Unit) })
            }
        }
        compose.onNodeWithText("New category").performClick()
        compose.onNodeWithText("Category name").performTextReplacement("Project decisions")
        compose.onNodeWithText("Save category").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Project decisions").assertIsDisplayed()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.runOnIdle { completion.complete(Unit) }
        compose.onNodeWithText("Category name").assertDoesNotExist()
        compose.onNodeWithText("Categories").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, writes); assertEquals("Project decisions", savedName) }
    }
}
