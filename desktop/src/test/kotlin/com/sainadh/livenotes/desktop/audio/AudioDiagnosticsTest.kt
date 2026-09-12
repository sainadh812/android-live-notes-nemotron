package com.sainadh.livenotes.desktop.audio

import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioDiagnosticsTest {
    @Test fun canceledMicrophoneTestClosesDeviceAndReportsActualSampleLevel() = runBlocking {
        val fixture = LineFixture()
        val diagnostics = AudioDiagnostics().apply { openInput = { fixture.input() } }
        var level = 0f
        try {
            diagnostics.microphone(null) { level = it; throw CancellationException("Stop test") }
            fail("The test must stop when canceled")
        } catch (_: CancellationException) { }
        assertTrue(level > 0)
        assertTrue(fixture.closed)
    }

    @Test fun disconnectedMicrophoneReportsFailureAndReleasesLine() = runBlocking {
        val fixture = LineFixture(disconnectOnStart = true)
        val diagnostics = AudioDiagnostics().apply { openInput = { fixture.input() } }
        try {
            diagnostics.microphone(null) { }
            fail("A disconnected microphone must not stay in a listening state")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("disconnected"))
        }
        assertTrue(fixture.closed)
    }

    @Test fun speakerTestRendersStereoToneAtDeviceRateAndReleasesOutput() = runBlocking {
        val fixture = LineFixture()
        var selected: String? = null
        val diagnostics = AudioDiagnostics().apply { openOutput = { id, _ -> selected = id; fixture.output() } }
        val message = diagnostics.speakers("headphones")
        assertEquals("headphones", selected)
        assertTrue(message.contains("Test tone finished"))
        assertTrue(fixture.closed)
        val pcm = fixture.played.toByteArray()
        assertEquals(48_000 * 3 / 4 * 4, pcm.size)
        assertTrue(pcm.any { it.toInt() != 0 })
        for (offset in pcm.indices step 4) {
            assertEquals(pcm[offset], pcm[offset + 2])
            assertEquals(pcm[offset + 1], pcm[offset + 3])
        }
    }

    private class LineFixture(val disconnectOnStart: Boolean = false) {
        val format = AudioFormat(48_000f, 16, 2, true, false)
        var closed = false
        var running = false
        var frames = 0L
        val played = ByteArrayOutputStream()
        fun input(): TargetDataLine = line(TargetDataLine::class.java)
        fun output(): SourceDataLine = line(SourceDataLine::class.java)
        private fun <T> line(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "getFormat" -> format
                "start" -> { running = !disconnectOnStart; null }
                "stop" -> { running = false; null }
                "close" -> { closed = true; running = false; null }
                "isOpen" -> !closed
                "isRunning", "isActive" -> running
                "available" -> 8192
                "getLongFramePosition" -> frames
                "getFramePosition" -> frames.toInt()
                "read" -> {
                    val target = args!![0] as ByteArray; val offset = args[1] as Int; val count = args[2] as Int
                    for (i in offset until offset + count step 2) { target[i] = 0; target[i + 1] = 4 }
                    count
                }
                "write" -> {
                    val count = args!![2] as Int
                    played.write(args[0] as ByteArray, args[1] as Int, count); frames += count / 4; count
                }
                "toString" -> "Audio test device"
                else -> null
            }
        })
    }
}
