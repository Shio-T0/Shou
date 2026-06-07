package io.github.shiot0.shou

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Native shell around the Shou web remote: a single full-screen WebView that
 *  - never lets the screen sleep (FLAG_KEEP_SCREEN_ON — the whole reason this exists),
 *  - runs immersive edge-to-edge,
 *  - points at the server URL you configure in Settings,
 *  - bridges the web remote to native superpowers (Wake-on-LAN, lock-screen media
 *    controls, the widget, the Quick Settings tile, per-server shortcuts) via
 *    [ShouBridge], and forwards the hardware volume rocker to the PC's player.
 *
 * The web remote stays the single source of truth; this app is the frame plus the
 * native muscle a web page can't reach.
 */
class MainActivity : AppCompatActivity(), BridgeHost {

    private lateinit var web: WebView
    private var loadedUrl: String? = null

    override val ctx: Context get() = applicationContext
    override fun evalJs(js: String) {
        runOnUiThread { runCatching { web.evaluateJavascript(js, null) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShouStore.init(this)
        Notifications.ensureChannels(this)
        AiringWorker.schedule(this)

        // A shortcut / widget can ask to open a specific saved server.
        applyRequestedRemote(intent)

        // Keep the screen awake the entire time the remote is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Draw into the display cutout / notch area too. Without this a fullscreen
        // (no system bars) window is letterboxed below the cutout, leaving a dark
        // strip at the top — the WebView background wouldn't reach the screen edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Edge-to-edge + immersive: hide the system bars, swipe to reveal them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B0A0E"))
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(ShouBridge(this), ShouBridge.NAME)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                if (url.scheme == "app") {                 // internal error-page links
                    when (url.host) {
                        "settings" -> openSettings()
                        "retry" -> load()
                    }
                    return true
                }
                // Shou pages stay in the WebView; anything else (external links) opens
                // in the system browser.
                if (url.host != null && url.host == configuredHost()) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) showError()
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                // Shou over self-signed TLS: trust it only if the user opted in
                // (Settings → "Allow self-signed certificate"). Otherwise refuse
                // and explain, rather than silently failing.
                if (prefs().getBoolean("allowBadCerts", false)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    showError(
                        "This server's HTTPS certificate isn't trusted. If it's your " +
                            "own Shou server, turn on “Allow self-signed certificate” " +
                            "in Settings.",
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })

        maybeRequestNotifications()
        load()
    }

    // --- Hardware volume rocker -> the PC's player ------------------------- //
    // A remote should drive the TV's volume, not the phone's ringer. When enabled
    // (Settings, on by default) we swallow the volume keys and forward them to mpv
    // via the same token-gated /volume endpoint the on-screen buttons use.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val isVol = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
        if (isVol && prefs().getBoolean("volumeKeys", true)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP) "up" else "down"
                evalJs("window.ShouVolume && window.ShouVolume('$dir')")
            }
            return true  // consume — don't change the phone's own volume
        }
        return super.dispatchKeyEvent(event)
    }

    private fun prefs() = getSharedPreferences("shou", MODE_PRIVATE)

    private fun configuredHost(): String? =
        prefs().getString("active_host", null)?.trim()?.ifEmpty { null }
            ?: prefs().getString("host", "")?.trim()?.ifEmpty { null }

    /** A shortcut/widget may carry a target server token; make it the active one. */
    private fun applyRequestedRemote(intent: Intent?) {
        val token = intent?.getStringExtra(EXTRA_REMOTE_TOKEN)?.trim().orEmpty()
        if (token.isEmpty()) return
        val r = ShouStore.remoteByToken(this, token) ?: return
        ShouStore.setActive(this, r.key, r.bestHost(), r.port, r.name)
    }

    /** Build the remote URL from the active server, or null if nothing is set yet. */
    private fun buildUrl(): String? {
        val host = ShouStore.activeHost(this)
        if (host.isEmpty()) return null
        val scheme = if (ShouStore.https(this)) "https" else "http"
        val port = ShouStore.activePort(this)
        val token = ShouStore.activeToken(this)
        val query = if (token.isEmpty()) "" else "?k=" + Uri.encode(token)
        return "$scheme://$host:$port/remote$query"
    }

    private fun load() {
        val url = buildUrl()
        if (url == null) {
            openSettings()
            return
        }
        loadedUrl = url
        web.loadUrl(url)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyRequestedRemote(intent)
        val url = buildUrl()
        if (url != null && url != loadedUrl) load()
    }

    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1,
                )
            }
        }
    }

    private fun showError(detail: String? = null) {
        val host = configuredHost() ?: "(not set)"
        val body = detail
            ?: "No response from <b>$host</b>. Make sure the Shou server is running on " +
            "your computer and that this phone is on the same network."
        val html = """
            <!doctype html><html><head><meta name="viewport"
              content="width=device-width,initial-scale=1,viewport-fit=cover">
            <style>
              html,body{height:100%;margin:0}
              body{background:#0B0A0E;color:#F4F1EA;font-family:sans-serif;
                   display:flex;flex-direction:column;align-items:center;
                   justify-content:center;gap:18px;text-align:center;padding:24px}
              h1{font-size:20px;margin:0;color:#FF4A32;letter-spacing:.04em}
              p{margin:0;color:#9A94A6;font-size:14px;line-height:1.5;max-width:340px}
              a{display:inline-block;margin-top:6px;padding:13px 22px;border-radius:14px;
                text-decoration:none;font-weight:700;font-size:14px}
              .p{background:#FF4A32;color:#fff}
              .s{color:#F4F1EA;border:1px solid #2a2733}
            </style></head><body>
              <h1>Can't reach Shou</h1>
              <p>$body</p>
              <a class="p" href="app://retry">Retry</a>
              <a class="s" href="app://settings">Settings</a>
            </body></html>
        """.trimIndent()
        loadedUrl = null
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        // Settings may have changed the server URL while we were away — reload if so.
        val url = buildUrl()
        if (url != null && url != loadedUrl) load()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REMOTE_TOKEN = "io.github.shiot0.shou.REMOTE_TOKEN"
    }
}
