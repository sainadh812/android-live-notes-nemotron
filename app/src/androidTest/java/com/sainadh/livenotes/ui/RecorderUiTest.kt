package com.sainadh.livenotes.ui

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sainadh.livenotes.LiveNotesTheme
import com.sainadh.livenotes.audio.PlaybackState
import com.sainadh.livenotes.data.RecordingAudioStatus
import com.sainadh.livenotes.data.SavedRecording
import com.sainadh.livenotes.data.WordCue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecorderUiTest {
    @get:Rule val compose = createComposeRule()

    private fun recording() = SavedRecording(
        recordingId = "ui-fixture", dateKey = "2026-09-10",
        text = "Every conversation is worth remembering. Tap a word and return to that moment.",
        updatedAtEpochMs = 1_789_070_400_000L, hasUnconfirmedWords = false,
        title = "A conversation worth keeping", durationMs = 20_000,
        audioFileName = "ui-fixture.wav", audioStatus = RecordingAudioStatus.READY,
        nativeWordCues = listOf(
            WordCue("Every", 1_000, 1_800, 0, 0, 5, isEstimated = false),
            WordCue("conversation", 1_800, 2_800, 0, 6, 18, isEstimated = false)
        )
    )

    @Test fun nativeTimingIsLabeledAndTimestampStartsPlayback() {
        var seekPosition = -1L
        val fixture = recording()
        compose.setContent {
            LiveNotesTheme {
                RecordingDetailScreen(fixture, PlaybackState(recordingId = fixture.recordingId, positionMs = 1_900, durationMs = 20_000),
                    captureActive = false, onBack = {}, onPlay = {}, onPlayFrom = { seekPosition = it },
                    onSeek = {}, onSpeed = {}, onCopy = {}, onShare = {}, onShareAudio = {}, onExport = {})
            }
        }
        compose.onNodeWithText("Tap a word to listen from there. Highlights follow word times from the speech model.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Seek to 00:01").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1_000L, seekPosition) }
        compose.onNodeWithContentDescription("Play recording").assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        saveTestScreenshot(image, "playback.png")
    }

    @Test fun oldTranscriptKeepsCopyAndShareWithoutPlayer() {
        var copies = 0
        var shares = 0
        val fixture = recording().copy(audioFileName = null, audioStatus = RecordingAudioStatus.UNAVAILABLE, nativeWordCues = emptyList())
        compose.setContent {
            LiveNotesTheme {
                RecordingDetailScreen(fixture, PlaybackState(), captureActive = false, onBack = {}, onPlay = {}, onPlayFrom = {},
                    onSeek = {}, onSpeed = {}, onCopy = { copies++ }, onShare = { shares++ }, onShareAudio = {}, onExport = {})
            }
        }
        compose.onNodeWithText("Copy").performScrollTo().performClick()
        compose.onNodeWithText("Share text").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, copies); assertEquals(1, shares) }
        compose.onNodeWithContentDescription("Play recording").assertDoesNotExist()
        compose.onNodeWithText("Share audio").assertDoesNotExist()
    }

    @Test fun captureDisablesSavedAudioPlayback() {
        compose.setContent {
            LiveNotesTheme { RecordingLibraryCard(recording(), isPlaying = false, playbackEnabled = false, onOpen = {}, onPlay = {}) }
        }
        compose.onNodeWithText("Play").assertIsNotEnabled()
        compose.onNodeWithText("Open recording").assertIsDisplayed()
    }

    @Test fun activeRecordingDoesNotClaimAudioUnavailable() {
        compose.setContent {
            LiveNotesTheme { RecordingLibraryCard(recording().copy(audioStatus = RecordingAudioStatus.RECORDING, audioFileName = null),
                isPlaying = false, playbackEnabled = false, onOpen = {}, onPlay = {}) }
        }
        compose.onNodeWithText("● Recording in progress").assertIsDisplayed()
        compose.onNodeWithText("Transcript only · Audio unavailable").assertDoesNotExist()
        compose.onNodeWithText("Play").assertDoesNotExist()
    }

    @Test fun blankTranscriptCannotBeCopiedSharedOrExported() {
        compose.setContent { LiveNotesTheme { TextActions("", {}, {}, {}) } }
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        compose.onNodeWithText("Share text").assertIsNotEnabled()
        compose.onNodeWithText("Save .txt").assertIsNotEnabled()
    }
}
