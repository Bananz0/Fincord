package org.akanework.gramophone.logic.data.matching

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONArray
import org.json.JSONObject
import uk.akane.accord.BuildConfig

/**
 * Turns a streaming service's idea of a track into a MusicBrainz release group id.
 *
 * This is the piece that makes requesting reliable. Every downloader worth pointing at - Lidarr
 * included - is built on MusicBrainz, so an id settles in one call what fuzzy name matching argues
 * about forever. A Spotify track carries an ISRC, which is a globally unique code for that exact
 * recording, and MusicBrainz will hand back the releases it appears on. No account and no key.
 *
 * Nothing here is required: every method returns null on any failure, and the caller falls back to
 * name matching. MusicBrainz is a free service run on donations, so calls are serialised to its
 * published one-per-second limit and only made when names alone were not conclusive.
 */
object MusicBrainzResolver {

    private const val TAG = "MusicBrainzResolver"
    private const val ROOT = "https://musicbrainz.org/ws/2"

    /** MusicBrainz blocks clients that do not identify themselves; this is the documented form. */
    private val USER_AGENT =
        "Fincord/${BuildConfig.MY_VERSION_NAME} (based on Accord; https://github.com/AkaneTan/Accord)"

    private const val MIN_INTERVAL_MILLIS = 1_100L

    /** MusicBrainz scores out of 100; anything this far behind the leader is a different record. */
    private const val SCORE_SPREAD = 15

    private const val MINIMUM_SCORE = 70

    private val throttle = Mutex()
    private var lastCallAt = 0L

    /** A release group, which is what "an album" means once pressings are set aside. */
    data class ReleaseGroup(
        val id: String,
        val title: String,
        val artist: String,
        val year: Int?,
        val primaryType: String?,
    )

    /**
     * The release group a recording belongs to, found by its ISRC.
     *
     * A recording can appear on many releases - the album, a single, three compilations. The album
     * is what someone asking for the song actually wants, so albums are preferred and compilations
     * are used only when there is nothing else.
     */
    suspend fun releaseGroupForIsrc(isrc: String): ReleaseGroup? {
        val code = isrc.filter(Char::isLetterOrDigit).uppercase()
        if (code.length != 12) return null
        val json = get("$ROOT/isrc/$code?fmt=json&inc=releases+release-groups") ?: return null
        val recordings = json.optJSONArray("recordings") ?: return null

        val groups = mutableListOf<ReleaseGroup>()
        for (i in 0 until recordings.length()) {
            val releases = recordings.optJSONObject(i)?.optJSONArray("releases") ?: continue
            for (j in 0 until releases.length()) {
                val release = releases.optJSONObject(j) ?: continue
                val group = release.optJSONObject("release-group") ?: continue
                val id = group.optString("id").takeIf { it.isNotBlank() } ?: continue
                groups += ReleaseGroup(
                    id = id,
                    title = group.optString("title").ifBlank { release.optString("title") },
                    artist = creditedArtist(group) ?: creditedArtist(release).orEmpty(),
                    year = group.optString("first-release-date").take(4).toIntOrNull()
                        ?: release.optString("date").take(4).toIntOrNull(),
                    primaryType = group.optString("primary-type").takeIf { it.isNotBlank() },
                )
            }
        }
        return groups.distinctBy(ReleaseGroup::id).minByOrNull(::releasePreference)
    }

