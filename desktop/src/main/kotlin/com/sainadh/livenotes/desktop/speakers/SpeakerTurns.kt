package com.sainadh.livenotes.desktop.speakers

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class SpeakerSpan(val speakerId: String, val startMs: Long, val endMs: Long)

@Serializable
data class DiarizationResult(
    val schemaVersion: Int = 1,
    val durationMs: Long,
    val spans: List<SpeakerSpan>,
    val speakerCount: Int,
)

data class SpeakerWord(val text: String, val startMs: Long?, val endMs: Long?)

/** Word indices refer to the original list, so even untimed or ambiguous text survives. */
data class SpeakerTurnAssignment(
    val speakerId: String?,
    val overlapping: Boolean,
    val startWordIndex: Int,
    val endWordIndexExclusive: Int,
    val startMs: Long?,
    val endMs: Long?,
)

/**
 * Assign strong temporal matches and leave uncertain words unassigned. Speaker transitions
 * inside a word do not imply overlapping voices: only simultaneously active spans do.
 * Diarization is an estimate; these assignments are editable by the user.
 */
fun assignSpeakerTurns(words: List<SpeakerWord>, spans: List<SpeakerSpan>): List<SpeakerTurnAssignment> {
    val sorted = spans.filter { it.startMs >= 0 && it.endMs > it.startMs }.sortedBy { it.startMs }
    val turns = mutableListOf<SpeakerTurnAssignment>()
    var firstCandidate = 0
    var previousStart = -1L
    words.forEachIndexed { index, word ->
        val start = word.startMs
        val end = word.endMs
        var speaker: String? = null
        var overlapping = false
        if (start != null && end != null && start >= 0 && end > start) {
            // Ordinarily word cues are ordered. Reset the cursor for older revised cues.
            if (start < previousStart) firstCandidate = 0
            previousStart = start
            while (firstCandidate < sorted.size && sorted[firstCandidate].endMs <= start) firstCandidate++
            val candidates = mutableListOf<SpeakerSpan>()
            var cursor = firstCandidate
            while (cursor < sorted.size && sorted[cursor].startMs < end) {
                if (sorted[cursor].endMs > start) candidates += sorted[cursor]
                cursor++
            }
            val coverage = mutableMapOf<String, Long>()
            // Union intervals for each speaker to avoid duplicate spans inflating coverage.
            candidates.groupBy { it.speakerId }.forEach { (id, matches) ->
                var covered = 0L
                var coveredUntil: Long = start
                matches.forEach { span ->
                    val from = max(max(start, span.startMs), coveredUntil)
                    val until = min(end, span.endMs)
                    covered += max(0L, until - from)
                    coveredUntil = max(coveredUntil, until)
                }
                coverage[id] = covered
            }
            overlapping = candidates.indices.any { a ->
                (a + 1 until candidates.size).any { b ->
                    candidates[a].speakerId != candidates[b].speakerId &&
                        max(start, max(candidates[a].startMs, candidates[b].startMs)) <
                        min(end, min(candidates[a].endMs, candidates[b].endMs))
                }
            }
            val ranked = coverage.entries.sortedByDescending { it.value }
            val duration = end - start
            if (!overlapping && ranked.isNotEmpty() && ranked[0].value >= duration * 0.5 &&
                (ranked.size == 1 || ranked[1].value < duration * 0.25)) {
                speaker = ranked[0].key
            }
        }
        val previous = turns.lastOrNull()
        if (previous != null && previous.speakerId == speaker && previous.overlapping == overlapping &&
            (start == null || previous.endMs == null || start - previous.endMs <= 2_000)) {
            turns[turns.lastIndex] = previous.copy(endWordIndexExclusive = index + 1,
                endMs = end ?: previous.endMs)
        } else {
            turns += SpeakerTurnAssignment(speaker, overlapping, index, index + 1, start, end)
        }
    }
    return turns
}
