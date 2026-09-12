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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
        val before = sha256(wav)
        val client = DiarizationClient(worker, models, jobs)
        check(client.available && client.modelsInstalled()) { "Packaged worker and verified models must be installed first" }
        val analyzing = CompletableDeferred<Unit>()
        val task = async {
            client.analyze(wav) { progress ->
                if (progress.stage == "analyzing" && (progress.fraction ?: 0.0) > 0.0) analyzing.complete(Unit)
            }
        }
        val diagnostics = linkedMapOf<String, JsonElement>()
        val evidenceFile = evidence.resolve("worker-cancellation.json")
        fun saveEvidence() = Files.writeString(evidenceFile, Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(diagnostics)))
        var workerTree = emptyList<ProcessHandle>()
        try {
            withTimeout(60_000) { analyzing.await() }
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
            check(sha256(wav) == before) { "Cancellation modified the source WAV" }
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
