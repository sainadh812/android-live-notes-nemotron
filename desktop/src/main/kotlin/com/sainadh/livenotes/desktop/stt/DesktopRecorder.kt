package com.sainadh.livenotes.desktop.stt

import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.desktop.audio.AudioDevices
import com.sainadh.livenotes.desktop.audio.Pcm16MonoConverter
import com.sainadh.livenotes.stt.NativeTranscriptSegments
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptSampleClock
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt
import javax.sound.sampled.TargetDataLine

/** Callbacks run off the UI thread. Phases: preparing, recording, saving, idle. */
class DesktopRecorder(
    private val onTranscriptUpdate: (TranscriptUpdate) -> Unit,
    private val onLevel: (durationMs: Long, level: Float) -> Unit,
    private val onFinished: (audioFile: File?, durationMs: Long, wordTiming: String?, error: String?) -> Unit,
    private val onPhase: (String) -> Unit,
    private val onProgress: (capturedMs: Long, transcribedMs: Long) -> Unit = { _, _ -> }
) : AutoCloseable {
    // Set only before start, by module-local tests. Production uses JNI/JavaSound.
    internal var speech: SpeechNativeApi = NativeSpeech
    internal var openMicrophone: (String?) -> TargetDataLine = AudioDevices::open
    internal var inputStallTimeoutMs = 5_000L
    private class Capture {
        val stop = AtomicBoolean()
        val done = CountDownLatch(1)
        val transcribedSamples = AtomicLong()
        var phase = -1 // Published only while holding the lifecycle lock.
        @Volatile var error: String? = null
        @Volatile var audio: File? = null
        @Volatile var durationMs = 0L
        @Volatile var line: TargetDataLine? = null
    }
    private val lock = Any()
    private val inference = Executors.newSingleThreadExecutor { task -> Thread(task, "speech-inference").apply { isDaemon = true } }
    private val microphone = Executors.newSingleThreadExecutor { task -> Thread(task, "microphone-capture").apply { isDaemon = true } }
    private val stopper = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "microphone-stop").apply { isDaemon = true } }
    private var active: Capture? = null
    private var closed = false

    fun start(modelFile: File, language: String = "en-US", micDeviceId: String? = null, outputFile: File) {
        synchronized(lock) {
            check(!closed) { "Recorder is closed" }
            check(active == null) { "A recording is already active" }
            val capture = Capture().also { active = it }
            inference.execute { record(capture, modelFile, language, micDeviceId, outputFile) }
        }
    }

    /** Returns immediately; already captured audio and queued inference are drained. */
    fun stop() { synchronized(lock) { active?.let(::requestStop) } }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active?.let(::requestStop)
        }
        // Running workers own their resources until all calls return.
        inference.shutdown()
        microphone.shutdown()
        stopper.shutdown()
    }

    private fun requestStop(capture: Capture) = synchronized(lock) {
        if (!capture.stop.compareAndSet(false, true)) return@synchronized
        phase(capture, "saving")
        // Normal reads are limited to available frames. A faulty driver may
        // still block; close only after normal owner-thread draining had time.
        stopper.schedule({
            val line = capture.line
            if (capture.done.count != 0L && line != null) {
                capture.error = capture.error ?: "Microphone did not stop promptly; its final audio may be incomplete."
                runCatching { line.close() }
            }
        }, 750, TimeUnit.MILLISECONDS)
    }

    private fun record(capture: Capture, model: File, language: String, device: String?, output: File) {
        var handle = 0L
        var captureStarted = false
        var backlog: DiskPcmBacklog? = null
        var failure: String? = null
        var timing: String? = null
        val segments = NativeTranscriptSegments()
        val clock = TranscriptSampleClock(16_000)
        var finalized = false
        try {
            phase(capture, "preparing")
            require(model.isFile) { "The selected speech model is missing" }
            speech.ensureLoaded()
            if (capture.stop.get()) return
            handle = speech.nativeInit(model.canonicalPath, language, -1)
            check(handle != 0L) { "Could not initialize speech model" }
            val pcmBacklog = DiskPcmBacklog(output.absoluteFile.parentFile).also { backlog = it }
            synchronized(lock) {
                if (capture.stop.get() || closed) return
                microphone.execute { capture(capture, device, output, pcmBacklog) }
                captureStarted = true
            }
            while (true) {
                val captureFinished = capture.done.count == 0L
                val chunk = pcmBacklog.next(captureFinished)
                if (chunk == null) {
                    if (captureFinished) break
                    Thread.sleep(20)
                    continue
                }
                val delta = speech.nativeFeedPcm(handle, chunk)
                clock.consume(chunk.size)
                capture.transcribedSamples.addAndGet(chunk.size.toLong())
                progress(capture)
                delta?.let { segments.update(it).forEach { update -> onTranscriptUpdate(clock.stamp(update)) } }
                check(!speech.nativeWasTruncated(handle)) {
                    "The speech model reached its output limit. Recorded audio was preserved; the transcript is incomplete."
                }
            }
            phase(capture, "saving")
            val ending = segments.finish(speech.nativeFinalizeStream(handle))
            onTranscriptUpdate(clock.stamp(ending))
            finalized = true
            check(!speech.nativeWasTruncated(handle)) {
                "The speech model reached its output limit. Recorded audio was preserved; the transcript may be incomplete."
            }
            timing = runCatching { speech.nativeWordTimings(handle).takeIf { it.isNotBlank() } }.getOrNull()
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
        } finally {
            requestStop(capture)
            if (captureStarted) capture.done.await()
            failure = failure ?: capture.error
            runCatching { backlog?.close() }.exceptionOrNull()?.let {
                failure = failure ?: "Audio saved, but temporary transcription audio could not be removed: ${it.message}"
            }
            if (!finalized && handle != 0L) runCatching { onTranscriptUpdate(clock.stamp(segments.interrupted())) }
                .exceptionOrNull()?.let { failure = failure ?: "Could not deliver transcript: ${it.message}" }
            // Native teardown is complete before the owner may change/delete a model.
            if (handle != 0L) runCatching { speech.nativeDestroy(handle) }.exceptionOrNull()?.let {
                failure = failure ?: "Could not release speech model: ${it.message}"
            }
            if (timing != null && capture.audio != null) {
                runCatching { NativeWordTimingFile.write(checkNotNull(capture.audio), checkNotNull(timing)) }
                    .exceptionOrNull()?.let { failure = failure ?: "Audio saved, but word timing could not be saved: ${it.message}" }
            }
            synchronized(lock) { if (active === capture) active = null }
            phase(capture, "idle")
            emit { onFinished(capture.audio, capture.durationMs, timing, failure) }
        }
    }

    private fun capture(state: Capture, device: String?, output: File, backlog: DiskPcmBacklog) {
        var wav: WavFileWriter? = null
        try {
            if (state.stop.get()) return
            openMicrophone(device).use { line ->
                state.line = line
                if (state.stop.get()) return
                wav = WavFileWriter(output, 16_000)
                val converter = Pcm16MonoConverter(line.format.sampleRate.toInt(), line.format.channels)
                val raw = ByteArray(line.format.frameSize * 4096)
                var savedSamples = 0L
                var lastLevelSamples = 0L
                var energy = 0.0
                var energySamples = 0
                var receivedSamples = false
                val lastInput = AtomicLong(System.nanoTime())
                fun readAvailable(): Int {
                    val available = (minOf(line.available(), raw.size) / line.format.frameSize) * line.format.frameSize
                    if (available == 0) return 0
                    val count = line.read(raw, 0, available)
                    check(count >= 0) { "Microphone stopped unexpectedly" }
                    if (count == 0) return 0
                    val pcm = converter.convert(raw, count)
                    checkNotNull(wav).write(pcm, 0, pcm.size) // Storage always precedes inference.
                    lastInput.set(System.nanoTime())
                    if (!receivedSamples) {
                        receivedSamples = true
                        // A device opening successfully does not mean it is delivering audio.
                        phase(state, "recording")
                    }
                    savedSamples += pcm.size
                    state.durationMs = savedSamples * 1000 / 16_000
                    try { backlog.append(pcm) } catch (error: java.io.IOException) {
                        throw java.io.IOException("Audio saved, but temporary transcription audio could not be written: ${error.message}", error)
                    }
                    for (sample in pcm) {
                        val value = sample / 32768f
                        energy += value * value
                        energySamples++
                    }
                    if (savedSamples - lastLevelSamples >= 1600) {
                        val level = if (energySamples == 0) 0f else sqrt(energy / energySamples).toFloat().coerceIn(0f, 1f)
                        emit { onLevel(state.durationMs, level) }
                        progress(state)
                        lastLevelSamples = savedSamples
                        energy = 0.0
                        energySamples = 0
                    }
                    return count
                }
                line.start()
                lastInput.set(System.nanoTime())
                val watchdog = synchronized(lock) {
                    if (state.stop.get() || closed) null else stopper.scheduleWithFixedDelay({
                        if (!state.stop.get() && state.done.count != 0L) {
                            val failure = when {
                                !line.isOpen -> "The microphone stopped or disconnected. Audio received so far was saved."
                                System.nanoTime() - lastInput.get() >= TimeUnit.MILLISECONDS.toNanos(inputStallTimeoutMs) ->
                                    "The microphone stopped delivering audio samples. Check its connection and Windows microphone access. Audio received so far was saved."
                                else -> null
                            }
                            if (failure != null && state.stop.compareAndSet(false, true)) {
                                state.error = state.error ?: failure
                                // Also releases a faulty read which blocked despite available().
                                runCatching { line.close() }
                            }
                        }
                    }, 50, 50, TimeUnit.MILLISECONDS)
                }
                try {
                    while (!state.stop.get()) {
                        // JavaSound may report isRunning=false until the first
                        // read. Connectivity is determined by an open handle
                        // and actual PCM arrivals, with the watchdog above.
                        check(line.isOpen) { "The microphone stopped or disconnected. Audio received so far was saved." }
                        if (readAvailable() == 0) Thread.sleep(10)
                    }
                    // Stop production, then drain retained device data only while
                    // the device remains open. A disconnect may already have closed it.
                    if (line.isOpen) {
                        line.stop()
                        var drained = 0
                        while (drained < line.format.sampleRate.toInt() * line.format.frameSize * 2) {
                            val read = readAvailable()
                            if (read == 0) break
                            drained += read
                        }
                        check(line.available() == 0) { "Microphone did not finish draining; the recording may miss its last audio." }
                    }
                } finally {
                    watchdog?.cancel(false)
                }
            }
        } catch (error: Throwable) {
            state.error = state.error ?: error.message ?: error.javaClass.simpleName
        } finally {
            state.line = null
            // Capture has ended even when inference still has minutes of audio
            // to process (for example after a disconnected microphone).
            phase(state, "saving")
            try { state.audio = wav?.finish() } catch (error: Throwable) {
                state.error = state.error ?: "Could not finalize recorded audio: ${error.message}"
            }
            emit { onLevel(state.durationMs, 0f) }
            progress(state)
            state.done.countDown()
        }
    }

    private fun phase(state: Capture, value: String) = synchronized(lock) {
        val next = when (value) { "preparing" -> 0; "recording" -> 1; "saving" -> 2; "idle" -> 3; else -> error("Unknown recording phase") }
        if (next <= state.phase || (next <= 1 && state.stop.get())) return@synchronized
        state.phase = next
        emit { onPhase(value) }
    }

    private fun progress(state: Capture) {
        // Read inference first: capture may advance concurrently, but it always
        // publishes its duration before making the corresponding PCM readable.
        val transcribedMs = state.transcribedSamples.get() * 1000 / 16_000
        emit { onProgress(state.durationMs, transcribedMs) }
    }

    private inline fun emit(callback: () -> Unit) {
        runCatching(callback).exceptionOrNull()?.let { error ->
            System.err.println("Recorder callback failed: ${error.message}")
            error.printStackTrace()
        }
    }
}
