package com.sainadh.livenotes.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.NotesDatabase
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.data.recordingNoteKey
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.SpeechSettingsStore
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBackupManagerTest {
    private val password = "my portable backup password".toCharArray()

    @Test fun freshStorageRestorePreservesNotesAudioTimingSecretsAndRepeatedImportDoesNotDuplicate() = runBlocking {
        Fixture().use { original -> Fixture().use { restored ->
            val audio = seed(original)
            val expected = original.repository.exportSnapshot()
            val archive = ByteArrayOutputStream().also { original.manager.exportBackup(it, password, includeModels = false) }.toByteArray()
            assertFalse(archive.toString(Charsets.ISO_8859_1).contains("fake-private-key-for-backup-test"))
            assertEquals(1, restored.manager.validateBackup(ByteArrayInputStream(archive), password).recordings)
            assertTrue(restored.repository.exportSnapshot().recordings.isEmpty())
            val imported = restored.manager.restoreBackup(ByteArrayInputStream(archive), password)
            assertEquals(1, imported.recordings)
            assertEquals(expected, restored.repository.exportSnapshot())
            assertArrayEquals(audio.readBytes(), File(restored.context.filesDir, "recordings/${audio.name}").readBytes())
            assertEquals(File(audio.parentFile, "${audio.name}.words").readText(),
                File(restored.context.filesDir, "recordings/${audio.name}.words").readText())
            assertEquals(original.secure.exportSnapshot(), restored.secure.exportSnapshot())
            assertEquals(original.speech.exportSnapshot(), restored.speech.exportSnapshot())
            val repeated = restored.manager.restoreBackup(ByteArrayInputStream(archive), password)
            assertEquals(0, repeated.recordings)
            assertEquals(0, repeated.files)
            assertEquals(expected, restored.repository.exportSnapshot())
        } }
    }

    @Test fun wrongPasswordTruncatedFooterAndExistingFileConflictNeverModifyTheLibraryOrSettings() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            val audio = seed(source)
            val archive = ByteArrayOutputStream().also { source.manager.exportBackup(it, password, includeModels = false) }.toByteArray()
            target.secure.saveApiKey("keep-existing-key")
            val before = target.repository.exportSnapshot()
            val settings = target.secure.exportSnapshot()
            suspend fun rejected(bytes: ByteArray, key: CharArray) {
                try { target.manager.restoreBackup(ByteArrayInputStream(bytes), key); fail("Expected rejected backup") }
                catch (_: IOException) { }
                assertEquals(before, target.repository.exportSnapshot())
                assertEquals(settings, target.secure.exportSnapshot())
            }
            rejected(archive, "incorrect backup password".toCharArray())
            rejected(archive.copyOf(archive.size - 20), password)
            val conflict = File(target.context.filesDir, "recordings/${audio.name}").apply { parentFile!!.mkdirs(); writeText("Existing bytes must survive") }
            rejected(archive, password)
            assertEquals("Existing bytes must survive", conflict.readText())
        } }
    }

    @Test fun failedDatabaseImportRollsBackNewFilesAndRestoredSettings() = runBlocking {
        Fixture().use { source -> Fixture().use { target ->
            val audio = seed(source)
            val archive = ByteArrayOutputStream().also { source.manager.exportBackup(it, password, false) }.toByteArray()
            target.secure.saveApiKey("keep-existing-key")
            val before = target.repository.exportSnapshot()
            val secureBefore = target.secure.exportSnapshot()
            val speechBefore = target.speech.exportSnapshot()
            target.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_import BEFORE INSERT ON daily_notes BEGIN SELECT RAISE(ABORT, 'forced test failure'); END"
            )
            var failed = false
            try { target.manager.restoreBackup(ByteArrayInputStream(archive), password) }
            catch (_: Exception) { failed = true }
            assertTrue("The forced database error must reject restore", failed)
            assertEquals(before, target.repository.exportSnapshot())
            assertEquals(secureBefore, target.secure.exportSnapshot())
            assertEquals(speechBefore, target.speech.exportSnapshot())
            assertFalse(File(target.context.filesDir, "recordings/${audio.name}").exists())
            assertFalse(File(target.context.filesDir, "recordings/${audio.name}.words").exists())
        } }
    }

    @Test fun exportRejectsMissingReferencedAudioInsteadOfClaimingCompleteBackup() = runBlocking {
        Fixture().use { fixture ->
            seed(fixture).delete()
            try { fixture.manager.exportBackup(ByteArrayOutputStream(), password, false); fail("Missing audio must fail") }
            catch (_: IOException) { }
        }
    }

    private suspend fun seed(fixture: Fixture): File {
        val id = UUID.randomUUID().toString()
        val now = 1_790_761_600_000L
        val audio = File(fixture.context.filesDir, "recordings/$id.wav")
        WavFileWriter(audio, 16_000).apply { write(ShortArray(1_600) { (it % 300).toShort() }, 0, 1_600); finish() }
        File(audio.parentFile, "${audio.name}.words").writeText("0\t100\t48656c6c6f")
        fixture.repository.beginRecording(id, now)
        fixture.repository.saveTranscript(id, TranscriptUpdate(0, "Hello original meeting.", TranscriptStatus.FINAL), now)
        fixture.repository.finishRecording(id, audio.name, 100)
        fixture.repository.upsertNote(DailyNote("2026-09-30", "Automatic summary", "Remember context", listOf("Follow up"), now))
        val category = fixture.repository.createNoteCategory("Work")
        fixture.repository.saveNoteDetails(recordingNoteKey(id), "Meeting title", category.id, "Pasted summary\nKeep formatting")
        fixture.repository.setNoteBookmarked(recordingNoteKey(id), true)
        fixture.secure.saveApiKey("fake-private-key-for-backup-test")
        fixture.secure.saveModel("custom-model")
        fixture.secure.saveAudioInputMode(AudioInputMode.PHONE_MIC)
        fixture.speech.selectModel(SpeechModel.MOONSHINE_TINY)
        return audio
    }

    private class Fixture : Closeable {
        private val parent = InstrumentationRegistry.getInstrumentation().targetContext
        private val id = UUID.randomUUID().toString()
        private val directory = File(parent.cacheDir, "backup-test-$id").apply { mkdirs() }
        private val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(parent) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = "backup-test-$id-$name"
                preferenceNames += key
                return parent.getSharedPreferences(key, mode)
            }
        }
        val database = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java).build()
        val repository = NotesRepository(database)
        val secure = ApiKeyStore(context)
        val speech = SpeechSettingsStore(context, null)
        val manager = LocalBackupManager(context, repository, secure, speech, ModelDownloadManager(context))
        override fun close() { database.close(); directory.deleteRecursively(); preferenceNames.forEach(parent::deleteSharedPreferences) }
    }
}
