package com.sainadh.livenotes.desktop

import com.sainadh.livenotes.ai.ChatCompletionClient
import com.sainadh.livenotes.ai.LlmConnectionRequest
import com.sainadh.livenotes.ai.LlmProvider
import com.sainadh.livenotes.ai.LlmSummaryRequest
import com.sainadh.livenotes.desktop.audio.AudioDevices
import com.sainadh.livenotes.desktop.audio.DesktopPlayer
import com.sainadh.livenotes.desktop.data.AppPaths
import com.sainadh.livenotes.desktop.data.ModelStore
import com.sainadh.livenotes.desktop.data.RecordingStore
import com.sainadh.livenotes.desktop.data.SecretStore
import com.sainadh.livenotes.desktop.data.SettingsStore
import com.sainadh.livenotes.desktop.data.WindowsSecretStore
import com.sainadh.livenotes.desktop.data.exportSummary
import com.sainadh.livenotes.desktop.data.exportTranscript
import com.sainadh.livenotes.desktop.speakers.DiarizationClient
import com.sainadh.livenotes.desktop.speakers.SpeakerWord
import com.sainadh.livenotes.desktop.speakers.assignSpeakerTurns
import com.sainadh.livenotes.desktop.stt.DesktopRecorder
import com.sainadh.livenotes.stt.LiveTranscriptBuffer
import com.sainadh.livenotes.stt.SpeechLanguage
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DesktopController(
    private val paths: AppPaths,
    private val store: RecordingStore = RecordingStore(paths),
    private val secrets: SecretStore = WindowsSecretStore(paths.root)
) : DesktopActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val settingsStore = SettingsStore(paths.root)
    private val modelStore = ModelStore(paths.models)
    private val aiHttp = okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
    private val ai = ChatCompletionClient(aiHttp)
    private val speakerClient = DiarizationClient(
        File(AppPaths.resources(), "speaker-worker").toPath(), paths.speakerModels.toPath(), paths.work.toPath())
    private var transcript = LiveTranscriptBuffer()
    @Volatile private var currentId: String? = null
    private var preparation: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private var finishSignal = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var loadJob: Job? = null
    private var summaryJob: Job? = null
    private var speakerJob: Job? = null
    private val downloads = mutableMapOf<String, Job>()
    private var closed = false
    private var ready = false
    private val settingsMutex = Mutex()
    private val secretsMutex = Mutex()
    private var playbackId: String? = null
    private var playedFile: File? = null
    private val deletingIds = mutableSetOf<String>()
    private var lastAutomaticSummary = 0L
    private var fullTextRequest = 0L
    private val events = Channel<RecordingEvent>(256)
    private val persistenceErrors = mutableMapOf<String, String>() // Owned by the event consumer.
    private val player = DesktopPlayer { playback ->
        mutableState.update { it.copy(playback = PlaybackView(playbackId, playback.positionMs, playback.durationMs, playback.isPlaying, playback.speed)) }
        playback.error?.let(::showError)
    }
    private val recorder = DesktopRecorder(
        onTranscriptUpdate = { update -> currentId?.let { events.trySendBlocking(RecordingEvent.Text(it, update)).getOrThrow() } },
        onLevel = { duration, level -> mutableState.update { it.copy(capture = it.capture.copy(durationMs = duration, level = level)) } },
        onFinished = { file, duration, _, error -> currentId?.let { events.trySendBlocking(RecordingEvent.Finished(it, file, duration, error, finishSignal)).getOrThrow() } },
        onPhase = { phase ->
            val mapped = when (phase) { "preparing" -> CapturePhase.PREPARING; "recording" -> CapturePhase.RECORDING; "saving" -> CapturePhase.SAVING; else -> null }
            if (mapped != null) mutableState.update { it.copy(capture = it.capture.copy(phase = mapped)) }
        })

    init {
        scope.launch(Dispatchers.IO) {
            for (event in events) {
                try {
                    when (event) {
                        is RecordingEvent.Text -> {
                            store.saveSegment(event.id, event.update)
                            if (event.id == currentId) {
                                val preview = transcript.update(event.update)
                                if (preview != null) mutableState.update { state -> state.copy(capture = state.capture.copy(
                                    preview = preview.text, segments = preview.segments, hasEarlierText = preview.hasEarlierText)) }
                            }
                        }
                        is RecordingEvent.Finished -> {
                            val failure = persistenceErrors.remove(event.id) ?: event.error
                            store.finish(event.id, event.file != null, event.durationMs, transcript.fullText().take(200), failure != null)
                            if (event.id == currentId) {
                                currentId = null
                                mutableState.update { it.copy(capture = it.capture.copy(phase = CapturePhase.IDLE, level = 0f)) }
                            }
                            refreshLibrary()
                            failure?.let(::showError)
                            event.completion.complete(Unit)
                            withContext(Dispatchers.Main) {
                                if (!closed && event.file != null && mutableState.value.settings.autoSpeakers && mutableState.value.speakerJob.modelsInstalled) analyzeSpeakers(event.id)
                                if (!closed && mutableState.value.settings.autoSummaries && mutableState.value.apiKeySaved) summarize(event.id)
                            }
                        }
                    }
                } catch (error: Throwable) {
                    recorder.stop()
                    val message = "Could not save meeting data: ${error.message}. The transcript may be incomplete; audio may be recovered when the app restarts."
                    if (event is RecordingEvent.Text) persistenceErrors.putIfAbsent(event.id, message)
                    showError(message)
                    if (event is RecordingEvent.Finished) {
                        currentId = null
                        mutableState.update { it.copy(capture = it.capture.copy(phase = CapturePhase.IDLE, level = 0f)) }
                        event.completion.complete(Unit)
                    }
                }
            }
        }
        launchOperation {
            val settings = withContext(Dispatchers.IO) { runCatching { settingsStore.load() }.getOrElse {
                showError("Saved settings could not be read. Default settings are active; your meeting library is preserved.")
                AppSettings()
            } }
            val model = SpeechModel.fromId(settings.modelId) ?: SpeechModel.NEMOTRON_ENGLISH
            val language = model.languages.firstOrNull { it.code == settings.languageCode } ?: model.languages.first()
            val validated = settings.copy(modelId = model.id, languageCode = language.code,
                providerId = runCatching { LlmProvider.valueOf(settings.providerId).name }.getOrDefault("OPENAI"))
            mutableState.update { it.copy(settings = validated) }
            val recovered = withContext(Dispatchers.IO) { store.recoverInterrupted() }
            refreshLibrary()
            refreshDownloads()
            refreshKey()
            refreshMicrophones()
            val installed = withContext(Dispatchers.IO) { speakerClient.modelsInstalled() }
            mutableState.update { it.copy(speakerJob = it.speakerJob.copy(modelsInstalled = installed)) }
            if (recovered > 0) showNotice("Recovered $recovered interrupted meeting${if (recovered == 1) "" else "s"} in your library.")
            ready = true
            mutableState.update { it.copy(initializing = false) }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                val snapshot = mutableState.value
                val id = currentId
                if (snapshot.capture.phase == CapturePhase.RECORDING && snapshot.settings.autoSummaries && snapshot.apiKeySaved && id != null &&
                    !snapshot.summaryBusy && System.currentTimeMillis() - lastAutomaticSummary >= 30_000) {
                    lastAutomaticSummary = System.currentTimeMillis()
                    summarize(id)
                }
            }
        }
    }
    private sealed interface RecordingEvent {
        data class Text(val id: String, val update: TranscriptUpdate) : RecordingEvent
        data class Finished(val id: String, val file: File?, val durationMs: Long, val error: String?, val completion: CompletableDeferred<Unit>) : RecordingEvent
    }
    private fun showError(message: String) { mutableState.update { it.copy(error = message.take(1500)) } }
    private fun showNotice(message: String) { mutableState.update { it.copy(notice = message) } }
    private fun launchOperation(action: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            mutableState.update { it.copy(initializing = false) }
            showError(error.message ?: "The operation could not be completed.")
        }
    }
    private suspend fun refreshLibrary() {
        if (mutableState.value.capture.active) return
        val recordings = withContext(Dispatchers.IO) { store.list() }
        mutableState.update { it.copy(recordings = recordings) }
    }
    private suspend fun refreshDownloads() {
        val values = withContext(Dispatchers.IO) { SpeechModel.entries.map { DownloadView(it.id, modelStore.installed(it)) } }
        mutableState.update { it.copy(downloads = values) }
    }
    private suspend fun refreshKey() {
        val provider = mutableState.value.settings.providerId
        val saved = withContext(Dispatchers.IO) { runCatching { secrets.read(provider).isNotBlank() }.getOrElse {
            showError("The saved AI key could not be unlocked. Save it again in Settings to use summaries; local recording is available.")
            false
        } }
        mutableState.update { if (it.settings.providerId == provider) it.copy(apiKeySaved = saved) else it }
    }
    override fun startRecording() {
        if (mutableState.value.capture.active || closed || !ready) return
        if (mutableState.value.speakerJob.active) { showError("Wait for speaker analysis to finish, or cancel it before recording."); return }
        player.pause()
        stopRequested.set(false)
        transcript = LiveTranscriptBuffer()
        finishSignal = CompletableDeferred()
        mutableState.update { it.copy(capture = CaptureView(phase = CapturePhase.PREPARING), liveFullText = null, error = null) }
        val settings = mutableState.value.settings
        preparation = launchOperation {
            try {
                val model = checkNotNull(SpeechModel.fromId(settings.modelId))
                val file = modelStore.verify(model)
                if (stopRequested.get()) return@launchOperation
                val id = UUID.randomUUID().toString()
                withContext(Dispatchers.IO) { store.begin(id, System.currentTimeMillis()) }
                currentId = id
                mutableState.update { it.copy(capture = it.capture.copy(recordingId = id)) }
                recorder.start(file, settings.languageCode, settings.microphoneId.ifBlank { null }, paths.audio(id))
                if (stopRequested.get()) recorder.stop()
            } finally {
                if (currentId == null) {
                    mutableState.update { it.copy(capture = it.capture.copy(phase = CapturePhase.IDLE)) }
                    finishSignal.complete(Unit)
                }
            }
        }
    }
    override fun stopRecording() {
        if (!mutableState.value.capture.active) return
        stopRequested.set(true)
        mutableState.update { it.copy(capture = it.capture.copy(phase = CapturePhase.SAVING)) }
        recorder.stop()
    }
    override fun refreshMicrophones() { launchOperation {
        val inputs = withContext(Dispatchers.IO) { AudioDevices.inputs().map { Microphone(it.id, it.name) } }
        mutableState.update { it.copy(microphones = inputs) }
    } }
    override fun selectRecording(id: String) {
        loadJob?.cancel()
        if (playbackId != id) { player.pause(); playbackId = null; playedFile = null }
        mutableState.update { it.copy(selected = null, loadingRecording = true) }
        loadJob = launchOperation {
            try {
                val document = withContext(Dispatchers.IO) { store.document(id) }
                ensureActive()
                mutableState.update { it.copy(selected = document) }
            } finally { mutableState.update { it.copy(loadingRecording = false) } }
        }
    }
    override fun closeRecording() { loadJob?.cancel(); player.pause(); mutableState.update { it.copy(selected = null, loadingRecording = false) } }
    private suspend fun reloadSelected(id: String) {
        if (mutableState.value.selected?.entry?.id != id) return
        val document = withContext(Dispatchers.IO) { store.document(id) }
        mutableState.update { if (it.selected?.entry?.id == id) it.copy(selected = document) else it }
    }
    override fun renameRecording(id: String, title: String) { launchOperation { withContext(Dispatchers.IO) { store.rename(id, title) }; refreshLibrary(); reloadSelected(id) } }
    override fun deleteRecording(id: String) {
        if (!deletingIds.add(id)) return
        launchOperation { try {
            check(currentId != id && mutableState.value.speakerJob.recordingId != id) { "Wait until this meeting has finished processing." }
            if (playbackId == id) { withContext(Dispatchers.IO) { player.unload() }; playedFile = null; playbackId = null }
            withContext(Dispatchers.IO) { store.delete(id) }
            if (mutableState.value.selected?.entry?.id == id) closeRecording()
            refreshLibrary()
        } finally { deletingIds.remove(id) } }
    }
    private fun preparePlayback(): Boolean {
        if (mutableState.value.capture.active) { showError("Stop recording before playing audio."); return false }
        val document = mutableState.value.selected ?: return false
        if (document.entry.id in deletingIds) return false
        if (!document.entry.hasAudio) { showError("This meeting has no saved audio."); return false }
        val file = paths.audio(document.entry.id)
        if (playedFile != file) {
            playbackId = document.entry.id; playedFile = file
            mutableState.update { it.copy(playback = PlaybackView(recordingId = document.entry.id, durationMs = document.entry.durationMs)) }
            player.load(file)
        }
        return true
    }
    override fun playPause() { runCatching {
        if (!preparePlayback()) return
        if (mutableState.value.playback.playing) player.pause() else player.play()
    }.onFailure { showError(it.message ?: "Could not play audio.") } }
    override fun seekTo(positionMs: Long) { runCatching { if (preparePlayback()) player.seekTo(positionMs) }.onFailure { showError(it.message ?: "Could not seek audio.") } }
    override fun setPlaybackSpeed(speed: Float) { runCatching { player.setSpeed(speed) }.onFailure { showError(it.message ?: "Could not change playback speed.") } }
    private suspend fun transcriptFor(id: String?): String = if (id == null) {
        val source = transcript; withContext(Dispatchers.Default) { source.fullText() }
    } else withContext(Dispatchers.IO) { store.document(id)?.let(::exportTranscript).orEmpty() }
    override fun copyTranscript(recordingId: String?) { launchOperation { copy(transcriptFor(recordingId)) } }
    override fun exportTranscript(recordingId: String?) { launchOperation { saveText(transcriptFor(recordingId), "meeting-transcript.txt") } }
    override fun copySummary(recordingId: String) { launchOperation { copy(withContext(Dispatchers.IO) { store.document(recordingId)?.let(::exportSummary).orEmpty() }) } }
    override fun exportSummary(recordingId: String) { launchOperation { saveText(withContext(Dispatchers.IO) { store.document(recordingId)?.let(::exportSummary).orEmpty() }, "meeting-summary.txt") } }
    private fun copy(text: String) {
        check(text.isNotBlank()) { "There is no text to copy yet." }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        showNotice("Copied to clipboard.")
    }
    private fun chooseSave(name: String): File? {
        val dialog = FileDialog(null as Frame?, "Save a copy", FileDialog.SAVE)
        return try { dialog.file = name; dialog.isVisible = true; dialog.file?.let { File(dialog.directory, it) } }
        finally { dialog.dispose() }
    }
    private suspend fun saveText(text: String, name: String) {
        check(text.isNotBlank()) { "There is no text to export yet." }
        val target = chooseSave(name) ?: return
        withContext(Dispatchers.IO) { safeExport(target) { temporary -> temporary.writeText(text, Charsets.UTF_8) } }
        showNotice("Saved ${target.name}.")
    }
    override fun exportAudio(recordingId: String) { launchOperation {
        check(currentId != recordingId) { "Stop recording before exporting its audio." }
        val source = paths.audio(recordingId)
        check(source.isFile) { "This meeting has no saved audio." }
        val target = chooseSave("meeting-audio.wav") ?: return@launchOperation
        withContext(Dispatchers.IO) { safeExport(target) { temporary -> Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING) } }
        showNotice("Saved ${target.name}.")
    } }
    private fun safeExport(target: File, write: (File) -> Unit) {
        val canonical = target.canonicalFile.toPath()
        check(!canonical.startsWith(paths.root.canonicalFile.toPath())) { "Choose a location outside the app's data folder." }
        val temporary = Files.createTempFile(canonical.parent, ".livenotes-export-", ".tmp")
        try {
            write(temporary.toFile())
            try { Files.move(temporary, canonical, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, canonical, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
    override fun openFullTranscript() {
        val request = ++fullTextRequest
        val source = transcript
        launchOperation {
            val text = withContext(Dispatchers.Default) { source.fullText() }
            if (request == fullTextRequest && source === transcript) mutableState.update { it.copy(liveFullText = text) }
        }
    }
    override fun closeFullTranscript() { ++fullTextRequest; mutableState.update { it.copy(liveFullText = null) } }
    override fun updateSettings(settings: AppSettings) {
        if (mutableState.value.capture.active) { showError("Settings can be changed after recording finishes."); return }
        val model = SpeechModel.fromId(settings.modelId) ?: return
        if (model.languages.none { it.code == settings.languageCode } || LlmProvider.entries.none { it.name == settings.providerId }) return
        // Publish synchronously so a following UI edit starts from the latest values.
        mutableState.update { it.copy(settings = settings) }
        launchOperation {
            settingsMutex.withLock { withContext(Dispatchers.IO) { settingsStore.save(mutableState.value.settings) } }
            refreshKey()
        }
    }
    override fun saveApiKey(key: String) {
        val provider = mutableState.value.settings.providerId
        launchOperation { secretsMutex.withLock { withContext(Dispatchers.IO) { secrets.save(provider, key) } }; refreshKey(); showNotice("API key saved with Windows account encryption.") }
    }
    override fun deleteApiKey() {
        val provider = mutableState.value.settings.providerId
        launchOperation { secretsMutex.withLock { withContext(Dispatchers.IO) { secrets.delete(provider) } }; refreshKey() }
    }
    override fun testConnection() { launchOperation {
        if (mutableState.value.connectionBusy) return@launchOperation
        mutableState.update { it.copy(connectionBusy = true) }
        try {
            val settings = mutableState.value.settings
            val key = withContext(Dispatchers.IO) { secrets.read(settings.providerId) }
            check(key.isNotBlank()) { "Save your API key first." }
            ai.testConnection(LlmConnectionRequest(LlmProvider.valueOf(settings.providerId), key, settings.summaryModel)).getOrThrow()
            showNotice("Connection successful.")
        } finally { mutableState.update { it.copy(connectionBusy = false) } }
    } }
    override fun dismissError() { mutableState.update { it.copy(error = null) } }
    override fun dismissNotice() { mutableState.update { it.copy(notice = null) } }

    override fun downloadModel(modelId: String) {
        if (downloads[modelId]?.isActive == true) return
        val model = SpeechModel.fromId(modelId) ?: return
        if (mutableState.value.capture.active) { showError("Download models after recording finishes."); return }
        fun status(value: DownloadView) { mutableState.update { it.copy(downloads = it.downloads.map { row -> if (row.modelId == modelId) value else row }) } }
        status(DownloadView(modelId, downloading = true, message = "Connecting…"))
        downloads[modelId] = launchOperation {
            try {
                modelStore.download(model) { fraction, message -> status(DownloadView(modelId, downloading = true, fraction = fraction, message = message)) }
                status(DownloadView(modelId, installed = true, message = "Ready"))
            } catch (cancelled: CancellationException) {
                status(DownloadView(modelId, installed = modelStore.installed(model), message = "Paused. Download again to resume."))
                throw cancelled
            } catch (error: Throwable) {
                status(DownloadView(modelId, installed = modelStore.installed(model), message = "Download failed. Retry to resume."))
                throw error
            }
        }
    }
    override fun cancelModelDownload(modelId: String) {
        SpeechModel.fromId(modelId)?.let(modelStore::cancel)
        downloads[modelId]?.cancel()
    }
    override fun removeModel(modelId: String) { launchOperation {
        check(!mutableState.value.capture.active && downloads[modelId]?.isActive != true) { "Finish recording and cancel this download before removing the model." }
        SpeechModel.fromId(modelId)?.let { withContext(Dispatchers.IO) { modelStore.remove(it) } }
        refreshDownloads()
    } }
    override fun installSpeakerModels() {
        if (mutableState.value.speakerJob.active || mutableState.value.capture.active) return
        mutableState.update { it.copy(speakerJob = it.speakerJob.copy(active = true, installing = true, message = "Preparing speaker models…")) }
        speakerJob = launchOperation {
            try {
                speakerClient.installModels { progress -> mutableState.update { it.copy(speakerJob = it.speakerJob.copy(
                    fraction = progress.fraction?.toFloat(), message = progress.message)) } }
                mutableState.update { it.copy(speakerJob = it.speakerJob.copy(modelsInstalled = true, message = "Speaker models ready")) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(speakerJob = it.speakerJob.copy(message = "Speaker setup canceled. You can retry.")) }
                throw cancelled
            } finally { mutableState.update { it.copy(speakerJob = it.speakerJob.copy(active = false, installing = false, fraction = null)) } }
        }
    }
    override fun analyzeSpeakers(recordingId: String, speakerCount: Int?) {
        val state = mutableState.value
        if (state.capture.active || state.speakerJob.active) return
        if (!state.speakerJob.modelsInstalled) { showError("Download speaker models in Settings first."); return }
        mutableState.update { it.copy(speakerJob = it.speakerJob.copy(recordingId = recordingId, active = true, fraction = null, message = "Finding speakers…")) }
        speakerJob = launchOperation {
            try {
                val document = withContext(Dispatchers.IO) { checkNotNull(store.document(recordingId)) }
                check(document.entry.hasAudio) { "Speaker analysis needs saved audio." }
                val result = speakerClient.analyze(paths.audio(recordingId).toPath(), speakerCount) { progress -> mutableState.update { it.copy(
                    speakerJob = it.speakerJob.copy(fraction = progress.fraction?.toFloat(), message = progress.message)) } }
                val (names, turns) = withContext(Dispatchers.Default) {
                    val tokens = Regex("[^\\s\\p{Z}]+").findAll(document.text).toList()
                    val timing = document.words.associateBy { it.startChar }
                    val words = tokens.map { token ->
                        val cue = timing[token.range.first]?.takeIf { it.endChar == token.range.last + 1 }
                        SpeakerWord(token.value, cue?.startMs, cue?.endMs)
                    }
                    val assigned = assignSpeakerTurns(words, result.spans)
                    val names = result.spans.sortedBy { it.startMs }.map { it.speakerId }.distinct().mapIndexed { index, id -> SpeakerName(id, "Speaker ${index + 1}") }
                    val turns = assigned.mapIndexed { index, turn ->
                        val first = tokens[turn.startWordIndex]
                        val last = tokens[turn.endWordIndexExclusive - 1]
                        TranscriptTurn(index, turn.speakerId, turn.startMs, turn.endMs, first.range.first, last.range.last + 1, turn.overlapping)
                    }
                    names to turns
                }
                withContext(Dispatchers.IO) { store.saveSpeakers(recordingId, names, turns) }
                refreshLibrary(); reloadSelected(recordingId)
                mutableState.update { it.copy(speakerJob = it.speakerJob.copy(message = if (names.isEmpty()) "No speakers detected" else "Found ${names.size} speakers. Open the meeting to name them.")) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(speakerJob = it.speakerJob.copy(message = "Speaker analysis canceled. Your recording is saved.")) }
                throw cancelled
            } finally {
                mutableState.update { it.copy(speakerJob = it.speakerJob.copy(active = false, recordingId = null, fraction = null)) }
            }
        }
    }
    override fun cancelSpeakerJob() { speakerJob?.cancel() }
    override fun renameSpeaker(recordingId: String, speakerId: String, name: String) { launchOperation {
        withContext(Dispatchers.IO) { store.renameSpeaker(recordingId, speakerId, name) }; reloadSelected(recordingId)
    } }
    override fun mergeSpeakers(recordingId: String, sourceSpeakerId: String, targetSpeakerId: String) { launchOperation {
        withContext(Dispatchers.IO) { store.mergeSpeakers(recordingId, sourceSpeakerId, targetSpeakerId) }
        refreshLibrary(); reloadSelected(recordingId)
    } }
    override fun assignTurnSpeaker(recordingId: String, turnId: Int, speakerId: String?) { launchOperation {
        withContext(Dispatchers.IO) { store.assignSpeaker(recordingId, turnId, speakerId) }; reloadSelected(recordingId)
    } }
    override fun summarize(recordingId: String) {
        if (summaryJob?.isActive == true) return
        mutableState.update { it.copy(summaryBusy = true) }
        val settings = mutableState.value.settings
        summaryJob = launchOperation {
            try {
                val key = withContext(Dispatchers.IO) { secrets.read(settings.providerId) }
                check(key.isNotBlank()) { "Save your AI provider API key in Settings first." }
                val segments = withContext(Dispatchers.IO) { store.segments(recordingId) }
                check(segments.any { it.text.isNotBlank() }) { "There is no transcript to summarize yet." }
                // Bound individual requests; process every segment in chronological order.
                // Rebuild from the snapshot so revised tentative text never accumulates twice.
                val batches = mutableListOf<List<SummaryPiece>>()
                var batch = mutableListOf<SummaryPiece>()
                var chars = 0
                for (segment in segments) {
                    val pieces = segment.text.chunked(8_000).ifEmpty { listOf("") }
                    pieces.forEachIndexed { part, text ->
                        if (batch.isNotEmpty() && chars + text.length > 12_000) { batches += batch; batch = mutableListOf(); chars = 0 }
                        batch += SummaryPiece("${segment.segmentId}:$part", text, segment.status.name, segment.appendToPrevious || part > 0)
                        chars += text.length
                    }
                }
                if (batch.isNotEmpty()) batches += batch
                var summary = ""
                var context = ""
                var actions = emptyList<String>()
                for (pieces in batches) {
                    ensureActive()
                    val payload = buildJsonArray { pieces.forEach { piece -> add(buildJsonObject {
                        put("recordingId", recordingId); put("segmentId", piece.id); put("revision", 1)
                        put("status", piece.status); put("appendToPrevious", piece.append); put("text", piece.text)
                    }) } }.toString()
                    val result = ai.summarizeConversation(LlmSummaryRequest(LlmProvider.valueOf(settings.providerId), key,
                        settings.summaryModel, summary, context, payload)).getOrThrow()
                    ensureActive()
                    summary = result.summary; context = result.runningContext; actions = (actions + result.actionItems).distinct()
                }
                withContext(Dispatchers.IO) { store.saveSummary(recordingId, summary, context, actions) }
                reloadSelected(recordingId)
                if (currentId != recordingId) showNotice("Meeting summary saved.")
            } finally { mutableState.update { it.copy(summaryBusy = false) } }
        }
    }
    private data class SummaryPiece(val id: String, val text: String, val status: String, val append: Boolean)

    /** Window close awaits saved PCM, database writes, and process shutdown. */
    suspend fun shutdown() {
        if (closed) return
        closed = true
        stopRecording()
        preparation?.join()
        finishSignal.await()
        downloads.keys.toList().forEach(::cancelModelDownload)
        downloads.values.toList().forEach { it.join() }
        speakerJob?.cancel(); speakerJob?.join()
        aiHttp.dispatcher.cancelAll()
        summaryJob?.cancel(); summaryJob?.join()
        player.close(); recorder.close()
        events.close()
        scope.cancel()
        withContext(Dispatchers.IO) { store.close() }
    }
}
