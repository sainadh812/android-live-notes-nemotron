package com.sainadh.livenotes
import android.app.Application
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    val conversationOrchestrator = Orchestrator()
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
class Orchestrator {
    val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
    val updates = java.util.Collections.synchronizedList(mutableListOf<Pair<String, TranscriptUpdate>>())
    val entered = CountDownLatch(1)
    var gate: CountDownLatch? = null
    suspend fun onTranscript(recordingId: String, update: TranscriptUpdate, timestampMs: Long): Result<Unit> {
        entered.countDown()
        check(gate?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting for test write gate" }
        writes += "${update.status == TranscriptStatus.FINAL}:${update.text}"
        updates += recordingId to update
        return Result.success(Unit)
    }
}
