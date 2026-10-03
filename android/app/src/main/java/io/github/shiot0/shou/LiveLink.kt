package io.github.shiot0.shou

import android.content.Context
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Where the link to the PC stands, for the status dot and the offline screen. */
enum class Link { NONE, CONNECTING, LIVE, OFFLINE }

/**
 * The app's one live connection to a Shou server, shared by the remote screen and the
 * cast player. It holds the Socket.IO channel the server broadcasts kiosk state on, sends
 * control commands in order over HTTP, re-finds the PC when its address changes, and
 * mirrors playback out to the lock-screen controls, widget and Quick Settings tile.
 */
object LiveLink {

    private lateinit var app: Context
    // One thread owns connect/disconnect so a quick server switch can't interleave.
    private val linkThread = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + linkThread.asCoroutineDispatcher())
    // Commands go out one at a time, in tap order (typing a query depends on it).
    private val commands = Executors.newSingleThreadExecutor()

    private val _link = MutableStateFlow(Link.NONE)
    private val _state = MutableStateFlow<KioskState?>(null)
    private val _remote = MutableStateFlow<Remote?>(null)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val link: StateFlow<Link> = _link
    val state: StateFlow<KioskState?> = _state
    val remote: StateFlow<Remote?> = _remote
    /** One-off messages for a snackbar ("Can't reach Living room"). */
    val notices: SharedFlow<String> = _notices

    private var socket: Socket? = null
    private var host = ""
    private var watchdog: Job? = null
    private var idleJob: Job? = null
    private var lastPlayback: Playback? = null
    private var lastFinished = ""

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        ShouStore.init(app)
    }

    /** Connect to [r] (or drop the link when null). Probes the saved address first and
     *  then `<name>.local`, and keeps re-probing while offline so a new IP self-heals. */
    fun connect(r: Remote?) {
        scope.launch {
            closeSocket()
            watchdog?.cancel()
            _remote.value = r
            _state.value = null
            lastPlayback = null
            if (r == null) {
                _link.value = Link.NONE
                return@launch
            }
            _link.value = Link.CONNECTING
            watchdog = launch {
                // Probing is blocking network I/O: keep it off the link thread.
                openSocket(r, withContext(Dispatchers.IO) { reachableHost(r) } ?: r.bestHost())
                while (isActive) {
                    delay(8000)
                    if (_link.value == Link.LIVE) continue
                    val alt = withContext(Dispatchers.IO) { reachableHost(r) } ?: continue
                    if (alt != host) openSocket(r, alt)
                }
            }
        }
    }

    /** Reconnect to whatever is active (after a settings change, or coming back). */
    fun reconnect() = connect(ShouStore.activeRemote(app))

    /** The app came to the front: cancel any pending idle drop and make sure we're on. */
    fun foreground() {
        scope.launch {
            idleJob?.cancel()
            idleJob = null
            if (socket == null) {
                val r = _remote.value ?: ShouStore.activeRemote(app)
                if (r != null) connect(r)
            }
        }
    }

    /** The app went to the back. Keep the link while something plays (the lock-screen
     *  controls need it); otherwise let it go after a short grace period. */
    fun background() {
        scope.launch {
            idleJob?.cancel()
            idleJob = launch {
                delay(45_000)
                val s = _state.value
                if (s?.playing == null && s?.cast?.active != true) {
                    watchdog?.cancel()
                    closeSocket()
                    _link.value = Link.NONE
                }
            }
        }
    }

    /** POST a control command (`left`, `search/key`, …). Commands are sent in order;
     *  while the PC is unreachable they're dropped with a notice instead of piling up. */
    fun send(path: String, params: Map<String, String> = emptyMap()) {
        if (_link.value == Link.OFFLINE || _link.value == Link.NONE) {
            val name = _remote.value?.name?.ifBlank { null } ?: "the PC"
            _notices.tryEmit("Can't reach $name right now")
            return
        }
        commands.execute { ServerClient.command(app, path, params) }
    }

    // --- internals --------------------------------------------------------- //

    private fun reachableHost(r: Remote): String? {
        val candidates = listOf(r.host, r.hostname).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        for (h in candidates) {
            val who = ServerClient.whoami(app, h, r.port) ?: continue
            // Remember what worked (and the portable <name>.local) for next time.
            val healed = r.copy(
                host = h,
                hostname = r.hostname.ifBlank { who.optString("host") },
                name = r.name.ifBlank { who.optString("name", h) },
            )
            if (healed != r) {
                ShouStore.saveRemotes(app, ShouStore.remotes(app).map { if (it.key == r.key) healed else it })
                if (_remote.value?.key == r.key) _remote.value = healed
            }
            return h
        }
        return null
    }

    private fun openSocket(r: Remote, h: String) {
        closeSocket()
        host = h
        ShouStore.setActive(app, r.key, h, r.port, r.name)
        val scheme = if (ShouStore.https(app)) "https" else "http"
        val opts = IO.Options.builder()
            .setQuery("k=" + URLEncoder.encode(r.key, "UTF-8"))
            .setReconnectionDelay(1000)
            .setReconnectionDelayMax(4000)
            .setTimeout(6000)
            .build()
        if (scheme == "https" && ShouStore.allowBadCerts(app)) {
            ServerClient.relaxedOkHttp()?.let { opts.callFactory = it; opts.webSocketFactory = it }
        }
        val s = try {
            IO.socket("$scheme://$h:${r.port}", opts)
        } catch (e: Exception) {
            _link.value = Link.OFFLINE
            return
        }
        s.on(Socket.EVENT_CONNECT) { _link.value = Link.LIVE }
        s.on(Socket.EVENT_DISCONNECT) { if (socket === s) _link.value = Link.OFFLINE }
        s.on(Socket.EVENT_CONNECT_ERROR) { if (socket === s) _link.value = Link.OFFLINE }
        // The server greets each new client with a state broadcast from its connect
        // handler, which lands before the connect ack; socket.io-client-java replays such
        // buffered events with the event name prepended to args, so look for the payload.
        s.on("state") { args -> args.firstNotNullOfOrNull { it as? JSONObject }?.let { onState(it) } }
        socket = s
        s.connect()
        Shortcuts.publish(app)
        ShouWidgetProvider.refresh(app)
        ShouTileService.refresh(app)
    }

    private fun closeSocket() {
        socket?.let { s ->
            socket = null
            runCatching { s.off(); s.disconnect(); s.close() }
        }
    }

    private fun onState(o: JSONObject) {
        val s = try { KioskState.parse(o) } catch (e: Exception) { return }
        _state.value = s
        mirrorPlayback(s)
        notifyFinished(s)
    }

    /** Feed the media notification, widget and tile from the live playback state. */
    private fun mirrorPlayback(s: KioskState) {
        val p = s.playing
        val pb = if (p != null && p.live) Playback(
            active = true,
            playing = !p.paused,
            title = p.title.ifBlank { "Shou" },
            subtitle = "Episode ${p.episode}",
            cover = p.cover,
            positionMs = (p.position * 1000).toLong(),
            durationMs = (p.duration * 1000).toLong(),
        ) else null
        if (pb == lastPlayback) return
        val was = lastPlayback
        lastPlayback = pb
        ShouStore.setPlayback(app, pb)
        PlaybackController.update(app, pb)
        // Position ticks every second; only the widget cares about those.
        ShouWidgetProvider.refresh(app)
        if (was?.active != pb?.active || was?.playing != pb?.playing) ShouTileService.refresh(app)
    }

    /** The finale's rating page is the clean "you finished it" moment — notify once. */
    private fun notifyFinished(s: KioskState) {
        val r = s.rating
        if (s.view == "rating" && r != null) {
            if (r.title.isNotBlank() && r.title != lastFinished) {
                lastFinished = r.title
                Notifications.event(app, "finished", r.title, "You finished it. Rate it in Shou?")
            }
        } else {
            lastFinished = ""
        }
    }
}
