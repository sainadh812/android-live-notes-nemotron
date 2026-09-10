package com.sainadh.livenotes
import android.app.Application
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import com.sainadh.livenotes.stt.SpeechModel as TestModel
class MainActivity
object R {
    object string {
        const val notification_listening_title = 1
        const val notification_channel_name = 2
        const val notification_channel_description = 3
    }
}
class LiveNotesApplication : Application() {
    val appContainer = AppContainer()
    companion object { var instance = LiveNotesApplication() }
}
class AppContainer {
    val modelDownloadManager = DownloadManager()
    val secureSettings = Settings()
    val speechSettings = SpeechSettingsStore(modelDownloadManager)
    var recordingRecovery = CompletableDeferred(Unit)
    val repository = Repository()
    val conversationOrchestrator = Orchestrator(repository)
}
class DownloadManager {
    var useNative = false
    var leased = false
    fun acquireForCapture(model: TestModel): Boolean { leased = useNative; return leased }
    fun releaseFromCapture(model: TestModel) { leased = false }
    fun isDownloaded(model: TestModel) = useNative
    fun modelFile(model: TestModel) = File("test.gguf")
}
data class TestLanguage(val code: String = "en-US")
data class TestSpeechSettings(val model: TestModel?, val language: TestLanguage = TestLanguage())
class SpeechSettingsStore(private val manager: DownloadManager) {
    val state get() = kotlinx.coroutines.flow.MutableStateFlow(TestSpeechSettings(if (manager.useNative) TestModel() else null))
}
class Settings { fun readAudioInputMode() = "phone" }
data class StartedRecording(val id: String, val timestampMs: Long)
data class FinishedRecording(val id: String, val audioFileName: String?, val durationMs: Long)
class Repository {
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    val starts = java.util.Collections.synchronizedList(mutableListOf<StartedRecording>())
    val finishes = java.util.Collections.synchronizedList(mutableListOf<FinishedRecording>())
    val beginEntered = CountDownLatch(1)
    val finishEntered = CountDownLatch(1)
    var beginGate: CountDownLatch? = null
    var finishGate: CountDownLatch? = null
    var failBegin = false
    var failFinish = false
    suspend fun beginRecording(recordingId: String, timestampMs: Long) {
        beginEntered.countDown()
        check(beginGate?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting for begin gate" }
        check(!failBegin) { "Recording database unavailable" }
        starts += StartedRecording(recordingId, timestampMs)
        events += "begin:$recordingId"
    }
    suspend fun finishRecording(recordingId: String, audioFileName: String?, durationMs: Long) {
        finishEntered.countDown()
        check(finishGate?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting for finish gate" }
        check(!failFinish) { "Recording metadata unavailable" }
        check(starts.any { it.id == recordingId }) { "Finish before begin" }
        finishes += FinishedRecording(recordingId, audioFileName, durationMs)
        events += "finish:$recordingId"
    }
}
class Orchestrator(private val repository: Repository) {
    val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
    val updates = java.util.Collections.synchronizedList(mutableListOf<Pair<String, TranscriptUpdate>>())
    val entered = CountDownLatch(1)
    var gate: CountDownLatch? = null
    var failWrite = false
    suspend fun onTranscript(recordingId: String, update: TranscriptUpdate, timestampMs: Long): Result<Unit> {
        entered.countDown()
        check(gate?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting for test write gate" }
        check(repository.starts.any { it.id == recordingId }) { "Transcript before begin" }
        if (failWrite) return Result.failure(IllegalStateException("Transcript storage unavailable"))
        writes += "${update.status == TranscriptStatus.FINAL}:${update.text}"
        updates += recordingId to update
        repository.events += "transcript:${update.text}"
        return Result.success(Unit)
    }
}
