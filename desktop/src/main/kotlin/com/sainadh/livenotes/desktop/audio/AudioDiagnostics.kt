package com.sainadh.livenotes.desktop.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** User-started, short device checks. Microphone samples are discarded, never saved or transcribed. */
internal class AudioDiagnostics {
    internal var openInput: (String?) -> TargetDataLine = AudioDevices::open
    internal var openOutput: (String?, Int) -> SourceDataLine = AudioDevices::openOutput
    @Volatile private var activeLine: DataLine? = null

    fun cancel() {
        // Driver close can block; it must never run on the UI thread.
        val line = activeLine ?: return
        Thread({ runCatching { line.close() } }, "audio-test-stop").apply { isDaemon = true; start() }
    }

    suspend fun microphone(id: String?, progress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        openInput(id).use { line ->
            activeLine = line
            try {
                currentCoroutineContext().ensureActive()
                val frameSize = line.format.frameSize
                val bytes = ByteArray(frameSize * 4096)
                val started = System.nanoTime()
                var lastFrames = started
                var nextUpdate = started
                var maximum = 0f
                var received = false
                line.start()
                while (System.nanoTime() - started < 10_000_000_000L) {
                    currentCoroutineContext().ensureActive()
                    check(line.isOpen && line.isRunning) { "The microphone disconnected or stopped. Choose an available device and try again." }
                    val available = minOf(line.available(), bytes.size) / frameSize * frameSize
                    if (available > 0) {
                        val count = line.read(bytes, 0, available)
                        check(count >= 0 && count % frameSize == 0) { "The microphone stopped returning complete audio frames." }
                        if (count > 0) {
                            received = true
                            lastFrames = System.nanoTime()
                            var energy = 0.0
                            for (offset in 0 until count step 2) {
                                val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort() / 32768.0
                                energy += sample * sample
                            }
                            val rms = sqrt(energy / (count / 2)).toFloat()
                            maximum = maxOf(maximum, rms)
                            if (lastFrames >= nextUpdate) { progress((rms * 8).coerceIn(0f, 1f)); nextUpdate = lastFrames + 100_000_000L }
                        }
                    }
                    check(System.nanoTime() - lastFrames < 3_000_000_000L) {
                        "No audio frames arrived from the microphone. Check Windows microphone access, reconnect the device, or choose another input."
                    }
                    delay(10)
                }
                check(received) { "The microphone did not provide audio." }
                if (maximum >= .002f) "Microphone test finished: sound detected."
                else "The microphone opened but no clear sound was detected. Check mute, input volume, and the selected device."
            } finally { activeLine = null }
        }
    }

    suspend fun speakers(id: String?): String = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        openOutput(id, 16_000).use { line ->
            activeLine = line
            try {
                currentCoroutineContext().ensureActive()
                val rate = line.format.sampleRate.toInt()
                val channels = line.format.channels
                val frameSize = line.format.frameSize
                val total = rate * 3 / 4
                val bytes = ByteArray(4096 * frameSize)
                val epoch = line.longFramePosition
                val deadline = System.nanoTime() + 4_000_000_000L
                var position = 0
                line.start()
                while (position < total || line.longFramePosition - epoch < position) {
                    currentCoroutineContext().ensureActive()
                    check(line.isOpen && line.isRunning) { "The output device disconnected or stopped. Choose another speaker or headset." }
                    check(System.nanoTime() < deadline) { "The output device did not finish the test tone. Reconnect it or choose another output." }
                    val count = minOf(total - position, bytes.size / frameSize, line.available() / frameSize)
                    if (count > 0) {
                        repeat(count) { frame ->
                            val index = position + frame
                            val fade = minOf(1.0, index / (rate * .02), (total - index) / (rate * .02))
                            val sample = (sin(2 * PI * 440 * index / rate) * fade * .06 * 32767).toInt()
                            repeat(channels) { channel ->
                                val offset = frame * frameSize + channel * 2
                                bytes[offset] = sample.toByte(); bytes[offset + 1] = (sample shr 8).toByte()
                            }
                        }
                        val written = line.write(bytes, 0, count * frameSize)
                        check(written >= 0 && written % frameSize == 0) { "The output device could not play a complete audio frame." }
                        position += written / frameSize
                    }
                    delay(5)
                }
                "Test tone finished. If you did not hear it, check the selected output, volume, or Windows Sound settings."
            } finally { activeLine = null }
        }
    }
}
