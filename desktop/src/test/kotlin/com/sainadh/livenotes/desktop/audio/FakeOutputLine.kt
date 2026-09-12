package com.sainadh.livenotes.desktop.audio

import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.SourceDataLine

/** A controllable device clock keeps playback tests independent of wall time and sound hardware. */
internal class FakeOutputLine(val format: AudioFormat, private val accepts: (AudioFormat) -> Boolean = { true }) {
    val closed = CountDownLatch(1)
    val acceptedFrames = AtomicLong()
    val playedFrames = AtomicLong()
    @Volatile var opened = false
    @Volatile var running = false
    private val pcm = ByteArrayOutputStream()
    fun pcmBytes(): ByteArray = synchronized(pcm) { pcm.toByteArray() }
    val line = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
        when (method.name) {
            "open" -> {
                val requested = args?.firstOrNull() as? AudioFormat ?: format
                if (!accepts(requested)) throw LineUnavailableException("Unsupported test device format")
                opened = true
                null
            }
            "getFormat" -> format
            "available", "getBufferSize" -> 16_384
            "write" -> {
                check(opened && running)
                val bytes = args!![0] as ByteArray
                val offset = args[1] as Int
                val count = args[2] as Int
                synchronized(pcm) { pcm.write(bytes, offset, count) }
                acceptedFrames.addAndGet((count / format.frameSize).toLong())
                count
            }
            "getLongFramePosition" -> playedFrames.get()
            "getFramePosition" -> playedFrames.get().toInt()
            "start" -> { running = true; null }
            "stop" -> { running = false; null }
            "close" -> { opened = false; running = false; closed.countDown(); null }
            "isOpen" -> opened
            "isActive", "isRunning" -> running
            "getControls" -> emptyArray<javax.sound.sampled.Control>()
            "isControlSupported" -> false
            "toString" -> "Test output ${format.sampleRate}/${format.channels}"
            else -> null
        }
    } as SourceDataLine
    fun open(): SourceDataLine = line.also { it.open(format) }
}
