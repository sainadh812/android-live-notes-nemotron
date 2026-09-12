package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.stt.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ModelDownloadSpec(val name: String, val url: String, val bytes: Long, val sha256: String)

class ModelStore(private val directory: File, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()) {
    private val calls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()
    fun cancel(model: SpeechModel) { calls[model.fileName]?.cancel() }
    fun file(model: SpeechModel) = File(directory, model.fileName)
    fun installed(model: SpeechModel): Boolean = validHeader(file(model), model.expectedBytes)
    fun remove(model: SpeechModel) {
        java.nio.file.Files.deleteIfExists(file(model).toPath())
        java.nio.file.Files.deleteIfExists(File(directory, "${model.fileName}.part").toPath())
    }
    suspend fun verify(model: SpeechModel): File = withContext(Dispatchers.IO) {
        val file = file(model)
        check(validHeader(file, model.expectedBytes)) { "Download or import ${model.title} in Settings before recording." }
        check(hash(file) == model.sha256) { "The ${model.title} file failed verification. Download or import a verified copy in Settings." }
        file
    }
    suspend fun download(model: SpeechModel, progress: (Float, String) -> Unit): File = download(
        ModelSources.downloadSpec(model), progress)

    /** Copies and verifies a selected file without modifying it or a resumable download. */
    suspend fun importModel(model: SpeechModel, source: File, progress: (Float, String) -> Unit): File =
        importModel(ModelSources.downloadSpec(model), source, progress)

    internal suspend fun importModel(spec: ModelDownloadSpec, source: File, progress: (Float, String) -> Unit): File =
        withContext(Dispatchers.IO) {
            requireFileName(spec.name)
            currentCoroutineContext().ensureActive()
            check(validHeader(source, spec.bytes)) {
                "Choose the matching ${spec.name} file (${spec.bytes} bytes). The selected file has an incorrect size or GGUF header."
            }
            Files.createDirectories(directory.toPath())
            val destination = File(directory, spec.name)
            if (destination.exists() && Files.isSameFile(source.toPath(), destination.toPath())) {
                progress(0f, "Verifying model…")
                check(hash(source) == spec.sha256) { "The selected model failed checksum verification. Choose the original ${spec.name} file." }
                currentCoroutineContext().ensureActive()
                progress(1f, "Ready")
                return@withContext destination
            }

            // Same-directory staging makes publication atomic. A unique name keeps imports
            // separate from each other and from the resumable .part download.
            val staging = Files.createTempFile(directory.toPath(), "${spec.name}.import-", ".tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                progress(0f, "Importing model…")
                FileInputStream(source).use { input ->
                    FileOutputStream(staging.toFile()).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            check(copied + read <= spec.bytes) { "The selected model changed size during import. Try again." }
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            copied += read
                            progress(copied.toFloat() / spec.bytes, "Importing ${copied / 1_000_000} / ${spec.bytes / 1_000_000} MB")
                        }
                        output.fd.sync()
                    }
                }
                currentCoroutineContext().ensureActive()
                progress(1f, "Verifying model…")
                check(copied == spec.bytes && validHeader(staging.toFile(), spec.bytes) &&
                    digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256) {
                    "The selected model failed checksum verification. Choose the original ${spec.name} file."
                }
                currentCoroutineContext().ensureActive()
                // Do not fall back to delete-then-copy if this filesystem cannot replace
                // atomically: an existing installed model must survive a failed import.
                Files.move(staging, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                progress(1f, "Ready")
                destination
            } finally {
                Files.deleteIfExists(staging)
            }
        }

    internal suspend fun download(spec: ModelDownloadSpec, progress: (Float, String) -> Unit): File = withContext(Dispatchers.IO) {
        requireFileName(spec.name)
        val destination = File(directory, spec.name)
        val partial = File(directory, "${spec.name}.part")
        directory.mkdirs()
        if (validHeader(destination, spec.bytes) && hash(destination) == spec.sha256) return@withContext destination
        if (partial.exists() && partial.length() > spec.bytes) check(partial.delete())
        var offset = partial.takeIf { it.isFile }?.length() ?: 0L
        if (offset < spec.bytes) {
            val request = Request.Builder().url(spec.url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            val call = client.newCall(request)
            calls[spec.name] = call
            val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.code == 206) {
                        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                            ?: throw IOException("Download server sent an invalid range.")
                        val (start, end, total) = match.destructured
                        check(start.toLong() == offset && end.toLong() >= offset && end.toLong() < spec.bytes && total.toLong() == spec.bytes) {
                            "Download server sent an unexpected range."
                        }
                        response.body?.contentLength()?.takeIf { it >= 0 }?.let { check(it == end.toLong() - offset + 1) }
                    } else if (response.code == 200) offset = 0L
                    else throw IOException("Model download failed (HTTP ${response.code}). Retry when connected.")
                    val body = response.body ?: throw IOException("Model download is empty.")
                    FileOutputStream(partial, offset > 0).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var count = offset
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                check(count + read <= spec.bytes) { "Model download exceeds its expected size." }
                                output.write(buffer, 0, read); count += read
                                progress(count.toFloat() / spec.bytes, "Downloading ${(count / 1_000_000)} / ${(spec.bytes / 1_000_000)} MB")
                            }
                        }
                        output.fd.sync()
                    }
                }
            } finally { cancellation?.dispose(); calls.remove(spec.name, call) }
        }
        currentCoroutineContext().ensureActive()
        check(partial.length() == spec.bytes) { "Download is incomplete. Retry to continue it." }
        progress(1f, "Verifying model…")
        if (!validHeader(partial, spec.bytes) || hash(partial) != spec.sha256) {
            partial.delete()
            error("The downloaded model failed verification. Retry the download.")
        }
        currentCoroutineContext().ensureActive()
        java.nio.file.Files.move(partial.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        progress(1f, "Ready")
        destination
    }
    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun requireFileName(name: String) {
        require(name == File(name).name && '/' !in name && '\\' !in name && name !in setOf(".", ".."))
    }
    private fun validHeader(file: File, bytes: Long): Boolean {
        if (!file.isFile || file.length() != bytes || bytes < 8) return false
        return runCatching { FileInputStream(file).use { input ->
            val header = ByteArray(4); input.read(header) == 4 && header.contentEquals("GGUF".toByteArray())
        } }.getOrDefault(false)
    }
}
