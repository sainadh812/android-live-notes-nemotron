package com.sainadh.livenotes.desktop.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class ModelStoreTest {
    private val bytes = "GGUFversion-and-model-test-data".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun validDownloadIsPublishedOnlyAfterHashVerification() = runBlocking {
        withServer { root, server, store ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val file = store.download(spec(server)) { _, _ -> }
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(java.io.File(root, "test.gguf.part").exists())
        }
    }
    @Test fun partialDownloadResumesAtVerifiedOffset() = runBlocking {
        withServer { root, server, store ->
            java.io.File(root, "test.gguf.part").writeBytes(bytes.take(8).toByteArray())
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-${bytes.lastIndex}/${bytes.size}")
                .setBody(Buffer().write(bytes.copyOfRange(8, bytes.size))))
            assertArrayEquals(bytes, store.download(spec(server)) { _, _ -> }.readBytes())
            assertEquals("bytes=8-", server.takeRequest().getHeader("Range"))
        }
    }
    @Test fun wrongRangeOrChecksumNeverPublishesModel() = runBlocking {
        withServer { root, server, store ->
            java.io.File(root, "test.gguf.part").writeBytes(bytes.take(8).toByteArray())
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-${bytes.lastIndex}/${bytes.size}").setBody(Buffer().write(bytes)))
            assertTrue(runCatching { store.download(spec(server)) { _, _ -> } }.isFailure)
            assertFalse(java.io.File(root, "test.gguf").exists())
            server.enqueue(MockResponse().setBody(Buffer().write(bytes.reversedArray())))
            assertTrue(runCatching { store.download(spec(server)) { _, _ -> } }.isFailure)
            assertFalse(java.io.File(root, "test.gguf").exists())
            assertFalse(java.io.File(root, "test.gguf.part").exists())
        }
    }
    private fun spec(server: MockWebServer) = ModelDownloadSpec("test.gguf", server.url("/model").toString(), bytes.size.toLong(), hash)
    private suspend fun withServer(test: suspend (java.io.File, MockWebServer, ModelStore) -> Unit) {
        val root = Files.createTempDirectory("desktop-model-test-").toFile()
        MockWebServer().use { server ->
            server.start()
            try { test(root, server, ModelStore(root)) } finally { root.deleteRecursively() }
        }
    }
}
