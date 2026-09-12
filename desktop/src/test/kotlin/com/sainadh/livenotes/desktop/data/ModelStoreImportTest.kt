package com.sainadh.livenotes.desktop.data

import com.sainadh.livenotes.stt.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class ModelStoreImportTest {
    private val bytes = ByteArray(3 * 128 * 1024 + 17) { (it % 251).toByte() }.also {
        "GGUF".toByteArray().copyInto(it)
    }
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val spec = ModelDownloadSpec("test.gguf",
        "https://github.com/sainadh812/android-live-notes-nemotron/releases/download/models-v1/test.gguf",
        bytes.size.toLong(), hash)

    @Test fun githubMappingsKeepEveryPinnedModelSizeAndChecksum() {
        SpeechModel.entries.forEach { model ->
            val actual = ModelSources.downloadSpec(model)
            assertEquals("https://github.com/sainadh812/android-live-notes-nemotron/releases/download/models-v1/${model.fileName}", actual.url)
            assertEquals(model.fileName, actual.name)
            assertEquals(model.expectedBytes, actual.bytes)
            assertEquals(model.sha256, actual.sha256)
            assertTrue(model.downloadUrl.startsWith("https://huggingface.co/"))
        }
        assertEquals("https://github.com/sainadh812/android-live-notes-nemotron/releases/tag/models-v1", ModelSources.RELEASE_PAGE)
    }

    @Test fun validImportReplacesDamagedModelAndKeepsSourceAndDownloadPartial() = runBlocking {
        withStore { _, installed, source, store ->
            installed.writeText("damaged old model")
            val partial = File(installed.parentFile, "${spec.name}.part").apply { writeText("resumable download") }
            val foreign = File(installed.parentFile, "${spec.name}.import-other.tmp").apply { writeText("other import") }
            val updates = mutableListOf<Pair<Float, String>>()
            assertEquals(installed, store.importModel(spec, source) { fraction, text -> updates += fraction to text })
            assertArrayEquals(bytes, installed.readBytes())
            assertArrayEquals(bytes, source.readBytes())
            assertEquals("resumable download", partial.readText())
            assertEquals("other import", foreign.readText())
            assertEquals(listOf(foreign.name, partial.name, installed.name).sorted(), installed.parentFile.list()!!.sorted())
            assertTrue(updates.count { it.second.startsWith("Importing ") && it.first > 0f } >= 3)
            assertTrue(updates.all { it.first in 0f..1f })
            assertEquals(1f to "Ready", updates.last())
        }
    }

    @Test fun defaultDesktopDownloadRequestsGitHubMirror() = runBlocking {
        withStore { _, installed, _, _ ->
            var requestedUrl: String? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requestedUrl = chain.request().url.toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(503).message("Synthetic response").body(ByteArray(0).toResponseBody()).build()
            }.build()
            val store = ModelStore(installed.parentFile, client)
            val model = SpeechModel.MOONSHINE_TINY
            assertTrue(runCatching { store.download(model) { _, _ -> } }.isFailure)
            assertEquals(ModelSources.downloadUrl(model), requestedUrl)
        }
    }

    @Test fun wrongSizeHeaderOrChecksumPreservesValidInstalledModel() = runBlocking {
        withStore { _, installed, source, store ->
            installed.writeBytes(bytes)
            val variants = listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().also { it[0] = 0 },
                bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() })
            variants.forEach { wrong ->
                source.writeBytes(wrong)
                assertTrue(runCatching { store.importModel(spec, source) { _, _ -> } }.isFailure)
                assertArrayEquals(bytes, installed.readBytes())
                assertArrayEquals(wrong, source.readBytes())
                assertEquals(listOf(installed.name), installed.parentFile.list()!!.toList())
            }
        }
    }

    @Test fun selectingInstalledModelVerifiesWithoutStagingOrReplacingIt() = runBlocking {
        withStore { _, installed, _, store ->
            installed.writeBytes(bytes)
            val modified = installed.lastModified()
            val updates = mutableListOf<String>()
            assertEquals(installed, store.importModel(spec, installed) { _, text -> updates += text })
            assertEquals(modified, installed.lastModified())
            assertEquals(listOf("Verifying model…", "Ready"), updates)
            assertEquals(listOf(installed.name), installed.parentFile.list()!!.toList())
            val corrupt = bytes.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            installed.writeBytes(corrupt)
            assertTrue(runCatching { store.importModel(spec, installed) { _, _ -> } }.isFailure)
            assertArrayEquals(corrupt, installed.readBytes())
        }
    }

    @Test fun cancellationDuringCopyOrBeforePublicationCleansOnlyOwnStaging() = runBlocking {
        withStore { _, installed, source, store ->
            installed.writeBytes(bytes)
            val foreign = File(installed.parentFile, "${spec.name}.import-other.tmp").apply { writeText("other import") }
            for (cancelAtVerification in listOf(false, true)) {
                coroutineScope {
                    val importing = launch(Dispatchers.IO) {
                        val operation = coroutineContext.job
                        store.importModel(spec, source) { fraction, text ->
                            if ((cancelAtVerification && text == "Verifying model…") ||
                                (!cancelAtVerification && text.startsWith("Importing ") && fraction > 0f)) operation.cancel()
                        }
                        fail("Cancelled import must not complete normally")
                    }
                    importing.join()
                    assertTrue(importing.isCancelled)
                }
                assertArrayEquals(bytes, installed.readBytes())
                assertArrayEquals(bytes, source.readBytes())
                assertEquals("other import", foreign.readText())
                assertEquals(listOf(installed.name, foreign.name).sorted(), installed.parentFile.list()!!.sorted())
            }
        }
    }

    @Test fun sourceIoFailurePreservesInstalledModelAndCleansStaging() = runBlocking {
        withStore { _, installed, source, store ->
            installed.writeBytes(bytes)
            val result = runCatching {
                store.importModel(spec, source) { fraction, text ->
                    if (fraction == 0f && text == "Importing model…") Files.delete(source.toPath())
                }
            }
            assertTrue(result.exceptionOrNull() is java.io.IOException)
            assertArrayEquals(bytes, installed.readBytes())
            assertEquals(listOf(installed.name), installed.parentFile.list()!!.toList())
        }
    }

    @Test fun githubOriginKeepsExistingDownloadRangeResumption() = runBlocking {
        withStore { _, installed, _, _ ->
            File(installed.parentFile, "${spec.name}.part").writeBytes(bytes.copyOfRange(0, 8))
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                assertEquals(spec.url, chain.request().url.toString())
                assertEquals("bytes=8-", chain.request().header("Range"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content")
                    .header("Content-Range", "bytes 8-${bytes.lastIndex}/${bytes.size}")
                    .body(bytes.copyOfRange(8, bytes.size).toResponseBody()).build()
            }.build()
            val store = ModelStore(installed.parentFile, client)
            assertArrayEquals(bytes, store.download(spec) { _, _ -> }.readBytes())
            assertEquals(1, requests)
            assertFalse(File(installed.parentFile, "${spec.name}.part").exists())
        }
    }

    private suspend fun withStore(test: suspend (File, File, File, ModelStore) -> Unit) {
        val root = Files.createTempDirectory("desktop-model-import-").toFile()
        val models = File(root, "models").apply { mkdirs() }
        val source = File(root, "Downloaded 模型 🙂.gguf").apply { writeBytes(bytes) }
        try { test(root, File(models, spec.name), source, ModelStore(models)) }
        finally { root.deleteRecursively() }
    }
}
