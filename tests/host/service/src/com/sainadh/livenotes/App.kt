package com.sainadh.livenotes
import android.app.Application
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    val conversationOrchestrator = Orchestrator()
}
class DownloadManager {
    var useNative = false
    fun findAnyDownloaded(): Int? = if (useNative) 1 else null
    fun modelFile(quant: Int) = File("test.gguf")
}
class Settings { fun readAudioInputMode() = "phone" }
class Orchestrator {
    val writes = java.util.Collections.synchronizedList(mutableListOf<String>())
    val entered = CountDownLatch(1)
    var gate: CountDownLatch? = null
    suspend fun onTranscript(text: String, isFinal: Boolean, timestampMs: Long): Result<Unit> {
        entered.countDown()
        check(gate?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting for test write gate" }
        writes += "$isFinal:$text"
        return Result.success(Unit)
    }
}
