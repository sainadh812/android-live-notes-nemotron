package com.sainadh.livenotes.stt
// The real listener contract; Android SpeechRecognizer itself is outside this harness.
class SpeechTranscriber {
    interface Listener {
        fun onStateChanged(state: String)
        fun onError(reason: String)
        fun onAudioProgress(durationMs: Long, level: Float) {}
        fun onAudioSaved(fileName: String, durationMs: Long) {}
        fun onAudioUnavailable(reason: String) {}
        fun onTranscript(text: String, isFinal: Boolean)
        fun onTranscriptUpdate(update: TranscriptUpdate) {
            onTranscript(update.text, update.status == TranscriptStatus.FINAL)
        }
    }
}
