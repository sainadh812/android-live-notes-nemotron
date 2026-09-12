package com.sainadh.livenotes.desktop.ui

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.sainadh.livenotes.data.WordCue
import com.sainadh.livenotes.desktop.*
import com.sainadh.livenotes.stt.SpeechModel
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
        // Use the test scene's actual viewport (1024×768 on Windows CI). Forcing a larger
        // requiredSize centers an oversized layout and crops the sidebar and player controls.
        compose.setContent { DesktopApp(state.copy(initializing = false), actions) }
    }

    @Test fun longCaptureKeepsFullTranscriptActionsAvailable() {
        val actions = TestActions()
        show(AppState(capture = CaptureView(CapturePhase.RECORDING, "live", 3_600_000, .42f,
            "We agreed to share the revised design tomorrow. The next step is to review the timeline together.", hasEarlierText = true,
            transcribedMs = 3_600_000)), actions)
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
        repeat(2) { compose.onNodeWithText("00:00").performClick() }
        assertEquals(listOf(0L, 0L), actions.playedFrom)
        assertEquals(0, actions.playPauseCalls)
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

    @Test fun aiSettingsCannotBeChangedDuringCapture() {
        val actions = TestActions()
        show(AppState(capture = CaptureView(phase = CapturePhase.RECORDING), apiKeySaved = true), actions)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("import-model-moonshine-tiny"))
        compose.onNodeWithTag("import-model-moonshine-tiny").assertIsNotEnabled()
        compose.onNodeWithTag("download-model-moonshine-tiny").assertIsNotEnabled()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("api-key-field"))
        compose.onNodeWithTag("api-key-field").assertIsNotEnabled()
        compose.onNodeWithText("Save AI settings").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Automatically create summaries").assertIsNotEnabled()
        assertNull(actions.key)
    }

    @Test fun githubAndManualImportActionsIdentifyTheSelectedModelCard() {
        val actions = TestActions()
        show(AppState(), actions)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasText("Open model downloads on GitHub"))
        compose.onNodeWithText("Open model downloads on GitHub").performClick()
        assertEquals(1, actions.openDownloadsCalls)
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("download-model-moonshine-tiny"))
        screenshot("settings-models")
        compose.onNodeWithTag("download-model-moonshine-tiny").performClick()
        assertEquals(SpeechModel.MOONSHINE_TINY.id, actions.downloadedModel)
        compose.onNodeWithTag("import-model-moonshine-tiny").performClick()
        assertEquals(SpeechModel.MOONSHINE_TINY.id, actions.importedModel)
    }

    @Test fun importingUsesTransferProgressAndCancellation() {
        val actions = TestActions()
        show(AppState(downloads = SpeechModel.entries.map { model -> DownloadView(model.id,
            downloading = model == SpeechModel.MOONSHINE_TINY, fraction = .4f, message = if (model == SpeechModel.MOONSHINE_TINY) "Importing model file…" else "") }), actions)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("cancel-model-moonshine-tiny"))
        compose.onNodeWithText("Importing model file…").assertExists()
        compose.onNodeWithTag("cancel-model-moonshine-tiny").performClick()
        assertEquals(SpeechModel.MOONSHINE_TINY.id, actions.canceledModel)
    }

    @Test fun audioSettingsOfferDeviceChecksAndWindowsPermissionGuidance() {
        val actions = TestActions()
        show(AppState(microphoneAccess = MicrophoneAccessView(true, "unknown", "Check desktop microphone access in Windows Settings."),
            outputDevices = listOf(Microphone("headset", "USB headset"))), actions)
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("test-microphone"))
        compose.onNodeWithTag("test-microphone").performScrollTo().performClick()
        compose.onNodeWithTag("test-speakers").performScrollTo().performClick()
        assertEquals(1, actions.microphoneTests)
        assertEquals(1, actions.speakerTests)
        compose.onNodeWithText("System default speakers").performScrollTo().performClick()
        compose.onNodeWithText("USB headset").performClick()
        assertEquals("headset", actions.settings?.outputDeviceId)
        compose.onNodeWithText("Windows microphone settings").performScrollTo().performClick()
        compose.onNodeWithText("Windows Sound settings").performScrollTo().performClick()
        assertEquals(1, actions.microphoneSettingsCalls)
        assertEquals(1, actions.soundSettingsCalls)
        screenshot("audio-settings")
    }

    @Test fun audioTestBlocksRecordingAndDeviceChangesUntilStopped() {
        val actions = TestActions()
        show(AppState(downloads = SpeechModel.entries.map { DownloadView(it.id, installed = true) },
            audioCheck = AudioCheckView(true, "microphone", .4f, "Speak into the selected microphone.")), actions)
        compose.onNodeWithText("Start recording").assertIsNotEnabled()
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasTestTag("test-microphone"))
        compose.onNodeWithTag("test-microphone").assertIsNotEnabled()
        compose.onNodeWithTag("test-speakers").assertIsNotEnabled()
        compose.onNodeWithText("Stop test").performScrollTo().performClick()
        assertEquals(1, actions.stopAudioCalls)
    }

    @Test fun slowTranscriptionShowsLagWithoutDisablingRecordingControls() {
        val actions = TestActions()
        show(AppState(capture = CaptureView(phase = CapturePhase.RECORDING, durationMs = 60_000,
            transcribedMs = 20_000, preview = "The meeting is still being recorded.")), actions)
        compose.onNodeWithTag("transcription-progress").assertTextEquals("Recording continues · transcript is 00:40 behind")
        compose.onNodeWithText("Stop & save").assertIsEnabled()
        screenshot("transcription-lag")
    }

    @Test fun stoppedRecordingShowsTranscriptCatchUpProgress() {
        val actions = TestActions()
        show(AppState(capture = CaptureView(phase = CapturePhase.SAVING, durationMs = 60_000,
            transcribedMs = 30_000)), actions)
        compose.onNodeWithTag("transcription-progress").assertTextEquals("Finishing transcript · 00:30 of 01:00 processed")
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("Exercise a supported desktop viewport", image.width >= 900 && image.height >= 640)
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
        val playedFrom = mutableListOf<Long>()
        var playPauseCalls = 0
        var openDownloadsCalls = 0
        var downloadedModel: String? = null
        var importedModel: String? = null
        var canceledModel: String? = null
        var microphoneTests = 0
        var speakerTests = 0
        var stopAudioCalls = 0
        var microphoneSettingsCalls = 0
        var soundSettingsCalls = 0
        var settings: AppSettings? = null
        override fun startRecording() = Unit
        override fun stopRecording() = Unit
        override fun refreshMicrophones() = Unit
        override fun testMicrophone() { microphoneTests++ }
        override fun testSpeakers() { speakerTests++ }
        override fun stopAudioTest() { stopAudioCalls++ }
        override fun openMicrophoneSettings() { microphoneSettingsCalls++ }
        override fun openSoundSettings() { soundSettingsCalls++ }
        override fun selectRecording(id: String) = Unit
        override fun closeRecording() = Unit
        override fun renameRecording(id: String, title: String) = Unit
        override fun deleteRecording(id: String) = Unit
        override fun playPause() { playPauseCalls++ }
        override fun playFrom(positionMs: Long) { playedFrom += positionMs }
        override fun seekTo(positionMs: Long) = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun copyTranscript(recordingId: String?) { copyCalls++; copiedRecording = recordingId }
        override fun exportTranscript(recordingId: String?) = Unit
        override fun exportAudio(recordingId: String) = Unit
        override fun copySummary(recordingId: String) = Unit
        override fun exportSummary(recordingId: String) = Unit
        override fun openFullTranscript() { fullCalls++ }
        override fun closeFullTranscript() = Unit
        override fun updateSettings(settings: AppSettings) { this.settings = settings }
        override fun saveApiKey(key: String) { this.key = key }
        override fun deleteApiKey() = Unit
        override fun testConnection() = Unit
        override fun summarize(recordingId: String) = Unit
        override fun downloadModel(modelId: String) { downloadedModel = modelId }
        override fun importModel(modelId: String) { importedModel = modelId }
        override fun openModelDownloads() { openDownloadsCalls++ }
        override fun cancelModelDownload(modelId: String) { canceledModel = modelId }
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
