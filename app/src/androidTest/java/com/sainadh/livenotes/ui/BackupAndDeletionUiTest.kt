package com.sainadh.livenotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesTheme
import com.sainadh.livenotes.data.SavedRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupAndDeletionUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressAndVisibleMenuBothRequestDeletionWithoutDeletingImmediately() {
        var deletionsRequested = 0
        val recording = SavedRecording("delete-fixture", "2026-09-30", "A transcript worth keeping.", 1L, false, title = "Project meeting")
        compose.setContent {
            LiveNotesTheme {
                RecordingLibraryCard(recording, isPlaying = false, playbackEnabled = true, onOpen = {}, onPlay = {},
                    onDelete = { deletionsRequested++ })
            }
        }
        compose.onNodeWithText("Project meeting").performTouchInput { longClick() }
        compose.onNodeWithText("Delete recording").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, deletionsRequested) }
        compose.onNodeWithText("Delete recording").performClick()
        compose.runOnIdle { assertEquals(1, deletionsRequested) }
        compose.onNodeWithContentDescription("Recording actions for Project meeting").performClick()
        compose.onNodeWithText("Delete recording").performClick()
        compose.runOnIdle { assertEquals(2, deletionsRequested) }
    }

    @Test fun deletionConfirmationSeparatesCancelFromConfirmAndShowsFailure() {
        var cancellations = 0
        var confirmations = 0
        compose.setContent {
            LiveNotesTheme {
                RecordingDeleteDialog("Project meeting", busy = false, error = "Unable to remove the recording. Try again.",
                    onDismiss = { cancellations++ }, onConfirm = { confirmations++ })
            }
        }
        compose.onNodeWithText("Daily summaries and categories are kept.").assertIsDisplayed()
        compose.onNodeWithText("Unable to remove the recording. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, cancellations); assertEquals(0, confirmations) }
        compose.onNodeWithText("Delete recording").performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }

    @Test fun createBackupRequiresMatchingPasswordAndCanExcludeDownloadedModels() {
        val state = BackupPasswordState().apply { open(BackupPasswordMode.CREATE) }
        var submitted: Pair<String, Boolean>? = null
        compose.setContent {
            LiveNotesTheme {
                BackupPasswordDialog(state, onDismiss = {}, onConfirm = { password, models -> submitted = password to models })
            }
        }
        compose.onNodeWithText("Choose backup location").assertIsNotEnabled()
        compose.onNodeWithText("Backup password").performScrollTo().performTextReplacement("short")
        compose.onNodeWithText("Confirm password").performScrollTo().performTextReplacement("short")
        compose.onNodeWithText("Choose backup location").assertIsNotEnabled()
        compose.onNodeWithText("Backup password").performScrollTo().performTextReplacement("private-password")
        compose.onNodeWithText("Choose backup location").assertIsNotEnabled()
        compose.onNodeWithText("Confirm password").performScrollTo().performTextReplacement("private-password")
        compose.onNodeWithText("Include downloaded models (large backup)").performScrollTo().performClick()
        compose.onNodeWithText("Choose backup location").performClick()
        compose.runOnIdle { assertEquals("private-password" to false, submitted) }
    }

    @Test fun cancellingRestoreClearsPasswordFromRetainedState() {
        val state = BackupPasswordState().apply { open(BackupPasswordMode.RESTORE) }
        compose.setContent { LiveNotesTheme { BackupPasswordDialog(state, onDismiss = {}, onConfirm = { _, _ -> }) } }
        compose.onNodeWithText("Backup password").performTextReplacement("private-password")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(state.mode); assertEquals("", state.password); assertEquals("", state.confirmation) }
    }

    @Test fun backupInProgressDisablesBothFileActions() {
        compose.setContent {
            LiveNotesTheme { BackupSettingsCard(busy = true, progress = "Saving your recordings…", onCreate = {}, onRestore = {}) }
        }
        compose.onNodeWithText("Saving your recordings…").assertIsDisplayed()
        compose.onNodeWithText("Create backup").assertIsNotEnabled()
        compose.onNodeWithText("Restore backup").assertIsNotEnabled()
    }
}
