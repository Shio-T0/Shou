package io.github.shiot0.shou

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** What the bridge needs back from the Activity that owns the WebView. */
interface BridgeHost {
    val ctx: Context
    /** Run a snippet of JS in the WebView (marshalled to the UI thread). */
    fun evalJs(js: String)
}

/**
 * The seam between the web remote (the single source of truth for servers + live state)
 * and the native features that can't live in a web page: Wake-on-LAN, the lock-screen
 * media controls, the home-screen widget, the Quick Settings tile, per-server shortcuts,
 * and system notifications.
 *
 * Exposed to JS as `window.ShouNative`. The web side feature-detects it and, when present,
 * mirrors its remotes set and playback state down here and calls native actions up here.
 * All methods run on a WebView binder thread, so anything touching the WebView is bounced
 * back to the UI thread via [BridgeHost.evalJs].
 */
class ShouBridge(private val host: BridgeHost) {

    private val ctx: Context get() = host.ctx
    private var scanner: NsdScanner? = null

    /** Lets JS confirm it's running inside the native app (vs a plain browser). */
    @JavascriptInterface
    fun version(): Int = 1

    /** Mirror the web remote's whole saved-servers set (each carries its key + MAC). */
    @JavascriptInterface
    fun syncRemotes(json: String) {
        ShouStore.setRemotes(ctx, json)
        Shortcuts.publish(ctx)
        ShouWidgetProvider.refresh(ctx)
    }

    /** Mark which server this WebView is currently driving, so background features
     *  (media session, widget, WOL) target the same PC the user is looking at. */
    @JavascriptInterface
    fun setActive(token: String, host: String, port: String, name: String) {
        ShouStore.setActive(ctx, token, host, port, name)
        ShouWidgetProvider.refresh(ctx)
        ShouTileService.refresh(ctx)
    }

    /** Live kiosk state, pushed each SocketIO tick. Drives the media notification. */
    @JavascriptInterface
    fun playback(json: String) {
        val p = parsePlayback(json)
        ShouStore.setPlayback(ctx, p)
        PlaybackController.update(ctx, p)
        ShouWidgetProvider.refresh(ctx)
        ShouTileService.refresh(ctx)
    }

    /** Wake a saved server by its key (or the active one if blank). True if a packet went out. */
    @JavascriptInterface
    fun wake(token: String): Boolean {
        val mac = if (token.isBlank()) ShouStore.activeMac(ctx)
        else ShouStore.remoteByToken(ctx, token)?.mac.orEmpty()
        if (mac.isBlank()) return false
        return Wol.wake(mac)
    }

    /** Start an mDNS scan; resolved servers are pushed back to `window.shouOnScan(json)`. */
    @JavascriptInterface
    fun scan() {
        scanner?.stop()
        val s = NsdScanner(ctx)
        scanner = s
        val results = JSONArray()
        s.start(
            timeoutMs = 6000,
            onFound = { r ->
                results.put(JSONObject().apply {
                    put("name", r.name); put("host", r.host); put("port", r.port)
                })
                push("window.shouOnScan && window.shouOnScan(${quote(results.toString())}, false)")
            },
            onDone = { push("window.shouOnScan && window.shouOnScan(${quote(results.toString())}, true)") },
        )
    }

    /** Post a system notification for a kiosk event (e.g. an episode finishing). */
    @JavascriptInterface
    fun notify(kind: String, title: String, body: String) {
        Notifications.event(ctx, kind, title, body)
    }

    private fun push(js: String) = host.evalJs(js)

    private fun parsePlayback(json: String): Playback? = try {
        val o = JSONObject(json)
        if (!o.optBoolean("active", false)) null
        else Playback(
            active = true,
            playing = o.optBoolean("playing", true),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            cover = o.optString("cover"),
            positionMs = (o.optDouble("position", 0.0) * 1000).toLong(),
            durationMs = (o.optDouble("duration", 0.0) * 1000).toLong(),
        )
    } catch (e: Exception) {
        null
    }

    /** JSON-string-quote a value so it can be embedded as a JS string literal. */
    private fun quote(s: String): String = JSONObject.quote(s)

    companion object {
        const val NAME = "ShouNative"
    }
}
