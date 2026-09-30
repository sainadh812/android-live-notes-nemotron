package com.sainadh.livenotes.data

import android.content.Context
import android.util.AtomicFile
import com.sainadh.livenotes.audio.recordingAudioFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Commit the database deletion before removing audio; retry interrupted cleanup on startup. */
class RecordingFileDeletion(private val context: Context, private val repository: NotesRepository) {
    @Serializable private data class Pending(val recordingId: String, val audioFileName: String?)
    private val directory = File(context.noBackupFilesDir, "recording-deletions")

    suspend fun delete(recording: SavedRecording): Boolean = withContext(Dispatchers.IO) {
        validateId(recording.recordingId)
        // Library cards can outlive a finalization or restore; only the database owns file identity.
        val metadata = repository.getRecordingMetadata(recording.recordingId)
        require(metadata?.audioStatus != RecordingAudioStatus.RECORDING) { "Stop and save this recording before deleting it." }
        metadata?.audioFileName?.let { recordingAudioFile(context, it) }
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare recording deletion." }
        val file = AtomicFile(File(directory, "${UUID.randomUUID()}.json"))
        val pending = Pending(recording.recordingId, metadata?.audioFileName)
        currentCoroutineContext().ensureActive()
        // Once durable intent exists, cancellation cannot strand a committed deletion without
        // its cleanup journal. Process death is handled by recover() on the next launch.
        withContext(NonCancellable) {
            writeJournal(file, pending)
            // Retain the journal even if Room reports failure: a cancellation/IO failure can
            // make the commit outcome ambiguous. Recovery checks the database before unlinking.
            val deleted = repository.deleteRecording(recording.recordingId)
            val actual = pending.copy(audioFileName = deleted.audioFileName)
            if (actual != pending) writeJournal(file, actual)
            val cleaned = try { cleanup(actual) } catch (_: Exception) { false }
            if (cleaned) file.delete()
            cleaned
        }
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        // Old Android AtomicFile can leave only .bak after a crash; newer versions use .new.
        // Let AtomicFile choose its committed copy instead of reading any auxiliary file directly.
        directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .filter { it.endsWith(".json") }.distinct().forEach { name ->
            currentCoroutineContext().ensureActive()
            val file = AtomicFile(File(directory, name))
            try {
                val pending = readJournal(file)
                validateId(pending.recordingId)
                pending.audioFileName?.let { recordingAudioFile(context, it) }
                if (repository.recordingExists(pending.recordingId) || cleanup(pending)) file.delete()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A corrupt journal never authorizes audio deletion, and one failed cleanup
                // cannot prevent other journals or interrupted recordings from recovering.
            }
        }
    }

    private fun writeJournal(file: AtomicFile, pending: Pending) {
        val output = file.startWrite()
        try {
            output.write(Json.encodeToString(pending).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            file.finishWrite(output)
            // Some AtomicFile implementations log a failed rename rather than throwing.
            // Do not remove database records unless the committed journal can be read back.
            check(readJournal(file) == pending) { "Could not commit recording deletion journal." }
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun readJournal(file: AtomicFile): Pending = file.openRead().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 16_384) { "Deletion journal is too large" }
            output.write(buffer, 0, count)
        }
        Json.decodeFromString<Pending>(output.toString(Charsets.UTF_8.name()))
    }

    private fun validateId(id: String) {
        if (id.startsWith("legacy:")) validateBackupDate(id.removePrefix("legacy:")) else validateRecordingId(id)
    }

    private suspend fun cleanup(pending: Pending): Boolean {
        val name = pending.audioFileName ?: return true
        if (repository.recordingAudioReferenceCount(name) > 0) return true
        val audio = recordingAudioFile(context, name)
        return listOf(audio, File(audio.parentFile, "${audio.name}.words"),
            File(audio.parentFile, "${audio.name}.part"), File(audio.parentFile, "${audio.name}.words.part"))
            .map { !it.exists() || it.delete() }.all { it }
    }
}
