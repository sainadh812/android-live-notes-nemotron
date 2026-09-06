package com.sainadh.livenotes

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sainadh.livenotes.ai.LlmConnectionRequest
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.service.ForegroundListeningService
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.ModelDownloadState
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.service.CapturePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val repository: NotesRepository,
    private val apiKeyStore: ApiKeyStore,
    private val modelDownloadManager: ModelDownloadManager
) : AndroidViewModel(application) {
    private val _connectionStatus = MutableStateFlow("Save settings, then test the AI connection.")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    val summaryError: StateFlow<String?> =
        (application as LiveNotesApplication).appContainer.conversationOrchestrator.summaryError

    val todayNote: StateFlow<DailyNote?> = repository.observeToday().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val savedRecordings = repository.observeSavedRecordings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val allNotes: StateFlow<List<DailyNote>> = repository.observeAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val isListening: StateFlow<Boolean> = ServiceStateTracker.listening.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    val latestTranscript: StateFlow<String> = ServiceStateTracker.latestTranscript.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ""
    )

    val modelDownloadState: StateFlow<ModelDownloadState> = modelDownloadManager.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ModelDownloadState.Idle
    )

    private val speechSettingsStore = (application as LiveNotesApplication).appContainer.speechSettings
    val speechSettings = speechSettingsStore.state
    val downloadedModels = modelDownloadManager.downloadedModels
    val modelDownloadTarget = modelDownloadManager.downloadTarget

    fun selectSpeechModel(model: SpeechModel?) {
        if (model == null || modelDownloadManager.isDownloaded(model)) speechSettingsStore.selectModel(model)
    }

    fun selectSpeechLanguage(language: SpeechLanguage) = speechSettingsStore.selectLanguage(language)

    fun saveAudioInputMode(mode: AudioInputMode) = apiKeyStore.saveAudioInputMode(mode)

    fun saveSettings(
        provider: LlmProvider,
        model: String,
        apiKey: String,
        audioInputMode: AudioInputMode
    ) {
        viewModelScope.launch {
            apiKeyStore.saveProvider(provider)
            apiKeyStore.saveModel(model.ifBlank { provider.defaultModel })
            apiKeyStore.saveAudioInputMode(audioInputMode)
            if (apiKey.isNotBlank()) {
                apiKeyStore.saveApiKey(apiKey)
            }
            _connectionStatus.value = "Settings saved. Tap Test AI connection to verify."
        }
    }

    fun testConnection(provider: LlmProvider, model: String, apiKey: String) {
        viewModelScope.launch {
            val resolvedKey = apiKey.trim().ifBlank { apiKeyStore.readApiKey().trim() }
            if (resolvedKey.isBlank()) {
                _connectionStatus.value = "Add an API key first."
                return@launch
            }

            val resolvedModel = model.trim().ifBlank { provider.defaultModel }
            _connectionStatus.value = "Testing ${provider.displayName}…"

            val app = getApplication<Application>() as LiveNotesApplication
            val result = app.appContainer.chatCompletionClient.testConnection(
                LlmConnectionRequest(
                    provider = provider,
                    apiKey = resolvedKey,
                    model = resolvedModel
                )
            )

            _connectionStatus.value = result.fold(
                onSuccess = { success ->
                    "Connected to ${success.provider.displayName} (${success.model}): ${success.preview.take(80)}".also {
                        Log.i(TAG, it)
                    }
                },
                onFailure = { failure ->
                    "Connection failed: ${failure.message ?: failure.javaClass.simpleName}".also {
                        Log.e(TAG, it, failure)
                    }
                }
            )
        }
    }

    fun hasApiKey(): Boolean = apiKeyStore.readApiKey().isNotBlank()

    fun currentProvider(): LlmProvider = apiKeyStore.readProvider()

    fun currentModel(): String = apiKeyStore.readModel(currentProvider())

    fun currentAudioInputMode(): AudioInputMode = apiKeyStore.readAudioInputMode()

    fun toggleListening() {
        if (ServiceStateTracker.listening.value) {
            stopListening()
        } else {
            startListening()
        }
    }

    fun startListening() = ForegroundListeningService.start(getApplication())

    fun stopListening() = ForegroundListeningService.stop(getApplication())

    fun retrySummary() {
        val app = getApplication<LiveNotesApplication>()
        app.appContainer.conversationOrchestrator.retrySummary()
    }

    fun downloadModel(model: SpeechModel) {
        viewModelScope.launch(Dispatchers.IO) { modelDownloadManager.download(model) }
    }

    fun deleteModel(model: SpeechModel) {
        if (ServiceStateTracker.capturePhase.value != CapturePhase.IDLE) return
        viewModelScope.launch {
            kotlinx.coroutines.withContext(Dispatchers.IO) { modelDownloadManager.delete(model) }
            // Keep the explicit selection even when its file was removed. A new
            // recording will ask for a download instead of switching providers.
        }
    }

    class Factory(private val application: LiveNotesApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                application = application,
                repository = application.appContainer.repository,
                apiKeyStore = application.appContainer.secureSettings,
                modelDownloadManager = application.appContainer.modelDownloadManager
            ) as T
        }
    }

    private companion object {
        const val TAG = "LiveNotesOpenAI"
    }
}
