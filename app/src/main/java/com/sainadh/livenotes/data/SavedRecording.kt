package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus

/** Text-only archive: audio is not saved by this recorder. */
data class SavedRecording(
    val recordingId: String,
    val dateKey: String,
    val text: String,
    val updatedAtEpochMs: Long,
    val hasUnconfirmedWords: Boolean
)

internal fun savedRecordings(
    segments: List<TranscriptSegmentEntity>,
    legacy: List<TranscriptChunkEntity>
): List<SavedRecording> {
    val recordings = segments.groupBy { it.recordingId }.mapNotNull { (id, rows) ->
        val ordered = rows.sortedBy { it.segmentId }
        val text = buildString {
            ordered.forEach { segment ->
                if (segment.text.isNotEmpty()) {
                    if (isNotEmpty() && !segment.appendToPrevious) append('\n')
                    append(segment.text)
                }
            }
        }
        if (text.isBlank()) null else SavedRecording(
            id, ordered.first().dateKey, text, rows.maxOf { it.updatedAtEpochMs },
            rows.any { it.text.isNotBlank() && it.status != TranscriptStatus.FINAL.name }
        )
    }
    // Legacy rows have no trustworthy recording IDs or partial-revision identity.
    // Display every row in date groups rather than inventing session boundaries or dropping text.
    val oldRecordings = legacy.groupBy { it.dateKey }.map { (date, rows) ->
        val ordered = rows.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
        SavedRecording("legacy:$date", date, ordered.joinToString("\n") { it.text },
            rows.maxOf { it.createdAtEpochMs }, rows.any { !it.isFinal })
    }
    return (recordings + oldRecordings).sortedByDescending { it.updatedAtEpochMs }
}
