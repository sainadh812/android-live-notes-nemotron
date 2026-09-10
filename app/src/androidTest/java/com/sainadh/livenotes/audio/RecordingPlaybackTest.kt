package com.sainadh.livenotes.audio

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sainadh.livenotes.data.SavedRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class RecordingPlaybackTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(message: String, test: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            var passed = false
            main { passed = test() }
            if (passed) return
            Thread.sleep(50)
        }
        fail(message)
    }

    @Test fun recordedPcmCanPlaySeekPauseChangeSpeedAndShare() {
        val file = File(context.filesDir, "recordings/playback-test-${System.nanoTime()}.wav")
        val writer = WavFileWriter(file, 16_000)
        val samples = ShortArray(80_000) { (sin(it * 2.0 * Math.PI * 440.0 / 16_000) * 2000).toInt().toShort() }
        writer.write(samples, 0, samples.size)
        assertEquals(file, writer.finish())
        val recording = SavedRecording(file.name, "2026-09-10", "Playback test", 1, false,
            durationMs = 5000, audioFileName = file.name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: RecordingPlayer
        var initialized = false
        try {
            main { player = RecordingPlayer(context, scope); initialized = true; player.play(recording, 1000) }
            waitFor("WAV did not prepare/play: ${if (initialized) player.state.value else "no player"}") {
                player.state.value.isPlaying && player.state.value.positionMs >= 900
            }
            main { player.pause(); player.seek(2500); player.setSpeed(1.5f) }
            assertFalse(player.state.value.isPlaying)
            assertEquals(2500L, player.state.value.positionMs)
            assertEquals(1.5f, player.state.value.speed)
            main { player.toggle() }
            waitFor("Resume did not advance playback") { player.state.value.positionMs > 2600 }
            main { player.pause() }
            val paused = player.state.value.positionMs
            Thread.sleep(250)
            assertEquals(paused, player.state.value.positionMs)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
            assertEquals("content", uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use {
                assertEquals("RIFF", String(ByteArray(4).also(it::read), Charsets.US_ASCII))
            }
            main { player.play(recording, 4500) }
            waitFor("Playback did not complete") { !player.state.value.isPlaying && player.state.value.positionMs >= 4950 }
        } finally {
            main { if (initialized) player.close() }
            scope.cancel()
            file.delete()
        }
    }

    @Test fun missingAndOutsideAudioFilesCannotBePlayedOrShared() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var player: RecordingPlayer
        main {
            player = RecordingPlayer(context, scope)
            player.play(SavedRecording("missing", "2026-09-10", "Text remains", 1, false,
                audioFileName = "missing-${System.nanoTime()}.wav"))
            assertNotNull(player.state.value.error)
            assertFalse(player.state.value.isPlaying)
            player.close()
        }
        scope.cancel()
        try {
            recordingAudioFile(context, "../settings.wav")
            fail("Path traversal was accepted")
        } catch (_: IllegalArgumentException) { }
        val privateFile = File(context.filesDir, "not-a-recording.txt")
        try {
            privateFile.writeText("test")
            FileProvider.getUriForFile(context, "${context.packageName}.recordings", privateFile)
            fail("FileProvider exposed a file outside recordings")
        } catch (_: IllegalArgumentException) {
        } finally { privateFile.delete() }
    }
}
