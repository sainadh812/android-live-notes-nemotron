package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.desktop.stt.FileTranscriber
import com.sainadh.livenotes.desktop.stt.RecorderBacklogCheck
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.SpeechModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Windows integration: the app's GitHub downloader, manual importer, and real speech engine. */
object ModelTransferCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1) { "Expected the speech evidence directory" }
        val evidence = File(args[0]).absoluteFile.apply { mkdirs() }
        val temporary = Files.createTempDirectory(evidence.toPath(), "model-transfer-").toFile()
        val model = SpeechModel.NEMOTRON_ENGLISH
        try {
            var lastPercent = -1
            val downloaded = ModelStore(File(temporary, "downloads")).download(model) { fraction, _ ->
                val percent = (fraction * 100).toInt() / 10 * 10
                if (percent != lastPercent) { println("github_download_percent=$percent"); lastPercent = percent }
            }
            // A browser can rename a download; import recognizes content rather than its name.
            val source = File(downloaded.parentFile, "Downloaded model \u6a21\u578b \uD83D\uDE00.gguf")
            Files.move(downloaded.toPath(), source.toPath())
            val store = ModelStore(File(temporary, "Imported \u6a21\u578b \uD83D\uDE00"))
            val imported = store.importModel(model, source) { _, _ -> }
            check(store.installed(model)) { "Imported model did not become available" }
            check(store.verify(model) == imported) { "Imported model failed the recording preflight" }
            check(source.length() == model.expectedBytes && sha256(source) == model.sha256) { "Import changed its source file" }
            val result = FileTranscriber.transcribe(imported, File(evidence, "jfk.wav"), "en-US")
            val wordCount = result.wordTiming?.let { NativeWordTimingFile.parse(it).size } ?: 0
            println("duration_ms=${result.durationMs}")
            println("timed_words=$wordCount")
            println(result.text)
            check(wordCount == 22) { "Expected 22 native word timings, received $wordCount; see transcript above" }
            // The decoder capitalizes this sentence as "Ask". Match the previous
            // Windows smoke check's case-insensitive phrase assertion.
            check(result.text.contains("ask what you can do for your country", ignoreCase = true)) {
                "The imported model did not produce the expected phrase; see transcript above"
            }
            RecorderBacklogCheck.verify(imported, File(evidence, "jfk.wav"), evidence)
            File(evidence, "model-transfer.json").writeText(buildJsonObject {
                put("passed", true)
                put("downloadUrl", ModelSources.downloadUrl(model))
                put("sha256", model.sha256)
                put("bytes", model.expectedBytes)
                put("sourceUnchanged", true)
                put("importVerified", true)
                put("unicodePaths", true)
                put("timedWords", wordCount)
            }.toString())
        } finally {
            check(temporary.deleteRecursively()) { "Model transfer test files were still locked after inference" }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
