package com.sainadh.livenotes

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sainadh.livenotes.ai.LlmConnectionRequest
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.audio.AudioInputMode
import com.sainadh.livenotes.audio.RecordingPlayer
import com.sainadh.livenotes.data.SavedRecording
import com.sainadh.livenotes.data.ApiKeyStore
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.data.NoteCategory
import com.sainadh.livenotes.data.NoteOrganization
import com.sainadh.livenotes.data.recordingNoteKey
import com.sainadh.livenotes.data.NotesRepository
import com.sainadh.livenotes.data.RecordingDetailsCache
import com.sainadh.livenotes.data.AppDataMaintenance
import com.sainadh.livenotes.service.ForegroundListeningService
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.ModelDownloadManager
import com.sainadh.livenotes.stt.ModelDownloadState
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.service.CapturePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import com.sainadh.livenotes.data.alignedWordCues
import com.sainadh.livenotes.audio.recordingAudioFile
import com.sainadh.livenotes.stt.NativeWordTimingFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    application: Application,
    private val repository: NotesRepository,
    private val apiKeyStore: ApiKeyStore,
    private val modelDownloadManager: ModelDownloadManager
) : AndroidViewModel(application) {
    private val recordingPlayer = RecordingPlayer(application, viewModelScope)
    val playback = recordingPlayer.state

    init {
        viewModelScope.launch {
            ServiceStateTracker.capturePhase.collect { phase ->
                if (phase != CapturePhase.IDLE) recordingPlayer.pause()
            }
        }
        viewModelScope.launch {
            AppDataMaintenance.busy.collect { busy -> if (busy) recordingPlayer.close() }
        }
    }

    fun playRecording(recording: SavedRecording, positionMs: Long = 0L) {
        if (!AppDataMaintenance.busy.value && ServiceStateTracker.capturePhase.value == CapturePhase.IDLE) recordingPlayer.play(recording, positionMs)
    }
    fun togglePlayback() {
        if (AppDataMaintenance.busy.value || ServiceStateTracker.capturePhase.value != CapturePhase.IDLE) return
        val state = playback.value
        val retry = savedRecordings.value.firstOrNull { it.recordingId == state.recordingId }
        if (state.error != null && retry != null) recordingPlayer.play(retry, state.positionMs)
        else recordingPlayer.toggle()
    }
    fun seekPlayback(positionMs: Long) = recordingPlayer.seek(positionMs)
    fun setPlaybackSpeed(speed: Float) = recordingPlayer.setSpeed(speed)
    fun pausePlayback() = recordingPlayer.pause()
    fun closePlayback() = recordingPlayer.close()
    suspend fun invalidateRecordingDetails() = recordingDetails.clear()
    override fun onCleared() {
        recordingPlayer.close()
        super.onCleared()
    }

    private val _connectionStatus = MutableStateFlow("Save settings, then test the AI connection.")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()
    val summaryError: StateFlow<String?> =
        (application as LiveNotesApplication).appContainer.conversationOrchestrator.summaryError

    val todayNote: StateFlow<DailyNote?> = repository.observeToday().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    val noteOrganizations: StateFlow<Map<String, NoteOrganization>?> = repository.observeNoteOrganizations()
        .map { entries -> entries.associateBy { it.noteKey } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val noteCategories: StateFlow<List<NoteCategory>?> = repository.observeNoteCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _noteMessage = MutableStateFlow<String?>(null)
    val noteMessage: StateFlow<String?> = _noteMessage.asStateFlow()
    fun clearNoteMessage() { _noteMessage.value = null }

    val savedRecordings = combine(
        repository.observeSavedRecordings(ServiceStateTracker.capturePhase.map { it != CapturePhase.IDLE }),
        noteOrganizations
    ) { recordings, organization ->
        recordings.map { recording ->
            val title = organization?.get(recordingNoteKey(recording.recordingId))?.title.orEmpty()
            if (title.isBlank() || title == recording.title) recording else recording.copy(title = title)
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    suspend fun saveNoteDetails(noteKey: String, title: String, categoryId: String?, userSummary: String): Result<Unit> =
        noteOperation { repository.saveNoteDetails(noteKey, title, categoryId, userSummary) }

    fun bookmarkNote(noteKey: String, bookmarked: Boolean) {
        viewModelScope.launch {
            noteOperation { repository.setNoteBookmarked(noteKey, bookmarked) }
                .onFailure { _noteMessage.value = it.message ?: "Could not update bookmark. Please try again." }
        }
    }

    suspend fun createNoteCategory(name: String): Result<NoteCategory> = noteOperation { repository.createNoteCategory(name) }
    suspend fun renameNoteCategory(id: String, name: String): Result<Unit> = noteOperation { repository.renameNoteCategory(id, name) }
    suspend fun deleteNoteCategory(id: String): Result<Unit> = noteOperation { repository.deleteNoteCategory(id) }

    private suspend fun <T> noteOperation(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            check(!AppDataMaintenance.busy.value) { "Wait for the backup, restore or deletion to finish." }
            Result.success(action())
        }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Result.failure(error) }
    }

    private val recordingDetails = RecordingDetailsCache { recording ->
        runCatching {
            val audio = recordingAudioFile(application, checkNotNull(recording.audioFileName))
            alignedWordCues(recording.text, NativeWordTimingFile.read(audio), recording.durationMs)
        }.getOrDefault(emptyList())
    }

    /** Native timing files are needed only by the opened player, never by live capture or library cards. */
    suspend fun loadRecordingDetails(recording: SavedRecording): SavedRecording = withContext(Dispatchers.IO) {
        recordingDetails.load(recording)
    }

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
        if (AppDataMaintenance.busy.value) return
        if (model == null || modelDownloadManager.isDownloaded(model)) speechSettingsStore.selectModel(model)
    }

    fun selectSpeechLanguage(language: SpeechLanguage) {
        if (!AppDataMaintenance.busy.value) speechSettingsStore.selectLanguage(language)
    }

    fun saveAudioInputMode(mode: AudioInputMode) {
        if (!AppDataMaintenance.busy.value) apiKeyStore.saveAudioInputMode(mode)
    }

    fun saveSettings(
        provider: LlmProvider,
        model: String,
        apiKey: String,
        audioInputMode: AudioInputMode
    ) {
        viewModelScope.launch {
            if (AppDataMaintenance.busy.value) return@launch
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

    fun startListening() {
        if (AppDataMaintenance.busy.value) {
            ServiceStateTracker.lastTranscriptionError.value = "Finish the backup, restore or deletion before recording."
            return
        }
        recordingPlayer.pause()
        ForegroundListeningService.start(getApplication())
    }

    fun stopListening() = ForegroundListeningService.stop(getApplication())

    fun retrySummary() {
        if (AppDataMaintenance.busy.value) return
        val app = getApplication<LiveNotesApplication>()
        app.appContainer.conversationOrchestrator.retrySummary()
    }

    fun downloadModel(model: SpeechModel) {
        if (AppDataMaintenance.busy.value) return
        viewModelScope.launch(Dispatchers.IO) { modelDownloadManager.download(model) }
    }

    fun deleteModel(model: SpeechModel) {
        if (AppDataMaintenance.busy.value || ServiceStateTracker.capturePhase.value != CapturePhase.IDLE) return
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
