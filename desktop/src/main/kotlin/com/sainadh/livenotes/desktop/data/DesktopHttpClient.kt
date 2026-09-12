package com.sainadh.livenotes.desktop.data

import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ProxySelector
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/** Honors the desktop's proxy selection and Windows-installed trusted authorities. */
object DesktopHttpClient {
    fun create(): OkHttpClient = create(
        standardTrustManager(),
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ::windowsTrustManager else null,
    )

    internal fun create(
        standardTrust: X509TrustManager,
        windowsTrust: (() -> X509TrustManager)?,
        proxySelector: ProxySelector? = ProxySelector.getDefault(),
    ): OkHttpClient {
        // A missing native provider/store must never disable certificate checking.
        // Standard public CAs continue to work if Windows certificate access fails.
        val nativeTrust = try { windowsTrust?.invoke() } catch (_: GeneralSecurityException) { null }
            catch (_: IOException) { null } catch (_: SecurityException) { null }
        val trust = if (nativeTrust == null) standardTrust else CombinedTrustManager(listOf(standardTrust, nativeTrust))
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(context.socketFactory, trust)
            .apply { if (proxySelector != null) proxySelector(proxySelector) }
            // Retain OkHttp's hostname verifier and authentication defaults. Windows
            // proxy discovery is enabled at JVM startup by java.net.useSystemProxies.
            .build()
    }

    /** The installed-launcher smoke check must exercise the bundled MSCAPI module. */
    internal fun verifyWindowsSupport() {
        check(System.getProperty("java.net.useSystemProxies").toBoolean()) { "System proxy discovery is not enabled" }
        check(windowsTrustManager().acceptedIssuers.isNotEmpty()) { "Windows trusted certificate store is empty" }
        create().connectionPool.evictAll()
    }

    private fun standardTrustManager(): X509TrustManager = trustManager(null)
    private fun windowsTrustManager(): X509TrustManager = trustManager(KeyStore.getInstance("Windows-ROOT").apply { load(null, null) })
    internal fun trustManager(store: KeyStore?): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }.trustManagers.filterIsInstance<X509TrustManager>().single()
}

/** A chain must pass one complete platform validation; exceptions never mean trust. */
private class CombinedTrustManager(private val delegates: List<X509TrustManager>) : X509ExtendedTrustManager() {
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegates.flatMap { it.acceptedIssuers.toList() }.distinct().toTypedArray()
    private inline fun validate(check: (X509TrustManager) -> Unit) {
        var rejected: CertificateException? = null
        for (delegate in delegates) {
            try { check(delegate); return } catch (failure: CertificateException) {
                if (rejected == null) rejected = failure else rejected.addSuppressed(failure)
            }
        }
        throw rejected ?: CertificateException("No certificate trust provider is available")
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = validate { it.checkServerTrusted(chain, authType) }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = validate { it.checkClientTrusted(chain, authType) }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = validate {
        if (it is X509ExtendedTrustManager) it.checkServerTrusted(chain, authType, socket) else it.checkServerTrusted(chain, authType)
    }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) = validate {
        if (it is X509ExtendedTrustManager) it.checkClientTrusted(chain, authType, socket) else it.checkClientTrusted(chain, authType)
    }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = validate {
        if (it is X509ExtendedTrustManager) it.checkServerTrusted(chain, authType, engine) else it.checkServerTrusted(chain, authType)
    }
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) = validate {
        if (it is X509ExtendedTrustManager) it.checkClientTrusted(chain, authType, engine) else it.checkClientTrusted(chain, authType)
    }
}

internal fun modelHttpFailure(code: Int): IOException = IOException(when (code) {
    407 -> "The office proxy requires sign-in (HTTP 407). Open the GitHub model download in your browser, then use Import in Settings."
    403, 451 -> "The model download was denied (HTTP $code). Open the GitHub model download in your browser, then use Import in Settings."
    else -> "Model download failed (HTTP $code). Retry, or download from GitHub in your browser and use Import in Settings."
})

internal fun modelNetworkFailure(failure: IOException): IOException {
    // HTTPS proxy CONNECT responses are consumed by OkHttp before ModelStore can
    // inspect a Response. Preserve the same recovery hint for that route.
    if (failure.message == "Failed to authenticate with proxy") {
        return IOException(modelHttpFailure(407).message, failure)
    }
    Regex("Unexpected response code for CONNECT: (\\d{3})").matchEntire(failure.message.orEmpty())?.let {
        return IOException("The office proxy denied the secure model connection (HTTP ${it.groupValues[1]}). Open the GitHub model download in your browser, then use Import in Settings.", failure)
    }
    if (generateSequence<Throwable>(failure) { it.cause }.take(16).any { it is SSLException || it is CertificateException }) {
        return IOException("A secure connection to the model download could not be verified. Check your office certificate/proxy settings, or download from GitHub in your browser and use Import in Settings.", failure)
    }
    return failure
}
