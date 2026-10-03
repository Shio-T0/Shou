package io.github.shiot0.shou

import android.content.Context
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Tiny HTTP client for the active Shou server. Every control in the app routes through
 * here (the remote screen, the media-session buttons, the widget, the Quick Settings
 * tile), speaking the same token-gated endpoints the web remote uses (POST /pause, …).
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

    /** POST a form (secrets go in the body, never the URL) and return the HTTP status
     *  with the JSON reply; status -1 when the PC couldn't be reached at all. */
    fun postForm(ctx: Context, path: String, form: Map<String, String>): Pair<Int, JSONObject?> {
        val base = ShouStore.activeBaseUrl(ctx) ?: return -1 to null
        val body = form.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }.toByteArray()
        return try {
            open(ctx, "$base/$path?k=${enc(ShouStore.activeToken(ctx))}", "POST", bodyLength = body.size)!!.use { conn ->
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                code to text?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
        } catch (e: Exception) {
            -1 to null
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

    /** Probe a candidate address with the unauthenticated /whoami. Returns the server's
     *  identity (name, host, ip, port) if a Shou server answers there, else null. */
    fun whoami(ctx: Context, host: String, port: String): JSONObject? {
        if (host.isBlank()) return null
        val scheme = if (ShouStore.https(ctx)) "https" else "http"
        return try {
            open(ctx, "$scheme://$host:$port/whoami", "GET", timeoutMs = 2500)?.use { conn ->
                if (conn.responseCode !in 200..299) return null
                val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                o.takeIf { it.optString("app") == "shou" }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** An OkHttp client that accepts a self-signed certificate, for the Socket.IO link
     *  when the user opted in (Settings → Allow self-signed certificates). */
    fun relaxedOkHttp(): OkHttpClient? = try {
        val tm = TrustAll()
        val sc = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(sc.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    } catch (e: Exception) {
        null
    }

    private class TrustAll : X509TrustManager {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun open(
        ctx: Context, url: String, method: String, timeoutMs: Int = 4000, bodyLength: Int = 0,
    ): HttpURLConnection? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs + 1000
        conn.useCaches = false
        if (conn is HttpsURLConnection && ShouStore.allowBadCerts(ctx)) relaxTls(conn)
        if (method == "POST") {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bodyLength)
        }
        return conn
    }

    /** Trust a self-signed cert only when the user opted in (Settings → Allow self-signed). */
    private fun relaxTls(conn: HttpsURLConnection) {
        try {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(TrustAll())
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
