import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.PowerManager
import android.speech.SpeechRecognizer
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.audio.BluetoothAudioRouter
import com.sainadh.livenotes.service.ForegroundListeningService
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.NemotronTranscriber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    var passed = 0
    fun drainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out draining main callbacks" }
            Handler.advance(0)
            Thread.sleep(1)
        }
        Handler.advance(0)
    }
    fun scenario(name: String, native: Boolean = true, run: (ForegroundListeningService) -> Unit) {
        Handler.reset()
        LiveNotesApplication.instance = LiveNotesApplication()
        LiveNotesApplication.instance.appContainer.modelDownloadManager.useNative = native
        PowerManager.locks.clear()
        NotificationManager.active = false
        NotificationManager.notifications = 0
        SpeechRecognizer.available = true
        SpeechRecognizer.failStart = false
        SpeechRecognizer.instances.clear()
        val service = ForegroundListeningService()
        service.onCreate()
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_START), 0, 1)
        run(service)
        service.onDestroy()
        check(!NotificationManager.active)
        check(PowerManager.locks.none { it.isHeld })
        check(!BluetoothAudioRouter.active)
        passed += 1
        println("PASS $name")
    }
    scenario("stop preserves final write before service destruction") { service ->
        val transcriber = NemotronTranscriber.last
        val orchestrator = LiveNotesApplication.instance.appContainer.conversationOrchestrator
        orchestrator.gate = CountDownLatch(1)
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        check(transcriber.stopRequested && !service.stopped)
        transcriber.listener.onTranscript("last words", true)
        check(orchestrator.entered.await(2, TimeUnit.SECONDS))
        transcriber.listener.onStateChanged("stopped")
        check(!service.stopped && !transcriber.destroyed)
        check(PowerManager.locks.none { it.isHeld } && !BluetoothAudioRouter.active)
        orchestrator.gate!!.countDown()
        drainUntil { service.stopped }
        check(orchestrator.writes == listOf("true:last words")) { orchestrator.writes }
        check(transcriber.destroyed && !NotificationManager.active)
    }
    scenario("late callbacks cannot recreate notifications or change transcript") { service ->
        val transcriber = NemotronTranscriber.last
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        transcriber.listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        val notifications = NotificationManager.notifications
        val transcript = ServiceStateTracker.latestTranscript.value
        transcriber.listener.onStateChanged("stopped")
        transcriber.listener.onError("late error")
        transcriber.listener.onTranscript("late text", true)
        check(!NotificationManager.active && NotificationManager.notifications == notifications)
        check(ServiceStateTracker.latestTranscript.value == transcript)
    }
    scenario("terminal failure retains error and releases capture resources") { service ->
        NemotronTranscriber.last.listener.onError("model failed")
        NemotronTranscriber.last.listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(!ServiceStateTracker.listening.value)
        check(ServiceStateTracker.lastTranscriptionError.value == "model failed")
        check(PowerManager.locks.none { it.isHeld } && !BluetoothAudioRouter.active)
    }
    scenario("restart after pending stop rejects callbacks from previous session") { service ->
        val first = NemotronTranscriber.last
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_START), 0, 3)
        first.listener.onStateChanged("stopped")
        val second = NemotronTranscriber.last
        check(second !== first && first.destroyed)
        check(ServiceStateTracker.listening.value && !service.stopped)
        first.listener.onStateChanged("stopped")
        first.listener.onError("old error")
        check(ServiceStateTracker.listening.value && ServiceStateTracker.lastTranscriptionError.value == null)
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 4)
        second.listener.onStateChanged("stopped")
        drainUntil { service.stopped }
    }
    scenario("OS recognizer final callback is saved before stop", native = false) { service ->
        val recognizer = SpeechRecognizer.instances.last()
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        check(!service.stopped)
        recognizer.callback.onResults(android.os.Bundle("OS final"))
        drainUntil { service.stopped }
        check(LiveNotesApplication.instance.appContainer.conversationOrchestrator.writes == listOf("true:OS final"))
        val notifications = NotificationManager.notifications
        Handler.advance(10_000)
        check(!NotificationManager.active && NotificationManager.notifications == notifications)
    }
    println("$passed service lifecycle scenarios passed")
}
