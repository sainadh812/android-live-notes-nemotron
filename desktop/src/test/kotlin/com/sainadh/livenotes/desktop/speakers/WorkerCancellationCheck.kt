package com.sainadh.livenotes.desktop.speakers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Windows CI integration check using the actual packaged ONNX worker and real speech. */
object WorkerCancellationCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 4) { "Expected worker directory, models directory, fixture WAV, evidence directory" }
        val worker = Path.of(args[0]).toAbsolutePath()
        val models = Path.of(args[1]).toAbsolutePath()
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
        try {
            withTimeout(60_000) { analyzing.await() }
            val children = ProcessHandle.current().descendants().use { it.toList() }
            check(children.isNotEmpty()) { "Expected the native speaker worker to be running" }
            check(!task.isCompleted) { "Fixture finished before cancellation could be exercised" }
            val start = System.nanoTime()
            withTimeout(10_000) { task.cancelAndJoin() }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            check(task.isCancelled) { "Expected coroutine cancellation" }
            check(children.none { it.isAlive }) { "Native speaker worker remained alive after cancellation" }
            Files.list(jobs).use { check(it.count() == 0L) { "Canceled speaker job left temporary output behind" } }
            check(sha256(wav) == before) { "Cancellation modified the source WAV" }
            val result = """{"passed":true,"elapsedMs":$elapsedMs,"processesStopped":${children.size},"wavUnchanged":true,"scratchClean":true}"""
            Files.writeString(evidence.resolve("worker-cancellation.json"), result)
            println(result)
        } finally {
            task.cancelAndJoin()
            Files.walk(jobs).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
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
