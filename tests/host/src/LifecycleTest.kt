import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import check.Gate
import com.sainadh.livenotes.stt.NemotronTranscriber
import com.sainadh.livenotes.stt.SpeechTranscriber
import java.io.File
import java.util.concurrent.TimeUnit

private fun await(label: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        Handler.drain()
        if (condition()) return
        Thread.sleep(5)
    }
    error("Timed out: $label")
}

fun main(args: Array<String>) {
    val scenario = args.single()
    val mainThread = Thread.currentThread()
    val events = mutableListOf<String>()
    val model = File.createTempFile("stub-model", ".gguf")
    val transcriber = NemotronTranscriber(Context(), model.path, listener = object : SpeechTranscriber.Listener {
        private fun event(value: String) {
            check(Thread.currentThread() === mainThread) { "Callback delivered off main thread" }
            events += value
        }
        override fun onStateChanged(state: String) = event(state)
        override fun onError(reason: String) = event("error:$reason")
        override fun onTranscript(text: String, isFinal: Boolean) = event("${if (isFinal) "final" else "partial"}:$text")
    })
    fun stopCount() = events.count { it == "stopped" }
    fun startAndFeed() {
        val expected = Gate.feeds.get() + 1
        transcriber.start()
        transcriber.start()
        await("first feed") { Gate.feeds.get() == expected }
    }
    fun stopAndWait(expected: Int) {
        transcriber.stop()
        transcriber.stop()
        await("stopped callback") { stopCount() == expected }
    }
    fun promptly(action: () -> Unit) {
        val before = System.nanoTime()
        action()
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 500) { "Lifecycle method blocked" }
    }
    try {
        when (scenario) {
            "stop-init", "destroy-init" -> {
                Gate.blockOperation = "init"
                transcriber.start()
                transcriber.start()
                check(Gate.entered.await(5, TimeUnit.SECONDS))
                if (scenario == "stop-init") {
                    promptly { transcriber.stop(); transcriber.stop() }
                    Gate.release.countDown()
                    await("stop after init") { stopCount() == 1 }
                    check(Gate.finalizes.get() == 1)
                } else {
                    promptly { transcriber.destroy() }
                    Gate.release.countDown()
                    await("destroy after init") { Gate.destroys.get() == 1 }
                    check(events.isEmpty()) { "Callbacks delivered after destruction: $events" }
                }
                check(Gate.inits.get() == 1)
                check(AudioRecord.starts.get() == 0) { "Microphone started after cancellation" }
                check("listening" !in events)
            }
            "stop-feed", "destroy-feed" -> {
                Gate.blockOperation = "feed"
                startAndFeed()
                if (scenario == "stop-feed") {
                    promptly { transcriber.stop() }
                    Thread.sleep(2100) // Regression: old join(2000) freed an active session.
                    Handler.drain()
                    check(Gate.finalizes.get() == 0 && Gate.destroys.get() == 0)
                    check(stopCount() == 0)
                    Gate.release.countDown()
                    await("final transcript and stop") { stopCount() == 1 }
                    check(events.indexOf("final:final transcript") < events.indexOf("stopped"))
                    check(events.none { it.startsWith("partial:") }) { "Late partial delivered after stop" }
                } else {
                    val before = events.size
                    promptly { transcriber.destroy() }
                    check(Gate.destroys.get() == 0)
                    Gate.release.countDown()
                    await("deferred destroy") { Gate.destroys.get() == 1 }
                    check(events.size == before) { "Callback delivered after destruction" }
                }
                check(AudioRecord.releases.get() == 1)
            }
            "restart" -> {
                startAndFeed()
                stopAndWait(1)
                startAndFeed()
                stopAndWait(2)
                check(Gate.inits.get() == 1 && Gate.restarts.get() == 1)
                check(AudioRecord.starts.get() == 2 && AudioRecord.releases.get() == 2)
                check(events.count { it == "final:final transcript" } == 2)
            }
            "restart-failure" -> {
                startAndFeed()
                stopAndWait(1)
                Gate.failOperation = "restart"
                transcriber.start()
                await("restart failure") { stopCount() == 2 }
                check(events.any { it.contains("native restart failed") })
                check(Gate.destroys.get() == 1)
                Gate.failOperation = ""
                startAndFeed()
                stopAndWait(3)
                check(Gate.inits.get() == 2) { "Failed native session reused" }
            }
            "init-failure", "feed-failure", "finalize-failure" -> {
                Gate.failOperation = scenario.removeSuffix("-failure")
                if (scenario == "finalize-failure") {
                    startAndFeed()
                    transcriber.stop()
                } else transcriber.start()
                await("native failure callback") { stopCount() == 1 }
                check(events.any { it.contains("native ${Gate.failOperation} failed") })
                check(events.indexOfFirst { it.startsWith("error:") } < events.indexOf("stopped"))
                if (scenario != "init-failure") check(Gate.destroys.get() == 1)
            }
            "audio-init", "audio-start", "audio-read" -> {
                Gate.audioFailure = scenario.removePrefix("audio-")
                transcriber.start()
                await("audio failure callback") { stopCount() == 1 }
                check(events.any { it.startsWith("error:") })
                check(AudioRecord.releases.get() == 1)
                check(Gate.destroys.get() == 1)
            }
            "idle-stop" -> {
                stopAndWait(1)
                check(Gate.inits.get() == 0)
                startAndFeed()
                stopAndWait(2)
            }
            else -> error("Unknown scenario: $scenario")
        }
        check(!Gate.concurrentNativeCalls) { "Native operations overlapped" }
        println("PASS $scenario")
    } finally {
        Gate.release.countDown()
        transcriber.destroy()
        model.delete()
    }
}
