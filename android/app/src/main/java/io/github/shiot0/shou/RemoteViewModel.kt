package io.github.shiot0.shou

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * The remote screen's brain. Everything the kiosk does lives on the server; this adds
 * the phone-side niceties on top of the plain control endpoints:
 *  - tap a poster to jump the PC's carousel to it (stepped with left/right, so it
 *    works against any Shou server version) with an optimistic highlight meanwhile,
 *  - a real text field for search, diffed into the server's one-key-at-a-time edits,
 *  - drag-to-seek, sent as relative jumps,
 *  - the saved-servers list, Wake-on-LAN and network discovery.
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    val link = LiveLink.link
    val state = LiveLink.state
    val remote = LiveLink.remote
    val notices = LiveLink.notices

    private val _remotes = MutableStateFlow<List<Remote>>(emptyList())
    val remotes: StateFlow<List<Remote>> = _remotes

    private val _pendingFocus = MutableStateFlow<Int?>(null)
    /** A poster tapped on the phone that the PC carousel is still stepping towards. */
    val pendingFocus: StateFlow<Int?> = _pendingFocus

    init {
        LiveLink.init(app)
        refreshRemotes()
        if (LiveLink.remote.value == null) LiveLink.connect(ShouStore.activeRemote(app))
        viewModelScope.launch {
            state.collect { s ->
                if (s != null && _pendingFocus.value == s.cursor) _pendingFocus.value = null
            }
        }
    }

    fun refreshRemotes() {
        _remotes.value = ShouStore.remotes(getApplication())
    }

    private fun cmd(path: String, params: Map<String, String> = emptyMap()) = LiveLink.send(path, params)

    // --- Kiosk navigation ------------------------------------------------- //
    fun open() = cmd("open")
    fun back() = cmd("back")
    fun left() = cmd("left")
    fun right() = cmd("right")
    fun select() = cmd("select")
    fun showList(mode: String) = cmd("list", mapOf("to" to mode))

    /** Jump the PC's carousel to item [i]: straight there via /focus, or — on a server
     *  from before /focus — by stepping left/right the shorter way round. */
    fun focus(i: Int) {
        val s = state.value ?: return
        val n = s.items.size
        val from = _pendingFocus.value ?: s.cursor
        if (n == 0 || i == from) return
        _pendingFocus.value = i
        viewModelScope.launch {
            val (code, _) = withContext(Dispatchers.IO) {
                ServerClient.postForm(getApplication(), "focus", mapOf("i" to i.toString()))
            }
            if (code == 404) {
                val fwd = (i - from + n) % n
                val bwd = (from - i + n) % n
                repeat(min(fwd, bwd)) { cmd(if (fwd <= bwd) "right" else "left") }
            }
            delay(3000)
            if (_pendingFocus.value == i) _pendingFocus.value = null
        }
    }

    // --- Playback ----------------------------------------------------------- //
    fun pause() = cmd("pause")
    fun nextEpisode() = cmd("next")
    fun prevEpisode() = cmd("prev")
    fun volume(dir: String) = cmd("volume", mapOf("d" to dir))
    fun throwToPhone() = cmd("throw")

    /** Seek by [seconds] (negative = back). The server caps one jump at 600 s. */
    fun seekBy(seconds: Int) {
        var left = seconds
        while (abs(left) >= 1) {
            val step = left.coerceIn(-600, 600)
            cmd(if (step > 0) "fwd" else "rew", mapOf("s" to abs(step).toString()))
            left -= step
        }
    }

    fun resume(e: ResumeEntry) =
        cmd("resume", mapOf("media_id" to e.mediaId.toString(), "episode" to e.episode.toString()))

    fun forget(e: ResumeEntry) =
        cmd("forget", mapOf("media_id" to e.mediaId.toString(), "episode" to e.episode.toString()))

    // --- Search ---------------------------------------------------------------- //
    // The server keeps one shared query (the kiosk keyboard edits it too) and only takes
    // one character or one backspace per call. We diff the text field against what we've
    // already sent and replay the difference in order.
    private var sentQuery = ""
    private var lastEdit = 0L

    fun editQuery(text: String) {
        val prefix = sentQuery.commonPrefixWith(text).length
        repeat(sentQuery.length - prefix) { cmd("search/back") }
        for (c in text.substring(prefix)) cmd("search/key", mapOf("c" to c.toString()))
        sentQuery = text
        lastEdit = SystemClock.uptimeMillis()
    }

    fun clearQuery() {
        sentQuery = ""
        lastEdit = SystemClock.uptimeMillis()
        cmd("search/clear")
    }

    /** Whether the field should take the server's text (someone typed on the PC, or the
     *  search was reset). Not while our own keystrokes may still be in flight. */
    fun adoptServerQuery(server: String): Boolean {
        if (SystemClock.uptimeMillis() - lastEdit < 1500) return false
        sentQuery = server
        return true
    }

    fun toggleGenre(g: String) = cmd("search/genre", mapOf("g" to g))
    fun clearGenres() = cmd("search/genres/clear")
    fun pick(i: Int) = cmd("search/pick", mapOf("i" to i.toString()))
    fun setStatus(status: String) = cmd("status", mapOf("to" to status))

    private var lastMore = 0L
    fun loadMore() {
        val now = SystemClock.uptimeMillis()
        if (now - lastMore < 700) return
        lastMore = now
        cmd("search/more")
    }

    // --- Saved servers -------------------------------------------------------- //
    fun switchTo(r: Remote) {
        if (r.key == remote.value?.key && link.value != Link.NONE) return
        val app = getApplication<Application>()
        ShouStore.setActive(app, r.key, r.bestHost(), r.port, r.name)
        LiveLink.connect(r)
    }

    /** Add or update a server (matched by id, then by key) and connect to it. */
    fun saveRemote(r: Remote) {
        val app = getApplication<Application>()
        val list = ShouStore.remotes(app).toMutableList()
        val at = list.indexOfFirst { it.id == r.id }.takeIf { it >= 0 }
            ?: list.indexOfFirst { it.key == r.key }.takeIf { it >= 0 }
        val saved = if (at != null) r.copy(id = list[at].id).also { list[at] = it } else r.also { list += it }
        ShouStore.saveRemotes(app, list)
        refreshRemotes()
        Shortcuts.publish(app)
        ShouStore.setActive(app, saved.key, saved.bestHost(), saved.port, saved.name)
        LiveLink.connect(saved)
    }

    fun deleteRemote(r: Remote) {
        val app = getApplication<Application>()
        val list = ShouStore.remotes(app).filterNot { it.id == r.id }
        ShouStore.saveRemotes(app, list)
        refreshRemotes()
        Shortcuts.publish(app)
        if (r.key == remote.value?.key) {
            val next = list.firstOrNull()
            if (next != null) switchTo(next) else LiveLink.connect(null)
        }
    }

    /** Send a Wake-on-LAN packet to [r]. Calls back with whether one went out. */
    fun wake(r: Remote, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { r.mac.isNotBlank() && Wol.wake(r.mac) }
            done(ok)
        }
    }

    fun retry() = LiveLink.reconnect()

    /** Point the PC's lists at the account it's signed in to AniList as. */
    fun useAccountLists() {
        viewModelScope.launch(Dispatchers.IO) { ServerClient.postForm(getApplication(), "auth/lists", emptyMap()) }
    }

    // --- Network discovery (mDNS `_shou._tcp`) ------------------------------------ //
    private val _found = MutableStateFlow<List<NsdResult>>(emptyList())
    val found: StateFlow<List<NsdResult>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private var scanner: NsdScanner? = null

    fun scan() {
        scanner?.stop()
        _found.value = emptyList()
        _scanning.value = true
        val s = NsdScanner(getApplication())
        scanner = s
        s.start(
            timeoutMs = 7000,
            onFound = { r -> _found.value = _found.value + r },
            onDone = { _scanning.value = false },
        )
    }

    override fun onCleared() {
        scanner?.stop()
        super.onCleared()
    }

    companion object {
        fun newId(): String =
            "r" + System.currentTimeMillis().toString(36) + (Math.random() * 1e6).toLong().toString(36)
    }
}
