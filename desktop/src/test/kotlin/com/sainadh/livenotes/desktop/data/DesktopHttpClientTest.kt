package com.sainadh.livenotes.desktop.data

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class DesktopHttpClientTest {
    private val model = "GGUFverified-local-tls-model".toByteArray()
    private val direct = object : ProxySelector() {
        override fun select(uri: URI) = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    }

    @Test fun combinedTrustValidatesRealCertificateChainsWithoutNetwork() {
        val client = client()
        val trust = requireNotNull(client.x509TrustManager)
        for (authority in listOf("standard", "windows")) {
            val certificates = certificates(authority)
            trust.checkServerTrusted(arrayOf(certificates[1], certificates[0]), "RSA")
        }
        val unknown = certificates("untrusted")
        assertTrue(runCatching { trust.checkServerTrusted(arrayOf(unknown[1], unknown[0]), "RSA") }.exceptionOrNull() is java.security.cert.CertificateException)
        client.connectionPool.evictAll()
    }

    @Test fun missingWindowsProviderKeepsValidationEnabledWithoutNetwork() {
        val client = DesktopHttpClient.create(trust("standard"), { throw KeyStoreException("native provider missing") }, direct)
        val trust = requireNotNull(client.x509TrustManager)
        val standard = certificates("standard")
        trust.checkServerTrusted(arrayOf(standard[1], standard[0]), "RSA")
        val unknown = certificates("windows")
        assertTrue(runCatching { trust.checkServerTrusted(arrayOf(unknown[1], unknown[0]), "RSA") }.exceptionOrNull() is java.security.cert.CertificateException)
        client.connectionPool.evictAll()
    }

    @Test fun bothStandardAndWindowsAuthoritiesPermitVerifiedModelDownloads() = runBlocking {
        val client = client()
        for (authority in listOf("standard", "windows")) withHttps(authority) { server ->
            val root = Files.createTempDirectory("trusted-model-").toFile()
            try {
                val file = ModelStore(root, client).download(spec("https://localhost:${server.address.port}/model")) { _, _ -> }
                assertArrayEquals(model, file.readBytes())
            } finally { root.deleteRecursively() }
        }
        client.connectionPool.evictAll()
    }

    @Test fun inaccessibleWindowsStoreKeepsStandardTrustAndRejectsUnknownAuthority() {
        val client = DesktopHttpClient.create(trust("standard"), { throw KeyStoreException("native provider missing") }, direct)
        withHttps("standard") { server ->
            client.newCall(Request.Builder().url("https://localhost:${server.address.port}/").build()).execute().use {
                assertEquals(200, it.code)
            }
        }
        withHttps("windows") { server ->
            assertTrue(runCatching { client.newCall(Request.Builder().url("https://localhost:${server.address.port}/").build()).execute().close() }.isFailure)
        }
        client.connectionPool.evictAll()
    }

    @Test fun untrustedCertificateAndWrongHostnameDoNotPublishOrErasePartialModel() = runBlocking {
        for ((authority, host) in listOf("untrusted" to "localhost", "windows" to "127.0.0.1")) withHttps(authority) { server ->
            val root = Files.createTempDirectory("untrusted-model-").toFile()
            val partial = java.io.File(root, "test.gguf.part").apply { writeBytes(model.copyOfRange(0, 8)) }
            val client = client()
            try {
                val failure = runCatching { ModelStore(root, client).download(spec("https://$host:${server.address.port}/model")) { _, _ -> } }.exceptionOrNull()
                assertNotNull(failure)
                assertTrue(failure!!.message.orEmpty().contains("secure connection"))
                assertTrue(failure.message.orEmpty().contains("Import in Settings"))
                assertFalse(java.io.File(root, "test.gguf").exists())
                assertArrayEquals(model.copyOfRange(0, 8), partial.readBytes())
            } finally { root.deleteRecursively(); client.connectionPool.evictAll() }
        }
    }

    @Test fun defaultProxySelectorIsUsedAndAuthenticationFailureExplainsBrowserImport() = runBlocking {
        val proxy = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val request = executor.submit<List<String>> {
            proxy.accept().use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream().bufferedReader()
                val lines = mutableListOf<String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    check(lines.size < 100)
                    lines += line
                }
                socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"office\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                lines
            }
        }
        val selector = object : ProxySelector() {
            override fun select(uri: URI) = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.localPort)))
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }
        val previous = ProxySelector.getDefault()
        val root = Files.createTempDirectory("proxy-model-").toFile()
        try {
            ProxySelector.setDefault(selector)
            val client = DesktopHttpClient.create(trust("standard"), null)
            val failure = runCatching { ModelStore(root, client).download(spec("https://model.test/model")) { _, _ -> } }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("proxy requires sign-in (HTTP 407)"))
            assertTrue(failure.message.orEmpty().contains("Import in Settings"))
            val headers = request.get(5, TimeUnit.SECONDS)
            assertEquals("CONNECT model.test:443 HTTP/1.1", headers.first())
            assertFalse(headers.any { it.startsWith("Proxy-Authorization:", ignoreCase = true) })
            assertFalse(java.io.File(root, "test.gguf").exists())
            client.connectionPool.evictAll()
        } finally { ProxySelector.setDefault(previous); proxy.close(); executor.shutdownNow(); root.deleteRecursively() }
    }

    private fun client(): OkHttpClient = DesktopHttpClient.create(trust("standard"), { trust("windows") }, direct)
        .newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

    private fun spec(url: String) = ModelDownloadSpec("test.gguf", url, model.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) })

    private fun trust(name: String): X509TrustManager = DesktopHttpClient.trustManager(KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setCertificateEntry(name, certificates(name).first())
    })

    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/tls/$name.pem")).bufferedReader().use { it.readText() }
    private fun certificates(name: String): List<X509Certificate> = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")
        .findAll(fixture(name)).map { CertificateFactory.getInstance("X.509").generateCertificate(it.value.byteInputStream()) as X509Certificate }.toList()

    private inline fun <T> withHttps(name: String, test: (HttpsServer) -> T): T {
        val pem = Regex("-----BEGIN PRIVATE KEY-----([\\s\\S]+?)-----END PRIVATE KEY-----").find(fixture(name))!!.groupValues[1]
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(pem)))
        val certificates = certificates(name)
        val password = "fixture-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", key, password, arrayOf(certificates[1], certificates[0]))
        }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(context)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, model.size.toLong())
            exchange.responseBody.use { it.write(model) }
            exchange.close()
        }
        server.start()
        try { return test(server) } finally { server.stop(0) }
    }
}
