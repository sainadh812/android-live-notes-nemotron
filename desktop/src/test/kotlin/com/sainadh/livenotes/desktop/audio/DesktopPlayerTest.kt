package com.sainadh.livenotes.desktop.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DesktopPlayerTest {
    @Test fun stereoOutputAt48000KeepsSourceTimingAndReleasesOnPause() = withWav(ShortArray(16_000) { (it % 50 * 300).toShort() }) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        val output = FakeOutputLine(AudioFormat(48_000f, 16, 2, true, false))
        val player = DesktopPlayer { states.offer(it) }.apply { openOutput = { _, _ -> output.open() } }
        try {
            player.load(file); player.play()
            awaitCondition { output.acceptedFrames.get() >= 48_000 }
            val samples = decode(output.pcmBytes(), 8)
            assertEquals(listOf(0, 0, 100, 100, 200, 200, 300, 300), samples)
            // Queueing one second must not move the word highlight ahead of the device clock.
            output.playedFrames.set(24_000)
            awaitState(states) { it.isPlaying && it.positionMs in 499L..500L }
            player.pause()
            val paused = awaitState(states) { !it.isPlaying && it.positionMs in 499L..500L }
            assertEquals(1_000L, paused.durationMs)
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
            player.unload()
        } finally { player.close(); player.unload() }
    }

    @Test fun resumeReopensDefaultAndDeviceChangesKeepPositionWithoutRedundantReopen() = withWav(ShortArray(16_000)) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        val opened = java.util.concurrent.CopyOnWriteArrayList<Pair<String?, FakeOutputLine>>()
        val player = DesktopPlayer { states.offer(it) }.apply { openOutput = { id, _ ->
            val output = FakeOutputLine(AudioFormat(if (opened.isEmpty()) 44_100f else 48_000f, 16, 2, true, false))
            opened += id to output
            output.open()
        } }
        try {
            player.load(file); player.play()
            awaitCondition { opened.firstOrNull()?.second?.acceptedFrames?.get()?.let { it >= 22_050 } == true }
            val first = opened[0].second
            first.playedFrames.set(11_025)
            awaitState(states) { it.isPlaying && it.positionMs in 249L..250L }
            player.pause()
            awaitState(states) { !it.isPlaying && it.positionMs in 249L..250L }
            assertTrue(first.closed.await(1, TimeUnit.SECONDS))
            player.play()
            awaitCondition { opened.size == 2 && opened[1].second.running }
            assertEquals(null, opened[1].first)
            player.setOutputDevice("headset")
            awaitCondition { opened.size == 3 && opened[2].second.running }
            assertEquals("headset", opened[2].first)
            assertTrue(opened[1].second.closed.await(1, TimeUnit.SECONDS))
            awaitState(states) { it.isPlaying && it.positionMs in 249L..250L }
            player.setOutputDevice("headset")
            Thread.sleep(100)
            assertEquals(3, opened.size)
            assertTrue(opened[2].second.running)
            player.pause(); player.unload()
        } finally { player.close(); player.unload() }
    }

    @Test fun changedRequestClosesDeviceThatFinishedOpeningLate() = withWav(ShortArray(16_000)) { file ->
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val states = LinkedBlockingQueue<PlaybackState>()
        val output = FakeOutputLine(AudioFormat(48_000f, 16, 2, true, false))
        val player = DesktopPlayer { states.offer(it) }.apply { openOutput = { _, _ ->
            opening.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            output.open()
        } }
        try {
            player.load(file); player.play()
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            player.pause(); release.countDown()
            assertTrue(output.closed.await(5, TimeUnit.SECONDS))
            awaitState(states) { it.file == file.absoluteFile && !it.isPlaying }
            assertEquals(0L, output.acceptedFrames.get())
            player.unload()
        } finally { release.countDown(); player.close(); player.unload() }
    }

    @Test fun disconnectedPlaybackDeviceStopsAndReportsError() = withWav(ShortArray(16_000)) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        val output = FakeOutputLine(AudioFormat(48_000f, 16, 2, true, false))
        val player = DesktopPlayer { states.offer(it) }.apply { openOutput = { _, _ -> output.open() } }
        try {
            player.load(file); player.play()
            awaitState(states) { it.isPlaying }
            output.line.close()
            val failed = awaitState(states) { it.error != null }
            assertTrue(!failed.isPlaying)
            assertTrue(failed.error!!.contains("disconnected"))
            player.unload()
        } finally { player.close(); player.unload() }
    }

    @Test fun outputStartsWithFirstWriteAndMissingClockProgressStillStopsPlayback() = withWav(ShortArray(16_000)) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        val output = FakeOutputLine(AudioFormat(48_000f, 16, 2, true, false))
        val player = DesktopPlayer { states.offer(it) }.apply {
            openOutput = { _, _ -> output.open() }
            outputStallTimeoutMs = 150
        }
        try {
            player.load(file); player.play()
            val failure = awaitState(states) { it.error != null }
            assertTrue("An initially non-running device must receive PCM", output.acceptedFrames.get() > 0)
            assertTrue(failure.error!!.contains("stopped accepting audio"))
            assertTrue(!failure.isPlaying)
            assertTrue(output.closed.await(1, TimeUnit.SECONDS))
        } finally { player.close(); player.unload() }
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Playback condition did not complete", predicate())
    }

    @Test fun unloadReleasesFileAndPlayerCanLoadItAgain() = withWav(ShortArray(1600)) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        val player = DesktopPlayer { states.offer(it) }
        try {
            player.load(file)
            awaitState(states) { it.file == file.absoluteFile && it.durationMs == 100L }
            player.unload()
            assertTrue("Unloaded WAV must be deletable on Windows", file.delete())
            RandomAccessFile(file, "rw").use { it.write(header(800)); it.setLength(844) }
            player.load(file)
            val reloaded = awaitState(states) { it.file == file.absoluteFile && it.durationMs == 25L }
            assertEquals(0L, reloaded.positionMs)
            player.close()
            player.unload() // Also waits for asynchronous close to release its reader.
            assertTrue("Closed player must release its WAV", file.delete())
        } finally {
            player.close()
        }
    }

    @Test fun rapidLoadSeekAndSpeedRequestsKeepRequestedPosition() = withWav(ShortArray(16_000)) { file ->
        val states = LinkedBlockingQueue<PlaybackState>()
        DesktopPlayer { states.offer(it) }.use { player ->
            player.load(file)
            player.seekTo(500)
            player.setSpeed(1.5f)
            val state = awaitState(states) { it.file == file.absoluteFile && it.positionMs == 500L && it.speed == 1.5f }
            assertTrue(!state.isPlaying)
            player.unload()
        }
    }

    private fun awaitState(states: LinkedBlockingQueue<PlaybackState>, predicate: (PlaybackState) -> Boolean): PlaybackState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val state = states.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (predicate(state)) return state
        }
        error("Player did not publish the expected state")
    }

    @Test fun resamplingPreservesSamplesAndInterpolatesHalfSpeed() = withWav(shortArrayOf(-1000, 0, 1000, 2000)) { file ->
        PcmWavReader(file).use { wav ->
            val bytes = ByteArray(32)
            val normal = wav.render(0.0, 1.0, bytes)
            assertEquals(listOf(-1000, 0, 1000, 2000), decode(bytes, normal.frames))
            val slow = wav.render(0.0, 0.5, bytes)
            assertEquals(listOf(-1000, -500, 0, 500, 1000, 1500, 2000, 2000), decode(bytes, slow.frames))
            val fast = wav.render(0.0, 2.0, bytes)
            assertEquals(listOf(-1000, 1000), decode(bytes, fast.frames))
            assertEquals(4.0, fast.nextPosition, 0.0)
        }
    }

    @Test fun seekingAcrossCacheBoundaryPreservesSignedPcm() = withWav(ShortArray(10_000) { (it - 5000).toShort() }) { file ->
        PcmWavReader(file).use { wav ->
            val bytes = ByteArray(24)
            wav.render(4093.0, 1.0, bytes)
            assertEquals((4093 until 4105).map { it - 5000 }, decode(bytes, 12))
            wav.render(2.0, 1.0, bytes)
            assertEquals((2 until 14).map { it - 5000 }, decode(bytes, 12))
            assertEquals(625L, wav.durationMs)
        }
    }

    @Test fun hourLongSparseWavSupportsRandomAccessWithoutLoadingAudio() {
        val folder = Files.createTempDirectory("desktop-player-large").toFile()
        try {
            val file = File(folder, "hour.wav")
            val frames = 16_000L * 60 * 60
            RandomAccessFile(file, "rw").use { out ->
                out.write(header(frames * 2))
                out.setLength(44 + frames * 2)
                out.seek(44 + (frames - 1) * 2)
                out.write(byteArrayOf(0x00, 0x80.toByte()))
            }
            PcmWavReader(file).use { wav ->
                assertEquals(3_600_000L, wav.durationMs)
                val bytes = ByteArray(16)
                val end = wav.render((frames - 2).toDouble(), 1.0, bytes)
                assertEquals(listOf(0, -32768), decode(bytes, end.frames))
                assertEquals(frames.toDouble(), end.nextPosition, 0.0)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun incompleteAndNonPcmWavsAreRejected() = withWav(shortArrayOf(1, 2, 3)) { file ->
        RandomAccessFile(file, "rw").use { it.setLength(it.length() - 1) }
        try {
            PcmWavReader(file).close()
            fail("Truncated audio must not play")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Incomplete"))
        }
        RandomAccessFile(file, "rw").use {
            it.setLength(50)
            it.seek(0)
            it.write(header(6))
            it.seek(20)
            it.write(3) // Floating point PCM is unsupported.
        }
        try {
            PcmWavReader(file).close()
            fail("Unsupported audio must not play")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("PCM16"))
        }
    }

    private fun withWav(samples: ShortArray, body: (File) -> Unit) {
        val folder = Files.createTempDirectory("desktop-player").toFile()
        try {
            val file = File(folder, "audio.wav")
            RandomAccessFile(file, "rw").use { out ->
                out.write(header(samples.size * 2L))
                val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach(pcm::putShort)
                out.write(pcm.array())
            }
            body(file)
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun header(bytes: Long): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + bytes).toInt()); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(bytes.toInt())
    }.array()

    private fun decode(bytes: ByteArray, frames: Int): List<Int> = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { data ->
        List(frames) { data.short.toInt() }
    }
}
