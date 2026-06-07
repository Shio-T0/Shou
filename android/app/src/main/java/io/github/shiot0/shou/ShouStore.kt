package io.github.shiot0.shou

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** One saved Shou server, mirrored from the web remote's localStorage set. */
data class Remote(
    val id: String,
    val name: String,
    val key: String,
    val host: String,
    val hostname: String,
    val port: String,
    val mac: String,
) {
    /** Best address to reach this server on right now. */
    fun bestHost(): String = host.ifBlank { hostname }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("key", key)
        put("host", host); put("hostname", hostname); put("port", port); put("mac", mac)
    }

    companion object {
        fun fromJson(o: JSONObject) = Remote(
            id = o.optString("id"),
            name = o.optString("name"),
            key = o.optString("key"),
            host = o.optString("host"),
            hostname = o.optString("hostname"),
            port = o.optString("port", "4100").ifBlank { "4100" },
            mac = o.optString("mac"),
        )
    }
}

/** A compact mirror of what the kiosk is doing, pushed from the web remote each tick. */
data class Playback(
    val active: Boolean,   // something is playing (mpv is up)
    val playing: Boolean,  // true = playing, false = paused
    val title: String,
    val subtitle: String,
    val cover: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The native source of truth shared by every background entry point (media session,
 * widget, Quick Settings tile, Wake-on-LAN, shortcuts). Two stores:
 *
 *  * a hardware-encrypted store for anything secret — the saved remotes (which carry
 *    their REMOTE_TOKEN keys) and the single-server token from Settings;
 *  * a plain store for the non-secret bits the widget/tile need to render quickly
 *    (active host/port/name + the last playback snapshot).
 */
object ShouStore {

    private const val SECURE = "shou_secure"
    private const val PLAIN = "shou"          // shared with SettingsActivity / MainActivity

    @Volatile private var securePrefs: SharedPreferences? = null
    @Volatile private var plainPrefs: SharedPreferences? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (plainPrefs == null) plainPrefs = app.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
        if (securePrefs == null) securePrefs = buildSecure(app)
    }

    private fun secure(ctx: Context): SharedPreferences {
        securePrefs?.let { return it }
        return buildSecure(ctx.applicationContext).also { securePrefs = it }
    }

    private fun plain(ctx: Context): SharedPreferences {
        plainPrefs?.let { return it }
        return ctx.applicationContext.getSharedPreferences(PLAIN, Context.MODE_PRIVATE)
            .also { plainPrefs = it }
    }

    /** Encrypted prefs, or a graceful fall back to plain prefs if the keystore misbehaves
     *  (some OEM builds throw on EncryptedSharedPreferences) — the app must still work. */
    private fun buildSecure(app: Context): SharedPreferences = try {
        val key = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app, SECURE, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Throwable) {
        app.getSharedPreferences(SECURE + "_fallback", Context.MODE_PRIVATE)
    }

    // --- Single-server settings (Settings screen) --------------------------- //
    // host / port / https / allowBadCerts stay in plain prefs (not secret); only the
    // token is sensitive, so it lives in the encrypted store.

    fun host(ctx: Context): String = plain(ctx).getString("host", "")!!.trim()
    fun port(ctx: Context): String = plain(ctx).getString("port", "4100")!!.trim().ifEmpty { "4100" }
    fun https(ctx: Context): Boolean = plain(ctx).getBoolean("https", false)
    fun allowBadCerts(ctx: Context): Boolean = plain(ctx).getBoolean("allowBadCerts", false)

    fun token(ctx: Context): String {
        // Read the encrypted token, migrating a legacy plaintext token on first run.
        val s = secure(ctx)
        s.getString("token", null)?.let { return it }
        val legacy = plain(ctx).getString("token", "")!!.trim()
        if (legacy.isNotEmpty()) {
            s.edit().putString("token", legacy).apply()
            plain(ctx).edit().remove("token").apply()
        }
        return legacy
    }

    fun saveSettings(
        ctx: Context, host: String, port: String, token: String,
        https: Boolean, allowBadCerts: Boolean,
    ) {
        plain(ctx).edit()
            .putString("host", host.trim())
            .putString("port", port.trim().ifEmpty { "4100" })
            .putBoolean("https", https)
            .putBoolean("allowBadCerts", allowBadCerts)
            .remove("token")  // never keep it in plaintext
            .apply()
        secure(ctx).edit().putString("token", token.trim()).apply()
    }

    // --- Saved remotes (synced from the web remote's localStorage set) ------- //

    fun setRemotes(ctx: Context, json: String) {
        val cleaned = try {
            val arr = JSONArray(json)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("key").isBlank()) continue
                out.put(Remote.fromJson(o).toJson())
            }
            out.toString()
        } catch (e: Exception) {
            return
        }
        secure(ctx).edit().putString("remotes", cleaned).apply()
    }

    fun remotes(ctx: Context): List<Remote> {
        val raw = secure(ctx).getString("remotes", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Remote::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun remoteByToken(ctx: Context, token: String): Remote? =
        remotes(ctx).firstOrNull { it.key == token }

    // --- The active endpoint (what background features talk to) -------------- //
    // Mirrored from the web on every load, so the media session / widget / WOL keep
    // working even after the user switched remotes inside the WebView.

    fun setActive(ctx: Context, token: String, host: String, port: String, name: String) {
        secure(ctx).edit().putString("active_token", token).apply()
        plain(ctx).edit()
            .putString("active_host", host)
            .putString("active_port", port.ifBlank { "4100" })
            .putString("active_name", name)
            .apply()
    }

    fun activeToken(ctx: Context): String =
        secure(ctx).getString("active_token", null)?.takeIf { it.isNotBlank() } ?: token(ctx)

    fun activeHost(ctx: Context): String =
        plain(ctx).getString("active_host", null)?.takeIf { it.isNotBlank() } ?: host(ctx)

    fun activePort(ctx: Context): String =
        plain(ctx).getString("active_port", null)?.takeIf { it.isNotBlank() } ?: port(ctx)

    fun activeName(ctx: Context): String =
        plain(ctx).getString("active_name", null)?.takeIf { it.isNotBlank() }
            ?: activeHost(ctx).ifBlank { "Shou" }

    /** Base http(s) URL for the active server, or null if nothing is configured yet. */
    fun activeBaseUrl(ctx: Context): String? {
        val host = activeHost(ctx)
        if (host.isBlank()) return null
        val scheme = if (https(ctx)) "https" else "http"
        return "$scheme://$host:${activePort(ctx)}"
    }

    /** MAC for Wake-on-LAN of the active server (from its saved remote), or "". */
    fun activeMac(ctx: Context): String =
        remoteByToken(ctx, activeToken(ctx))?.mac?.trim().orEmpty()

    // --- Playback snapshot (plain — refreshed ~1/sec, read by widget & tile) -- //

    fun setPlayback(ctx: Context, p: Playback?) {
        val e = plain(ctx).edit()
        if (p == null || !p.active) {
            e.putBoolean("pb_active", false)
        } else {
            e.putBoolean("pb_active", true)
                .putBoolean("pb_playing", p.playing)
                .putString("pb_title", p.title)
                .putString("pb_subtitle", p.subtitle)
                .putString("pb_cover", p.cover)
                .putLong("pb_pos", p.positionMs)
                .putLong("pb_dur", p.durationMs)
        }
        e.apply()
    }

    fun playback(ctx: Context): Playback? {
        val p = plain(ctx)
        if (!p.getBoolean("pb_active", false)) return null
        return Playback(
            active = true,
            playing = p.getBoolean("pb_playing", true),
            title = p.getString("pb_title", "") ?: "",
            subtitle = p.getString("pb_subtitle", "") ?: "",
            cover = p.getString("pb_cover", "") ?: "",
            positionMs = p.getLong("pb_pos", 0L),
            durationMs = p.getLong("pb_dur", 0L),
        )
    }
}
