package com.sainadh.livenotes.stt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a Nemotron 3.5 streaming ASR GGUF model file to app-private
 * storage (filesDir/models/), with progress reporting and resume-on-retry
 * support (HTTP Range requests against a partial .part file).
 *
 * ForegroundListeningService checks for the final (non-.part) file's
 * existence to decide whether to use NemotronTranscriber or fall back to
 * SpeechTranscriber - this class writes to a .part file and renames it to
 * the final name only on full success, so a failed/interrupted download
 * never looks like a complete model to that check.
 */
sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    object CheckingExisting : ModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState() {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
    data class Failed(val message: String) : ModelDownloadState()
    object Completed : ModelDownloadState()
}

class ModelDownloadManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private val modelsDir: File get() = File(context.filesDir, "models")

    fun modelFile(quant: NemotronQuant): File = File(modelsDir, quant.fileName)

    private fun partFile(quant: NemotronQuant): File = File(modelsDir, "${quant.fileName}.part")

    /** True if a fully-downloaded model file for this quant already exists. */
    fun isDownloaded(quant: NemotronQuant): Boolean = modelFile(quant).exists()

    /** Any quant already downloaded, preferring the caller's requested one. Null if none. */
    fun findAnyDownloaded(preferred: NemotronQuant = NemotronQuant.default): NemotronQuant? {
        if (isDownloaded(preferred)) return preferred
        return NemotronQuant.entries.firstOrNull { isDownloaded(it) }
    }

    /**
     * Downloads the given quant. Safe to call from a coroutine on
     * Dispatchers.IO. Resumes a previous partial download via HTTP Range
     * when a .part file already exists from an interrupted attempt.
     * Updates [state] throughout; caller observes it for UI progress.
     */
    fun download(quant: NemotronQuant) {
        _state.value = ModelDownloadState.CheckingExisting

        if (isDownloaded(quant)) {
            _state.value = ModelDownloadState.Completed
            return
        }

        modelsDir.mkdirs()
        val partFile = partFile(quant)
        val finalFile = modelFile(quant)
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        try {
            val requestBuilder = Request.Builder().url(quant.downloadUrl)
            if (resumeFrom > 0) {
                requestBuilder.addHeader("Range", "bytes=$resumeFrom-")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    // A 416 (Range Not Satisfiable) means our .part is already
                    // complete or corrupt-oversized - restart clean.
                    if (response.code == 416) {
                        partFile.delete()
                        return download(quant)
                    }
                    _state.value = ModelDownloadState.Failed("Download failed: HTTP ${response.code}")
                    return
                }

                val body = response.body ?: run {
                    _state.value = ModelDownloadState.Failed("Empty response body")
                    return
                }

                val isResuming = response.code == 206
                val contentLength = body.contentLength()
                val totalBytes = if (isResuming) resumeFrom + contentLength else contentLength

                val sink = if (isResuming) {
                    java.io.FileOutputStream(partFile, /* append = */ true)
                } else {
                    java.io.FileOutputStream(partFile, /* append = */ false)
                }

                var bytesWritten = if (isResuming) resumeFrom else 0L
                sink.use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReportedMb = -1L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesWritten += read

                            // Throttle StateFlow updates to roughly once per MB
                            // to avoid flooding Compose recomposition.
                            val currentMb = bytesWritten / (1024 * 1024)
                            if (currentMb != lastReportedMb) {
                                lastReportedMb = currentMb
                                _state.value = ModelDownloadState.Downloading(bytesWritten, totalBytes)
                            }
                        }
                    }
                }

                if (totalBytes > 0 && bytesWritten < totalBytes) {
                    _state.value = ModelDownloadState.Failed(
                        "Download incomplete ($bytesWritten of $totalBytes bytes) - retry to resume"
                    )
                    return
                }

                // Sanity-check the file is actually a GGUF model before treating
                // it as complete. A byte-count match against Content-Length
                // alone doesn't prove the bytes are real - if the server (or a
                // proxy in between) ever returned an error/redirect page with a
                // misleading Content-Length, or a download got silently
                // truncated/corrupted, this would previously still get renamed
                // to the final .gguf name and reported as available, and
                // NemotronTranscriber.start() would try to load garbage as a
                // model - which can fail silently or hang rather than
                // producing a clean error, looking exactly like "downloaded
                // fine but transcription never starts." GGUF files always
                // begin with the 4 magic bytes 'G','G','U','F' (0x47475546
                // little-endian) - cheap to check, catches this whole class
                // of corruption before it ever reaches the native loader.
                if (!looksLikeGguf(partFile)) {
                    partFile.delete()
                    _state.value = ModelDownloadState.Failed(
                        "Downloaded file is not a valid GGUF model (bad magic bytes) - deleted, please retry"
                    )
                    return
                }

                // Atomic-ish completion: only becomes visible to
                // ForegroundListeningService's file-existence check after
                // the full download succeeds.
                if (!partFile.renameTo(finalFile)) {
                    _state.value = ModelDownloadState.Failed("Failed to finalize downloaded file")
                    return
                }

                _state.value = ModelDownloadState.Completed
            }
        } catch (e: IOException) {
            _state.value = ModelDownloadState.Failed(e.message ?: "Network error - retry to resume")
        }
    }

    /** True if the file starts with the GGUF magic bytes ('G','G','U','F'). */
    private fun looksLikeGguf(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                read == 4 && magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (_: IOException) {
            false
        }
    }

    /** Deletes a downloaded (or partially downloaded) model file to free space. */
    fun delete(quant: NemotronQuant) {
        modelFile(quant).delete()
        partFile(quant).delete()
        _state.value = ModelDownloadState.Idle
    }
}
