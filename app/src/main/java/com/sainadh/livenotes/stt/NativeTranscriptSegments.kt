package com.sainadh.livenotes.stt

/** Converts the engine's cumulative snapshot into stable words and one replaceable tail. */
internal class NativeTranscriptSegments {
    private var committed = ""
    private var emittedChars = 0
    private var tentative = ""
    private var segmentId = 0L

    fun update(snapshot: String): List<TranscriptUpdate> {
        val parts = snapshot.split('\u0001', limit = 2)
        val nextCommitted = parts[0]
        check(nextCommitted.startsWith(committed)) {
            "Speech engine changed already committed text; recording stopped to preserve the saved transcript"
        }
        // A committed prefix may end inside a word. Keep that last word editable
        // until a separator arrives, preserving all spaces and punctuation exactly.
        val stableEnd = maxOf(emittedChars, nextCommitted.indexOfLast { it.isWhitespace() } + 1)
        val delta = nextCommitted.substring(emittedChars, stableEnd)
        val nextTentative = nextCommitted.substring(stableEnd) + parts.getOrElse(1) { "" }
        val updates = mutableListOf<TranscriptUpdate>()
        if (delta.isNotEmpty()) {
            updates += TranscriptUpdate(segmentId++, delta, TranscriptStatus.FINAL,
                endsUtterance = false, appendToPrevious = true)
        }
        if (nextTentative != tentative || delta.isNotEmpty()) {
            updates += TranscriptUpdate(segmentId, nextTentative, TranscriptStatus.PARTIAL,
                appendToPrevious = true)
        }
        committed = nextCommitted
        emittedChars = stableEnd
        tentative = nextTentative
        return updates
    }

    fun finish(fullText: String): TranscriptUpdate {
        check(fullText.startsWith(committed)) {
            "Speech engine changed already committed text while finishing; the unfinished text was preserved"
        }
        return TranscriptUpdate(segmentId, fullText.substring(emittedChars), TranscriptStatus.FINAL,
            appendToPrevious = true)
    }

    fun interrupted(): TranscriptUpdate =
        TranscriptUpdate(segmentId, tentative, TranscriptStatus.INTERRUPTED, appendToPrevious = true)
}
