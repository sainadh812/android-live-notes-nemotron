package com.sainadh.livenotes.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sainadh.livenotes.data.SavedRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val recordingId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val error: String? = null,
    val isLoading: Boolean = false
)

/** Main-thread player. One owner survives rotations; stale prepare callbacks cannot restart it. */
class RecordingPlayer(private val context: Context, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(PlaybackState())
    val state = mutableState.asStateFlow()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) pause()
        }, Handler(Looper.getMainLooper())).build()
    private var player: MediaPlayer? = null
    private var prepared = false
    private var pendingSeek = 0L
    private var pendingPlay = false
    private var progressJob: Job? = null
    private var receiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    fun play(recording: SavedRecording, positionMs: Long = 0L) {
        if (mutableState.value.recordingId == recording.recordingId && player != null) {
            seek(positionMs)
            if (prepared) resume() else pendingPlay = true
            return
        }
        releasePlayer()
        mutableState.value = PlaybackState(recordingId = recording.recordingId,
            durationMs = recording.durationMs, speed = mutableState.value.speed, isLoading = true)
        try {
            val file = recordingAudioFile(context, recording.audioFileName)
            check(file.isFile && file.length() > 44) { "Audio file is missing. You can still copy or share the transcript." }
            pendingSeek = positionMs.coerceAtLeast(0)
            pendingPlay = true
            val current = MediaPlayer()
            player = current
            current.setAudioAttributes(attributes)
            current.setDataSource(file.absolutePath)
            current.setOnPreparedListener { ready ->
                if (player !== ready) return@setOnPreparedListener
                prepared = true
                mutableState.value = mutableState.value.copy(durationMs = ready.duration.toLong(), isLoading = false)
                seek(pendingSeek)
                if (pendingPlay) resume()
            }
            current.setOnCompletionListener { completed ->
                if (player === completed) {
                    pendingPlay = false
                    progressJob?.cancel()
                    audioManager.abandonAudioFocusRequest(focusRequest)
                    mutableState.value = mutableState.value.copy(isPlaying = false, positionMs = mutableState.value.durationMs)
                }
            }
            current.setOnErrorListener { failed, _, _ ->
                if (player === failed) fail("This recording could not be played. Try reopening it.")
                true
            }
            ContextCompat.registerReceiver(context, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            current.prepareAsync()
        } catch (error: Exception) {
            fail(error.message ?: "Could not open recording")
        }
    }

    fun toggle() {
        if (mutableState.value.isPlaying || pendingPlay) pause() else if (prepared) resume()
        else if (player != null) pendingPlay = true
    }

    private fun resume() {
        val current = player ?: return
        if (!prepared) { pendingPlay = true; return }
        try {
            if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                pendingPlay = false
                mutableState.value = mutableState.value.copy(error = "Audio is in use by another app. Try Play again.")
                return
            }
            if (mutableState.value.positionMs >= mutableState.value.durationMs) seek(0)
            current.playbackParams = current.playbackParams.setSpeed(mutableState.value.speed)
            current.start()
            pendingPlay = false
            mutableState.value = mutableState.value.copy(isPlaying = true, error = null)
            progressJob?.cancel()
            progressJob = scope.launch {
                while (player === current && mutableState.value.isPlaying) {
                    mutableState.value = mutableState.value.copy(positionMs = current.currentPosition.toLong())
                    delay(60)
                }
            }
        } catch (error: Exception) { fail(error.message ?: "Playback failed") }
    }

    fun pause() {
        pendingPlay = false
        progressJob?.cancel()
        if (prepared) runCatching { player?.pause() }
        audioManager.abandonAudioFocusRequest(focusRequest)
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    fun seek(positionMs: Long) {
        pendingSeek = positionMs.coerceIn(0, mutableState.value.durationMs.coerceAtLeast(0))
        if (prepared) {
            try { player?.seekTo(pendingSeek, MediaPlayer.SEEK_CLOSEST) }
            catch (error: Exception) { fail(error.message ?: "Could not seek recording"); return }
        }
        mutableState.value = mutableState.value.copy(positionMs = pendingSeek)
    }

    fun setSpeed(speed: Float) {
        if (speed !in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) return
        try {
            if (prepared && mutableState.value.isPlaying) player?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
            mutableState.value = mutableState.value.copy(speed = speed)
        } catch (error: Exception) { mutableState.value = mutableState.value.copy(error = "This playback speed is unavailable.") }
    }

    fun close() = releasePlayer()

    private fun fail(message: String) {
        releasePlayer()
        mutableState.value = mutableState.value.copy(isPlaying = false, isLoading = false, error = message)
    }

    private fun releasePlayer() {
        pause()
        prepared = false
        val previous = player
        player = null
        runCatching { previous?.release() }
        if (receiverRegistered) runCatching { context.unregisterReceiver(noisyReceiver) }
        receiverRegistered = false
    }
}

internal fun recordingAudioFile(context: Context, fileName: String?): File {
    require(!fileName.isNullOrBlank() && fileName.endsWith(".wav") &&
        fileName != "." && fileName != ".." && !fileName.contains('/') && !fileName.contains('\\')) {
        "No saved audio is available for this transcript."
    }
    val directory = File(context.filesDir, "recordings").canonicalFile
    val file = File(directory, fileName).canonicalFile
    require(file.parentFile == directory) { "Invalid recording file" }
    return file
}
