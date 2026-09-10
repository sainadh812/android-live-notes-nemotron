package com.sainadh.livenotes.data

import com.sainadh.livenotes.stt.TranscriptStatus

/** Session metadata, text, and recording-relative segment bounds. Older archives remain text-only. */
data class SavedRecording(
    val recordingId: String,
    val dateKey: String,
    val text: String,
    val updatedAtEpochMs: Long,
    val hasUnconfirmedWords: Boolean,
    val title: String = "Recording",
    val startedAtEpochMs: Long = updatedAtEpochMs,
    val durationMs: Long = 0L,
    val audioFileName: String? = null,
    val audioStatus: String = RecordingAudioStatus.UNAVAILABLE,
    val segments: List<RecordingSegment> = emptyList(),
    val nativeWordCues: List<WordCue> = emptyList()
) {
    val wordCues: List<WordCue> get() = nativeWordCues.ifEmpty { recordingWordCues(segments) }
}

internal fun savedRecordings(
    segments: List<TranscriptSegmentEntity>,
    legacy: List<TranscriptChunkEntity>,
    metadata: List<RecordingEntity> = emptyList()
): List<SavedRecording> {
    val rowsByRecording = segments.groupBy { it.recordingId }
    val metadataByRecording = metadata.associateBy { it.recordingId }
    val recordings = (rowsByRecording.keys + metadataByRecording.keys).mapNotNull { id ->
        val ordered = rowsByRecording[id].orEmpty().sortedBy { it.segmentId }
        val info = metadataByRecording[id]
        val timeline = ordered.map { row ->
            RecordingSegment(row.segmentId, row.text,
                runCatching { TranscriptStatus.valueOf(row.status) }.getOrDefault(TranscriptStatus.INTERRUPTED),
                row.appendToPrevious, row.startMs, row.endMs)
        }
        val text = transcriptText(timeline)
        if (text.isBlank() && info == null) null else SavedRecording(
            recordingId = id,
            dateKey = info?.dateKey ?: ordered.first().dateKey,
            text = text,
            updatedAtEpochMs = maxOf(info?.updatedAtEpochMs ?: 0L, ordered.maxOfOrNull { it.updatedAtEpochMs } ?: 0L),
            hasUnconfirmedWords = ordered.any { it.text.isNotBlank() && it.status != TranscriptStatus.FINAL.name },
            title = info?.title ?: "Recording",
            startedAtEpochMs = info?.startedAtEpochMs ?: ordered.minOf { it.createdAtEpochMs },
            durationMs = info?.durationMs ?: 0L,
            audioFileName = info?.audioFileName,
            audioStatus = info?.audioStatus ?: RecordingAudioStatus.UNAVAILABLE,
            segments = timeline
        )
    }
    // Legacy rows have no trustworthy recording IDs or partial-revision identity.
    // Display every row in date groups rather than inventing session boundaries or dropping text.
    val oldRecordings = legacy.groupBy { it.dateKey }.map { (date, rows) ->
        val ordered = rows.sortedWith(compareBy({ it.createdAtEpochMs }, { it.id }))
        SavedRecording("legacy:$date", date, ordered.joinToString("\n") { it.text },
            rows.maxOf { it.createdAtEpochMs }, rows.any { !it.isFinal },
            title = "Imported transcript", startedAtEpochMs = ordered.first().createdAtEpochMs)
    }
    return (recordings + oldRecordings).sortedByDescending { it.updatedAtEpochMs }
}
