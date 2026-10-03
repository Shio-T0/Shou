package io.github.shiot0.shou

import org.json.JSONArray
import org.json.JSONObject

/*
 * The kiosk's live state, exactly as the server broadcasts it on the Socket.IO `state`
 * event (see broadcast() in shou/server.py). The server stays the single source of
 * truth: the app renders this and sends control commands back over HTTP.
 */

/** One show on the Watching / Planned carousel. */
data class Card(
    val id: Int,
    val title: String,
    val progress: Int,
    val total: Int?,
    val available: Int?,
    val episodeText: String,
    val cover: String,
    val color: Int?,
    val banner: String,
    val caughtUp: Boolean,
) {
    /** Episodes you can actually watch right now (aired so far, else the total). */
    val watchable: Int? get() = available ?: total
    val fraction: Float
        get() = watchable?.takeIf { it > 0 }?.let { (progress.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
}

data class Playing(
    val title: String,
    val episode: Int,
    val total: Int?,
    val cover: String,
    val color: Int?,
    val banner: String,
    val position: Double,
    val duration: Double,
    val paused: Boolean,
) {
    /** mpv is up and reporting position — before that, the source is still loading. */
    val live: Boolean get() = duration > 0
}

data class Sequel(val finished: String, val sequelTitle: String)

data class Rating(
    val title: String,
    val cover: String,
    val color: Int?,
    val banner: String,
    val score: Double,
    val max: Double,
    val format: String,
    val stars: Double,
    val submitting: Boolean,
    val done: Boolean,
) {
    val scoreText: String
        get() = if (format == "POINT_10_DECIMAL") "%.1f".format(score)
        else if (score == Math.floor(score)) score.toLong().toString() else score.toString()
    val maxText: String get() = if (max == Math.floor(max)) max.toLong().toString() else max.toString()
}

/** A half-watched episode in the Continue Watching history. */
data class ResumeEntry(
    val mediaId: Int,
    val episode: Int,
    val title: String,
    val cover: String,
    val color: Int?,
    val banner: String,
    val position: Double,
    val duration: Double,
    val percent: Double,
) {
    val fraction: Float
        get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f)
        else (percent / 100.0).toFloat().coerceIn(0f, 1f)
}

data class SearchResult(
    val id: Int,
    val title: String,
    val format: String,
    val episodes: Int?,
    val year: Int?,
    val score: Int?,
    val cover: String,
    val color: Int?,
    val listStatus: String?,
)

data class Detail(
    val loading: Boolean,
    val id: Int,
    val title: String,
    val format: String,
    val episodes: Int?,
    val duration: Int?,
    val year: Int?,
    val genres: List<String>,
    val score: Int?,
    val studio: String,
    val description: String,
    val cover: String,
    val color: Int?,
    val banner: String,
    val listStatus: String?,
    val progress: Int,
)

data class Season(val id: Int, val title: String, val year: Int?, val format: String, val episodes: Int?)

data class SearchState(
    val query: String,
    val genres: List<String>,
    val genreList: List<String>,
    val tagList: List<String>,
    val results: List<SearchResult>,
    val cursor: Int,
    val hasMore: Boolean,
    val detail: Detail?,
    val seasons: List<Season>,
    val seasonIdx: Int,
    val busy: Boolean,
    val statuses: List<Pair<String, String>>,
    val canWrite: Boolean,
)

/** Which AniList account the PC is signed in as (null from servers before sign-in support). */
data class Account(
    val signedIn: Boolean,
    val name: String,
    val avatar: String,
    val expired: Boolean,
    val listUser: String,
    val clientId: String,
    val authUrl: String,
    val redirect: String,
) {
    /** Signed in as one account while the lists shown belong to another. */
    val listsElsewhere: Boolean
        get() = signedIn && name.isNotBlank() && listUser.isNotBlank() && !name.equals(listUser, ignoreCase = true)
}

/** The episode "thrown" from the PC to this phone. */
data class Cast(
    val active: Boolean,
    val resolving: Boolean,
    val kind: String,
    val src: String,
    val sub: String,
    val position: Double,
    val title: String,
    val episode: Int,
    val error: String,
)

data class KioskState(
    val view: String,
    val list: String,
    val items: List<Card>,
    val cursor: Int,
    val sequel: Sequel?,
    val playing: Playing?,
    val rating: Rating?,
    val message: String,
    val history: List<ResumeEntry>,
    val search: SearchState?,
    val cast: Cast?,
    val account: Account?,
) {
    val focused: Card? get() = items.getOrNull(cursor) ?: items.firstOrNull()

    /** The server boots into "loading" with no message and stays there until someone
     *  presses Open — that's the kiosk being closed, not anything actually loading. */
    val kioskClosed: Boolean get() = view == "loading" && message.isBlank() && items.isEmpty()

    companion object {
        fun parse(o: JSONObject): KioskState = KioskState(
            view = o.optString("view", "loading"),
            list = o.optString("list", "watching"),
            items = o.optJSONArray("items").objects().map(::card),
            cursor = o.optInt("cursor", 0),
            sequel = o.optJSONObject("sequel")?.let {
                Sequel(it.optString("finished"), it.optString("sequel_title"))
            },
            playing = o.optJSONObject("playing")?.let(::playing),
            rating = o.optJSONObject("rating")?.let(::rating),
            message = o.optString("message").nullless(),
            history = o.optJSONArray("history").objects().map(::resume),
            search = o.optJSONObject("search")?.let(::search),
            cast = o.optJSONObject("cast")?.let(::cast),
            account = o.optJSONObject("account")?.let(::account),
        )

        private fun account(o: JSONObject) = Account(
            signedIn = o.optBoolean("signedIn"),
            name = o.optString("name").nullless(),
            avatar = o.optString("avatar").nullless(),
            expired = o.optBoolean("expired"),
            listUser = o.optString("listUser").nullless(),
            clientId = o.optString("clientId").nullless(),
            authUrl = o.optString("authUrl").nullless(),
            redirect = o.optString("redirect").nullless().ifBlank { "https://anilist.co/api/v2/oauth/pin" },
        )

        private fun card(o: JSONObject) = Card(
            id = o.optInt("id"),
            title = o.optString("title").nullless(),
            progress = o.optInt("progress"),
            total = o.optIntOrNull("total"),
            available = o.optIntOrNull("available"),
            episodeText = o.optString("episodeText").nullless(),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            banner = o.optString("banner").nullless(),
            caughtUp = o.optBoolean("caughtUp"),
        )

        private fun playing(o: JSONObject) = Playing(
            title = o.optString("title").nullless(),
            episode = o.optInt("episode", 1),
            total = o.optIntOrNull("total"),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            banner = o.optString("banner").nullless(),
            position = o.optDouble("position", 0.0).finite(),
            duration = o.optDouble("duration", 0.0).finite(),
            paused = o.optBoolean("paused", false),
        )

        private fun rating(o: JSONObject) = Rating(
            title = o.optString("title").nullless(),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            banner = o.optString("banner").nullless(),
            score = o.optDouble("score", 0.0).finite(),
            max = o.optDouble("max", 10.0).finite(),
            format = o.optString("format").nullless(),
            stars = o.optDouble("stars", 0.0).finite(),
            submitting = o.optBoolean("submitting"),
            done = o.optBoolean("done"),
        )

        private fun resume(o: JSONObject) = ResumeEntry(
            mediaId = o.optInt("media_id"),
            episode = o.optInt("episode"),
            title = o.optString("title").nullless(),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            banner = o.optString("banner").nullless(),
            position = o.optDouble("position", 0.0).finite(),
            duration = o.optDouble("duration", 0.0).finite(),
            percent = o.optDouble("percent", 0.0).finite(),
        )

        private fun result(o: JSONObject) = SearchResult(
            id = o.optInt("id"),
            title = o.optString("title").nullless(),
            format = o.optString("format").nullless(),
            episodes = o.optIntOrNull("episodes"),
            year = o.optIntOrNull("year"),
            score = o.optIntOrNull("score"),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            listStatus = o.optString("listStatus").nullless().ifBlank { null },
        )

        private fun detail(o: JSONObject) = Detail(
            loading = o.optBoolean("loading"),
            id = o.optInt("id"),
            title = o.optString("title").nullless(),
            format = o.optString("format").nullless(),
            episodes = o.optIntOrNull("episodes"),
            duration = o.optIntOrNull("duration"),
            year = o.optIntOrNull("year"),
            genres = o.optJSONArray("genres").strings(),
            score = o.optIntOrNull("score"),
            studio = o.optString("studio").nullless(),
            description = o.optString("description").nullless(),
            cover = o.optString("cover").nullless(),
            color = parseColor(o.optString("color")),
            banner = o.optString("banner").nullless(),
            listStatus = o.optString("listStatus").nullless().ifBlank { null },
            progress = o.optInt("progress"),
        )

        private fun search(o: JSONObject) = SearchState(
            query = o.optString("query").nullless(),
            genres = o.optJSONArray("genres").strings(),
            genreList = o.optJSONArray("genreList").strings(),
            tagList = o.optJSONArray("tagList").strings(),
            results = o.optJSONArray("results").objects().map(::result),
            cursor = o.optInt("cursor", 0),
            hasMore = o.optBoolean("hasMore"),
            detail = o.optJSONObject("detail")?.let(::detail),
            seasons = o.optJSONArray("seasons").objects().map {
                Season(
                    it.optInt("id"), it.optString("title").nullless(), it.optIntOrNull("year"),
                    it.optString("format").nullless(), it.optIntOrNull("episodes"),
                )
            },
            seasonIdx = o.optInt("seasonIdx", 0),
            busy = o.optBoolean("busy"),
            statuses = o.optJSONArray("statuses").let { arr ->
                (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    arr?.optJSONArray(i)?.let { it.optString(0) to it.optString(1) }
                }
            },
            canWrite = o.optBoolean("canWrite", true),
        )

        private fun cast(o: JSONObject) = Cast(
            active = o.optBoolean("active"),
            resolving = o.optBoolean("resolving"),
            kind = o.optString("kind").nullless(),
            src = o.optString("src").nullless(),
            sub = o.optString("sub").nullless(),
            position = o.optDouble("position", 0.0).finite(),
            title = o.optString("title").nullless(),
            episode = o.optInt("episode", 0),
            error = o.optString("error").nullless(),
        )

        /** "#1f2233" -> ARGB int, or null for anything unparseable. */
        fun parseColor(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }
    }
}

/** Display labels + accent keys for AniList list statuses. */
object ListStatus {
    val label = mapOf(
        "CURRENT" to "Watching", "PLANNING" to "Planned", "COMPLETED" to "Completed",
        "PAUSED" to "Paused", "DROPPED" to "Dropped", "REPEATING" to "Rewatching",
    )
}

// --- org.json helpers: JSON null arrives as the string "null" via optString ------- //

private fun String?.nullless(): String = if (this == null || this == "null") "" else this

private fun Double.finite(): Double = if (isNaN() || isInfinite()) 0.0 else this

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key).takeIf { optDouble(key).isFinite() }

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
