package com.sainadh.livenotes.desktop.speakers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files

class DiarizationClientTest {
    @Test fun cancelledNativeJobStopsAndPreservesRecording() = runBlocking {
        // Exercises real process cancellation on Unix test runners. The Windows package
        // is also exercised with actual ONNX models by speaker-worker/smoke_test.py.
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("speaker-client-test-")
        try {
            val worker = root.resolve("speaker-worker.exe")
            Files.writeString(worker, """
                |#!/bin/sh
                |printf '%s' "${'$'}${'$'}" > "${'$'}(dirname "${'$'}0")/pid"
                |printf '%s\n' '{"event":"ready","protocolVersion":1}'
                |printf '%s\n' '{"event":"progress","stage":"analyzing","fraction":0.1,"message":"Working"}'
                |while :; do sleep 1; done
                |
            """.trimMargin())
            assertTrue(worker.toFile().setExecutable(true))
            val audio = root.resolve("recording.wav")
            Files.write(audio, byteArrayOf(1, 2, 3))
            val started = CompletableDeferred<Unit>()
            val jobs = root.resolve("jobs")
            val client = DiarizationClient(root, root.resolve("models"), jobs)
            val task = launch { client.analyze(audio) { if (it.stage == "analyzing") started.complete(Unit) } }
            withTimeout(5_000) { started.await() }
            val pid = Files.readString(root.resolve("pid")).toLong()
            withTimeout(5_000) { task.cancelAndJoin() }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertEquals(3L, Files.size(audio))
            Files.list(jobs).use { assertEquals(0L, it.count()) }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}
