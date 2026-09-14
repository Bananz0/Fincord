package org.akanework.gramophone.logic.data.lastfm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A minimal Last.fm 2.0 API client covering what a music player needs: linking an account,
 * announcing the current track, and submitting scrobbles.
 *
 * Hand-rolled rather than pulled from a library because the surface is three endpoints and one
 * signing rule, and every maintained JVM Last.fm wrapper is either abandoned or drags in a second
 * HTTP stack. It reuses the app's shared OkHttp connection pool for the same reason the Jellyfin SDK
 * does - a separate pool would mean separate TLS handshakes.
 *
 * Every call is a suspending function that does its network work on [Dispatchers.IO] and throws
 * [LastFmException] with the server's own message on failure, so callers can show something better
 * than "something went wrong".
 */
class LastFmClient(
    private val apiKey: String,
    private val apiSecret: String,
    /**
     * When set, signed calls are posted here instead of straight to Last.fm, and this endpoint adds
     * the API key and signature. Lets the shared secret live on a server rather than on every phone.
     */
    private val brokerUrl: String? = null,
    private val http: OkHttpClient = JellyfinClientHolder.apiHttpClient(),
) {

    /**
     * Starts the web authorisation flow: asks Last.fm for a request token.
     *
     * The token then goes to [authorizationUrl], the user approves it on Last.fm's own site, and
     * [getSession] turns it into a permanent session key. This is the flow to use for anything other
     * people will install - it means an Accord user never types their Last.fm password into Accord.
     */
    suspend fun getToken(): AuthRequest {
        val response = post(mapOf("method" to "auth.getToken"))
        val token = response.optString("token").takeIf { it.isNotBlank() }
            ?: throw LastFmException("Last.fm did not return a token")
        // A proxy may return the API key alongside the token. It has to come from somewhere: the
        // approval URL carries it in plain sight, so a device with no local key cannot build one.
        // The key is public - only the shared secret is not - so handing it back here is safe.
        return AuthRequest(token, response.optString("api_key").takeIf { it.isNotBlank() })
    }

    /** Where to send the user to approve the request. */
    fun authorizationUrl(request: AuthRequest): String {
        val key = request.apiKey ?: apiKey.takeIf { it.isNotBlank() }
            ?: throw LastFmException(
                "No API key available. Set one here, or have the signing proxy return api_key."
            )
        return "$AUTH_ROOT?api_key=$key&token=${request.token}"
    }

    /** A request token, plus the API key to approve it with when a proxy supplied one. */
    data class AuthRequest(val token: String, val apiKey: String?)

    /**
     * Turns an approved request token into a session key.
     *
     * Throws while the token is still unapproved, which is expected: the caller retries after the
     * user comes back from the browser.
     */
    suspend fun getSession(token: String): Session {
        val response = post(mapOf("method" to "auth.getSession", "token" to token))
        val session = response.optJSONObject("session")
            ?: throw LastFmException("Last.fm did not return a session")
        return Session(
            name = session.optString("name"),
            key = session.optString("key"),
        )
    }

    /**
     * Tells Last.fm what is playing right now. Purely cosmetic - it drives the "now scrobbling"
     * badge on the profile and expires on its own, so failures here are not worth queueing.
     */
    suspend fun updateNowPlaying(sessionKey: String, track: Track) {
        post(
            buildMap {
                put("method", "track.updateNowPlaying")
                put("sk", sessionKey)
                putAll(track.toParams())
            }
        )
    }

    /**
     * Submits up to [MAX_BATCH] scrobbles in one call.
     *
     * Batching matters for the offline queue: a phone that spent a train journey without signal can
     * come back with dozens of plays, and one request per play would be both slow and a good way to
     * get rate-limited.
     *
     * Returns the number Last.fm accepted. A non-zero "ignored" count is not an error - it usually
     * means the metadata was too sparse for Last.fm to match, and retrying would never help.
     */
    suspend fun scrobble(sessionKey: String, entries: List<TimedTrack>): Int {
        require(entries.size <= MAX_BATCH) { "Last.fm accepts at most $MAX_BATCH scrobbles per call" }
        if (entries.isEmpty()) return 0
        val params = buildMap {
            put("method", "track.scrobble")
            put("sk", sessionKey)
            entries.forEachIndexed { index, entry ->
                entry.track.toParams().forEach { (name, value) -> put("$name[$index]", value) }
                put("timestamp[$index]", entry.timestampSeconds.toString())
            }
        }
        val response = post(params)
        val accepted = response.optJSONObject("scrobbles")
            ?.optJSONObject("@attr")
            ?.optInt("accepted")
            ?: entries.size
        val ignored = response.optJSONObject("scrobbles")
            ?.optJSONObject("@attr")
            ?.optInt("ignored")
            ?: 0
        if (ignored > 0) {
            Log.w(TAG, "Last.fm ignored $ignored of ${entries.size} scrobbles")
        }
        return accepted
    }

    /**
     * Artists Last.fm considers similar to [artist].
     *
     * Read-only, so it needs the API key but no session - recommendations work before the user has
     * linked an account.
     */
    suspend fun getSimilarArtists(artist: String, limit: Int = 30): List<String> {
        val response = get(
            mapOf(
                "method" to "artist.getSimilar",
                "artist" to artist,
                "limit" to limit.toString(),
                "autocorrect" to "1",
            )
        )
        val array = response.optJSONObject("similarartists")?.optJSONArray("artist")
            ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    /** Every track the linked user has loved, newest first. */
    suspend fun getLovedTracks(username: String): List<Track> {
        if (username.isBlank()) return emptyList()
        val result = mutableListOf<Track>()
        var page = 1
        var totalPages: Int
        do {
            val response = get(
                mapOf(
                    "method" to "user.getLovedTracks",
                    "user" to username,
                    "limit" to LOVED_PAGE_SIZE.toString(),
                    "page" to page.toString(),
                )
            )
            val loved = response.optJSONObject("lovedtracks") ?: break
            val tracks = loved.optJSONArray("track")
            if (tracks != null) {
                for (index in 0 until tracks.length()) {
                    val item = tracks.optJSONObject(index) ?: continue
                    val title = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val artist = item.optJSONObject("artist")?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                        ?: item.optString("artist").takeIf { it.isNotBlank() }
                        ?: continue
                    result += Track(artist = artist, title = title)
                }
            }
            totalPages = loved.optJSONObject("@attr")?.optInt("totalPages", page) ?: page
            page++
        } while (page <= totalPages && page <= MAX_LOVED_PAGES)
        return result.distinctBy { "${it.artist.lowercase()}\u0000${it.title.lowercase()}" }
    }

    /**
     * Walks a user's scrobble history, newest first, calling [onPage] with each page.
     *
     * Timestamped plays rather than `user.getTopTracks`, which would hand back a finished count per
     * track in a fraction of the requests. The totals are the wrong shape for an importer that has
     * to be safe to re-run: they cover all time with no way to ask for a window, so a second import
     * could only replace what it wrote before, and nothing could tell a play this app already
     * reported to Jellyfin from one it had not. Timestamps make both possible.
     *
     * [fromSeconds] is exclusive and is how a repeat import stays cheap - passing the point the
     * last run reached asks Last.fm only for what has happened since.
     *
     * Pages are handed over as they arrive rather than accumulated: a decade of listening is
     * hundreds of thousands of rows, and there is no reason for all of them to be resident when the
     * caller only wants a tally. Returning false from [onPage] stops the walk.
     */
    suspend fun getRecentTracks(
        username: String,
        fromSeconds: Long? = null,
        toSeconds: Long? = null,
        onProgress: ((fetched: Int, total: Int) -> Unit)? = null,
        onPage: (List<TimedTrack>) -> Boolean,
    ) {
        if (username.isBlank()) return
        var page = 1
        var totalPages: Int
        var fetched = 0
        var reportedTotal = 0
        do {
            val response = get(
                buildMap {
                    put("method", "user.getRecentTracks")
                    put("user", username)
                    put("limit", RECENT_PAGE_SIZE.toString())
                    put("page", page.toString())
                    // Last.fm returns the artist's MBID and album inline when asked for extended
                    // data, which costs nothing extra and gives the matcher an album to fall back
                    // on when a title is ambiguous.
                    put("extended", "1")
                    fromSeconds?.let { put("from", (it + 1).toString()) }
                    toSeconds?.let { put("to", it.toString()) }
                }
            )
            val recent = response.optJSONObject("recenttracks") ?: break
            val attributes = recent.optJSONObject("@attr")
            totalPages = attributes?.optInt("totalPages", page) ?: page
            if (page == 1) reportedTotal = attributes?.optInt("total", 0) ?: 0

            val entries = recent.optJSONArray("track")
            val batch = mutableListOf<TimedTrack>()
            if (entries != null) {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    // A track playing right now has no timestamp and is not yet a scrobble. It
                    // would arrive again on the next run with one, so counting it here would count
                    // it twice.
                    if (item.optJSONObject("@attr")?.optBoolean("nowplaying") == true) continue
                    val playedAt = item.optJSONObject("date")?.optString("uts")?.toLongOrNull()
                        ?: continue
                    val title = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val artist = item.optJSONObject("artist")?.let {
                        it.optString("name").takeIf { name -> name.isNotBlank() }
                            ?: it.optString("#text").takeIf { text -> text.isNotBlank() }
                    } ?: continue
                    val album = item.optJSONObject("album")?.optString("#text")
                        ?.takeIf { it.isNotBlank() }
                    batch += TimedTrack(
                        track = Track(artist = artist, title = title, album = album),
                        timestampSeconds = playedAt,
                    )
                }
            }
            fetched += batch.size
            onProgress?.invoke(fetched, reportedTotal)
            if (!onPage(batch)) return
            page++
        } while (page <= totalPages && page <= MAX_RECENT_PAGES)
    }

    /**
     * Sends an unsigned, read-only call. Routed through the proxy too when one is configured, so a
     * device without an API key can still fetch recommendations.
     */
    private suspend fun get(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        brokerUrl?.let { broker ->
            val body = FormBody.Builder().apply {
                params.forEach { (name, value) -> add(name, value) }
            }.build()
            return@withContext execute(Request.Builder().url(broker).post(body).build())
        }
        val url = API_ROOT.toHttpUrl().newBuilder().apply {
            params.forEach { (name, value) -> addQueryParameter(name, value) }
            addQueryParameter("api_key", apiKey)
            addQueryParameter("format", "json")
        }.build()
        execute(Request.Builder().url(url).build())
    }

    /**
     * Sends a signed call, either through the proxy or directly.
     *
     * The proxy receives exactly the parameters Last.fm would, minus the credentials, and is
     * responsible for adding `api_key` and `api_sig` before forwarding. It returns Last.fm's
     * response untouched, so everything downstream is identical either way.
     */
    private suspend fun post(params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        brokerUrl?.let { broker ->
            val body = FormBody.Builder().apply {
                params.forEach { (name, value) -> add(name, value) }
            }.build()
            return@withContext execute(Request.Builder().url(broker).post(body).build())
        }
        val signed = params + ("api_key" to apiKey)
        val body = FormBody.Builder().apply {
            signed.forEach { (name, value) -> add(name, value) }
            add("api_sig", sign(signed))
            // Deliberately added after signing: "format" is the one parameter Last.fm excludes from
            // the signature, and including it produces a silent "Invalid method signature" instead.
            add("format", "json")
        }.build()
        execute(Request.Builder().url(API_ROOT).post(body).build())
    }

    private fun execute(request: Request): JSONObject {
        val text = try {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw LastFmException("Could not reach Last.fm", e)
        }
        if (text.isBlank()) throw LastFmException("Last.fm returned an empty response")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw LastFmException("Last.fm returned an unreadable response", e)
        }
        if (json.has("error")) {
            // Last.fm answers errors with HTTP 200 and an error code in the body, so the status line
            // says nothing useful and the payload has to be checked on every call.
            throw LastFmException(
                json.optString("message").ifBlank { "Last.fm error ${json.optInt("error")}" },
                code = json.optInt("error"),
            )
        }
        return json
    }

    /**
     * Last.fm's signature: every parameter except `format` and `callback`, sorted by name, joined as
     * name+value with no separators, the shared secret appended, then MD5.
     */
    private fun sign(params: Map<String, String>): String {
        val payload = buildString {
            params.toSortedMap().forEach { (name, value) -> append(name).append(value) }
            append(apiSecret)
        }
        val digest = MessageDigest.getInstance("MD5").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class Session(val name: String, val key: String)

    /**
     * A track as Last.fm wants to hear about it. [artist] and [title] are the only fields it can
     * match on; everything else improves the match but may be omitted.
     */
    data class Track(
        val artist: String,
        val title: String,
        val album: String? = null,
        val albumArtist: String? = null,
        val durationSeconds: Int? = null,
        val trackNumber: Int? = null,
    ) {
        fun toParams(): Map<String, String> = buildMap {
            put("artist", artist)
            put("track", title)
            album?.takeIf { it.isNotBlank() }?.let { put("album", it) }
            // Sending an album artist identical to the track artist is noise; Last.fm infers it.
            albumArtist?.takeIf { it.isNotBlank() && it != artist }?.let { put("albumArtist", it) }
            durationSeconds?.takeIf { it > 0 }?.let { put("duration", it.toString()) }
            trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it.toString()) }
        }
    }

    /** A track plus the moment playback started, in Unix seconds, as scrobbling requires. */
    data class TimedTrack(val track: Track, val timestampSeconds: Long)

    class LastFmException(
        message: String,
        cause: Throwable? = null,
        val code: Int = 0,
    ) : Exception(message, cause) {
        /**
         * Whether retrying later could plausibly succeed. Authentication and validation failures
         * never fix themselves, so queued scrobbles that hit them are dropped rather than retried
         * forever.
         */
        val isTransient: Boolean
            get() = code == 0 || code == 11 || code == 16 || code == 29
    }

    companion object {
        private const val TAG = "LastFmClient"
        private const val API_ROOT = "https://ws.audioscrobbler.com/2.0/"
        private const val AUTH_ROOT = "https://www.last.fm/api/auth/"

        /** Last.fm's documented per-request cap for track.scrobble. */
        const val MAX_BATCH = 50
        private const val LOVED_PAGE_SIZE = 1000
        private const val MAX_LOVED_PAGES = 50

        /**
         * Last.fm's documented ceiling for `user.getRecentTracks`. Asking for more is answered
         * with 200 anyway, so the limit has to be respected rather than discovered.
         */
        private const val RECENT_PAGE_SIZE = 200

        /**
         * A backstop, not a budget. Two hundred thousand scrobbles is beyond a heavy decade of
         * listening; a walk that reaches this has almost certainly hit a paging bug at the far end
         * of someone's history rather than found more music.
         */
        private const val MAX_RECENT_PAGES = 1000
    }
}