    /**
     * The release group matching an artist and album by name.
     *
     * Used when there is no ISRC - a Deezer playlist, or a name the user typed. MusicBrainz scores
     * its own results, but its scores are generous, so the answer is put through [ReleaseMatcher]
     * before it is trusted.
     */
    suspend fun releaseGroupFor(artist: String, album: String): ReleaseGroup? {
        if (album.isBlank()) return null
        val query = buildString {
            append("releasegroup:").append(quote(album))
            if (artist.isNotBlank()) append(" AND artist:").append(quote(artist))
        }
        val json = get("$ROOT/release-group?fmt=json&limit=10&query=" + encode(query)) ?: return null
        val rows = json.optJSONArray("release-groups") ?: return null
        val candidates = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ReleaseGroup(
                id = id,
                title = row.optString("title"),
                artist = creditedArtist(row).orEmpty(),
                year = row.optString("first-release-date").take(4).toIntOrNull(),
                primaryType = row.optString("primary-type").takeIf { it.isNotBlank() },
            )
        }
        val matched = ReleaseMatcher.best(
            ReleaseQuery(artist = artist, album = album),
            candidates.map(::Candidate),
        )
        return matched?.release?.group
    }

    /**
     * Release groups matching a phrase somebody typed.
     *
     * The last resort, for when the downloader's own index has nothing. MusicBrainz searches title
     * and artist credit together and scores what it finds, but generously - a bare artist name
     * returns whatever album happens to be titled after them at full score - so only results close
     * to the leader are kept, and the caller still ranks what comes back.
     */
    suspend fun releaseGroupsFor(text: String, limit: Int = 5): List<ReleaseGroup> {
        if (text.isBlank()) return emptyList()
        val json = get("$ROOT/release-group?fmt=json&limit=10&query=" + encode(text))
            ?: return emptyList()
        val rows = json.optJSONArray("release-groups") ?: return emptyList()
        val scored = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            row.optInt("score") to ReleaseGroup(
                id = id,
                title = row.optString("title"),
                artist = creditedArtist(row).orEmpty(),
                year = row.optString("first-release-date").take(4).toIntOrNull(),
                primaryType = row.optString("primary-type").takeIf { it.isNotBlank() },
            )
        }
        val best = scored.maxOfOrNull { it.first } ?: return emptyList()
        return scored
            .filter { it.first >= (best - SCORE_SPREAD) && it.first >= MINIMUM_SCORE }
            .sortedWith(compareByDescending<Pair<Int, ReleaseGroup>> { it.first }
                .thenBy { releasePreference(it.second) })
            .map { it.second }
            .take(limit)
    }

    /**
     * Everything an artist has released, newest first.
     *
     * This is what makes an artist page possible for someone the downloader has never tracked: it
     * has no discography to offer until it does, whereas MusicBrainz - which it is built on - will
     * list one for anybody. Compilations and other people's records the artist merely appears on
     * are left out; they belong to whoever they are credited to.
     */
    suspend fun releaseGroupsOfArtist(artistId: String, limit: Int = 100): List<ReleaseGroup> {
        if (artistId.isBlank()) return emptyList()
        val cacheKey = artistId.lowercase() + ":" + limit
        cachedArtistReleases(cacheKey)?.let { return it }
        val json = get(
            "$ROOT/release-group?fmt=json&limit=$limit&artist=" + encode(artistId)
        ) ?: return emptyList()
        val result = artistReleaseGroups(json.optJSONArray("release-groups"))
        remember(cacheKey, result)
        return result
    }

    /**
     * Turns MusicBrainz's `release-groups` array into an artist page's worth of records.
     *
     * Separate from the fetch because the array does not always come from MusicBrainz directly:
     * the Jellyfin plugin caches the same array for the whole server, and the two paths are only
     * interchangeable if what arrives is read, filtered and ordered by the same code. Which
     * releases belong on an artist's page is a client decision and stays here - a change of mind
     * about live albums should not need a server redeploy.
     */
    fun artistReleaseGroups(rows: JSONArray?): List<ReleaseGroup> {
        if (rows == null) return emptyList()
        return (0 until rows.length())
            .mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val secondary = row.optJSONArray("secondary-types")
                    ?.let { types -> (0 until types.length()).map { types.optString(it) } }
                    .orEmpty()
                // A compilation or a live album is rarely what someone browsing a discography
                // meant, and including them buries the records that are. Every other secondary
                // type stays: dropping all of them cost the artist their actual records.
                if (secondary.any { it.lowercase() in EXCLUDED_SECONDARY_TYPES }) {
                    return@mapNotNull null
                }
                ReleaseGroup(
                    id = id,
                    title = row.optString("title"),
                    artist = creditedArtist(row).orEmpty(),
                    year = row.optString("first-release-date").take(4).toIntOrNull(),
                    primaryType = row.optString("primary-type").takeIf { it.isNotBlank() },
                )
            }
            .distinctBy(ReleaseGroup::id)
            .sortedWith(compareBy<ReleaseGroup> { releasePreference(it) }
                .thenByDescending { it.year ?: 0 })
    }

    /**
     * Records a discography that came from somewhere else under the key [releaseGroupsOfArtist]
     * looks for, so a second page for the same artist does not go back out to the network at all.
     */
    fun remember(artistId: String, releases: List<ReleaseGroup>, limit: Int = 100) {
        if (artistId.isBlank() || releases.isEmpty()) return
        remember(artistId.lowercase() + ":" + limit, releases)
    }

    private fun remember(cacheKey: String, releases: List<ReleaseGroup>) {
        synchronized(artistReleaseCache) {
            artistReleaseCache[cacheKey] = ArtistReleaseCache(System.currentTimeMillis(), releases)
        }
    }

    /**
     * Secondary types that mean "not one of this artist's own records".
     *
     * The list used to be "has any secondary type at all", which reads as a reasonable proxy and is
     * not one: MusicBrainz files a mixtape as an Album with the secondary type Mixtape/Street, so
     * the blanket rule threw away the only album some artists have. Bhad Bhabie's discography came
     * back as twenty-five singles and no `15`. A remix or a soundtrack is likewise a record the
     * artist made, and belongs on their page.
     */
    private val EXCLUDED_SECONDARY_TYPES = setOf(
        "compilation",
        "live",
        "dj-mix",
        "interview",
        "audiobook",
        "audio drama",
        "spokenword",
    )

    /** Albums first, then EPs and singles, then the compilations and live records nobody meant. */
    private fun releasePreference(group: ReleaseGroup): Int = when {
        group.primaryType.equals("Album", ignoreCase = true) -> 0
        group.primaryType.equals("EP", ignoreCase = true) -> 1
        group.primaryType.equals("Single", ignoreCase = true) -> 2
        group.primaryType == null -> 3
        else -> 4
    }

    private fun creditedArtist(json: JSONObject): String? {
        val credits = json.optJSONArray("artist-credit") ?: return null
        return (0 until credits.length())
            .mapNotNull { credits.optJSONObject(it) }
            .joinToString("") { credit ->
                val name = credit.optString("name").ifBlank {
                    credit.optJSONObject("artist")?.optString("name").orEmpty()
                }
                name + credit.optString("joinphrase")
            }
            .takeIf { it.isNotBlank() }
    }

    /** Lucene syntax: the phrase is quoted, so anything that would end the quote has to go. */
    private fun quote(text: String): String =
        "\"" + text.replace('"', ' ').replace('\\', ' ').trim() + "\""

    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, "UTF-8")

    /**
     * One request, retried once when the service says it is busy.
     *
     * MusicBrainz answers 503 with "currently busy" fairly readily - it happened twice in a handful
     * of calls while this was being written - and a single retry a second later turns almost all of
     * those into answers. Anything else is given up on immediately: this is a best-effort fallback,
     * and a resolver that threw would take the whole request down with it.
     */
    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        repeat(ATTEMPTS) { attempt ->
            val result = try {
                waitForSlot()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()
                val (code, body) = http.newCall(request).execute()
                    .use { it.code to it.body?.string().orEmpty() }
                when {
                    code == 503 && attempt < ATTEMPTS - 1 -> BUSY
                    code !in 200..299 -> {
                        Log.d(TAG, "MusicBrainz returned $code")
                        null
                    }
                    body.isBlank() -> null
                    else -> JSONObject(body)
                }
            } catch (e: Exception) {
                Log.d(TAG, "MusicBrainz lookup failed", e)
                null
            }
            if (result !== BUSY) return@withContext result as JSONObject?
            delay(BUSY_BACKOFF_MILLIS)
        }
        null
    }

    private const val ATTEMPTS = 2
    private const val BUSY_BACKOFF_MILLIS = 900L

    /** Distinguishes "try again" from "there is no answer"; both are non-results to the caller. */
    private val BUSY = JSONObject()

    private suspend fun waitForSlot() = throttle.withLock {
        val since = System.currentTimeMillis() - lastCallAt
        if (since in 0 until MIN_INTERVAL_MILLIS) delay(MIN_INTERVAL_MILLIS - since)
        lastCallAt = System.currentTimeMillis()
    }

    private val http: OkHttpClient by lazy { JellyfinClientHolder.apiHttpClient() }

    private data class ArtistReleaseCache(
        val storedAt: Long,
        val releases: List<ReleaseGroup>,
    )

    /** Reopening an artist should be instant and should not spend MusicBrainz's public rate limit. */
    private val artistReleaseCache = object : LinkedHashMap<String, ArtistReleaseCache>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtistReleaseCache>?) =
            size > ARTIST_CACHE_SIZE
    }

    private fun cachedArtistReleases(key: String): List<ReleaseGroup>? =
        synchronized(artistReleaseCache) {
            artistReleaseCache[key]?.takeIf {
                System.currentTimeMillis() - it.storedAt < ARTIST_CACHE_MAX_AGE_MS
            }?.releases
        }

    private const val ARTIST_CACHE_SIZE = 24
    private const val ARTIST_CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1_000L

    /** Adapts a release group to the shared matcher without leaking the matcher into the API. */
    private class Candidate(val group: ReleaseGroup) : ReleaseCandidate {
        override val title get() = group.title
        override val artist get() = group.artist
        override val year get() = group.year
        override val totalTracks: Int? get() = null
        override val musicBrainzId get() = group.id
    }
}
