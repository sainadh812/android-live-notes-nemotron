package com.sainadh.livenotes.stt

/** Converts JNI committed deltas into stable words and one replaceable tail. */
internal class NativeTranscriptSegments {
    // Keep history only for a single integrity check at finalize. Live updates
    // append to this buffer and inspect just the unfinished committed word.
    private val committed = StringBuilder()
    private var pendingCommitted = ""
    private var emittedChars = 0
    private var tentative = ""
    private var segmentId = 0L

    fun update(deltaSnapshot: String): List<TranscriptUpdate> {
        val separator = deltaSnapshot.indexOf('\u0001')
        check(separator >= 0) { "Speech engine returned an invalid transcript update" }
        val newCommitted = deltaSnapshot.substring(0, separator)
        committed.append(newCommitted)
        val nextCommitted = pendingCommitted + newCommitted
        // A committed prefix may end inside a word. Keep that last word editable
        // until a separator arrives, preserving all spaces and punctuation exactly.
        val stableEnd = nextCommitted.indexOfLast { it.isWhitespace() } + 1
        val delta = nextCommitted.substring(0, stableEnd)
        pendingCommitted = nextCommitted.substring(stableEnd)
        val nextTentative = pendingCommitted + deltaSnapshot.substring(separator + 1)
        val updates = mutableListOf<TranscriptUpdate>()
        if (delta.isNotEmpty()) {
            updates += TranscriptUpdate(segmentId++, delta, TranscriptStatus.FINAL,
                endsUtterance = false, appendToPrevious = true)
        }
        if (nextTentative != tentative || delta.isNotEmpty()) {
            updates += TranscriptUpdate(segmentId, nextTentative, TranscriptStatus.PARTIAL,
                appendToPrevious = true)
        }
        emittedChars += stableEnd
        tentative = nextTentative
        return updates
    }

    fun finish(fullText: String): TranscriptUpdate {
        check(fullText.startsWith(committed.toString())) {
            "Speech engine changed already committed text while finishing; the unfinished text was preserved"
        }
        return TranscriptUpdate(segmentId, fullText.substring(emittedChars), TranscriptStatus.FINAL,
            appendToPrevious = true)
    }

    fun interrupted(): TranscriptUpdate =
        TranscriptUpdate(segmentId, tentative, TranscriptStatus.INTERRUPTED, appendToPrevious = true)
}
