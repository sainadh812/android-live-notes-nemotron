package com.sainadh.livenotes

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sainadh.livenotes.backup.BackupProgress
import com.sainadh.livenotes.data.AppDataMaintenance
import com.sainadh.livenotes.data.SavedRecording
import com.sainadh.livenotes.service.CapturePhase
import com.sainadh.livenotes.service.ServiceStateTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryTransferState(
    val busy: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val isError: Boolean = false,
    val restoreGeneration: Int = 0,
    val deletedRecordingId: String? = null
)

/** Passwords are held only in memory; file picker results survive Activity recreation. */
class LibraryTransferViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LiveNotesApplication
    private val mutableState = MutableStateFlow(LibraryTransferState())
    val state = mutableState.asStateFlow()
    private var pendingPassword: CharArray? = null
    private var pendingModels = true
    var restoreUri: Uri? = null
        private set
    private val mutableDeletion = MutableStateFlow<SavedRecording?>(null)
    val deletion = mutableDeletion.asStateFlow()

    fun prepareExport(password: String, includeModels: Boolean) {
        clearPendingPassword()
        pendingPassword = password.toCharArray()
        pendingModels = includeModels
    }

    fun chooseRestore(uri: Uri?) { restoreUri = uri }
    fun cancelPicker() { clearPendingPassword(); restoreUri = null }
    fun pickerFailed() { cancelPicker(); fail("Could not open the file picker. Try a document app such as Files.") }
    fun requestDeletion(recording: SavedRecording) {
        if (!mutableState.value.busy && !AppDataMaintenance.busy.value) {
            mutableDeletion.value = recording
            mutableState.value = mutableState.value.copy(message = null, isError = false)
        }
    }
    fun cancelDeletion() { if (!mutableState.value.busy) mutableDeletion.value = null }

    fun exportTo(uri: Uri?) {
        val password = pendingPassword
        val includeModels = pendingModels
        pendingPassword = null
        if (uri == null) { password?.fill('\u0000'); return }
        if (password == null) {
            fail("The app restarted before backup began. Create a new backup; the selected file is incomplete.")
            removeIncomplete(uri)
            return
        }
        val started = perform("Creating backup…", password) {
            var complete = false
            try {
                val resolver = app.contentResolver
                val output = requireNotNull(resolver.openOutputStream(uri, "wt")) { "Cannot write to the selected location." }
                app.appContainer.localBackupManager.exportBackup(output, password, includeModels, ::progress)
                progress(BackupProgress("Checking the saved backup…", 0L))
                val input = requireNotNull(resolver.openInputStream(uri)) { "Cannot reopen the saved backup for verification." }
                app.appContainer.localBackupManager.validateBackup(input, password, ::progress)
                complete = true
                mutableState.value = mutableState.value.copy(message =
                    "Backup saved and verified. Keep this file and its password before uninstalling. " +
                    if (includeModels) "Downloaded models are included." else "Downloaded models were excluded; download them again after restoring.", isError = false)
            } finally {
                if (!complete) withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
            }
        }
        if (!started) removeIncomplete(uri)
    }

    fun restore(passwordText: String) {
        val uri = restoreUri ?: run { fail("Choose the backup file again."); return }
        val password = passwordText.toCharArray()
        perform("Checking backup…", password) {
            val input = requireNotNull(app.contentResolver.openInputStream(uri)) { "Cannot open the selected backup." }
            app.appContainer.localBackupManager.restoreBackup(input, password, ::progress)
            restoreUri = null
            mutableState.value = mutableState.value.copy(
                message = "Backup restored. Existing entries were kept; imported recordings, notes and settings are ready.",
                isError = false, restoreGeneration = mutableState.value.restoreGeneration + 1)
        }
    }

    fun deleteSelected() {
        val recording = mutableDeletion.value ?: return
        perform("Deleting recording…") {
            val filesRemoved = app.appContainer.recordingFileDeletion.delete(recording)
            mutableDeletion.value = null
            if (ServiceStateTracker.recordingId.value == recording.recordingId) {
                ServiceStateTracker.resetTranscript()
                ServiceStateTracker.recordingId.value = null
            }
            mutableState.value = mutableState.value.copy(deletedRecordingId = recording.recordingId,
                message = if (filesRemoved) "Recording deleted." else "Recording deleted. Audio cleanup will retry when the app next opens.",
                isError = !filesRemoved)
        }
    }

    private fun perform(message: String, password: CharArray? = null, action: suspend () -> Unit): Boolean {
        val lease = AppDataMaintenance.tryBegin { ServiceStateTracker.capturePhase.value == CapturePhase.IDLE }
        if (lease == null) {
            password?.fill('\u0000')
            fail("Stop recording and wait for the current file operation to finish, then try again.")
            return false
        }
        mutableState.value = mutableState.value.copy(busy = true, progress = message, message = null, isError = false)
        viewModelScope.launch {
            try {
                app.appContainer.recordingRecovery.await()
                withContext(Dispatchers.IO) { action() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                fail(error.message ?: "This operation could not finish. Your existing notes have been kept.")
            } finally {
                password?.fill('\u0000')
                lease.close()
                mutableState.value = mutableState.value.copy(busy = false, progress = null)
            }
        }
        return true
    }

    private fun progress(value: BackupProgress) {
        mutableState.value = mutableState.value.copy(progress = value.message)
    }
    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(message = message, isError = true)
    }
    private fun clearPendingPassword() { pendingPassword?.fill('\u0000'); pendingPassword = null }
    private fun removeIncomplete(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }
    }
    override fun onCleared() { clearPendingPassword(); super.onCleared() }
}
