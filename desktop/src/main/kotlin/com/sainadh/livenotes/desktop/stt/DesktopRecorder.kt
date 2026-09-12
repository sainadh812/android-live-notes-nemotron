package com.sainadh.livenotes.desktop.stt

import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.desktop.audio.AudioDevices
import com.sainadh.livenotes.desktop.audio.Pcm16MonoConverter
import com.sainadh.livenotes.stt.NativeTranscriptSegments
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptSampleClock
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import javax.sound.sampled.TargetDataLine

/** Callbacks run off the UI thread. Phases: preparing, recording, saving, idle. */
class DesktopRecorder(
    private val onTranscriptUpdate: (TranscriptUpdate) -> Unit,
    private val onLevel: (durationMs: Long, level: Float) -> Unit,
    private val onFinished: (audioFile: File?, durationMs: Long, wordTiming: String?, error: String?) -> Unit,
    private val onPhase: (String) -> Unit
) : AutoCloseable {
    // Set only before start, by module-local tests. Production uses JNI/JavaSound.
    internal var speech: SpeechNativeApi = NativeSpeech
    internal var openMicrophone: (String?) -> TargetDataLine = AudioDevices::open
    private class Capture {
        val stop = AtomicBoolean()
        val done = CountDownLatch(1)
        val queue = ArrayBlockingQueue<FloatArray>(20) // 10 seconds at 16 kHz.
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

    private fun requestStop(capture: Capture) {
        if (!capture.stop.compareAndSet(false, true)) return
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
        var failure: String? = null
        var timing: String? = null
        val segments = NativeTranscriptSegments()
        val clock = TranscriptSampleClock(16_000)
        var finalized = false
        try {
            emit { onPhase("preparing") }
            require(model.isFile) { "The selected speech model is missing" }
            speech.ensureLoaded()
            if (capture.stop.get()) return
            handle = speech.nativeInit(model.canonicalPath, language, -1)
            check(handle != 0L) { "Could not initialize speech model" }
            synchronized(lock) {
                if (capture.stop.get() || closed) return
                microphone.execute { capture(capture, device, output) }
                captureStarted = true
            }
            while (capture.done.count != 0L || capture.queue.isNotEmpty()) {
                val chunk = capture.queue.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val delta = speech.nativeFeedPcm(handle, chunk)
                clock.consume(chunk.size)
                delta?.let { segments.update(it).forEach { update -> onTranscriptUpdate(clock.stamp(update)) } }
                check(!speech.nativeWasTruncated(handle)) {
                    "The speech model reached its output limit. Recorded audio was preserved; the transcript is incomplete."
                }
            }
            emit { onPhase("saving") }
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
            if (!finalized && handle != 0L) runCatching { onTranscriptUpdate(clock.stamp(segments.interrupted())) }
                .exceptionOrNull()?.let { failure = failure ?: "Could not deliver transcript: ${it.message}" }
            failure = failure ?: capture.error
            // Native teardown is complete before the owner may change/delete a model.
            if (handle != 0L) runCatching { speech.nativeDestroy(handle) }.exceptionOrNull()?.let {
                failure = failure ?: "Could not release speech model: ${it.message}"
            }
            if (timing != null && capture.audio != null) {
                runCatching { NativeWordTimingFile.write(checkNotNull(capture.audio), checkNotNull(timing)) }
                    .exceptionOrNull()?.let { failure = failure ?: "Audio saved, but word timing could not be saved: ${it.message}" }
            }
            synchronized(lock) { if (active === capture) active = null }
            emit { onPhase("idle") }
            emit { onFinished(capture.audio, capture.durationMs, timing, failure) }
        }
    }

    private fun capture(state: Capture, device: String?, output: File) {
        var wav: WavFileWriter? = null
        try {
            if (state.stop.get()) return
            openMicrophone(device).use { line ->
                state.line = line
                if (state.stop.get()) return
                wav = WavFileWriter(output, 16_000)
                val converter = Pcm16MonoConverter(line.format.sampleRate.toInt(), line.format.channels)
                val raw = ByteArray(line.format.frameSize * 4096)
                val queued = FloatArray(8000)
                var pending = 0
                var savedSamples = 0L
                var lastLevelSamples = 0L
                var energy = 0.0
                var energySamples = 0
                fun enqueue(count: Int) {
                    check(state.queue.offer(queued.copyOf(count))) {
                        "Transcription could not keep up. Audio captured so far was saved, but the transcript is incomplete."
                    }
                }
                fun readAvailable(): Int {
                    val available = (minOf(line.available(), raw.size) / line.format.frameSize) * line.format.frameSize
                    if (available == 0) return 0
                    val count = line.read(raw, 0, available)
                    check(count >= 0) { "Microphone stopped unexpectedly" }
                    val pcm = converter.convert(raw, count)
                    checkNotNull(wav).write(pcm, 0, pcm.size) // Storage always precedes inference.
                    savedSamples += pcm.size
                    state.durationMs = savedSamples * 1000 / 16_000
                    for (sample in pcm) {
                        val value = sample / 32768f
                        energy += value * value
                        energySamples++
                        queued[pending++] = value
                        if (pending == queued.size) { enqueue(pending); pending = 0 }
                    }
                    if (savedSamples - lastLevelSamples >= 1600) {
                        val level = if (energySamples == 0) 0f else sqrt(energy / energySamples).toFloat().coerceIn(0f, 1f)
                        emit { onLevel(state.durationMs, level) }
                        lastLevelSamples = savedSamples
                        energy = 0.0
                        energySamples = 0
                    }
                    return count
                }
                line.start()
                emit { onPhase("recording") }
                while (!state.stop.get()) if (readAvailable() == 0) Thread.sleep(10)
                // Capture is the sole owner of JavaSound. Stop production, then
                // drain its retained device buffer, with a finite defensive bound.
                line.stop()
                var drained = 0
                while (drained < line.format.sampleRate.toInt() * line.format.frameSize * 2) {
                    val read = readAvailable()
                    if (read == 0) break
                    drained += read
                }
                check(line.available() == 0) { "Microphone did not finish draining; the recording may miss its last audio." }
                if (pending > 0) enqueue(pending)
            }
        } catch (error: Throwable) {
            state.error = state.error ?: error.message ?: error.javaClass.simpleName
        } finally {
            state.line = null
            try { state.audio = wav?.finish() } catch (error: Throwable) {
                state.error = state.error ?: "Could not finalize recorded audio: ${error.message}"
            }
            emit { onLevel(state.durationMs, 0f) }
            state.done.countDown()
        }
    }

    private inline fun emit(callback: () -> Unit) {
        runCatching(callback).exceptionOrNull()?.let { error ->
            System.err.println("Recorder callback failed: ${error.message}")
            error.printStackTrace()
        }
    }
}
