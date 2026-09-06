package com.sainadh.livenotes.ai

import com.sainadh.livenotes.data.TranscriptSegmentEntity
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable identities let the summarizer distinguish revisions from new speech. */
internal fun formatTranscriptSegments(segments: List<TranscriptSegmentEntity>): String {
    if (segments.isEmpty()) return ""
    return buildJsonArray {
        // Wall-clock adjustments must not reorder pieces of one recording.
        val ordered = segments.groupBy { it.recordingId }.values
            .sortedBy { group -> group.minOf { it.createdAtEpochMs } }
            .flatMap { group -> group.sortedBy { it.segmentId } }
        ordered.forEach { segment ->
            add(buildJsonObject {
                put("recording", segment.recordingId)
                put("segment", segment.segmentId)
                put("revision", segment.revision)
                put("status", segment.status)
                put("appendToPrevious", segment.appendToPrevious)
                put("text", segment.text)
            })
        }
    }.toString()
}
