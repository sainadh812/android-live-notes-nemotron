package com.sainadh.livenotes.stt

/**
 * Bounds transcript revisions on the audio consumed by the model. These are
 * estimated segment boundaries, not decoder word timestamps: a streaming model
 * may report words after their sound. The UI must label word alignment estimated.
 */
internal class TranscriptSampleClock(private val sampleRateHz: Int) {
    private var consumedSamples = 0L
    private var currentSegment = -1L
    private var currentStartMs = 0L
    private var committedEndMs = 0L

    fun consume(samples: Int) {
        require(samples >= 0)
        consumedSamples += samples
    }

    fun stamp(update: TranscriptUpdate): TranscriptUpdate {
        if (currentSegment != update.segmentId) {
            currentSegment = update.segmentId
            currentStartMs = committedEndMs
        }
        val endMs = maxOf(currentStartMs, consumedSamples * 1_000L / sampleRateHz)
        if (update.status != TranscriptStatus.PARTIAL) committedEndMs = endMs
        return update.copy(startMs = currentStartMs, endMs = endMs)
    }
}
