package com.sainadh.livenotes

import android.app.Application
import com.sainadh.livenotes.ai.ChatCompletionClient
import com.sainadh.livenotes.ai.ConversationOrchestrator
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.NotesDatabase
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.SpeechSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import com.sainadh.livenotes.audio.WavFileWriter
import java.io.File

class LiveNotesApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val summaryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = NotesDatabase.build(application)
    private val apiKeyStore = ApiKeyStore(application)
    private val notesRepository = NotesRepository(database)

    val chatCompletionClient = ChatCompletionClient()

    val conversationOrchestrator = ConversationOrchestrator(
        repository = notesRepository,
        apiKeyStore = apiKeyStore,
        chatCompletionClient = chatCompletionClient,
        summaryScope = summaryScope
    )

    val repository: NotesRepository = notesRepository
    // Runs once before a new recording can start. Recover checkpointed samples
    // after process death, including a rename completed before the database write.
    val recordingRecovery = summaryScope.async {
        notesRepository.unfinishedRecordings().forEach { recording ->
            val audio = runCatching {
                val id = java.util.UUID.fromString(recording.recordingId).toString()
                WavFileWriter.recover(File(application.filesDir, "recordings/$id.wav"))
            }.getOrNull()
            notesRepository.finishRecording(recording.recordingId, audio?.file?.name, audio?.durationMs ?: 0L)
        }
    }
    val secureSettings: ApiKeyStore = apiKeyStore
    val modelDownloadManager = ModelDownloadManager(application)
    val speechSettings = SpeechSettingsStore(application, modelDownloadManager.findAnyDownloaded())
}
