package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus

/** Transcript bounds are recording-relative milliseconds; null means no timing is known. */
data class RecordingSegment(
    val segmentId: Long,
    val text: String,
    val status: TranscriptStatus,
    val appendToPrevious: Boolean = false,
    val startMs: Long? = null,
    val endMs: Long? = null
)

/** An estimated word interval. Character offsets address the complete transcript, end-exclusive. */
data class WordCue(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val segmentId: Long,
    val startChar: Int,
    val endChar: Int,
    val isEstimated: Boolean = true
)

/** Native snapshot text can differ from committed UI text; require complete exact coverage. */
internal fun alignedWordCues(text: String, words: List<com.sainadh.livenotes.stt.NativeWordTimingFile.Word>, durationMs: Long): List<WordCue> {
    if (words.isEmpty()) return emptyList()
    var cursor = 0
    val cues = mutableListOf<WordCue>()
    for ((index, word) in words.withIndex()) {
        val start = text.indexOf(word.text, cursor)
        if (start < cursor || text.substring(cursor, start).isNotBlank() || word.endMs > durationMs + 500) return emptyList()
        cursor = start + word.text.length
        // RNNT tokens can be point timestamps. Keep the decoder start and
        // highlight until the next decoder word (or recording end).
        val intervalEnd = if (word.endMs > word.startMs) word.endMs
            else words.getOrNull(index + 1)?.startMs ?: durationMs
        val endMs = minOf(maxOf(intervalEnd, word.startMs + 1), durationMs)
        if (endMs <= word.startMs) return emptyList()
        cues += WordCue(word.text, word.startMs, endMs, index.toLong(), start, cursor, isEstimated = false)
    }
    return if (text.substring(cursor).isBlank()) cues else emptyList()
}

private data class SegmentSpan(
    val segment: RecordingSegment,
    val startChar: Int,
    val endChar: Int,
    val characterWeights: IntArray
)

private data class TranscriptLayout(val text: String, val spans: List<SegmentSpan>)

private fun transcriptLayout(segments: List<RecordingSegment>): TranscriptLayout {
    val spans = mutableListOf<SegmentSpan>()
    val text = buildString {
        segments.sortedBy { it.segmentId }.forEach { segment ->
            if (segment.text.isNotEmpty()) {
                if (isNotEmpty() && !segment.appendToPrevious) append('\n')
                val start = length
                append(segment.text)
                val weights = IntArray(segment.text.length + 1)
                segment.text.forEachIndexed { index, character ->
                    weights[index + 1] = weights[index] + if (character.isWhitespace()) 0 else 1
                }
                spans += SegmentSpan(segment, start, length, weights)
            }
        }
    }
    return TranscriptLayout(text, spans)
}

fun transcriptText(segments: List<RecordingSegment>): String = transcriptLayout(segments).text

/**
 * Distributes each segment's duration by visible character count. Estimates retain silence between
 * segments and never invent bounds for historical/untimed text. A token split across native pieces
 * remains one word. Its complete text must have timing before a cue can be returned.
 */
fun recordingWordCues(segments: List<RecordingSegment>): List<WordCue> {
    val layout = transcriptLayout(segments)
    val words = Regex("[^\\s\\p{Z}]+").findAll(layout.text)
    val cues = mutableListOf<WordCue>()
    var firstSpan = 0
    words.forEach { word ->
        val startChar = word.range.first
        val endChar = word.range.last + 1
        while (firstSpan < layout.spans.size && layout.spans[firstSpan].endChar <= startChar) firstSpan++
        var index = firstSpan
        var wordStart: Long? = null
        var wordEnd: Long? = null
        var hasCompleteTiming = true
        while (index < layout.spans.size && layout.spans[index].startChar < endChar) {
            val span = layout.spans[index++]
            val start = span.segment.startMs
            val end = span.segment.endMs
            if (start == null || end == null || start < 0 || end <= start || span.characterWeights.last() == 0) {
                hasCompleteTiming = false
                break
            }
            val overlapStart = maxOf(startChar, span.startChar) - span.startChar
            val overlapEnd = minOf(endChar, span.endChar) - span.startChar
            val totalWeight = span.characterWeights.last().toDouble()
            val estimatedStart = start + ((end - start) * (span.characterWeights[overlapStart] / totalWeight)).toLong()
            val estimatedEnd = start + ((end - start) * (span.characterWeights[overlapEnd] / totalWeight)).toLong()
            wordStart = minOf(wordStart ?: estimatedStart, estimatedStart)
            wordEnd = maxOf(wordEnd ?: estimatedEnd, estimatedEnd)
        }
        if (hasCompleteTiming && wordStart != null && wordEnd != null && wordEnd > wordStart) {
            cues += WordCue(word.value, wordStart, wordEnd,
                layout.spans[firstSpan].segment.segmentId, startChar, endChar)
        }
    }
    return cues
}
