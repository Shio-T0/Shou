package io.github.shiot0.shou

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Tiny HTTP client for talking to the active Shou server from the background — the
 * media-session transport buttons, the widget, and the Quick Settings tile all route
 * through here, so they keep working when the WebView isn't in front. It speaks the
 * same token-gated control endpoints the web remote uses (POST /pause, /next, …).
 */
object ServerClient {

    /** Fire a POST control command (e.g. "pause", "next", "volume") at the active server.
     *  Best-effort and non-blocking-safe: callers should invoke it off the main thread. */
    fun command(ctx: Context, path: String, params: Map<String, String> = emptyMap()): Boolean {
        val base = ShouStore.activeBaseUrl(ctx) ?: return false
        val token = ShouStore.activeToken(ctx)
        val query = StringBuilder("?k=").append(enc(token))
        for ((k, v) in params) query.append('&').append(enc(k)).append('=').append(enc(v))
        val url = "$base/$path$query"
        return try {
            open(ctx, url, "POST")?.use { it.responseCode in 200..299 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** GET the (token-gated) /airing feed as a JSON string, or null on failure. */
    fun airing(ctx: Context): String? {
        val base = ShouStore.activeBaseUrl(ctx) ?: return null
        val url = "$base/airing?k=${enc(ShouStore.activeToken(ctx))}"
        return try {
            open(ctx, url, "GET")?.use { conn ->
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun open(ctx: Context, url: String, method: String): HttpURLConnection? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4000
        conn.readTimeout = 5000
        conn.useCaches = false
        if (conn is HttpsURLConnection && ShouStore.allowBadCerts(ctx)) relaxTls(conn)
        if (method == "POST") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
        }
        return conn
    }

    /** Trust a self-signed cert only when the user opted in (Settings → Allow self-signed).
     *  Mirrors the WebView's onReceivedSslError choice for these direct HTTP calls. */
    private fun relaxTls(conn: HttpsURLConnection) {
        try {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sc = SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
            conn.sslSocketFactory = sc.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            // fall back to strict verification
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }
}
