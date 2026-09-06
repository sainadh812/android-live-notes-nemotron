package com.sainadh.livenotes.stt

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

sealed class ModelDownloadState {
    object Idle : ModelDownloadState()
    object CheckingExisting : ModelDownloadState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : ModelDownloadState() {
        val progress: Float get() = if (totalBytes > 0) {
            (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
        } else 0f
    }
    data class Failed(val message: String) : ModelDownloadState()
    object Completed : ModelDownloadState()
}

/** Downloads pinned model bytes, publishing the final file only after full verification. */
class ModelDownloadManager internal constructor(
    private val modelsDir: File,
    private val client: OkHttpClient
) {
    constructor(
        context: Context,
        client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    ) : this(File(context.applicationContext.filesDir, "models"), client)

    private val operationLock = ReentrantLock()
    // Short file/lease transitions only. Never hold this during hashing or network I/O.
    private val captureGuard = Any()
    private val captureUsers = mutableMapOf<SpeechModel, Int>()
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()
    private val _downloadTarget = MutableStateFlow<SpeechModel?>(null)
    val downloadTarget: StateFlow<SpeechModel?> = _downloadTarget.asStateFlow()
    private val _downloadedModels = MutableStateFlow(scanDownloadedModels())
    val downloadedModels: StateFlow<Set<SpeechModel>> = _downloadedModels.asStateFlow()

    fun modelFile(model: SpeechModel): File = File(modelsDir, model.fileName)

    private fun partFile(model: SpeechModel): File = File(modelsDir, "${model.fileName}.part")

    /** Pins an installed file against removal/repair until its capture owner releases it. */
    fun acquireForCapture(model: SpeechModel): Boolean = synchronized(captureGuard) {
        if (!isDownloaded(model)) return@synchronized false
        captureUsers[model] = (captureUsers[model] ?: 0) + 1
        true
    }

    fun releaseFromCapture(model: SpeechModel) = synchronized(captureGuard) {
        val remaining = (captureUsers[model] ?: 0) - 1
        if (remaining > 0) captureUsers[model] = remaining else captureUsers.remove(model)
        Unit
    }

    private fun requireUnused(model: SpeechModel) {
        if ((captureUsers[model] ?: 0) > 0) {
            throw IOException("Stop recording before changing ${model.title}.")
        }
    }

    /** Cheap startup check; download() also hashes legacy files before reporting Completed. */
    fun isDownloaded(model: SpeechModel): Boolean = try {
        val file = modelFile(model)
        file.isFile && file.length() == model.expectedBytes && looksLikeGguf(file)
    } catch (_: SecurityException) {
        false
    }

    fun findAnyDownloaded(preferred: SpeechModel = SpeechModel.NEMOTRON_Q8): SpeechModel? =
        preferred.takeIf(::isDownloaded) ?: (listOf(
            SpeechModel.NEMOTRON_Q8, SpeechModel.NEMOTRON_Q6,
            SpeechModel.NEMOTRON_Q5, SpeechModel.NEMOTRON_Q4
        ) + SpeechModel.entries).firstOrNull(::isDownloaded)

    private fun scanDownloadedModels(): Set<SpeechModel> =
        SpeechModel.entries.filterTo(linkedSetOf(), ::isDownloaded)

    private fun refreshDownloadedModels() = synchronized(captureGuard) {
        _downloadedModels.value = scanDownloadedModels()
    }

    private fun checkModelUnused(model: SpeechModel) = synchronized(captureGuard) {
        requireUnused(model)
    }

    private fun removeCorruptModel(model: SpeechModel, target: File) = synchronized(captureGuard) {
        // A recording can acquire the old file while the hash is being checked.
        requireUnused(model)
        deleteChecked(target)
        refreshDownloadedModels()
    }

    private fun publishVerifiedModel(model: SpeechModel, partial: File, target: File) =
        synchronized(captureGuard) {
            requireUnused(model)
            if (!partial.renameTo(target)) throw IOException("Could not finalize the verified model file")
            refreshDownloadedModels()
        }

    /** Blocking I/O: call on an I/O worker. Concurrent download requests are ignored. */
    fun download(model: SpeechModel) {
        if (!operationLock.tryLock()) return
        try {
            _downloadTarget.value = model
            _state.value = ModelDownloadState.CheckingExisting
            checkModelUnused(model)
            check(model.expectedBytes >= 4 && model.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
                "Model download metadata is invalid"
            }
            if (!modelsDir.isDirectory && !modelsDir.mkdirs()) {
                throw IOException("Could not create model storage directory")
            }

            val target = modelFile(model)
            val partial = partFile(model)
            if (target.exists()) {
                try {
                    verifyModelFile(target, model.expectedBytes, model.sha256)
                    refreshDownloadedModels()
                    _state.value = ModelDownloadState.Completed
                    return
                } catch (_: CorruptModelException) {
                    removeCorruptModel(model, target)
                }
            }
            if (partial.exists() && (partial.length() > model.expectedBytes ||
                    (partial.length() >= 4 && !looksLikeGguf(partial)))) {
                deleteChecked(partial)
            }

            // An interrupted process can leave every byte on disk before the rename.
            if (partial.length() != model.expectedBytes) downloadBytes(model, partial)
            _state.value = ModelDownloadState.CheckingExisting
            try {
                verifyModelFile(partial, model.expectedBytes, model.sha256)
            } catch (error: CorruptModelException) {
                deleteChecked(partial)
                throw IOException("${error.message}. Corrupt download removed; please retry.", error)
            }
            publishVerifiedModel(model, partial, target)
            _state.value = ModelDownloadState.Completed
        } catch (error: IOException) {
            _state.value = ModelDownloadState.Failed(error.message ?: "Download failed; retry to resume")
        } catch (error: SecurityException) {
            _state.value = ModelDownloadState.Failed("Cannot access model storage: ${error.message.orEmpty()}")
        } catch (error: IllegalArgumentException) {
            _state.value = ModelDownloadState.Failed("Invalid model download: ${error.message.orEmpty()}")
        } catch (error: IllegalStateException) {
            _state.value = ModelDownloadState.Failed(error.message ?: "Could not download model")
        } finally {
            operationLock.unlock()
        }
    }

    private fun downloadBytes(model: SpeechModel, partial: File) {
        var retriedUnsatisfiedRange = false
        while (true) {
            val resumeFrom = partial.length()
            val request = Request.Builder().url(model.downloadUrl)
                // Byte offsets must address the exact, uncompressed representation.
                .header("Accept-Encoding", "identity")
                .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                .build()
            val retryClean = client.newCall(request).execute().use { response ->
                if (response.code == 416) {
                    if (resumeFrom == 0L || retriedUnsatisfiedRange) {
                        throw IOException("Server rejected the model download range (HTTP 416)")
                    }
                    deleteChecked(partial)
                    return@use true
                }
                if (response.code != 200 && response.code != 206) {
                    throw IOException("Download failed: HTTP ${response.code}; retry to resume")
                }
                val encoding = response.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw IOException("Server returned an encoded model; cannot safely resume")
                }
                val body = response.body ?: throw IOException("Empty model response")
                val append = response.code == 206
                val start = if (append) resumeFrom else 0L
                val responseEnd = if (append) {
                    validateModelContentRange(
                        response.header("Content-Range"), resumeFrom, model.expectedBytes, body.contentLength()
                    )
                } else {
                    if (response.header("Content-Range") != null) {
                        throw IOException("Server returned Content-Range without HTTP 206")
                    }
                    if (body.contentLength() >= 0 && body.contentLength() != model.expectedBytes) {
                        throw IOException("Model size differs from the published size (${model.expectedBytes} bytes)")
                    }
                    model.expectedBytes
                }
                // A 200 response replaces the partial and can reclaim its existing storage.
                val additionalBytes = model.expectedBytes - resumeFrom
                val availableBytes = modelsDir.usableSpace
                if (availableBytes > 0 && availableBytes < additionalBytes) {
                    throw IOException("Not enough storage: need $additionalBytes more bytes for this model")
                }
                var written = start
                _state.value = ModelDownloadState.Downloading(written, model.expectedBytes)
                try {
                    FileOutputStream(partial, append).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var lastReported = written
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (count == 0) continue
                                if (count.toLong() > responseEnd - written) {
                                    throw CorruptModelException("Server sent more model bytes than expected")
                                }
                                output.write(buffer, 0, count)
                                written += count
                                if (written - lastReported >= 1024 * 1024 || written == model.expectedBytes) {
                                    _state.value = ModelDownloadState.Downloading(written, model.expectedBytes)
                                    lastReported = written
                                }
                            }
                        }
                        output.fd.sync()
                    }
                } catch (error: CorruptModelException) {
                    deleteChecked(partial)
                    throw IOException("${error.message}. Corrupt download removed; please retry.", error)
                }
                if (written != model.expectedBytes) {
                    throw IOException("Download incomplete ($written of ${model.expectedBytes} bytes); retry to resume")
                }
                false
            }
            if (!retryClean) return
            retriedUnsatisfiedRange = true
        }
    }

    /** Blocking file I/O; never queues a deletion behind a potentially lengthy transfer. */
    fun delete(model: SpeechModel) {
        if (!operationLock.tryLock()) return
        try {
            deleteAndReportResult(model)
        } finally {
            operationLock.unlock()
        }
    }

    private fun deleteAndReportResult(model: SpeechModel) {
        try {
            deleteUnusedModel(model)
        } catch (error: IOException) {
            _downloadTarget.value = model
            _state.value = ModelDownloadState.Failed(error.message ?: "Could not delete model")
        } catch (error: SecurityException) {
            _downloadTarget.value = model
            _state.value = ModelDownloadState.Failed("Cannot delete model: ${error.message.orEmpty()}")
        }
        refreshDownloadedModels()
    }

    private fun deleteUnusedModel(model: SpeechModel) = synchronized(captureGuard) {
        if ((captureUsers[model] ?: 0) == 0) {
            deleteChecked(modelFile(model))
            deleteChecked(partFile(model))
            if (_downloadTarget.value == model) {
                _downloadTarget.value = null
                _state.value = ModelDownloadState.Idle
            }
        }
    }
}

