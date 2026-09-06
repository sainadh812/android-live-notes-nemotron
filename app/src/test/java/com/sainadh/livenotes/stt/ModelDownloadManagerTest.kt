package com.sainadh.livenotes.stt

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ModelDownloadManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val model = SpeechModel.MOONSHINE_TINY

    @Test fun acceptsOnlyTheRequestedRangeAndImmutableTotal() {
        assertEquals(1000L, validateModelContentRange("bytes 600-999/1000", 600, 1000, 400))
        assertEquals(800L, validateModelContentRange("bytes 600-799/1000", 600, 1000, -1))
        listOf(null, "bytes 0-399/1000", "bytes 600-999/1001", "bytes 600-1000/1000",
            "bytes 600-599/1000", "bytes 600-999/*", "bytes 600-999/999999999999999999999999").forEach {
            assertThrows(IOException::class.java) { validateModelContentRange(it, 600, 1000, 400) }
        }
        assertThrows(IOException::class.java) {
            validateModelContentRange("bytes 600-999/1000", 600, 1000, 399)
        }
    }

    @Test fun rejectsSameSizeCorruptionBeyondTheGgufHeader() {
        val file = temporaryFolder.newFile()
        val bytes = "GGUFcorrect model bytes".toByteArray()
        file.writeBytes(bytes)
        verifyModelFile(file, bytes.size.toLong(), sha256(bytes))
        val corrupted = bytes.copyOf().also { it[it.lastIndex] = 0 }
        file.writeBytes(corrupted)
        assertThrows(IOException::class.java) { verifyModelFile(file, bytes.size.toLong(), sha256(bytes)) }
    }

    @Test fun rejectsTruncationOversizeAndNonGgufFilesEvenWithMatchingDigest() {
        val file = temporaryFolder.newFile()
        val bytes = "GGUFmodel".toByteArray()
        file.writeBytes(bytes)
        assertThrows(IOException::class.java) { verifyModelFile(file, bytes.size + 1L, sha256(bytes)) }
        assertThrows(IOException::class.java) { verifyModelFile(file, bytes.size - 1L, sha256(bytes)) }
        val errorPage = "<html>not a model</html>".toByteArray()
        file.writeBytes(errorPage)
        assertThrows(IOException::class.java) {
            verifyModelFile(file, errorPage.size.toLong(), sha256(errorPage))
        }
    }

    @Test fun mismatchedResumeResponsePreservesPartialWithoutPublishing() {
        val directory = temporaryFolder.newFolder()
        val previous = "GGUFpartial".toByteArray()
        val partial = File(directory, "${model.fileName}.part").apply { writeBytes(previous) }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("bytes=${previous.size}-", chain.request().header("Range"))
            response(chain.request(), 206, "bad".toResponseBody(),
                "bytes 0-2/${model.expectedBytes}")
        }.build()
        val manager = ModelDownloadManager(directory, client)
        manager.download(model)
        assertTrue(manager.state.value is ModelDownloadState.Failed)
        assertArrayEquals(previous, partial.readBytes())
        assertFalse(manager.modelFile(model).exists())
        assertTrue(manager.downloadedModels.value.isEmpty())
    }

    @Test fun repeated416StopsAfterOneCleanRetry() {
        val directory = temporaryFolder.newFolder()
        val partial = File(directory, "${model.fileName}.part").apply { writeText("GGUFpartial") }
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            if (requests.incrementAndGet() == 1) assertNotNull(chain.request().header("Range"))
            else assertNull(chain.request().header("Range"))
            response(chain.request(), 416, "".toResponseBody())
        }.build()
        val manager = ModelDownloadManager(directory, client)
        manager.download(model)
        assertEquals(2, requests.get())
        assertTrue(manager.state.value is ModelDownloadState.Failed)
        assertFalse(partial.exists())
        assertFalse(manager.modelFile(model).exists())
    }

    @Test fun disconnectedTransferKeepsBytesForRetry() {
        val directory = temporaryFolder.newFolder()
        val prefix = "GGUFdownloaded prefix".toByteArray()
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = model.expectedBytes
            override fun source(): BufferedSource = object : Source {
                private var sent = false
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (sent) throw IOException("Connection interrupted")
                    sent = true
                    sink.write(prefix)
                    return prefix.size.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            }.buffer()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, body)
        }.build()
        val manager = ModelDownloadManager(directory, client)
        manager.download(model)
        assertTrue(manager.state.value is ModelDownloadState.Failed)
        assertArrayEquals(prefix, File(directory, "${model.fileName}.part").readBytes())
        assertFalse(manager.modelFile(model).exists())
    }

    @Test fun concurrentDownloadCannotReplaceTheActiveTarget() {
        val directory = temporaryFolder.newFolder()
        val otherPartial = File(directory, "${SpeechModel.NEMOTRON_Q8.fileName}.part")
            .apply { writeText("GGUFpartial") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            response(chain.request(), 503, "".toResponseBody())
        }.build()
        val manager = ModelDownloadManager(directory, client)
        val worker = Thread { manager.download(model) }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            manager.download(SpeechModel.NEMOTRON_Q8)
            manager.delete(SpeechModel.NEMOTRON_Q8)
            assertTrue(otherPartial.exists())
            assertEquals(model, manager.downloadTarget.value)
            assertEquals(1, requests.get())
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertTrue(manager.state.value is ModelDownloadState.Failed)
    }

    @Test fun installedModelsFlowRefreshesAfterDeletionAndKeepsLegacyPreference() {
        val directory = temporaryFolder.newFolder()
        listOf(SpeechModel.NEMOTRON_Q6, SpeechModel.NEMOTRON_Q4, model).forEach { installed ->
            java.io.RandomAccessFile(File(directory, installed.fileName), "rw").use {
                it.write("GGUF".toByteArray())
                it.setLength(installed.expectedBytes)
            }
        }
        val manager = ModelDownloadManager(directory, OkHttpClient())
        assertEquals(setOf(SpeechModel.NEMOTRON_Q6, SpeechModel.NEMOTRON_Q4, model), manager.downloadedModels.value)
        assertEquals(SpeechModel.NEMOTRON_Q6, manager.findAnyDownloaded())
        manager.delete(SpeechModel.NEMOTRON_Q6)
        assertEquals(setOf(SpeechModel.NEMOTRON_Q4, model), manager.downloadedModels.value)
        assertEquals(SpeechModel.NEMOTRON_Q4, manager.findAnyDownloaded())
    }

    @Test fun captureLeaseBlocksDeletionUntilEveryOwnerReleases() {
        val directory = temporaryFolder.newFolder()
        val installed = makeInstalledFile(directory, model)
        val manager = ModelDownloadManager(directory, OkHttpClient())
        assertTrue(manager.acquireForCapture(model))
        assertTrue(manager.acquireForCapture(model))
        manager.delete(model)
        assertTrue(installed.exists())
        manager.releaseFromCapture(model)
        manager.delete(model)
        assertTrue(installed.exists())
        manager.releaseFromCapture(model)
        manager.delete(model)
        assertFalse(installed.exists())
        assertFalse(manager.downloadedModels.value.contains(model))
    }

    @Test fun missingOrIncompleteModelCannotAcquireCaptureLease() {
        val directory = temporaryFolder.newFolder()
        val manager = ModelDownloadManager(directory, OkHttpClient())
        assertFalse(manager.acquireForCapture(model))
        manager.modelFile(model).writeText("GGUFincomplete")
        assertFalse(manager.acquireForCapture(model))
        manager.releaseFromCapture(model)
        manager.delete(model)
        assertFalse(manager.modelFile(model).exists())
    }

    @Test fun repairCannotRemoveAnActiveModelOrStartNetworkWork() {
        val directory = temporaryFolder.newFolder()
        // Correct size/container but deliberately wrong digest: this would be repaired when idle.
        val installed = makeInstalledFile(directory, model)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            response(chain.request(), 503, "".toResponseBody())
        }.build()
        val manager = ModelDownloadManager(directory, client)
        assertTrue(manager.acquireForCapture(model))
        try {
            manager.download(model)
            assertTrue((manager.state.value as ModelDownloadState.Failed).message.contains("Stop recording"))
            assertEquals(0, requests.get())
            assertTrue(installed.exists())
            assertEquals(model.expectedBytes, installed.length())
            assertTrue(manager.downloadedModels.value.contains(model))
        } finally {
            manager.releaseFromCapture(model)
        }
    }

    private fun makeInstalledFile(directory: File, model: SpeechModel): File =
        File(directory, model.fileName).also { file ->
            java.io.RandomAccessFile(file, "rw").use {
                it.write("GGUF".toByteArray())
                it.setLength(model.expectedBytes)
            }
        }

    private fun response(request: okhttp3.Request, code: Int, body: ResponseBody, range: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body).apply { if (range != null) header("Content-Range", range) }.build()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
