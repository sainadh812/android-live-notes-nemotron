package com.sainadh.livenotes

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.backup.LocalBackupManager
import com.sainadh.livenotes.data.NotesDatabase
import com.sainadh.livenotes.data.NotesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LibraryTransferViewModelTest {
    @Test fun wrongPasswordKeepsSelectedFileAndRetryRestoresWithoutReplacingExistingNotes() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<LiveNotesApplication>()
        val container = app.appContainer
        container.recordingRecovery.await()
        val before = container.repository.exportSnapshot()
        val secureBefore = container.secureSettings.exportSnapshot()
        val speechBefore = container.speechSettings.exportSnapshot()
        val password = "a private restore test password"
        val backup = File.createTempFile("restore-retry-", ".livenotes", app.cacheDir)
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[LibraryTransferViewModel::class.java]
        }
        val observed = CopyOnWriteArrayList<LibraryTransferState>()
        val observer = launch(Dispatchers.Unconfined) { model.state.collect { observed += it } }
        try {
            // Export an isolated empty library with the current settings. Successful restore
            // must still authenticate and merge it, while every pre-existing row stays intact.
            val sourceDatabase = Room.inMemoryDatabaseBuilder(app, NotesDatabase::class.java).build()
            try {
                val source = LocalBackupManager(app, NotesRepository(sourceDatabase), container.secureSettings,
                    container.speechSettings, container.modelDownloadManager)
                val key = password.toCharArray()
                try { source.exportBackup(backup.outputStream(), key, includeModels = false) }
                finally { key.fill('\u0000') }
            } finally { sourceDatabase.close() }

            val uri = Uri.fromFile(backup)
            withContext(Dispatchers.Main) {
                model.chooseRestore(uri)
                model.restore("an incorrect backup password")
                assertTrue(model.state.value.busy)
                assertNotNull(model.state.value.progress)
            }
            val failed = withTimeout(60_000) { model.state.first { !it.busy && it.isError } }
            assertFalse(failed.message.isNullOrBlank())
            assertEquals(0, failed.restoreGeneration)
            assertNull(failed.progress)
            assertEquals(uri, model.restoreUri)
            assertEquals(before, container.repository.exportSnapshot())
            assertEquals(secureBefore, container.secureSettings.exportSnapshot())
            assertEquals(speechBefore, container.speechSettings.exportSnapshot())

            // Retry directly: no second chooseRestore() or file-picker round trip.
            withContext(Dispatchers.Main) {
                model.restore(password)
                assertTrue(model.state.value.busy)
                assertNotNull(model.state.value.progress)
                assertFalse(model.state.value.isError)
            }
            val restored = withTimeout(60_000) { model.state.first { !it.busy && it.restoreGeneration == 1 } }
            assertFalse(restored.isError)
            assertFalse(restored.message.isNullOrBlank())
            assertNull(restored.progress)
            assertNull(model.restoreUri)
            assertEquals(before, container.repository.exportSnapshot())
            assertEquals(secureBefore, container.secureSettings.exportSnapshot())
            assertEquals(speechBefore, container.speechSettings.exportSnapshot())
            assertTrue(observed.any { it.busy && !it.progress.isNullOrBlank() })
            assertTrue(observed.any { !it.busy && it.isError && it.restoreGeneration == 0 })
            assertTrue(observed.any { !it.busy && !it.isError && it.restoreGeneration == 1 })
        } finally {
            observer.cancel()
            withContext(Dispatchers.Main) { store.clear() }
            backup.delete()
        }
    }
}
