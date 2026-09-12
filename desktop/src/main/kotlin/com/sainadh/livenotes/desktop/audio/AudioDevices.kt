package com.sainadh.livenotes.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import javax.sound.sampled.SourceDataLine
import java.util.Base64

data class AudioInputDevice(val id: String, val name: String)
data class AudioOutputDevice(val id: String, val name: String)

/** Microphones, including Bluetooth inputs, exposed by the operating system. */
object AudioDevices {
    private fun deviceId(info: Mixer.Info): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        listOf(info.name, info.vendor, info.description, info.version).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    )

    fun inputs(): List<AudioInputDevice> = AudioSystem.getMixerInfo().mapNotNull { info ->
        runCatching {
            val mixer = AudioSystem.getMixer(info)
            if (mixer.targetLineInfo.any { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }) {
                AudioInputDevice(deviceId(info), info.name)
            } else null
        }.getOrNull()
    }

    /** Playback endpoints are separate from microphone endpoints; this does not capture system audio. */
    fun outputs(): List<AudioOutputDevice> = AudioSystem.getMixerInfo().mapNotNull { info ->
        runCatching {
            val mixer = AudioSystem.getMixer(info)
            if (mixer.sourceLineInfo.any { SourceDataLine::class.java.isAssignableFrom(it.lineClass) }) {
                AudioOutputDevice(deviceId(info), info.name)
            } else null
        }.getOrNull()
    }

    internal fun openOutput(id: String?, sourceSampleRate: Int): SourceDataLine {
        val selected = id?.let { selectedId ->
            val info = AudioSystem.getMixerInfo().firstOrNull { deviceId(it) == selectedId }
                ?: error("The selected playback device is no longer available. Reconnect it or choose another output.")
            AudioSystem.getMixer(info)
        }
        return openCompatibleOutput(sourceSampleRate) { info ->
            (selected?.getLine(info) ?: AudioSystem.getLine(info)) as SourceDataLine
        }
    }

    // Acquiring the line is injectable so negotiation is tested without audio hardware.
    internal fun openCompatibleOutput(sourceSampleRate: Int, acquire: (DataLine.Info) -> SourceDataLine): SourceDataLine {
        require(sourceSampleRate > 0)
        var lastFailure: Throwable? = null
        val rates = listOf(sourceSampleRate, 48_000, 44_100, 32_000, 16_000, 8_000).distinct()
        for (rate in rates) for (channels in 1..2) {
            val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
            var line: SourceDataLine? = null
            try {
                line = acquire(DataLine.Info(SourceDataLine::class.java, format))
                line.open(format, (rate / 10).coerceAtLeast(1024) * channels * 2)
                return line
            } catch (error: Exception) {
                runCatching { line?.close() }
                lastFailure = error
            }
        }
        throw IllegalStateException("Cannot open this playback device. Check Windows sound settings or choose another output.", lastFailure)
    }

    internal fun open(id: String?): TargetDataLine {
        val selected = id?.let { selectedId ->
            val info = AudioSystem.getMixerInfo().firstOrNull { deviceId(it) == selectedId }
                ?: error("The selected microphone is no longer available")
            AudioSystem.getMixer(info)
        }
        var lastFailure: Throwable? = null
        // Narrow-band Bluetooth microphones may expose only 8 kHz PCM. Keep
        // capture PCM16 mono/stereo and convert these rates to the model's 16 kHz.
        for ((rate, channels) in listOf(16_000 to 1, 48_000 to 1, 44_100 to 1, 48_000 to 2, 44_100 to 2, 32_000 to 1, 8_000 to 1)) {
            val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
            val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
            var line: TargetDataLine? = null
            try {
                line = (selected?.getLine(lineInfo) ?: AudioSystem.getLine(lineInfo)) as TargetDataLine
                line.open(format, rate * channels * 2 / 2)
                return line
            } catch (error: Exception) {
                runCatching { line?.close() }
                lastFailure = error
            }
        }
        throw IllegalStateException("Cannot open this microphone. Check Windows microphone permission and device availability.", lastFailure)
    }
}

/** Stateful conversion to the recorder's 16 kHz mono format, using bounded blocks. */
internal class Pcm16MonoConverter(private val sourceRate: Int, private val channels: Int) {
    private var sourceFrame = 0L
    private var nextOutputPosition = 0.0
    private var previous = 0.0
    init { require(sourceRate > 0 && channels in 1..2) }

    fun convert(bytes: ByteArray, count: Int): ShortArray {
        require(count in 0..bytes.size && count % (channels * 2) == 0)
        val frames = count / (channels * 2)
        val output = ShortArray((frames * 16_000L / sourceRate + 3).toInt())
        var written = 0
        repeat(frames) { frame ->
            var value = 0.0
            repeat(channels) { channel ->
                val offset = (frame * channels + channel) * 2
                value += ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toDouble()
            }
            value /= channels
            while (nextOutputPosition <= sourceFrame.toDouble()) {
                val interpolated = if (sourceFrame == 0L) value else
                    previous + (value - previous) * (nextOutputPosition - (sourceFrame - 1))
                output[written++] = interpolated.toInt().coerceIn(-32768, 32767).toShort()
                nextOutputPosition += sourceRate / 16_000.0
            }
            previous = value
            sourceFrame++
        }
        return output.copyOf(written)
    }
}
