package com.sainadh.livenotes.stt

import java.util.TreeMap

/** Full text is retained, but frequent display snapshots only visit a bounded tail. */
class LiveTranscriptBuffer(
    private val maxPreviewChars: Int = 4_000,
    private val maxPreviewSegments: Int = 120
) {
    data class Preview(val text: String, val segments: List<TranscriptUpdate>, val hasEarlierText: Boolean)

    private val segments = TreeMap<Long, TranscriptUpdate>()

    init {
        require(maxPreviewChars > 0 && maxPreviewSegments > 0)
    }

    @Synchronized
    fun update(update: TranscriptUpdate): Preview? {
        val previous = segments[update.segmentId]
        if (previous == update || (previous != null && previous.status != TranscriptStatus.PARTIAL)) return null
        segments[update.segmentId] = update
        val recent = ArrayList<TranscriptUpdate>()
        var remaining = maxPreviewChars
        var truncated = false
        for (segment in segments.descendingMap().values) {
            if (recent.size == maxPreviewSegments || remaining <= 1) {
                truncated = true
                break
            }
            // Reserve a separator per entry so both text and layout input remain bounded.
            var take = minOf(segment.text.length, remaining - 1)
            val start = segment.text.length - take
            if (start > 0 && start < segment.text.length && segment.text[start].isLowSurrogate() &&
                segment.text[start - 1].isHighSurrogate()) take--
            recent += if (take == segment.text.length) segment else segment.copy(
                text = segment.text.takeLast(take), startMs = null, endMs = null
            )
            remaining -= take + 1
            if (take < segment.text.length) {
                truncated = true
                break
            }
        }
        recent.reverse()
        return Preview(join(recent), recent, truncated)
    }

    /** Call on demand, off the UI thread; no full transcript is rebuilt during capture updates. */
    @Synchronized
    fun fullText(): String = join(segments.values)

    private fun join(values: Collection<TranscriptUpdate>): String = buildString {
        for (segment in values) {
            if (segment.text.isNotEmpty()) {
                if (isNotEmpty() && !segment.appendToPrevious) append('\n')
                append(segment.text)
            }
        }
    }
}
