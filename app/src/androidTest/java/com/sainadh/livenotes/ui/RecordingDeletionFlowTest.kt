package com.sainadh.livenotes.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LibraryTransferViewModel
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.MainActivity
import com.sainadh.livenotes.MainViewModel
import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.dailyNoteKey
import com.sainadh.livenotes.data.recordingNoteKey
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordingDeletionFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val lazyList = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
    private val readOnlyText = SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)

    @Test fun cancelPreservesRecordingAndConfirmRemovesOnlyItsRowsAndAudio() {
        val app = ApplicationProvider.getApplicationContext<LiveNotesApplication>()
        val repository = app.appContainer.repository
        val targetId = UUID.randomUUID().toString()
        val otherId = UUID.randomUUID().toString()
        val suffix = targetId.take(8)
        val targetTitle = "Delete target $suffix"
        val date = LocalDate.of(2099, 1, 1).plusDays((targetId.hashCode().toLong() and 0xffffL))
        val startedAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val targetAudio = File(app.filesDir, "recordings/$targetId.wav")
        val otherAudio = File(app.filesDir, "recordings/$otherId.wav")
        val targetWords = File(targetAudio.parentFile, "${targetAudio.name}.words")
        val otherWords = File(otherAudio.parentFile, "${otherAudio.name}.words")

        val before = runBlocking {
            app.appContainer.recordingRecovery.await()
            val category = repository.createNoteCategory("Deletion test $suffix")
            suspend fun seed(id: String, audio: File, word: String, title: String) {
                repository.beginRecording(id, startedAt)
                repository.updateRecordingTitle(id, "Original $word $suffix")
                repository.saveTranscript(id, TranscriptUpdate(0, "$word recording words $suffix.", TranscriptStatus.FINAL), startedAt)
                val writer = WavFileWriter(audio, 16_000)
                writer.write(ShortArray(16_000) { ((it % 20 - 10) * 100).toShort() }, 0, 16_000)
                check(writer.finish() == audio)
                val encodedWord = word.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
                NativeWordTimingFile.write(audio, "0\t500\t$encodedWord\n")
                repository.finishRecording(id, audio.name, writer.durationMs)
                repository.saveNoteDetails(recordingNoteKey(id), title, category.id, "My summary for $word $suffix.")
                repository.setNoteBookmarked(recordingNoteKey(id), true)
            }
            seed(targetId, targetAudio, "Target", targetTitle)
            seed(otherId, otherAudio, "Other", "Keep recording $suffix")
            repository.upsertNote(DailyNote(date.toString(), "Keep this daily summary $suffix.", "Daily context",
                listOf("Keep this action item."), startedAt))
            repository.saveNoteDetails(dailyNoteKey(date.toString()), "Keep daily note $suffix", category.id, "Keep my daily summary.")
            repository.exportSnapshot()
        }
        val targetAudioBytes = targetAudio.readBytes()
        val targetWordBytes = targetWords.readBytes()
        val otherAudioBytes = otherAudio.readBytes()
        val otherWordBytes = otherWords.readBytes()

        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            compose.onNodeWithText("Notes").performClick()
            lateinit var model: MainViewModel
            lateinit var transfer: LibraryTransferViewModel
            activity.onActivity {
                model = ViewModelProvider(it)[MainViewModel::class.java]
                transfer = ViewModelProvider(it)[LibraryTransferViewModel::class.java]
            }
            compose.waitUntil(10_000) {
                model.savedRecordings.value.any { it.recordingId == targetId && it.title == targetTitle } &&
                    model.noteOrganizations.value != null && model.noteCategories.value != null
            }
            compose.onNodeWithText("Search notes").performTextReplacement(targetTitle)
            compose.onNodeWithText("Search notes").performImeAction()
            fun requestDeletion() {
                compose.onNode(lazyList).performScrollToNode(hasText(targetTitle) and readOnlyText)
                compose.onNode(hasText(targetTitle) and readOnlyText).performTouchInput { longClick() }
                compose.onNodeWithText("Delete recording").performClick()
                compose.onNodeWithText("Delete recording?").assertIsDisplayed()
            }

            requestDeletion()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Delete recording?").fetchSemanticsNodes().isEmpty()
            }
            assertEquals(before, runBlocking { repository.exportSnapshot() })
            assertArrayEquals(targetAudioBytes, targetAudio.readBytes())
            assertArrayEquals(targetWordBytes, targetWords.readBytes())

            requestDeletion()
            activity.recreate()
            compose.onNodeWithText("Delete recording?").assertIsDisplayed()
            compose.onNodeWithText("Daily summaries and categories are kept.").assertIsDisplayed()
            compose.onNodeWithText("Delete recording").performClick()
            compose.waitUntil(10_000) { transfer.state.value.deletedRecordingId == targetId && !transfer.state.value.busy }
            compose.onNodeWithText("Delete recording?").assertDoesNotExist()

            val after = runBlocking { repository.exportSnapshot() }
            assertEquals(before.copy(
                recordings = before.recordings.filterNot { it.recordingId == targetId },
                segments = before.segments.filterNot { it.recordingId == targetId },
                organizations = before.organizations.filterNot { it.noteKey == recordingNoteKey(targetId) }
            ), after)
            assertFalse(targetAudio.exists())
            assertFalse(targetWords.exists())
            assertArrayEquals(otherAudioBytes, otherAudio.readBytes())
            assertArrayEquals(otherWordBytes, otherWords.readBytes())
        }
    }
}
