package com.sainadh.livenotes.desktop

import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val modelId: String = SpeechModel.NEMOTRON_ENGLISH.id,
    val languageCode: String = "en-US",
    val microphoneId: String = "",
    val providerId: String = "OPENAI",
    val summaryModel: String = "",
    val autoSummaries: Boolean = false,
    val autoSpeakers: Boolean = true
)

enum class CapturePhase { IDLE, PREPARING, RECORDING, SAVING }

data class CaptureView(
    val phase: CapturePhase = CapturePhase.IDLE,
    val recordingId: String? = null,
    val durationMs: Long = 0,
    val level: Float = 0f,
    val preview: String = "",
    val segments: List<TranscriptUpdate> = emptyList(),
    val hasEarlierText: Boolean = false
) { val active: Boolean get() = phase != CapturePhase.IDLE }

data class Microphone(val id: String, val name: String)
data class RecordingEntry(
    val id: String,
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val hasAudio: Boolean,
    val preview: String,
    val speakerCount: Int = 0,
    val interrupted: Boolean = false
)
data class SpeakerName(val id: String, val name: String)
data class TranscriptTurn(
    val id: Int,
    val speakerId: String?,
    val startMs: Long?,
    val endMs: Long?,
    val startChar: Int,
    val endChar: Int,
    val overlapping: Boolean = false
)
data class RecordingDocument(
    val entry: RecordingEntry,
    val text: String,
    val words: List<WordCue> = emptyList(),
    val turns: List<TranscriptTurn> = emptyList(),
    val speakers: List<SpeakerName> = emptyList(),
    val summary: String = "",
    val actionItems: List<String> = emptyList(),
    val speakerStatus: String = "Not analyzed"
)
data class PlaybackView(
    val recordingId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val speed: Float = 1f
)
data class DownloadView(
    val modelId: String,
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val fraction: Float? = null,
    val message: String = ""
)
data class SpeakerJobView(
    val recordingId: String? = null,
    val active: Boolean = false,
    val fraction: Float? = null,
    val message: String = "",
    val modelsInstalled: Boolean = false,
    val installing: Boolean = false
)
data class AppState(
    val initializing: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val microphones: List<Microphone> = emptyList(),
    val capture: CaptureView = CaptureView(),
    val recordings: List<RecordingEntry> = emptyList(),
    val selected: RecordingDocument? = null,
    val loadingRecording: Boolean = false,
    val playback: PlaybackView = PlaybackView(),
    val downloads: List<DownloadView> = SpeechModel.entries.map { DownloadView(it.id) },
    val speakerJob: SpeakerJobView = SpeakerJobView(),
    val liveFullText: String? = null,
    val apiKeySaved: Boolean = false,
    val summaryBusy: Boolean = false,
    val connectionBusy: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

/** UI callbacks return immediately; the controller owns background work and errors. */
interface DesktopActions {
    fun startRecording()
    fun stopRecording()
    fun refreshMicrophones()
    fun selectRecording(id: String)
    fun closeRecording()
    fun renameRecording(id: String, title: String)
    fun deleteRecording(id: String)
    fun playPause()
    fun playFrom(positionMs: Long)
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    fun copyTranscript(recordingId: String?)
    fun exportTranscript(recordingId: String?)
    fun exportAudio(recordingId: String)
    fun copySummary(recordingId: String)
    fun exportSummary(recordingId: String)
    fun openFullTranscript()
    fun closeFullTranscript()
    fun updateSettings(settings: AppSettings)
    fun saveApiKey(key: String)
    fun deleteApiKey()
    fun testConnection()
    fun summarize(recordingId: String)
    fun downloadModel(modelId: String)
    fun importModel(modelId: String)
    fun openModelDownloads()
    fun cancelModelDownload(modelId: String)
    fun removeModel(modelId: String)
    fun installSpeakerModels()
    fun analyzeSpeakers(recordingId: String, speakerCount: Int? = null)
    fun cancelSpeakerJob()
    fun renameSpeaker(recordingId: String, speakerId: String, name: String)
    fun mergeSpeakers(recordingId: String, sourceSpeakerId: String, targetSpeakerId: String)
    fun assignTurnSpeaker(recordingId: String, turnId: Int, speakerId: String?)
    fun dismissError()
    fun dismissNotice()
}
