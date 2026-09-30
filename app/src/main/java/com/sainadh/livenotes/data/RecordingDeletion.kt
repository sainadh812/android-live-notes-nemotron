package com.sainadh.livenotes.data

/** Files are removed by the coordinator only after this database transaction has committed. */
data class RecordingDeletionResult(
    val recordingId: String,
    val audioFileName: String?,
    val deleted: Boolean,
    val segmentsDeleted: Int = 0,
    val legacyChunksDeleted: Int = 0
)
