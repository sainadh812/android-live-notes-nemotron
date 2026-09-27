package com.sainadh.livenotes.ui

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sainadh.livenotes.LiveNotesApplication
import com.sainadh.livenotes.MainActivity
import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.data.DailyNote
import com.sainadh.livenotes.service.CapturePhase
import com.sainadh.livenotes.service.ServiceStateTracker
import com.sainadh.livenotes.stt.SpeechModel
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlin.math.sin

/**
 * Opt-in promotional capture of the real activity with synthetic sample data.
 * No speech recognition, model download, AI request, purchase, or real microphone is used.
 * The saved WAV is intentionally silent. This demonstrates UI, not model accuracy or latency.
 * Run only with instrumentation argument demoCapture=true on a disposable emulator.
 */
@RunWith(AndroidJUnit4::class)
class DemoCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output by lazy { File(context.getExternalFilesDir(null), "demo-capture").apply { mkdirs() } }
    private val timeline = mutableListOf<String>()
    private var originMs = 0L

    private fun hold(ms: Long) { Thread.sleep(ms) }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val elapsed = SystemClock.elapsedRealtime() - originMs
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { stream ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        timeline += "$name\t$elapsed"
        File(output, "timeline.tsv").writeText("frame\telapsed_ms\n" + timeline.joinToString("\n") + "\n")
    }

    @Test fun captureSampleMeetingJourney() {
        assumeTrue("Media capture is opt-in", InstrumentationRegistry.getArguments().getString("demoCapture") == "true")
        val container = (compose.activity.application as LiveNotesApplication).appContainer
        val repository = container.repository
        val id = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val date = LocalDate.now().toString()
        val sentences = listOf(
            "Let's ship the onboarding update on Friday.",
            "Maya will polish the welcome screen.",
            "Arun will test the sign-in flow.",
            "We'll review everything tomorrow at ten."
        )
        val summary = "Friday's launch is focused on a smoother first-time experience. The welcome screen and sign-in flow are the final priorities."
        val actions = listOf(
            "Maya: polish the welcome screen.",
            "Arun: test the sign-in flow.",
            "Review the update tomorrow at 10 AM."
        )
        val wav = File(context.filesDir, "recordings/$id.wav")
        runBlocking {
            container.recordingRecovery.await()
            repository.beginRecording(id, timestamp)
            repository.updateRecordingTitle(id, "Friday launch planning")
        }
        // A valid silent audio fixture drives the production MediaPlayer and seek controls.
        WavFileWriter(wav, 16_000).let { writer ->
            val second = ShortArray(16_000)
            repeat(28) { writer.write(second, 0, second.size) }
            check(writer.finish() == wav)
        }
        compose.runOnIdle {
            // Select only the visible settings state; no model is downloaded or invoked.
            container.speechSettings.selectModel(SpeechModel.MOONSHINE_TINY)
            ServiceStateTracker.resetTranscript()
            ServiceStateTracker.capturePhase.value = CapturePhase.IDLE
            ServiceStateTracker.lastTranscriptionError.value = null
            ServiceStateTracker.audioNotice.value = null
        }
        compose.waitForIdle()
        originMs = SystemClock.elapsedRealtime()
        File(output, "capture-origin-elapsed-realtime-ms.txt").writeText(originMs.toString())
        File(output, "README.txt").writeText(
            "Actual Live Meeting Notes MainActivity rendered on an Android emulator.\n" +
                "All names, transcript, waveform, timings, and summary are synthetic demonstration fixtures.\n" +
                "The WAV is silent; no microphone, speech model, AI service, or purchase was invoked.\n" +
                "Use a visible 'Demo with sample content' disclosure in edited promotional media.\n" +
                "Playback and navigation use the real app implementation. No app production source is modified.\n" +
                "timeline.tsv timestamps are milliseconds from capture-origin-elapsed-realtime-ms.txt.\n"
        )
        capture("recorder")
        hold(2_000)
        compose.runOnIdle {
            ServiceStateTracker.capturePhase.value = CapturePhase.RECORDING
            ServiceStateTracker.listening.value = true
            ServiceStateTracker.activeEngine.value = "Moonshine Tiny"
            ServiceStateTracker.audioRoute.value = "Phone microphone"
            ServiceStateTracker.recordingId.value = id
        }
        sentences.forEachIndexed { segment, sentence ->
            val words = sentence.split(' ')
            for (count in words.indices) {
                val final = count == words.lastIndex
                val update = TranscriptUpdate(
                    segment.toLong(), words.take(count + 1).joinToString(" "),
                    if (final) TranscriptStatus.FINAL else TranscriptStatus.PARTIAL,
                    startMs = segment * 7_000L, endMs = (segment + 1) * 7_000L
                )
                compose.runOnIdle {
                    val preview = requireNotNull(ServiceStateTracker.transcriptDocument.update(update))
                    ServiceStateTracker.latestTranscript.value = preview.text
                    ServiceStateTracker.liveSegments.value = preview.segments
                    ServiceStateTracker.durationMs.value = segment * 7_000L + (count + 1) * 650L
                    ServiceStateTracker.audioLevel.value = (0.3 + 0.6 * kotlin.math.abs(sin(count * 1.7 + segment))).toFloat()
                }
                hold(150)
            }
            runBlocking {
                repository.saveTranscript(id, TranscriptUpdate(segment.toLong(), sentence, TranscriptStatus.FINAL,
                    startMs = segment * 7_000L, endMs = (segment + 1) * 7_000L), timestamp + segment * 7_000L)
            }
            scrollTo(sentence)
            capture("live-${segment + 1}")
            hold(650)
        }
        File(output, "timeline.json").writeText("""{
  "recorder": ["recorder.png"],
  "live": ["live-1.png", "live-2.png", "live-3.png", "live-4.png"],
  "playback": ["playback.png", "playback-1.png", "playback-2.png", "playback-3.png", "playback-4.png", "seek.png"],
  "summary": ["summary.png"],
  "export": ["export.png"]
}
""")
        capture("live")
        hold(1_000)
        runBlocking {
            repository.finishRecording(id, wav.name, 28_000)
            repository.upsertNote(DailyNote(date, summary, "Sample meeting", actions, timestamp))
        }
        compose.runOnIdle {
            ServiceStateTracker.capturePhase.value = CapturePhase.IDLE
            ServiceStateTracker.listening.value = false
            ServiceStateTracker.audioLevel.value = 0f
        }
        compose.onNodeWithText("Notes").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithText("Friday launch planning").fetchSemanticsNode() }.isSuccess
        }
        capture("library")
        hold(1_500)
        scrollTo("Open recording")
        compose.onNodeWithText("Open recording").performClick()
        compose.onNodeWithContentDescription("Play recording").performClick()
        hold(1_100)
        capture("playback")
        repeat(4) { index ->
            hold(650)
            capture("playback-${index + 1}")
        }
        // Estimated paragraph times come from the seeded segment boundaries, as in the real app.
        compose.onNodeWithContentDescription("Forward 10 seconds").performClick()
        hold(900)
        capture("seek")
        compose.onNodeWithContentDescription("Pause recording").performClick()
        scrollTo("Copy")
        capture("export")
        hold(1_500)
        compose.onNodeWithText("‹  Back").performClick()
        scrollTo("Read note")
        compose.onNodeWithText("Read note").performClick()
        scrollTo(actions.last())
        capture("summary")
        hold(3_500)
        compose.onNodeWithText("Record").performClick()
        scrollTo("The key takeaways")
        scrollTo("NEXT STEPS")
        capture("takeaways")
        hold(2_000)
    }
}
