import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.PowerManager
import android.speech.SpeechRecognizer
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.AppContainer
import com.sainadh.livenotes.audio.BluetoothAudioRouter
import com.sainadh.livenotes.service.CapturePhase
import com.sainadh.livenotes.service.ForegroundListeningService
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.NemotronTranscriber
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred

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
    fun scenario(name: String, native: Boolean = true, awaitPrepared: Boolean = true,
                 setup: (AppContainer) -> Unit = {}, run: (ForegroundListeningService) -> Unit) {
        Handler.reset()
        LiveNotesApplication.instance = LiveNotesApplication()
        LiveNotesApplication.instance.appContainer.modelDownloadManager.useNative = native
        setup(LiveNotesApplication.instance.appContainer)
        PowerManager.locks.clear()
        NotificationManager.active = false
        NotificationManager.notifications = 0
        SpeechRecognizer.available = true
        SpeechRecognizer.failStart = false
        SpeechRecognizer.instances.clear()
        NemotronTranscriber.instances.clear()
        val service = ForegroundListeningService()
        service.onCreate()
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_START), 0, 1)
        if (awaitPrepared) drainUntil { ServiceStateTracker.listening.value || service.stopped }
        run(service)
        service.onDestroy()
        check(!NotificationManager.active)
        check(!LiveNotesApplication.instance.appContainer.modelDownloadManager.leased)
        check(ServiceStateTracker.capturePhase.value == CapturePhase.IDLE)
        check(PowerManager.locks.none { it.isHeld })
        check(!BluetoothAudioRouter.active)
        passed += 1
        println("PASS $name")
    }
    scenario("UI waits for the microphone and finishes only after saving") { service ->
        check(ServiceStateTracker.capturePhase.value == CapturePhase.PREPARING)
        check(ServiceStateTracker.activeEngine.value == "Test native model")
        check(LiveNotesApplication.instance.appContainer.modelDownloadManager.leased)
        val transcriber = NemotronTranscriber.last
        val recording = LiveNotesApplication.instance.appContainer.repository.starts.single()
        check(recording.id == ServiceStateTracker.recordingId.value && recording.timestampMs > 0L)
        check(transcriber.audioFile?.name == "${recording.id}.wav")
        check(transcriber.audioFile?.parentFile?.name == "recordings")
        transcriber.listener.onStateChanged("loading model")
        check(ServiceStateTracker.capturePhase.value == CapturePhase.PREPARING)
        transcriber.listener.onStateChanged("listening")
        check(ServiceStateTracker.capturePhase.value == CapturePhase.RECORDING)
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        check(ServiceStateTracker.capturePhase.value == CapturePhase.FINISHING)
        transcriber.listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(ServiceStateTracker.capturePhase.value == CapturePhase.IDLE)
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
        val duration = ServiceStateTracker.durationMs.value
        transcriber.listener.onStateChanged("stopped")
        transcriber.listener.onError("late error")
        transcriber.listener.onTranscript("late text", true)
        transcriber.listener.onAudioProgress(9_000, 0.9f)
        transcriber.listener.onAudioSaved("stale.wav", 9_000)
        transcriber.listener.onAudioUnavailable("late audio notice")
        check(!NotificationManager.active && NotificationManager.notifications == notifications)
        check(ServiceStateTracker.latestTranscript.value == transcript)
        check(ServiceStateTracker.durationMs.value == duration && ServiceStateTracker.audioNotice.value == null)
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
        drainUntil { NemotronTranscriber.instances.size == 2 }
        val second = NemotronTranscriber.last
        check(second !== first && first.destroyed)
        check(ServiceStateTracker.listening.value && !service.stopped)
        first.listener.onStateChanged("stopped")
        first.listener.onError("old error")
        first.listener.onAudioProgress(88_000, 1f)
        first.listener.onAudioSaved("old.wav", 88_000)
        check(ServiceStateTracker.durationMs.value == 0L)
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
    scenario("native segments display together but persist as individual revisions") { service ->
        val listener = NemotronTranscriber.last.listener
        listener.onTranscriptUpdate(TranscriptUpdate(0, "hel", TranscriptStatus.PARTIAL, false, true))
        listener.onTranscriptUpdate(TranscriptUpdate(0, "hello ", TranscriptStatus.FINAL, false, true))
        listener.onTranscriptUpdate(TranscriptUpdate(1, "wor", TranscriptStatus.PARTIAL, false, true))
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        listener.onTranscriptUpdate(TranscriptUpdate(1, "world", TranscriptStatus.FINAL, true, true))
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(ServiceStateTracker.latestTranscript.value == "hello world")
        check(ServiceStateTracker.liveSegments.value.map { it.text } == listOf("hello ", "world"))
        val updates = LiveNotesApplication.instance.appContainer.conversationOrchestrator.updates
        check(updates.map { it.second.segmentId } == listOf(0L, 0L, 1L, 1L))
        check(updates.map { it.first }.distinct().size == 1)
        check(updates.last().second.text == "world")
    }
    scenario("interrupted OS utterance reaches storage before its retry result", native = false) { service ->
        val first = SpeechRecognizer.instances.last()
        first.callback.onPartialResults(android.os.Bundle("send proposal Friday"))
        first.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        Handler.advance(1_500)
        SpeechRecognizer.instances.last().callback.onResults(android.os.Bundle("also book a room"))
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        drainUntil { service.stopped }
        val updates = LiveNotesApplication.instance.appContainer.conversationOrchestrator.updates.map { it.second }
        check(updates.map { it.status } == listOf(TranscriptStatus.PARTIAL, TranscriptStatus.INTERRUPTED, TranscriptStatus.FINAL))
        check(updates[0].segmentId == updates[1].segmentId && updates[1].segmentId != updates[2].segmentId)
        check(ServiceStateTracker.latestTranscript.value == "send proposal Friday\nalso book a room")
    }
    scenario("microphone finished state permits queued transcript writes") { service ->
        val listener = NemotronTranscriber.last.listener
        listener.onStateChanged("finishing")
        check(!ServiceStateTracker.listening.value && !service.stopped)
        listener.onTranscriptUpdate(TranscriptUpdate(0, "drained audio", TranscriptStatus.FINAL, true, true))
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(LiveNotesApplication.instance.appContainer.conversationOrchestrator.writes == listOf("true:drained audio"))
    }
    scenario("audio metadata waits for transcript writes and its own database write") { service ->
        val app = LiveNotesApplication.instance.appContainer
        val listener = NemotronTranscriber.last.listener
        val id = ServiceStateTracker.recordingId.value!!
        app.conversationOrchestrator.gate = CountDownLatch(1)
        app.repository.finishGate = CountDownLatch(1)
        listener.onTranscript("first words", false)
        check(app.conversationOrchestrator.entered.await(2, TimeUnit.SECONDS))
        listener.onAudioProgress(1_200, 0.4f)
        check(ServiceStateTracker.durationMs.value == 1_200L && ServiceStateTracker.audioLevel.value == 0.4f)
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        listener.onAudioSaved("$id.wav", 1_250)
        listener.onTranscript("first words complete", true)
        listener.onStateChanged("stopped")
        check(app.repository.finishes.isEmpty() && app.repository.finishEntered.count == 1L)
        app.conversationOrchestrator.gate!!.countDown()
        drainUntil { app.repository.finishEntered.count == 0L }
        check(!service.stopped && !NemotronTranscriber.last.destroyed)
        check(ServiceStateTracker.audioLevel.value == 0f)
        check(app.repository.events == listOf("begin:$id", "transcript:first words", "transcript:first words complete"))
        app.repository.finishGate!!.countDown()
        drainUntil { service.stopped }
        val saved = app.repository.finishes.single()
        check(saved.id == id && saved.audioFileName == "$id.wav" && saved.durationMs == 1_250L)
        check(app.repository.events.last() == "finish:$id")
    }
    scenario("audio-only recording survives a transcription failure") { service ->
        val listener = NemotronTranscriber.last.listener
        val id = ServiceStateTracker.recordingId.value!!
        listener.onAudioProgress(3_000, 0.2f)
        listener.onError("Speech model failed after capture")
        listener.onAudioSaved("$id.wav", 3_125)
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        val app = LiveNotesApplication.instance.appContainer
        check(app.conversationOrchestrator.writes.isEmpty())
        val saved = app.repository.finishes.single()
        check(saved.audioFileName == "$id.wav" && saved.durationMs == 3_125L)
        check(ServiceStateTracker.lastTranscriptionError.value == "Speech model failed after capture")
    }
    scenario("OS text-only recording saves metadata with a visible audio notice", native = false) { service ->
        check(ServiceStateTracker.audioNotice.value?.contains("text only") == true)
        val recognizer = SpeechRecognizer.instances.last()
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        recognizer.callback.onResults(android.os.Bundle("saved text"))
        drainUntil { service.stopped }
        check(LiveNotesApplication.instance.appContainer.repository.finishes.single().audioFileName == null)
        check(ServiceStateTracker.audioNotice.value?.contains("text only") == true)
    }
    scenario("failed begin never constructs capture", setup = { it.repository.failBegin = true }) { service ->
        check(service.stopped && NemotronTranscriber.instances.isEmpty())
        check(SpeechRecognizer.instances.isEmpty())
        val app = LiveNotesApplication.instance.appContainer
        check(app.repository.starts.isEmpty() && app.repository.finishes.isEmpty())
        check(app.conversationOrchestrator.writes.isEmpty())
        check(ServiceStateTracker.lastTranscriptionError.value == "Recording database unavailable")
    }
    scenario("stop while recovery is pending does not create a new recording", awaitPrepared = false,
        setup = { it.recordingRecovery = CompletableDeferred() }) { service ->
        val app = LiveNotesApplication.instance.appContainer
        check(app.repository.starts.isEmpty() && NemotronTranscriber.instances.isEmpty())
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        check(ServiceStateTracker.capturePhase.value == CapturePhase.FINISHING)
        app.recordingRecovery.complete(Unit)
        drainUntil { service.stopped }
        check(app.repository.starts.isEmpty() && app.repository.finishes.isEmpty())
        check(NemotronTranscriber.instances.isEmpty())
    }
    scenario("stop during recording insert closes metadata without starting capture", awaitPrepared = false,
        setup = { it.repository.beginGate = CountDownLatch(1) }) { service ->
        val app = LiveNotesApplication.instance.appContainer
        check(app.repository.beginEntered.await(2, TimeUnit.SECONDS))
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        check(!service.stopped && NemotronTranscriber.instances.isEmpty())
        app.repository.beginGate!!.countDown()
        drainUntil { service.stopped }
        check(NemotronTranscriber.instances.isEmpty())
        check(app.repository.starts.single().id == app.repository.finishes.single().id)
        check(app.repository.finishes.single().audioFileName == null)
    }
    scenario("recording metadata failure preserves the original capture error", setup = { it.repository.failFinish = true }) { service ->
        val listener = NemotronTranscriber.last.listener
        listener.onError("Microphone read failed")
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        val error = ServiceStateTracker.lastTranscriptionError.value.orEmpty()
        check(error.contains("Microphone read failed") && error.contains("Recording metadata unavailable"))
        check(LiveNotesApplication.instance.appContainer.repository.finishes.isEmpty())
    }
    scenario("transcript database failure still finalizes saved audio", setup = { it.conversationOrchestrator.failWrite = true }) { service ->
        val listener = NemotronTranscriber.last.listener
        val id = ServiceStateTracker.recordingId.value!!
        listener.onError("Audio capture stopped early")
        listener.onTranscript("unsaved words", true)
        drainUntil { ServiceStateTracker.lastTranscriptionError.value?.contains("Transcript storage unavailable") == true }
        listener.onStateChanged("ready")
        check(ServiceStateTracker.lastTranscriptionError.value?.contains("Transcript storage unavailable") == true)
        listener.onAudioSaved("$id.wav", 500)
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(LiveNotesApplication.instance.appContainer.repository.finishes.single().audioFileName == "$id.wav")
        check(ServiceStateTracker.lastTranscriptionError.value?.contains("Transcript storage unavailable") == true)
        check(ServiceStateTracker.lastTranscriptionError.value?.contains("Audio capture stopped early") == true)
    }
    scenario("one hour of live updates retains every saved word with bounded display state") { service ->
        val listener = NemotronTranscriber.last.listener
        val orchestrator = LiveNotesApplication.instance.appContainer.conversationOrchestrator
        val expected = StringBuilder()
        repeat(7_200) { index ->
            val text = "meeting word $index. "
            expected.append(text)
            listener.onTranscriptUpdate(TranscriptUpdate(index.toLong(), text, TranscriptStatus.FINAL,
                appendToPrevious = true, startMs = index * 500L, endMs = (index + 1L) * 500))
            check(ServiceStateTracker.latestTranscript.value.length <= 4_000)
            check(ServiceStateTracker.liveSegments.value.size <= 120)
            if (index % 100 == 99) drainUntil { orchestrator.writes.size == index + 1 }
        }
        service.onStartCommand(Intent(ForegroundListeningService.ACTION_STOP), 0, 2)
        listener.onStateChanged("stopped")
        drainUntil { service.stopped }
        check(orchestrator.updates.size == 7_200)
        check(orchestrator.updates.joinToString("") { it.second.text } == expected.toString())
        check(ServiceStateTracker.transcriptDocument.fullText() == expected.toString())
        check(ServiceStateTracker.hasEarlierTranscript.value)
        check(ServiceStateTracker.latestTranscript.value.endsWith("meeting word 7199. "))
    }
    println("$passed service lifecycle scenarios passed")
}
