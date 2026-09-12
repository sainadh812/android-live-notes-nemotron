package com.sainadh.livenotes.desktop.speakers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class DiarizationProgress(val stage: String, val fraction: Double?, val message: String)

/** Launches only the packaged worker; no commands or model URLs come from transcript text. */
class DiarizationClient(
    private val workerDirectory: Path,
    private val modelsDirectory: Path,
    private val workDirectory: Path,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val executable: Path get() = workerDirectory.resolve("speaker-worker.exe")
    val available: Boolean get() = Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)

    /** Cheap UI readiness hint. The worker also verifies every model's SHA-256 before use. */
    fun modelsInstalled(): Boolean = runCatching {
        val ready = modelsDirectory.resolve("ready.json")
        Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) && Files.size(ready) < 1_024 &&
            json.parseToJsonElement(Files.readString(ready)).jsonObject["modelSet"]?.jsonPrimitive?.content ==
            "pyannote3-titanet-small-2024-10" &&
            Files.size(modelsDirectory.resolve("segmentation.onnx")) == 5_992_913L &&
            Files.size(modelsDirectory.resolve("embedding.onnx")) == 40_257_283L
    }.getOrDefault(false)

    suspend fun installModels(onProgress: (DiarizationProgress) -> Unit = {}) = mutex.withLock {
        withContext(Dispatchers.IO) {
            Files.createDirectories(modelsDirectory)
            runJob(listOf("--install-models", "--models", modelsDirectory.toAbsolutePath().toString()), onProgress) { }
            check(modelsInstalled()) { "Speaker models did not finish installing. Please retry." }
        }
    }

    suspend fun analyze(
        wav: Path,
        numSpeakers: Int? = null,
        onProgress: (DiarizationProgress) -> Unit = {},
    ): DiarizationResult = mutex.withLock {
        require(numSpeakers == null || numSpeakers in 1..20) { "Speaker count must be between 1 and 20." }
        withContext(Dispatchers.IO) {
            require(Files.isRegularFile(wav, LinkOption.NOFOLLOW_LINKS)) { "The saved audio file is unavailable." }
            val arguments = mutableListOf("--analyze", wav.toRealPath().toString(),
                "--models", modelsDirectory.toAbsolutePath().normalize().toString())
            if (numSpeakers != null) arguments += listOf("--num-speakers", numSpeakers.toString())
            runJob(arguments, onProgress) { job ->
                val output = job.resolve("result.json")
                check(Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && Files.size(output) <= 32L * 1024 * 1024) {
                    "Speaker analysis did not produce a valid result."
                }
                json.decodeFromString<DiarizationResult>(Files.readString(output)).also(::validateResult)
            }
        }
    }

    private suspend fun <T> runJob(
        arguments: List<String>,
        onProgress: (DiarizationProgress) -> Unit,
        result: (Path) -> T,
    ): T = coroutineScope {
        check(available) { "The speaker worker is missing. Reinstall the Windows app." }
        Files.createDirectories(workDirectory)
        val job = Files.createTempDirectory(workDirectory, "speaker-job-")
        var process: Process? = null
        val descendants = linkedMapOf<Long, ProcessHandle>()
        try {
            val command = mutableListOf(executable.toRealPath().toString())
            command += arguments
            command += listOf("--scratch", job.toAbsolutePath().toString())
            if ("--analyze" in arguments) command += listOf("--output", job.resolve("result.json").toAbsolutePath().toString())
            process = ProcessBuilder(command).directory(workerDirectory.toRealPath().toFile()).start()
            process.outputStream.close()
            val running = process
            val failure = AtomicReference<String?>(null)
            val stderrTail = StringBuilder()
            val stdout = async(Dispatchers.IO) {
                running.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    readBoundedLines(reader) { line ->
                        val event = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                            ?: return@readBoundedLines
                        when (event["event"]?.jsonPrimitive?.content) {
                            "error" -> failure.set(event["message"]?.jsonPrimitive?.content?.take(1_000))
                            "progress" -> {
                                val fraction = event["fraction"]?.jsonPrimitive?.doubleOrNull
                                    ?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
                                onProgress(DiarizationProgress(
                                    event["stage"]?.jsonPrimitive?.content?.take(40) ?: "analyzing", fraction,
                                    event["message"]?.jsonPrimitive?.content?.take(500) ?: "Analyzing speakers"))
                            }
                        }
                    }
                }
            }
            val stderr = async(Dispatchers.IO) {
                running.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(2_048)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        stderrTail.append(buffer, 0, count)
                        if (stderrTail.length > 8_192) stderrTail.delete(0, stderrTail.length - 8_192)
                    }
                }
            }
            while (true) {
                rememberDescendants(running, descendants)
                if (running.waitFor(100, TimeUnit.MILLISECONDS)) break
                currentCoroutineContext().ensureActive()
            }
            currentCoroutineContext().ensureActive()
            stdout.await()
            stderr.await()
            check(running.exitValue() == 0) {
                failure.get() ?: "Speaker analysis failed. ${stderrTail.toString().takeLast(500)}"
            }
            currentCoroutineContext().ensureActive()
            result(job)
        } finally {
            // Native ONNX inference need not poll Kotlin cancellation. Stop its process instead.
            process?.let { running ->
                stopProcessTree(running, descendants)
                runCatching { running.inputStream.close() }
                runCatching { running.errorStream.close() }
            }
            // The unique job directory is the only recursive-delete target.
            runCatching { Files.walk(job).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
            listOf("segmentation.onnx.installing", "embedding.onnx.installing").forEach {
                runCatching { Files.deleteIfExists(modelsDirectory.resolve(it)) }
            }
        }
    }

    private fun rememberDescendants(process: Process, descendants: MutableMap<Long, ProcessHandle>) {
        process.descendants().use { children -> children.forEach { descendants[it.pid()] = it } }
    }

    private fun stopProcessTree(process: Process, descendants: MutableMap<Long, ProcessHandle>) {
        rememberDescendants(process, descendants)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        descendants.values.toList().asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
        // TerminateProcess on Windows is asynchronous. Root exit alone does not mean its
        // children have stopped using the WAV/models/scratch, including orphaned children.
        while (true) {
            rememberDescendants(process, descendants)
            val alive = descendants.values.filter { it.isAlive }
            if (!process.isAlive && alive.isEmpty()) return
            check(System.nanoTime() < deadline) {
                "Could not stop speaker worker processes ${
                    (alive.map { it.pid() } + listOfNotNull(process.pid().takeIf { process.isAlive })).joinToString()
                }; temporary files were retained."
            }
            alive.forEach { it.destroyForcibly() }
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(25, TimeUnit.MILLISECONDS)
            } else Thread.sleep(25)
        }
    }

    internal fun validateResult(result: DiarizationResult) {
        require(result.schemaVersion == 1 && result.durationMs in 0..43_200_000L) { "Unsupported speaker analysis result." }
        require(result.speakerCount in 0..10_000 && result.spans.size <= 500_000) { "Invalid speaker count or turn count." }
        val idPattern = Regex("speaker_[1-9][0-9]{0,4}")
        require(result.spans.all {
            idPattern.matches(it.speakerId) && it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= result.durationMs
        }) { "Speaker analysis returned invalid timestamps." }
        require(result.spans.map { it.speakerId }.toSet().size == result.speakerCount) { "Speaker analysis returned inconsistent labels." }
    }

    private fun readBoundedLines(reader: BufferedReader, consume: (String) -> Unit) {
        val line = StringBuilder()
        var oversized = false
        while (true) {
            val next = reader.read()
            if (next == -1) {
                if (!oversized && line.isNotEmpty()) consume(line.toString())
                break
            }
            if (next == '\n'.code) {
                if (!oversized) consume(line.toString())
                line.setLength(0)
                oversized = false
            } else if (line.length < 16_384) line.append(next.toChar()) else oversized = true
        }
    }
}
