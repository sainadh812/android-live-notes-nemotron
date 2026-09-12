package com.sainadh.livenotes.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.desktop.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Synthetic fixtures exist only in tests. Run with the desktop UI test task on Windows. */
class DesktopUiTest {
    @get:Rule val compose = createComposeRule()

    private fun show(state: AppState, actions: TestActions) {
        compose.setContent { Box(Modifier.requiredSize(1_200.dp, 800.dp)) { DesktopApp(state.copy(initializing = false), actions) } }
    }

    @Test fun longCaptureKeepsFullTranscriptActionsAvailable() {
        val actions = TestActions()
        show(AppState(capture = CaptureView(CapturePhase.RECORDING, "live", 3_600_000, .42f,
            "We agreed to share the revised design tomorrow. The next step is to review the timeline together.", hasEarlierText = true)), actions)
        compose.onNodeWithText("1:00:00").assertExists()
        compose.onNodeWithText("Stop & save").assertIsEnabled()
        screenshot("recording")
        compose.onNodeWithText("Copy text").performScrollTo().performClick()
        assertEquals(1, actions.copyCalls)
        assertNull(actions.copiedRecording)
        compose.onNodeWithText("Full transcript").performScrollTo().performClick()
        assertEquals(1, actions.fullCalls)
    }

    @Test fun savedMeetingSupportsSpeakerCorrectionsKnownCountAndMerge() {
        val actions = TestActions()
        val document = fixture()
        show(AppState(selected = document, recordings = listOf(document.entry), speakerJob = SpeakerJobView(modelsInstalled = true),
            playback = PlaybackView(document.entry.id, 1_000, document.entry.durationMs)), actions)
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithText("Design review · September").assertExists()
        screenshot("playback")
        compose.onNodeWithText("Speakers").performClick()
        compose.onNodeWithText("Analyze speakers again").performClick()
        compose.onNodeWithText("Automatic detection").performClick()
        compose.onNodeWithText("3 speakers").performClick()
        compose.onNodeWithText("Start analysis").performClick()
        assertEquals(3, actions.requestedCount)
        compose.onNodeWithTag("speaker-list").performScrollToNode(hasText("Merge"))
        compose.onAllNodesWithText("Merge")[0].performScrollTo().performClick()
        compose.onNodeWithText("Merge speakers").performClick()
        assertEquals("speaker1" to "speaker2", actions.merged)
    }

    @Test fun apiKeyInputIsClearedAfterSaving() {
        val actions = TestActions()
        show(AppState(), actions)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("api-key-field"))
        compose.onNodeWithTag("api-key-field").performTextInput("test-only-key")
        compose.onNodeWithText("Save AI settings").performScrollTo().performClick()
        assertEquals("test-only-key", actions.key)
        assertEquals("", compose.onNodeWithTag("api-key-field").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, image[x, y].toArgb())
        val destination = File("build/reports/desktop-ui/$name.png")
        destination.parentFile.mkdirs()
        ImageIO.write(output, "png", destination)
    }

    private fun fixture(): RecordingDocument {
        val text = StringBuilder()
        val sentence = "We have a clear direction for the design. Let us confirm the decisions and owners before the next review."
        val tokens = sentence.split(' ')
        val words = (0 until 7_200).map { index ->
            val word = tokens[index % tokens.size]
            val start = text.length
            text.append(word).append(' ')
            WordCue(word, index * 500L, (index + 1) * 500L, index.toLong(), start, start + word.length, false)
        }
        val turns = words.chunked(60).mapIndexed { index, group -> TranscriptTurn(index, "speaker${index % 2 + 1}", group.first().startMs, group.last().endMs, group.first().startChar, group.last().endChar) }
        return RecordingDocument(RecordingEntry("fixture", "Design review · September", 1_789_168_000_000, 3_600_000, true, sentence, 2),
            text.toString(), words, turns, listOf(SpeakerName("speaker1", "Alex"), SpeakerName("speaker2", "Morgan")),
            "The team agreed on a clear design direction and will confirm owners before the next review.", listOf("Share the updated design.", "Confirm owners and review dates."), "Speakers ready")
    }

    private class TestActions : DesktopActions {
        var copyCalls = 0
        var copiedRecording: String? = "unset"
        var fullCalls = 0
        var requestedCount: Int? = null
        var merged: Pair<String, String>? = null
        var key: String? = null
        override fun startRecording() = Unit
        override fun stopRecording() = Unit
        override fun refreshMicrophones() = Unit
        override fun selectRecording(id: String) = Unit
        override fun closeRecording() = Unit
        override fun renameRecording(id: String, title: String) = Unit
        override fun deleteRecording(id: String) = Unit
        override fun playPause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun copyTranscript(recordingId: String?) { copyCalls++; copiedRecording = recordingId }
        override fun exportTranscript(recordingId: String?) = Unit
        override fun exportAudio(recordingId: String) = Unit
        override fun copySummary(recordingId: String) = Unit
        override fun exportSummary(recordingId: String) = Unit
        override fun openFullTranscript() { fullCalls++ }
        override fun closeFullTranscript() = Unit
        override fun updateSettings(settings: AppSettings) = Unit
        override fun saveApiKey(key: String) { this.key = key }
        override fun deleteApiKey() = Unit
        override fun testConnection() = Unit
        override fun summarize(recordingId: String) = Unit
        override fun downloadModel(modelId: String) = Unit
        override fun cancelModelDownload(modelId: String) = Unit
        override fun removeModel(modelId: String) = Unit
        override fun installSpeakerModels() = Unit
        override fun analyzeSpeakers(recordingId: String, speakerCount: Int?) { requestedCount = speakerCount }
        override fun cancelSpeakerJob() = Unit
        override fun renameSpeaker(recordingId: String, speakerId: String, name: String) = Unit
        override fun mergeSpeakers(recordingId: String, sourceSpeakerId: String, targetSpeakerId: String) { merged = sourceSpeakerId to targetSpeakerId }
        override fun assignTurnSpeaker(recordingId: String, turnId: Int, speakerId: String?) = Unit
        override fun dismissError() = Unit
        override fun dismissNotice() = Unit
    }
}
