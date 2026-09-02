package com.sainadh.livenotes

import android.app.Application
import com.sainadh.livenotes.ai.ChatCompletionClient
import com.sainadh.livenotes.ai.ConversationOrchestrator
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.NotesDatabase
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.stt.ModelDownloadManager

class LiveNotesApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val database = NotesDatabase.build(application)
    private val apiKeyStore = ApiKeyStore(application)
    private val notesRepository = NotesRepository(
        dailyNoteDao = database.dailyNoteDao(),
        transcriptChunkDao = database.transcriptChunkDao()
    )

    val chatCompletionClient = ChatCompletionClient()

    val conversationOrchestrator = ConversationOrchestrator(
        repository = notesRepository,
        apiKeyStore = apiKeyStore,
        chatCompletionClient = chatCompletionClient
    )

    val repository: NotesRepository = notesRepository
    val secureSettings: ApiKeyStore = apiKeyStore
    val modelDownloadManager = ModelDownloadManager(application)
}
