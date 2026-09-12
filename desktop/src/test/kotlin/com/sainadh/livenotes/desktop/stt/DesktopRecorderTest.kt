package com.sainadh.livenotes.desktop.stt

import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import org.junit.Assert.*
import org.junit.Test

class DesktopRecorderTest {
    private class Microphone(frames: Int, val blockRead: Boolean = false, val sample: Short = 16_384,
        val sampleAt: ((Int) -> Short)? = null) {
        val remaining = AtomicInteger(frames)
        val readFrames = AtomicInteger()
        val closed = CountDownLatch(1)
        val emptied = CountDownLatch(1)
        val readEntered = CountDownLatch(1)
        val line = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getFormat" -> AudioFormat(16_000f, 16, 1, true, false)
                "available" -> remaining.get() * 2
                "read" -> {
                    readEntered.countDown()
                    if (blockRead) {
                        check(closed.await(5, TimeUnit.SECONDS))
                        return@newProxyInstance -1
                    }
                    val count = minOf(args!![2] as Int / 2, remaining.get())
                    val bytes = args[0] as ByteArray
                    val offset = args[1] as Int
                    repeat(count) {
                        val value = sampleAt?.invoke(readFrames.get() + it) ?: sample
                        bytes[offset + it * 2] = value.toByte(); bytes[offset + it * 2 + 1] = (value.toInt() shr 8).toByte()
                    }
                    remaining.addAndGet(-count)
                    readFrames.addAndGet(count)
                    if (remaining.get() == 0) emptied.countDown()
                    count * 2
                }
                "close" -> { closed.countDown(); null }
                "isOpen", "isActive", "isRunning" -> closed.count != 0L
                "getControls" -> emptyArray<javax.sound.sampled.Control>()
                "isControlSupported" -> false
                "toString" -> "Test microphone"
                else -> null
            }
        } as TargetDataLine
    }

    private class Engine(val hold: Boolean = false, val fail: Boolean = false, val holdInit: Boolean = false,
        val expectedSample: Float = 0.5f, val expectedSampleAt: ((Int) -> Float)? = null) : SpeechNativeApi {
        val initEntered = CountDownLatch(1)
        val initRelease = CountDownLatch(if (holdInit) 1 else 0)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(if (hold) 1 else 0)
        val consumed = AtomicInteger()
        val calls = AtomicInteger()
        @Volatile var destroyed = false
        override fun ensureLoaded() {}
        override fun nativeInit(modelPath: String, language: String, attContextRight: Int): Long {
            initEntered.countDown()
            check(initRelease.await(5, TimeUnit.SECONDS))
            return 1L
        }
        override fun nativeFeedPcm(handle: Long, pcm: FloatArray): String? {
            pcm.forEachIndexed { index, sample ->
                assertEquals(expectedSampleAt?.invoke(consumed.get() + index) ?: expectedSample, sample, 0f)
            }
            consumed.addAndGet(pcm.size)
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (fail) error("test inference failure")
            return if (calls.getAndIncrement() == 0) "hello \u0001world" else null
        }
        override fun nativeFinalizeStream(handle: Long) = "hello world"
        override fun nativeWordTimings(handle: Long) = ""
        override fun nativeWasTruncated(handle: Long) = false
        override fun nativeDestroy(handle: Long) { destroyed = true }
    }

    private class Fixture(val microphone: Microphone, val engine: Engine, failUpdates: Boolean = false) : AutoCloseable {
        val directory = Files.createTempDirectory("desktop-recorder-test").toFile()
        val model = File(directory, "test.gguf").apply { writeText("fixture") }
        val output = File(directory, "meeting.wav")
        val finished = CountDownLatch(1)
        val updates = mutableListOf<TranscriptUpdate>()
        val phases = java.util.concurrent.CopyOnWriteArrayList<String>()
        val progress = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Long>>()
        @Volatile var error: String? = null
        @Volatile var saved: File? = null
        @Volatile var duration = 0L
        val recorder = DesktopRecorder(
            onTranscriptUpdate = { if (failUpdates) error("persistence channel closed") else updates += it },
            onLevel = { _, _ -> }, onPhase = { phases += it },
            onFinished = { file, milliseconds, _, failure ->
                check(engine.destroyed)
                saved = file; duration = milliseconds; error = failure; finished.countDown()
            }, onProgress = { captured, transcribed -> progress += captured to transcribed }
        ).apply { speech = engine; openMicrophone = { microphone.line } }
        fun start() = recorder.start(model, outputFile = output)
        fun awaitFinished() {
            assertTrue("Recording did not finish", finished.await(5, TimeUnit.SECONDS))
            assertTrue("Temporary PCM backlog was not removed", directory.listFiles().orEmpty().none { it.name.startsWith(".speech-backlog-") })
        }
        fun assertAudio() {
            assertEquals(output, saved)
            assertEquals(44L + microphone.readFrames.get() * 2L, output.length())
            assertEquals(microphone.readFrames.get() * 1000L / 16_000, duration)
        }
        override fun close() {
            engine.release.countDown()
            engine.initRelease.countDown()
            recorder.close()
            finished.await(5, TimeUnit.SECONDS)
            directory.deleteRecursively()
        }
    }

    @Test fun stopDrainsCaptureWhileInferenceIsHeldAndDestroysBeforeFinished() {
        Fixture(Microphone(17_123), Engine(hold = true)).use { fixture ->
            fixture.start()
            assertTrue(fixture.engine.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.microphone.emptied.await(5, TimeUnit.SECONDS))
            fixture.recorder.stop()
            assertTrue(fixture.microphone.closed.await(5, TimeUnit.SECONDS))
            assertFalse(fixture.engine.destroyed)
            assertEquals(1L, fixture.finished.count)
            fixture.engine.release.countDown()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertNull(fixture.error)
            assertEquals(17_123, fixture.engine.consumed.get())
            val latest = fixture.updates.associateBy { it.segmentId }.values
            assertEquals("hello world", latest.joinToString("") { it.text })
            assertTrue(latest.all { it.status == TranscriptStatus.FINAL })
        }
    }

    @Test fun inferenceFailurePreservesAudioAndSignalsIncompleteTranscript() {
        Fixture(Microphone(17_123), Engine(fail = true)).use { fixture ->
            fixture.start()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertTrue(fixture.error.orEmpty().contains("test inference failure"))
            assertEquals(TranscriptStatus.INTERRUPTED, fixture.updates.last().status)
        }
    }

    @Test fun captureContinuesBeyondOneMinuteWhileInferenceIsBlockedThenDrainsEverySampleInOrder() {
        val frames = 16_000 * 65 + 123
        val sampleAt: (Int) -> Short = { (it * 73 + 29).toShort() }
        Fixture(Microphone(frames, sampleAt = sampleAt), Engine(hold = true,
            expectedSampleAt = { sampleAt(it) / 32768f })).use { fixture ->
            fixture.start()
            assertTrue(fixture.engine.entered.await(5, TimeUnit.SECONDS))
            assertTrue("Capture stopped before reading the meeting", fixture.microphone.emptied.await(5, TimeUnit.SECONDS))
            assertEquals(frames, fixture.microphone.readFrames.get())
            assertEquals("Slow inference must not close the microphone", 1L, fixture.microphone.closed.count)
            assertEquals(1L, fixture.finished.count)
            fixture.recorder.stop()
            assertTrue(fixture.microphone.closed.await(5, TimeUnit.SECONDS))
            assertEquals("Stop must wait for inference to catch up", 1L, fixture.finished.count)
            fixture.engine.release.countDown()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertNull(fixture.error)
            assertEquals(frames, fixture.engine.consumed.get())
            assertTrue(fixture.progress.any { (captured, transcribed) -> captured - transcribed > 60_000 })
            assertEquals(frames * 1000L / 16_000, fixture.progress.last().first)
            assertEquals(fixture.progress.last().first, fixture.progress.last().second)
            assertTrue(fixture.progress.all { (captured, transcribed) -> captured >= transcribed })
            val bytes = fixture.output.readBytes()
            repeat(frames) { index ->
                val value = ((bytes[44 + index * 2].toInt() and 255) or
                    (bytes[45 + index * 2].toInt() shl 8)).toShort()
                assertEquals("Saved PCM sample $index", sampleAt(index), value)
            }
        }
    }

    @Test fun closeDuringModelLoadWaitsForOwningWorkerAndNeverOpensMicrophone() {
        Fixture(Microphone(8_000), Engine(holdInit = true)).use { fixture ->
            fixture.start()
            assertTrue(fixture.engine.initEntered.await(5, TimeUnit.SECONDS))
            fixture.recorder.close()
            assertFalse(fixture.engine.destroyed)
            fixture.engine.initRelease.countDown()
            fixture.awaitFinished()
            assertTrue(fixture.engine.destroyed)
            assertEquals(0, fixture.microphone.readFrames.get())
            assertNull(fixture.saved)
            assertNull(fixture.error)
        }
    }

    @Test fun stopUnblocksFaultyDriverAndReportsPossibleTailLoss() {
        Fixture(Microphone(8_000, blockRead = true), Engine()).use { fixture ->
            fixture.start()
            assertTrue(fixture.microphone.readEntered.await(5, TimeUnit.SECONDS))
            fixture.recorder.stop()
            fixture.awaitFinished()
            assertTrue(fixture.error.orEmpty().contains("did not stop promptly"))
            assertTrue(fixture.engine.destroyed)
            assertNull(fixture.saved)
        }
    }

    @Test fun transcriptDeliveryFailureStopsCaptureAndSurfacesError() {
        Fixture(Microphone(17_123), Engine(), failUpdates = true).use { fixture ->
            fixture.start()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertTrue(fixture.error.orEmpty().contains("persistence channel closed"))
        }
    }

    @Test fun disconnectedMicrophonePreservesAudioAndFinalPartialInferenceChunk() {
        Fixture(Microphone(9_123), Engine()).use { fixture ->
            fixture.start()
            assertTrue(fixture.microphone.emptied.await(5, TimeUnit.SECONDS))
            fixture.microphone.line.close()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertEquals(9_123, fixture.engine.consumed.get())
            assertTrue(fixture.error.orEmpty().contains("disconnected"))
        }
    }

    @Test fun disconnectedMicrophoneShowsSavingWhileInferenceStillHasHeldBacklog() {
        Fixture(Microphone(17_123), Engine(hold = true)).use { fixture ->
            fixture.start()
            assertTrue(fixture.engine.entered.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.microphone.emptied.await(5, TimeUnit.SECONDS))
            fixture.microphone.line.close()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (fixture.phases.lastOrNull() != "saving" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("Capture ended, but its transcript is still catching up", "saving", fixture.phases.lastOrNull())
            assertEquals(1L, fixture.finished.count)
            assertFalse(fixture.engine.destroyed)
            fixture.engine.release.countDown()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertEquals(17_123, fixture.engine.consumed.get())
            assertTrue(fixture.error.orEmpty().contains("disconnected"))
            assertEquals(listOf("preparing", "recording", "saving", "idle"), fixture.phases.toList())
        }
    }

    @Test fun stopBeforeFirstSamplesNeverPublishesRecordingAfterSaving() {
        Fixture(Microphone(0), Engine()).use { fixture ->
            val opened = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            fixture.recorder.openMicrophone = {
                opened.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS))
                fixture.microphone.line
            }
            fixture.start()
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            fixture.recorder.stop()
            assertEquals("saving", fixture.phases.last())
            fixture.microphone.remaining.addAndGet(8_000)
            releaseOpen.countDown()
            fixture.awaitFinished()
            assertEquals(listOf("preparing", "saving", "idle"), fixture.phases.toList())
            assertNull(fixture.saved)
            assertNull(fixture.error)
        }
    }

    @Test fun stoppedSampleDeliveryEndsCaptureAndPreservesReceivedAudio() {
        Fixture(Microphone(9_123), Engine()).use { fixture ->
            fixture.recorder.inputStallTimeoutMs = 100
            fixture.start()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertEquals(9_123, fixture.engine.consumed.get())
            assertTrue(fixture.error.orEmpty().contains("delivering audio samples"))
        }
    }

    @Test fun microphoneWithoutFirstSamplesNeverReportsRecording() {
        Fixture(Microphone(0), Engine()).use { fixture ->
            fixture.recorder.inputStallTimeoutMs = 100
            fixture.start()
            fixture.awaitFinished()
            assertFalse(fixture.phases.contains("recording"))
            assertNull(fixture.saved)
            assertTrue(fixture.error.orEmpty().contains("delivering audio samples"))
        }
    }

    @Test fun silentPcmKeepsCaptureAliveWhileSamplesContinueArriving() {
        Fixture(Microphone(8_000, sample = 0), Engine(expectedSample = 0f)).use { fixture ->
            fixture.recorder.inputStallTimeoutMs = 400
            fixture.start()
            assertTrue(fixture.microphone.emptied.await(5, TimeUnit.SECONDS))
            repeat(6) {
                fixture.microphone.remaining.addAndGet(1600)
                Thread.sleep(100)
                assertEquals(1L, fixture.finished.count)
            }
            fixture.recorder.stop()
            fixture.awaitFinished()
            fixture.assertAudio()
            assertNull(fixture.error)
        }
    }
}
