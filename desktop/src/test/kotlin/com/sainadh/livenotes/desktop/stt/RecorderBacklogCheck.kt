package com.sainadh.livenotes.desktop.stt

import com.sainadh.livenotes.desktop.audio.PcmWavReader
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine

/** Real Windows JNI/model with a deterministic microphone; no physical audio device required. */
object RecorderBacklogCheck {
    fun verify(model: File, fixture: File, evidence: File) {
        val pcm = pcmBytes(fixture)
        val frames = pcm.size / 2
        check(frames > 21 * 8_000) { "Fixture must exceed the former queue plus the blocked feed" }
        val durationMs = frames * 1000L / 16_000
        val directory = Files.createTempDirectory(evidence.toPath(), "recorder-backlog-").toFile()
        val output = File(directory, "recorded meeting.wav")
        val firstFeed = CountDownLatch(1)
        val releaseFeed = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val lineClosed = CountDownLatch(1)
        val readBytes = AtomicInteger()
        val nativeDestroyed = AtomicBoolean()
        val destroyedBeforeFinished = AtomicBoolean()
        val transcribedMs = AtomicLong()
        val peakLagMs = AtomicLong()
        val updates = mutableMapOf<Long, TranscriptUpdate>()
        var saved: File? = null
        var error: String? = null
        var timings: String? = null
        var savedDuration = 0L
        val line = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getFormat" -> AudioFormat(16_000f, 16, 1, true, false)
                "available" -> pcm.size - readBytes.get()
                "read" -> {
                    val offset = readBytes.get()
                    val count = minOf(args!![2] as Int, pcm.size - offset)
                    pcm.copyInto(args[0] as ByteArray, args[1] as Int, offset, offset + count)
                    readBytes.addAndGet(count)
                    count
                }
                "close" -> { lineClosed.countDown(); null }
                "isOpen", "isRunning", "isActive" -> lineClosed.count != 0L
                "getControls" -> emptyArray<javax.sound.sampled.Control>()
                "isControlSupported" -> false
                "toString" -> "Recorded JFK fixture microphone"
                else -> null
            }
        } as TargetDataLine
        val recorder = DesktopRecorder(
            onTranscriptUpdate = { updates[it.segmentId] = it }, onLevel = { _, _ -> }, onPhase = {},
            onFinished = { file, duration, wordTiming, failure ->
                saved = file; savedDuration = duration; timings = wordTiming; error = failure
                destroyedBeforeFinished.set(nativeDestroyed.get())
                finished.countDown()
            },
            onProgress = { capturedMs, decodedMs ->
                transcribedMs.set(decodedMs)
                peakLagMs.accumulateAndGet(capturedMs - decodedMs, ::maxOf)
                if (capturedMs == durationMs) captured.countDown()
            }
        ).apply {
            openMicrophone = { line }
            // Empty fixture input deliberately waits for the test to call Stop.
            inputStallTimeoutMs = 90_000
            speech = object : SpeechNativeApi by NativeSpeech {
                override fun nativeFeedPcm(handle: Long, pcm: FloatArray): String? {
                    firstFeed.countDown()
                    check(releaseFeed.await(120, TimeUnit.SECONDS)) { "Fixture capture never released blocked inference" }
                    return NativeSpeech.nativeFeedPcm(handle, pcm)
                }
                override fun nativeDestroy(handle: Long) {
                    NativeSpeech.nativeDestroy(handle)
                    nativeDestroyed.set(true)
                }
            }
        }
        try {
            recorder.start(model, outputFile = output)
            check(firstFeed.await(90, TimeUnit.SECONDS)) { "Real speech engine did not reach its first feed" }
            check(captured.await(30, TimeUnit.SECONDS)) { "Capture stopped before the fixture exceeded the old queue capacity" }
            check(lineClosed.count == 1L && finished.count == 1L) { "Slow inference stopped capture" }
            check(peakLagMs.get() > 10_000) { "Fixture did not exercise a transcription backlog over 10 seconds" }
            recorder.stop()
            check(lineClosed.await(5, TimeUnit.SECONDS)) { "Stop did not release fixture microphone" }
            check(finished.count == 1L) { "Stop returned before held inference was drained" }
            releaseFeed.countDown()
            check(finished.await(180, TimeUnit.SECONDS)) { "Real transcription did not drain its backlog" }
            check(error == null) { "Recorder reported: $error" }
            check(destroyedBeforeFinished.get()) { "Finished callback preceded native destruction" }
            check(saved == output && savedDuration == durationMs)
            check(pcmBytes(output).contentEquals(pcm)) { "Recorded PCM differs from the fixture" }
            check(transcribedMs.get() == durationMs) { "Inference did not consume the complete recording" }
            val text = updates.toSortedMap().values.joinToString("") { it.text }
            val timedWords = timings?.let { NativeWordTimingFile.parse(it).size } ?: 0
            println("recorder_backlog_text=$text")
            println("recorder_backlog_timed_words=$timedWords")
            check(text.contains("ask what you can do for your country", ignoreCase = true))
            check(timedWords == 22) { "Expected 22 native word timings, received $timedWords" }
            check(directory.listFiles().orEmpty().none { it.name.startsWith(".speech-backlog-") }) { "Temporary PCM spool remains" }
            // Windows deletion proves capture, spool, and saved-WAV readers released their handles.
            check(directory.deleteRecursively()) { "Recorder files remained locked after completion" }
            File(evidence, "recorder-backlog.json").writeText("""{
              "passed": true,
              "capturedMs": $durationMs,
              "transcribedMs": ${transcribedMs.get()},
              "peakLagMs": ${peakLagMs.get()},
              "pcmBytes": ${pcm.size},
              "pcmMatchesFixture": true,
              "nativeDestroyedBeforeFinished": true,
              "temporarySpoolRemoved": true,
              "filesUnlocked": true,
              "timedWords": $timedWords
            }""".trimIndent())
        } finally {
            releaseFeed.countDown()
            recorder.stop()
            recorder.close()
            finished.await(30, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    private fun pcmBytes(file: File): ByteArray = PcmWavReader(file).use { wav ->
        check(wav.sampleRate == 16_000 && wav.frameCount in 1..16_000 * 30L)
        ByteArray(wav.frameCount.toInt() * 2).also { bytes ->
            check(wav.render(0.0, 1.0, bytes, wav.frameCount.toInt()).frames == wav.frameCount.toInt())
        }
    }
}