private class CorruptModelException(message: String) : IOException(message)

private fun deleteChecked(file: File) {
    if (file.exists() && !file.delete()) throw IOException("Could not remove model file ${file.name}")
}

private fun looksLikeGguf(file: File): Boolean = try {
    file.inputStream().use { input ->
        val magic = ByteArray(4)
        input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
    }
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

/** Validates a resumed response before a single byte can be appended; returns its exclusive end. */
internal fun validateModelContentRange(header: String?, start: Long, total: Long, bodyLength: Long): Long {
    val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(header.orEmpty())
        ?: throw IOException("Server returned an invalid Content-Range; partial download preserved")
    val from = match.groupValues[1].toLongOrNull()
    val through = match.groupValues[2].toLongOrNull()
    val size = match.groupValues[3].toLongOrNull()
    if (from != start || size != total || through == null || through < start || through >= total) {
        throw IOException("Server returned a mismatched Content-Range; partial download preserved")
    }
    val length = through - start + 1
    if (bodyLength >= 0 && bodyLength != length) {
        throw IOException("Server returned a mismatched range length; partial download preserved")
    }
    return through + 1
}

/** All downloaded bytes must match the immutable catalog, including legacy completed files. */
internal fun verifyModelFile(file: File, expectedBytes: Long, expectedSha256: String) {
    if (!file.isFile || file.length() != expectedBytes) {
        throw CorruptModelException("Model size does not match the published size")
    }
    if (!looksLikeGguf(file)) throw CorruptModelException("Downloaded file is not a GGUF model")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (!actual.equals(expectedSha256, ignoreCase = true)) {
        throw CorruptModelException("Model checksum does not match the published SHA-256")
    }
}
