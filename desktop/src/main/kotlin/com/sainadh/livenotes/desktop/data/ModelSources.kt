package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.stt.SpeechModel

/** Desktop mirrors retain the exact model bytes and checksums used by Android. */
object ModelSources {
    const val RELEASE_PAGE = "https://github.com/sainadh812/android-live-notes-nemotron/releases/tag/models-v1"
    private const val DOWNLOAD_BASE = "https://github.com/sainadh812/android-live-notes-nemotron/releases/download/models-v1"

    fun downloadUrl(model: SpeechModel): String = "$DOWNLOAD_BASE/${model.fileName}"

    internal fun downloadSpec(model: SpeechModel) = ModelDownloadSpec(
        model.fileName, downloadUrl(model), model.expectedBytes, model.sha256)
}
