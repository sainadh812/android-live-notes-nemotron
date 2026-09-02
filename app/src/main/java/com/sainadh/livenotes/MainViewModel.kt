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
import com.sainadh.livenotes.stt.NemotronQuant
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

    val todayNote: StateFlow<DailyNote?> = repository.observeToday().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
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
        if (isListening.value) {
            ForegroundListeningService.stop(getApplication())
        } else {
            ForegroundListeningService.start(getApplication())
        }
    }

    /** Which quant (if any) is already fully downloaded on this device. */
    fun downloadedQuant(): NemotronQuant? = modelDownloadManager.findAnyDownloaded()

    /**
     * Starts (or resumes) downloading the given quant's GGUF model file.
     * Safe to call again after a Failed state to retry/resume. Listening
     * must be restarted (stop then start) after a download completes for
     * ForegroundListeningService to pick up the newly-available model,
     * since it decides which transcriber to use once, in onCreate.
     */
    fun downloadModel(quant: NemotronQuant) {
        viewModelScope.launch(Dispatchers.IO) {
            modelDownloadManager.download(quant)
        }
    }

    fun deleteModel(quant: NemotronQuant) {
        modelDownloadManager.delete(quant)
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
