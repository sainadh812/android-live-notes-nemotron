import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.speech.SpeechRecognizer
import com.sainadh.livenotes.stt.SpeechTranscriber
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.nio.file.Files

fun main() {
    var passed = 0
    fun scenario(name: String, run: (SpeechTranscriber, MutableList<String>, MutableList<TranscriptUpdate>) -> Unit) {
        Handler.reset()
        SpeechRecognizer.available = true
        SpeechRecognizer.failStart = false
        SpeechRecognizer.failStop = false
        SpeechRecognizer.callbackOnCancel = false
        SpeechRecognizer.instances.clear()
        val events = mutableListOf<String>()
        val updates = mutableListOf<TranscriptUpdate>()
        val transcriber = SpeechTranscriber(Context(), object : SpeechTranscriber.Listener {
            override fun onTranscript(text: String, isFinal: Boolean) { events += "text:$isFinal:$text" }
            override fun onTranscriptUpdate(update: TranscriptUpdate) {
                updates += update
                super.onTranscriptUpdate(update)
            }
            override fun onStateChanged(state: String) { events += "state:$state" }
            override fun onError(reason: String) { events += "error:$reason" }
        })
        run(transcriber, events, updates)
        transcriber.destroy()
        passed += 1
        println("PASS $name")
    }
    scenario("missing recognizer reports error then stopped") { t, events, _ ->
        SpeechRecognizer.available = false
        t.start()
        check(events.size == 2 && events[0].startsWith("error:") && events[1] == "state:stopped") { events }
    }
    scenario("provider start failure destroys the created recognizer") { t, events, _ ->
        SpeechRecognizer.failStart = true
        t.start()
        check(SpeechRecognizer.instances.single().destroyed)
        check(events.takeLast(2) == listOf("error:Provider rejected start", "state:stopped")) { events }
    }
    scenario("stop waits for final result before stopped") { t, events, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("draft"))
        t.stop()
        check(recognizer.stopped && "state:stopped" !in events)
        recognizer.callback.onResults(Bundle("final words"))
        check(events.takeLast(2) == listOf("text:true:final words", "state:stopped")) { events }
        check(updates.map { it.status } == listOf(TranscriptStatus.PARTIAL, TranscriptStatus.FINAL)) { updates }
        check(updates.map { it.segmentId }.distinct().size == 1) { updates }
        val completed = events.toList()
        Handler.advance(10_000)
        check(events == completed && recognizer.destroyed)
    }
    scenario("stalled stop preserves uncertain hypothesis and completes once") { t, events, updates ->
        t.start()
        SpeechRecognizer.instances.last().callback.onPartialResults(Bundle("last words"))
        t.stop()
        Handler.advance(5_000)
        check(events.takeLast(2) == listOf("text:false:last words", "state:stopped")) { events }
        check(updates.last().status == TranscriptStatus.INTERRUPTED && updates.last().endsUtterance) { updates }
        check(updates.map { it.segmentId }.distinct().size == 1) { updates }
        val completed = events.toList()
        t.stop()
        SpeechRecognizer.instances.last().callback.onResults(Bundle("late confirmation"))
        Handler.advance(5_000)
        check(events == completed) { events }
        check(events.count { it == "state:stopped" } == 1)
    }
    scenario("stop cancels queued restart") { t, events, _ ->
        t.start()
        SpeechRecognizer.instances.last().callback.onResults(Bundle("done"))
        t.stop()
        Handler.advance(10_000)
        check(SpeechRecognizer.instances.size == 1)
        check(events.last() == "state:stopped")
    }
    scenario("stop during error backoff does not repeat recovered words") { t, events, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("interrupted words"))
        recognizer.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        check(updates.last().status == TranscriptStatus.INTERRUPTED) { updates }
        val beforeStop = updates.toList()
        t.stop()
        check(events.last() == "state:stopped" && updates == beforeStop) { events }
        Handler.advance(10_000)
        check(SpeechRecognizer.instances.size == 1)
    }
    scenario("callbacks from a replaced provider are ignored") { t, events, _ ->
        t.start()
        val old = SpeechRecognizer.instances.last()
        old.callback.onResults(Bundle("first"))
        Handler.advance(700)
        check(SpeechRecognizer.instances.size == 2)
        val before = events.toList()
        old.callback.onResults(Bundle("stale"))
        old.callback.onError(SpeechRecognizer.ERROR_AUDIO)
        check(events == before) { events }
    }
    scenario("destroy suppresses pending stop and provider callbacks") { t, events, _ ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        t.stop()
        t.destroy()
        val before = events.toList()
        recognizer.callback.onResults(Bundle("late"))
        Handler.advance(10_000)
        check(events == before && recognizer.destroyed)
    }
    scenario("repeated errors stop despite readiness callbacks") { t, events, _ ->
        t.start()
        repeat(3) { index ->
            val recognizer = SpeechRecognizer.instances.last()
            recognizer.callback.onReadyForSpeech(null)
            recognizer.callback.onError(SpeechRecognizer.ERROR_NETWORK)
            if (index < 2) Handler.advance(1_500L * (index + 1))
        }
        check(events.last() == "state:stopped") { events }
        check(SpeechRecognizer.instances.size == 3)
    }
    scenario("network retry preserves a stable interrupted utterance before new speech") { t, _, updates ->
        t.start()
        val old = SpeechRecognizer.instances.last()
        old.callback.onPartialResults(Bundle("send"))
        old.callback.onPartialResults(Bundle("send the proposal"))
        old.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        check(updates.map { it.status } == listOf(TranscriptStatus.PARTIAL, TranscriptStatus.PARTIAL, TranscriptStatus.INTERRUPTED)) { updates }
        check(updates.map { it.segmentId }.distinct().size == 1) { updates }
        check(updates.last().text == "send the proposal" && old.destroyed)
        Handler.advance(1_500)
        val next = SpeechRecognizer.instances.last()
        next.callback.onPartialResults(Bundle("by Friday"))
        next.callback.onResults(Bundle("By Friday."))
        check(updates.takeLast(2).map { it.segmentId }.distinct().size == 1) { updates }
        check(updates.first().segmentId != updates.last().segmentId) { updates }
        check(updates.filter { it.status == TranscriptStatus.FINAL }.single().text == "By Friday.") { updates }
    }
    for (error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
        scenario("error $error preserves a partial before retry") { t, _, updates ->
            t.start()
            val old = SpeechRecognizer.instances.last()
            old.callback.onPartialResults(Bundle("keep these words"))
            old.callback.onError(error)
            check(updates.last().status == TranscriptStatus.INTERRUPTED) { updates }
            check(updates.first().segmentId == updates.last().segmentId) { updates }
            Handler.advance(1_200)
            check(SpeechRecognizer.instances.size == 2 && old.destroyed)
            SpeechRecognizer.instances.last().callback.onResults(Bundle("new utterance"))
            check(updates.last().segmentId != updates.first().segmentId) { updates }
        }
    }
    scenario("terminal callbacks are ignored throughout retry backoff") { t, events, updates ->
        t.start()
        val old = SpeechRecognizer.instances.last()
        old.callback.onPartialResults(Bundle("interrupted"))
        SpeechRecognizer.callbackOnCancel = true
        old.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        val before = events.toList()
        old.callback.onPartialResults(Bundle("stale partial"))
        old.callback.onResults(Bundle("stale final"))
        old.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        old.callback.onReadyForSpeech(null)
        old.callback.onEndOfSpeech()
        check(events == before && updates.count { it.status == TranscriptStatus.INTERRUPTED } == 1) { events }
        Handler.advance(1_500)
        check(SpeechRecognizer.instances.size == 2)
    }
    scenario("empty results recover a hypothesis without claiming confirmation") { t, _, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("uncertain"))
        t.stop()
        recognizer.callback.onResults(Bundle("   "))
        check(updates.last().status == TranscriptStatus.INTERRUPTED && updates.last().text == "uncertain") { updates }
        check(updates.none { it.status == TranscriptStatus.FINAL }) { updates }
    }
    scenario("provider stop failure preserves uncertainty once") { t, events, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("last words"))
        SpeechRecognizer.failStop = true
        t.stop()
        check(updates.last().status == TranscriptStatus.INTERRUPTED && recognizer.destroyed) { updates }
        val completed = events.toList()
        Handler.advance(5_000)
        check(events == completed && events.count { it == "state:stopped" } == 1) { events }
    }
    scenario("provider error during stop preserves uncertainty once") { t, events, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("last words"))
        t.stop()
        recognizer.callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
        recognizer.callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
        check(updates.last().status == TranscriptStatus.INTERRUPTED) { updates }
        check(updates.count { it.status == TranscriptStatus.INTERRUPTED } == 1 && events.count { it == "state:stopped" } == 1)
    }
    scenario("fatal error preserves interrupted speech before stopped") { t, events, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("before permission loss"))
        recognizer.callback.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        check(updates.last().status == TranscriptStatus.INTERRUPTED) { updates }
        check(events.takeLast(3)[0] == "text:false:before permission loss" && events.last() == "state:stopped") { events }
        Handler.advance(10_000)
        check(SpeechRecognizer.instances.size == 1)
    }
    scenario("duplicate hypotheses and duplicate final results are not emitted twice") { t, _, updates ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        repeat(3) { recognizer.callback.onPartialResults(Bundle("same words")) }
        repeat(3) { recognizer.callback.onResults(Bundle("same words")) }
        check(updates.map { it.status } == listOf(TranscriptStatus.PARTIAL, TranscriptStatus.FINAL)) { updates }
    }
    scenario("an empty provider session does not invent transcript rows") { t, _, updates ->
        t.start()
        SpeechRecognizer.instances.last().callback.onError(SpeechRecognizer.ERROR_NO_MATCH)
        Handler.advance(1_200)
        SpeechRecognizer.instances.last().callback.onResults(null)
        t.stop()
        check(updates.isEmpty()) { updates }
    }
    run {
        Handler.reset()
        SpeechRecognizer.available = true
        SpeechRecognizer.failStart = false
        SpeechRecognizer.failStop = false
        SpeechRecognizer.callbackOnCancel = false
        SpeechRecognizer.instances.clear()
        val directory = Files.createTempDirectory("speech-text-only").toFile()
        val destination = File(directory, "recording.wav")
        val availability = mutableListOf<String>()
        val transcriber = SpeechTranscriber(Context(), object : SpeechTranscriber.Listener {
            override fun onTranscript(text: String, isFinal: Boolean) = Unit
            override fun onStateChanged(state: String) = Unit
            override fun onError(reason: String) = error(reason)
            override fun onAudioUnavailable(reason: String) { availability += reason }
            override fun onAudioSaved(fileName: String, durationMs: Long) = error("OS provider claimed invented audio")
        }, audioFile = destination)
        try {
            transcriber.start()
            transcriber.start()
            check(availability.size == 1 && availability.single().contains("text only"))
            SpeechRecognizer.instances.last().callback.onResults(Bundle("text remains available"))
            Handler.advance(700)
            check(SpeechRecognizer.instances.size == 2 && availability.size == 1)
            transcriber.stop()
            SpeechRecognizer.instances.last().callback.onResults(Bundle("final text"))
            check(!destination.exists() && directory.listFiles().orEmpty().isEmpty())
            passed += 1
            println("PASS OS provider explicitly reports text-only capability without inventing audio")
        } finally {
            transcriber.destroy()
            directory.deleteRecursively()
        }
    }
    println("$passed speech lifecycle scenarios passed")
}
