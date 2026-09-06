package com.sainadh.livenotes.stt

enum class TranscriptStatus { PARTIAL, FINAL, INTERRUPTED }

/** Identity is local to one recording. Revisions replace the same segment. */
data class TranscriptUpdate(
    val segmentId: Long,
    val text: String,
    val status: TranscriptStatus,
    // Committed native pieces are stable, but do not each warrant an AI request.
    val endsUtterance: Boolean = status != TranscriptStatus.PARTIAL,
    // Native pieces preserve the model's exact spacing; OS utterances are separate.
    val appendToPrevious: Boolean = false
)
