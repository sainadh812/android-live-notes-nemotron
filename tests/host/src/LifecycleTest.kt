import android.content.Context
import android.media.AudioRecord
import android.os.Handler
import check.Gate
import com.sainadh.livenotes.stt.NemotronTranscriber
import com.sainadh.livenotes.stt.SpeechTranscriber
import com.sainadh.livenotes.stt.NativeTranscriptSegments
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    val updates = mutableListOf<TranscriptUpdate>()
    val audioProgress = mutableListOf<Pair<Long, Float>>()
    val savedAudio = mutableListOf<Pair<String, Long>>()
    val model = File.createTempFile("stub-model", ".gguf")
    val audioDirectory = Files.createTempDirectory("capture-test").toFile()
    val audioFile = if (scenario.startsWith("saved-")) File(audioDirectory, "recording.wav") else null
    val transcriber = NemotronTranscriber(Context(), model.path, listener = object : SpeechTranscriber.Listener {
        private fun event(value: String) {
            check(Thread.currentThread() === mainThread) { "Callback delivered off main thread" }
            events += value
        }
        override fun onStateChanged(state: String) = event(state)
        override fun onError(reason: String) = event("error:$reason")
        override fun onAudioProgress(durationMs: Long, level: Float) {
            check(Thread.currentThread() === mainThread)
            audioProgress += durationMs to level
        }
        override fun onAudioSaved(fileName: String, durationMs: Long) {
            savedAudio += fileName to durationMs
            event("audio-saved")
        }
        override fun onTranscript(text: String, isFinal: Boolean) = error("Legacy cumulative callback used")
        override fun onTranscriptUpdate(update: TranscriptUpdate) {
            updates += update
            event("${update.status.name.lowercase()}:${update.text}")
        }
    }, audioFile = audioFile)
    fun stopCount() = events.count { it == "stopped" }
    fun startAndFeed() {
        val expected = Gate.feeds.get() + 1
        transcriber.start()
        transcriber.start()
        // A queued tail can advance past the first call between polls.
        // This is a startup milestone; each scenario checks its final counts.
        await("first feed") { Gate.feeds.get() >= expected }
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
            "saved-audio", "saved-tail", "saved-no-text" -> {
                Gate.noTextUpdates = scenario == "saved-no-text"
                if (scenario == "saved-tail") {
                    AudioRecord.availableFrames.set(17_123)
                    Gate.blockEmptyRead = true
                }
                startAndFeed()
                if (scenario == "saved-tail") {
                    check(Gate.readEntered.await(5, TimeUnit.SECONDS))
                    transcriber.stop()
                    Gate.readRelease.countDown()
                }
                stopAndWait(1)
                val file = checkNotNull(audioFile)
                check(file.isFile && !File(audioDirectory, "recording.wav.part").exists())
                val data = file.readBytes()
                check(data.size == 44 + AudioRecord.readFrames.get() * 2)
                val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                check(header.getInt(40) == data.size - 44)
                check((44 until data.size step 2).all { header.getShort(it).toInt() == 16384 })
                val duration = AudioRecord.readFrames.get() * 1_000L / 16_000
                check(savedAudio == listOf("recording.wav" to duration))
                check(events.indexOf("audio-saved") < events.indexOf("stopped"))
                check(audioProgress.last().first == duration)
                check(audioProgress.zipWithNext().all { (a, b) -> a.first <= b.first })
                check(audioProgress.any { it.second == 0.5f })
                check(updates.all { it.startMs == 0L && checkNotNull(it.endMs) <= duration })
                check(updates.last().endMs == duration)
                if (scenario == "saved-no-text") {
                    check(updates.size == 1 && updates.single().status == TranscriptStatus.FINAL)
                }
                check(AudioRecord.starts.get() == 1 && AudioRecord.releases.get() == 1)
            }
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
                    val completed = AtomicInteger()
                    promptly { transcriber.destroy { completed.incrementAndGet() } }
                    check(completed.get() == 0)
                    Gate.release.countDown()
                    await("destroy completion after init") { completed.get() == 1 }
                    check(Gate.destroys.get() == 1)
                    check(events.isEmpty()) { "Callbacks delivered after destruction: $events" }
                }
                check(Gate.inits.get() == 1)
                check(AudioRecord.starts.get() == 0) { "Microphone started after cancellation" }
                check("listening" !in events)
            }
            "stop-feed", "destroy-feed", "saved-destroy" -> {
                Gate.blockOperation = "feed"
                startAndFeed()
                if (scenario == "stop-feed") {
                    promptly { transcriber.stop() }
                    Thread.sleep(2100) // Regression: old join(2000) freed an active session.
                    Handler.drain()
                    check(AudioRecord.releases.get() == 1) { "Slow inference kept the microphone open" }
                    check(Gate.finalizes.get() == 0 && Gate.destroys.get() == 0)
                    check(stopCount() == 0)
                    Gate.release.countDown()
                    await("final transcript and stop") { stopCount() == 1 }
                    check(events.indexOf("final:final transcript") < events.indexOf("stopped"))
                    check(events.last() == "stopped") { "Transcript delivered after stop completed" }
                } else {
                    val before = events.size
                    val completed = AtomicInteger()
                    promptly { transcriber.destroy { completed.incrementAndGet() } }
                    check(Gate.destroys.get() == 0 && completed.get() == 0)
                    Gate.release.countDown()
                    await("deferred destroy completion") { completed.get() == 1 }
                    check(Gate.destroys.get() == 1)
                    check(events.size == before) { "Callback delivered after destruction" }
                }
                check(AudioRecord.releases.get() == 1)
            }
            "slow-feed-queue", "queued-tail", "destroy-queue" -> {
                Gate.blockOperation = "feed"
                AudioRecord.availableFrames.set(4 * 8000)
                if (scenario == "queued-tail") {
                    AudioRecord.availableFrames.set(3 * 8000 + 1000)
                    Gate.blockEmptyRead = true
                }
                startAndFeed()
                if (scenario == "queued-tail") {
                    check(Gate.readEntered.await(5, TimeUnit.SECONDS))
                    // This audio is still in the driver when stop is requested.
                    promptly { transcriber.stop() }
                    AudioRecord.availableFrames.addAndGet(123)
                    Gate.readRelease.countDown()
                } else {
                    await("capture continued during blocked inference") { AudioRecord.readFrames.get() == 4 * 8000 }
                    if (scenario == "destroy-queue") promptly { transcriber.destroy() }
                    else promptly { transcriber.stop() }
                }
                await("mic released before inference returns") { AudioRecord.releases.get() == 1 }
                check(Gate.feeds.get() == 1 && Gate.finalizes.get() == 0 && Gate.destroys.get() == 0)
                val beforeRelease = events.size
                Gate.release.countDown()
                if (scenario == "destroy-queue") {
                    await("destroy after queued capture") { Gate.destroys.get() == 1 }
                    check(Gate.feeds.get() == 1 && Gate.finalizes.get() == 0)
                    check(events.size == beforeRelease)
                } else {
                    await("queued audio drained") { stopCount() == 1 }
                    check(Gate.feeds.get() == 4)
                    check(Gate.feedFrames.get() == AudioRecord.readFrames.get()) { "Captured samples were lost" }
                    if (scenario == "queued-tail") check(Gate.feedSizes.last() == 1123)
                    check(Gate.finalizes.get() == 1 && events.last() == "stopped")
                }
            }
            "read-error-tail" -> {
                AudioRecord.availableFrames.set(1000)
                Gate.audioFailure = "read-after-tail"
                transcriber.start()
                await("read error preserves buffered samples") { stopCount() == 1 }
                check(Gate.feedFrames.get() == 1000 && Gate.feeds.get() == 1)
                check(Gate.finalizes.get() == 1 && Gate.destroys.get() == 1)
                check(events.single { it.startsWith("error:") }.contains("Microphone read failed"))
                check(events.last() == "stopped")
            }
            "feed-error-partial" -> {
                startAndFeed()
                await("first hypothesis") { updates.any { it.text == "tentative" } }
                Gate.failOperation = "feed"
                AudioRecord.availableFrames.addAndGet(8000)
                await("feed error preserves tentative text") { stopCount() == 1 }
                check(updates.last().status == TranscriptStatus.INTERRUPTED && updates.last().text == "tentative")
                check(Gate.finalizes.get() == 0 && Gate.destroys.get() == 1)
            }
            "tail-limit" -> {
                Gate.blockOperation = "feed"
                Gate.blockEmptyRead = true
                startAndFeed()
                check(Gate.readEntered.await(5, TimeUnit.SECONDS))
                promptly { transcriber.stop() }
                AudioRecord.availableFrames.addAndGet(20 * 8000)
                Gate.readRelease.countDown()
                await("bounded tail drain releases mic") { AudioRecord.releases.get() == 1 }
                check(AudioRecord.readFrames.get() == 5 * 8000)
                Gate.release.countDown()
                await("tail limit finalizes captured samples") { stopCount() == 1 }
                check(Gate.feedFrames.get() == AudioRecord.readFrames.get())
                check(events.single { it.startsWith("error:") }.contains("did not finish draining"))
            }
            "overflow", "saved-overflow" -> {
                Gate.blockOperation = "feed"
                startAndFeed()
                // Fill the queue only after one native call is definitely in flight.
                AudioRecord.availableFrames.addAndGet(30 * 8000)
                await("overflow stops microphone") { AudioRecord.releases.get() == 1 }
                check(Gate.feeds.get() == 1 && Gate.finalizes.get() == 0)
                check(AudioRecord.readFrames.get() == 22 * 8000)
                Gate.release.countDown()
                await("overflow drains accepted queue") { stopCount() == 1 }
                check(Gate.feeds.get() == 21 && Gate.feedFrames.get() == 21 * 8000)
                check(Gate.finalizes.get() == 1 && Gate.destroys.get() == 1)
                check(events.single { it.startsWith("error:") }.contains("could not keep up"))
                check(events.indexOfFirst { it.startsWith("error:") } < events.indexOf("final:final transcript"))
                check(events.last() == "stopped")
            }
            "destroy-completion" -> {
                Gate.blockOperation = "feed"
                startAndFeed()
                val completed = AtomicInteger()
                transcriber.destroy { error("One caller failed cleanup") }
                transcriber.destroy {
                    check(Gate.destroys.get() == 1 && AudioRecord.releases.get() == 1)
                    completed.incrementAndGet()
                }
                transcriber.destroy { completed.incrementAndGet() }
                check(completed.get() == 0)
                Gate.release.countDown()
                await("all destroy completions after release") { completed.get() == 2 }
                transcriber.destroy { completed.incrementAndGet() }
                check(completed.get() == 3 && Gate.destroys.get() == 1)
            }
            "truncated-feed", "truncated-finalize" -> {
                Gate.truncateOperation = scenario.removePrefix("truncated-")
                startAndFeed()
                if (scenario == "truncated-finalize") transcriber.stop()
                await("output limit is surfaced") { stopCount() == 1 }
                check(events.single { it.startsWith("error:") }.contains("output limit"))
                if (scenario == "truncated-feed") {
                    check(updates.last().text == "tentative" && updates.last().status == TranscriptStatus.INTERRUPTED)
                } else {
                    check(updates.last().text == "final transcript" && updates.last().status == TranscriptStatus.FINAL)
                }
                check(Gate.destroys.get() == 1 && AudioRecord.releases.get() == 1)
            }
            "segments" -> {
                val assembler = NativeTranscriptSegments()
                val saved = linkedMapOf<Long, TranscriptUpdate>()
                fun accept(batch: List<TranscriptUpdate>) = batch.forEach { saved[it.segmentId] = it }
                accept(assembler.update("Hello\u0001 wor"))
                check(saved[0L]?.text == "Hello wor")
                accept(assembler.update(" \u0001world"))
                check(saved[0L] == TranscriptUpdate(0, "Hello ", TranscriptStatus.FINAL, false, true))
                accept(assembler.update("world\u0001!"))
                check(saved[1L]?.text == "world!")
                accept(assembler.update(" \u0001again"))
                check(saved[1L]?.text == "world ")
                val ending = assembler.finish("Hello world again.")
                accept(listOf(ending))
                check(saved.values.joinToString("") { it.text } == "Hello world again.")
                check(saved.values.all { it.status == TranscriptStatus.FINAL && it.appendToPrevious })
                check(!saved.getValue(0L).endsUtterance && ending.endsUtterance)
                val interrupted = NativeTranscriptSegments()
                interrupted.update("committed \u0001unfinished")
                check(interrupted.interrupted() == TranscriptUpdate(1, "unfinished", TranscriptStatus.INTERRUPTED, true, true))
                check(runCatching { interrupted.update("malformed") }.isFailure)
                check(runCatching { interrupted.finish("changed final") }.isFailure)
                check(interrupted.interrupted().text == "unfinished")
                val cleared = NativeTranscriptSegments()
                cleared.update("\u0001uncertain")
                check(cleared.update("\u0001").single().text.isEmpty())
                check(cleared.finish("").endsUtterance)

                val hour = NativeTranscriptSegments()
                val finalWords = StringBuilder()
                val expected = StringBuilder()
                repeat(7_200) { index ->
                    // Split the committed word across feeds and preserve Unicode.
                    val wordPart = if (index % 2 == 0) "नमस्ते🙂" else "𠮷 "
                    expected.append(wordPart)
                    hour.update("$wordPart\u0001revisable").forEach { update ->
                        if (update.status == TranscriptStatus.FINAL) finalWords.append(update.text)
                    }
                }
                finalWords.append(hour.finish(expected.toString() + "last word.").text)
                check(finalWords.toString() == expected.toString() + "last word.")

                val noSpaces = NativeTranscriptSegments()
                val noSpacesExpected = StringBuilder()
                val noSpacesSaved = StringBuilder()
                var noSpacesTail = ""
                repeat(7_200) { index ->
                    val part = if (index % 2 == 0) "中文🙂" else "𠮷文"
                    noSpacesExpected.append(part)
                    noSpaces.update("$part\u0001").forEach { update ->
                        check(update.appendToPrevious)
                        check(update.text.firstOrNull()?.isLowSurrogate() != true)
                        check(update.text.lastOrNull()?.isHighSurrogate() != true)
                        if (update.status == TranscriptStatus.FINAL) noSpacesSaved.append(update.text)
                        else noSpacesTail = update.text
                    }
                    check(noSpacesTail.length <= 256)
                }
                check(noSpacesSaved.toString() + noSpacesTail == noSpacesExpected.toString())
                noSpacesSaved.append(noSpaces.finish(noSpacesExpected.toString() + "结束🙂").text)
                check(noSpacesSaved.toString() == noSpacesExpected.toString() + "结束🙂")

                val moonshine = NativeTranscriptSegments()
                moonshine.update("\u0001earlier full hypothesis")
                check(moonshine.update("\u0001Completely revised.").single().text == "Completely revised.")
                check(moonshine.finish("Final rewrite.").text == "Final rewrite.")
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
                if (scenario == "finalize-failure") check(updates.last().status == TranscriptStatus.INTERRUPTED && updates.last().text == "tentative")
            }
            "audio-init", "audio-start", "audio-read", "saved-audio-start" -> {
                Gate.audioFailure = scenario.removePrefix("saved-").removePrefix("audio-")
                transcriber.start()
                await("audio failure callback") { stopCount() == 1 }
                check(events.any { it.startsWith("error:") })
                check(AudioRecord.releases.get() == 1)
                check(Gate.destroys.get() == 1)
                if (scenario == "saved-audio-start") {
                    check(savedAudio.isEmpty() && !checkNotNull(audioFile).exists())
                    check(!File(audioDirectory, "recording.wav.part").exists())
                }
            }
            "idle-stop" -> {
                stopAndWait(1)
                check(Gate.inits.get() == 0)
                startAndFeed()
                stopAndWait(2)
            }
            else -> error("Unknown scenario: $scenario")
        }
        if (scenario == "saved-overflow" || scenario == "saved-destroy") {
            val file = checkNotNull(audioFile)
            check(file.isFile && file.length() == 44L + AudioRecord.readFrames.get() * 2L)
            check(!File(audioDirectory, "recording.wav.part").exists())
            val bytes = file.readBytes()
            check(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == bytes.size - 44)
            if (scenario == "saved-destroy") check(savedAudio.isEmpty())
            else {
                check(savedAudio == listOf("recording.wav" to AudioRecord.readFrames.get() * 1_000L / 16_000))
                check(AudioRecord.readFrames.get() > Gate.feedFrames.get()) { "Queue overflow was not exercised" }
            }
        }
        check(!Gate.concurrentNativeCalls) { "Native operations overlapped" }
        println("PASS $scenario")
    } finally {
        Gate.release.countDown()
        Gate.readRelease.countDown()
        transcriber.destroy()
        model.delete()
        audioDirectory.deleteRecursively()
    }
}
