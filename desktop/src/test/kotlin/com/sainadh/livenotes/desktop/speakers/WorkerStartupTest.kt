package com.sainadh.livenotes.desktop.speakers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class WorkerStartupTest {
    @Test fun missingWorkerExplainsUnavailableComponentAndPreservesExistingFiles() = runBlocking {
        Fixture(false).use { fixture ->
            val failure = runCatching { fixture.client.installModels() }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("speaker-worker.exe is missing or unavailable"))
            assertTrue(failure.message.orEmpty().contains("IT/security team"))
            fixture.assertPreserved()
        }
    }

    @Test fun windowsSecurityDenialIsActionableAndCleansScratch() = runBlocking {
        Fixture().use { fixture ->
            fixture.client.launchWorker = { _, _ -> throw IOException("CreateProcess error=225, operation blocked") }
            val progress = mutableListOf<DiarizationProgress>()
            val failure = runCatching { fixture.client.installModels { progress += it } }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("security detection for speaker-worker.exe (error 225)"))
            assertFalse(failure.message.orEmpty().contains("false positive"))
            assertEquals("starting", progress.first().stage)
            fixture.assertPreserved()
        }
    }

    @Test fun workerRemovedBetweenInspectionAndLaunchReportsMissingComponent() = runBlocking {
        Fixture().use { fixture ->
            fixture.client.launchWorker = { _, _ ->
                Files.delete(fixture.executable)
                throw IOException("CreateProcess error=2, file not found")
            }
            val failure = runCatching { fixture.client.installModels() }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("missing or unavailable"))
            fixture.assertPreserved()
        }
    }

    @Test fun unresponsiveStartupHasDeadlineAndStopsActualProcess() = runBlocking {
        Fixture().use { fixture ->
            fixture.child("silent")
            fixture.client.startupTimeoutMs = 500
            val failure = runCatching { fixture.client.installModels() }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("did not become ready"))
            assertFalse(requireNotNull(fixture.process).isAlive)
            fixture.assertPreserved()
        }
    }

    @Test fun cancellationBeforeReadinessStopsActualProcess() = runBlocking {
        Fixture().use { fixture ->
            fixture.child("silent")
            val task = launch { fixture.client.installModels() }
            withTimeout(10_000) { fixture.launched.await() }
            withTimeout(10_000) { task.cancelAndJoin() }
            assertFalse(requireNotNull(fixture.process).isAlive)
            fixture.assertPreserved()
        }
    }

    @Test fun readyWorkerCanContinueBeyondStartupDeadline() = runBlocking {
        Fixture().use { fixture ->
            fixture.child("ready")
            fixture.client.startupTimeoutMs = 5_000
            val analyzing = CompletableDeferred<Unit>()
            val task = launch { fixture.client.analyze(fixture.audio) { if (it.stage == "analyzing") analyzing.complete(Unit) } }
            try {
                withTimeout(10_000) { analyzing.await() }
                delay(5_200)
                assertTrue("Startup deadline must not become an inference deadline", task.isActive)
            } finally { withTimeout(10_000) { task.cancelAndJoin() } }
            assertFalse(requireNotNull(fixture.process).isAlive)
            fixture.assertPreserved()
        }
    }

    @Test fun incompatibleProtocolIsRejectedAndStopsActualProcess() = runBlocking {
        Fixture().use { fixture ->
            fixture.child("incompatible")
            val failure = runCatching { fixture.client.installModels() }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("incompatible startup protocol"))
            assertFalse(requireNotNull(fixture.process).isAlive)
            fixture.assertPreserved()
        }
    }

    @Test fun exitBeforeReadinessReportsRuntimeFailureAndPreservesFiles() = runBlocking {
        Fixture().use { fixture ->
            fixture.child("exit")
            val failure = runCatching { fixture.client.installModels() }.exceptionOrNull()
            assertTrue(failure!!.message.orEmpty().contains("stopped before it became ready (exit 7)"))
            assertTrue(failure.message.orEmpty().contains("fixture runtime could not load"))
            fixture.assertPreserved()
        }
    }

    private class Fixture(createExecutable: Boolean = true) : AutoCloseable {
        val root = Files.createTempDirectory("speaker-startup-test-")
        private val worker = Files.createDirectory(root.resolve("worker"))
        private val models = Files.createDirectory(root.resolve("models"))
        private val jobs = root.resolve("jobs")
        val executable = worker.resolve("speaker-worker.exe")
        val audio = root.resolve("meeting.wav").also { Files.writeString(it, "original recording") }
        val client = DiarizationClient(worker, models, jobs)
        val launched = CompletableDeferred<Unit>()
        var process: Process? = null
        init {
            if (createExecutable) Files.writeString(executable, "test-only launch marker")
            Files.writeString(models.resolve("ready.json"), "existing model marker")
            Files.writeString(models.resolve("segmentation.onnx"), "existing model")
        }
        fun child(mode: String) {
            // A JVM fixture is portable to Windows and exercises real Process/pipe
            // cancellation without scripts or a test-specific native executable.
            val java = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val classpath = listOf(WorkerProtocolFixture::class.java, Unit::class.java)
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(File.pathSeparator)
            client.launchWorker = { _, directory ->
                ProcessBuilder(java.toString(), "-cp", classpath, WorkerProtocolFixture::class.java.name, mode)
                    .directory(directory.toFile()).start().also { process = it; launched.complete(Unit) }
            }
        }
        fun assertPreserved() {
            assertEquals("original recording", Files.readString(audio))
            assertEquals("existing model marker", Files.readString(models.resolve("ready.json")))
            assertEquals("existing model", Files.readString(models.resolve("segmentation.onnx")))
            if (Files.exists(jobs)) Files.list(jobs).use { assertEquals(0L, it.count()) }
        }
        override fun close() {
            process?.let { if (it.isAlive) { it.destroyForcibly(); it.waitFor() } }
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/** Test-only child process used by WorkerStartupTest on Windows and Unix. */
object WorkerProtocolFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "ready", "incompatible" -> {
                println("{\"event\":\"ready\",\"protocolVersion\":${if (args[0] == "ready") 1 else 2}}")
                println("{\"event\":\"progress\",\"stage\":\"analyzing\",\"fraction\":0.1,\"message\":\"Working\"}")
                System.out.flush()
            }
            "exit" -> { System.err.println("fixture runtime could not load"); kotlin.system.exitProcess(7) }
        }
        Thread.sleep(60_000)
    }
}
