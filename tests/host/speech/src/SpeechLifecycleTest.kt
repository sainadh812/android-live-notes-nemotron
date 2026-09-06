import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.speech.SpeechRecognizer
import com.sainadh.livenotes.stt.SpeechTranscriber

fun main() {
    var passed = 0
    fun scenario(name: String, run: (SpeechTranscriber, MutableList<String>) -> Unit) {
        Handler.reset()
        SpeechRecognizer.available = true
        SpeechRecognizer.failStart = false
        SpeechRecognizer.instances.clear()
        val events = mutableListOf<String>()
        val transcriber = SpeechTranscriber(Context(), object : SpeechTranscriber.Listener {
            override fun onTranscript(text: String, isFinal: Boolean) { events += "text:$isFinal:$text" }
            override fun onStateChanged(state: String) { events += "state:$state" }
            override fun onError(reason: String) { events += "error:$reason" }
        })
        run(transcriber, events)
        transcriber.destroy()
        passed += 1
        println("PASS $name")
    }
    scenario("missing recognizer reports error then stopped") { t, events ->
        SpeechRecognizer.available = false
        t.start()
        check(events.size == 2 && events[0].startsWith("error:") && events[1] == "state:stopped") { events }
    }
    scenario("provider start failure destroys the created recognizer") { t, events ->
        SpeechRecognizer.failStart = true
        t.start()
        check(SpeechRecognizer.instances.single().destroyed)
        check(events.takeLast(2) == listOf("error:Provider rejected start", "state:stopped")) { events }
    }
    scenario("stop waits for final result before stopped") { t, events ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("draft"))
        t.stop()
        check(recognizer.stopped && "state:stopped" !in events)
        recognizer.callback.onResults(Bundle("final words"))
        check(events.takeLast(2) == listOf("text:true:final words", "state:stopped")) { events }
        val completed = events.toList()
        Handler.advance(10_000)
        check(events == completed && recognizer.destroyed)
    }
    scenario("stalled stop preserves latest hypothesis and completes once") { t, events ->
        t.start()
        SpeechRecognizer.instances.last().callback.onPartialResults(Bundle("last words"))
        t.stop()
        Handler.advance(5_000)
        check(events.takeLast(2) == listOf("text:true:last words", "state:stopped")) { events }
        check(events.count { it == "state:stopped" } == 1)
    }
    scenario("stop cancels queued restart") { t, events ->
        t.start()
        SpeechRecognizer.instances.last().callback.onResults(Bundle("done"))
        t.stop()
        Handler.advance(10_000)
        check(SpeechRecognizer.instances.size == 1)
        check(events.last() == "state:stopped")
    }
    scenario("stop during error backoff finalizes the latest hypothesis") { t, events ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        recognizer.callback.onPartialResults(Bundle("interrupted words"))
        recognizer.callback.onError(SpeechRecognizer.ERROR_NETWORK)
        t.stop()
        check(events.takeLast(2) == listOf("text:true:interrupted words", "state:stopped")) { events }
        Handler.advance(10_000)
        check(SpeechRecognizer.instances.size == 1)
    }
    scenario("callbacks from a replaced provider are ignored") { t, events ->
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
    scenario("destroy suppresses pending stop and provider callbacks") { t, events ->
        t.start()
        val recognizer = SpeechRecognizer.instances.last()
        t.stop()
        t.destroy()
        val before = events.toList()
        recognizer.callback.onResults(Bundle("late"))
        Handler.advance(10_000)
        check(events == before && recognizer.destroyed)
    }
    scenario("repeated errors stop despite readiness callbacks") { t, events ->
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
    println("$passed speech lifecycle scenarios passed")
}
