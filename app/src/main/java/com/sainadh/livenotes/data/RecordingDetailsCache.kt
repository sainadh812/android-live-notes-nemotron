package com.sainadh.livenotes.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps only the most recently opened immutable recording, rather than every library's word cues. */
internal class RecordingDetailsCache(
    private val loadWordCues: suspend (SavedRecording) -> List<WordCue>
) {
    private val mutex = Mutex()
    private var source: SavedRecording? = null
    private var details: SavedRecording? = null

    suspend fun load(recording: SavedRecording): SavedRecording {
        if (recording.audioFileName == null || recording.audioStatus != RecordingAudioStatus.READY) return recording
        return mutex.withLock {
            if (source == recording) return@withLock checkNotNull(details)
            val loaded = recording.copy(nativeWordCues = loadWordCues(recording))
            source = recording
            details = loaded
            loaded
        }
    }
}
