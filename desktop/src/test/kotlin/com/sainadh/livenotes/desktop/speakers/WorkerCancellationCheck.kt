package com.sainadh.livenotes.desktop.speakers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** Windows CI integration check using the actual packaged ONNX worker and real speech. */
object WorkerCancellationCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 4) { "Expected worker directory, smoke evidence directory, fixture WAV, cancellation evidence directory" }
        val worker = Path.of(args[0]).toAbsolutePath()
        // Resolve inside the JVM: the Windows Java launcher can lose Unicode in command-line
        // arguments under its active code page. ProcessBuilder must still pass this actual
        // Unicode path to the worker, matching a non-ASCII Windows user profile.
        val models = Path.of(args[1]).toAbsolutePath().resolve("speaker models \u4f1a\u8bae")
        val wav = Path.of(args[2]).toAbsolutePath()
        val evidence = Path.of(args[3]).toAbsolutePath()
        Files.createDirectories(evidence)
        val jobs = Files.createTempDirectory(evidence, "cancellation-jobs-")
        val sourceBefore = sha256(wav)
        // The 57-second source can finish before Windows process inspection completes.
        // Extend only the test input so this check cancels real, ongoing native inference.
        val cancellationWav = evidence.resolve("cancellation-recording.wav")
        val durationMs = repeatFixture(wav, cancellationWav)
        val before = sha256(cancellationWav)
        val client = DiarizationClient(worker, models, jobs)
        check(client.available && client.modelsInstalled()) { "Packaged worker and verified models must be installed first" }
        val analyzing = CompletableDeferred<DiarizationProgress>()
        val task = async {
            client.analyze(cancellationWav) { progress ->
                if (progress.stage == "analyzing" && (progress.fraction ?: 0.0) > 0.0) analyzing.complete(progress)
            }
        }
        val diagnostics = linkedMapOf<String, JsonElement>(
            "testRecordingDurationMs" to JsonPrimitive(durationMs),
            "sourceSha256Before" to JsonPrimitive(sourceBefore),
            "testRecordingSha256Before" to JsonPrimitive(before),
        )
        val evidenceFile = evidence.resolve("worker-cancellation.json")
        fun saveEvidence() = Files.writeString(evidenceFile, Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(diagnostics)))
        var workerTree = emptyList<ProcessHandle>()
        try {
            val firstProgress = withTimeout(60_000) { analyzing.await() }
            diagnostics["firstAnalysisFraction"] = firstProgress.fraction?.let(::JsonPrimitive) ?: JsonNull
            val children = ProcessHandle.current().descendants().use { it.toList() }
            diagnostics["jvmDescendantsBefore"] = JsonArray(children.map(::describeProcess))
            val executable = worker.resolve("speaker-worker.exe").toRealPath()
            val workers = children.filter { child ->
                child.info().command().map { command ->
                    runCatching { Files.isSameFile(Path.of(command), executable) }.getOrDefault(false)
                }.orElse(false)
            }
            check(workers.isNotEmpty()) { "Expected the packaged speaker worker to be running" }
            workerTree = (workers + workers.flatMap { it.descendants().use { children -> children.toList() } })
                .distinctBy { it.pid() }
            diagnostics["workerTreeBefore"] = JsonArray(workerTree.map(::describeProcess))
            saveEvidence()
            check(!task.isCompleted) { "Fixture finished before cancellation could be exercised" }
            val start = System.nanoTime()
            withTimeout(10_000) { task.cancelAndJoin() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            diagnostics["elapsedMs"] = JsonPrimitive(elapsedMs)
            diagnostics["workerTreeAfter"] = JsonArray(workerTree.map(::describeProcess))
            diagnostics["jvmDescendantsAfter"] = JsonArray(ProcessHandle.current().descendants().use {
                it.toList().map(::describeProcess)
            })
            saveEvidence()
            check(task.isCancelled) { "Expected coroutine cancellation" }
            check(workerTree.none { it.isAlive }) {
                "Native speaker worker remained alive after cancellation: ${workerTree.filter { it.isAlive }.map { it.pid() }}"
            }
            Files.list(jobs).use { check(it.count() == 0L) { "Canceled speaker job left temporary output behind" } }
            val sourceAfter = sha256(wav)
            val after = sha256(cancellationWav)
            diagnostics["sourceSha256After"] = JsonPrimitive(sourceAfter)
            diagnostics["testRecordingSha256After"] = JsonPrimitive(after)
            check(sourceAfter == sourceBefore) { "Cancellation modified the source fixture WAV" }
            check(after == before) { "Cancellation modified the test recording WAV" }
            diagnostics["passed"] = JsonPrimitive(true)
            diagnostics["processesStopped"] = JsonPrimitive(workerTree.size)
            diagnostics["wavUnchanged"] = JsonPrimitive(true)
            diagnostics["scratchClean"] = JsonPrimitive(true)
            saveEvidence()
            println(Files.readString(evidenceFile))
        } catch (failure: Throwable) {
            diagnostics["passed"] = JsonPrimitive(false)
            diagnostics["error"] = JsonPrimitive(failure.toString())
            diagnostics["remainingWorkerProcesses"] = JsonArray(workerTree.filter { it.isAlive }.map(::describeProcess))
            saveEvidence()
            throw failure
        } finally {
            task.cancelAndJoin()
            Files.walk(jobs).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            if (workerTree.none { it.isAlive }) Files.deleteIfExists(cancellationWav)
        }
    }

    private fun repeatFixture(source: Path, destination: Path): Long {
        AudioSystem.getAudioInputStream(source.toFile()).use { input ->
            val format = input.format
            check(format.encoding == AudioFormat.Encoding.PCM_SIGNED && format.sampleRate == 16_000f &&
                format.sampleSizeInBits == 16 && format.channels == 1 && format.frameSize == 2 && !format.isBigEndian) {
                "Cancellation fixture must be mono 16 kHz PCM16 WAV"
            }
            check(input.frameLength in 1..(16_000L * 120)) { "Expected a short source fixture" }
            val pcm = input.readAllBytes()
            check(pcm.size.toLong() == input.frameLength * format.frameSize) { "Source fixture was truncated" }
            val minimumFrames = 16_000L * 60 * 10
            val repeats = ((minimumFrames + input.frameLength - 1) / input.frameLength).toInt()
            val frames = input.frameLength * repeats
            val streams = Collections.enumeration(List(repeats) { ByteArrayInputStream(pcm) })
            AudioInputStream(SequenceInputStream(streams), format, frames).use { repeated ->
                check(AudioSystem.write(repeated, AudioFileFormat.Type.WAVE, destination.toFile()) > 0)
            }
            return frames / 16
        }
    }

    private fun describeProcess(process: ProcessHandle) = buildJsonObject {
        val info = process.info()
        put("pid", process.pid())
        put("parentPid", process.parent().map { JsonPrimitive(it.pid()) }.orElse(JsonNull))
        put("command", info.command().map { JsonPrimitive(it) }.orElse(JsonNull))
        put("startTime", info.startInstant().map { JsonPrimitive(it.toString()) }.orElse(JsonNull))
        put("alive", process.isAlive)
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
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
