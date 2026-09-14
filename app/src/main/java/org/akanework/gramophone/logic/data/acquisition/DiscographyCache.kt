package org.akanework.gramophone.logic.data.acquisition

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The last discography fetched for each artist, kept across restarts.
 *
 * An artist page is expensive out of all proportion to what it shows. MusicBrainz is a donated
 * public service, so calls to it are serialised to one per second and retried when it says it is
 * busy; Lidarr adds its own round trips on top. The result is a page that spends a second or two
 * blank every single time it is opened - including the second and third time, for the same artist,
 * on the same afternoon.
 *
 * So the assembled list is written to disk and handed straight back on the next open. The network
 * work still runs and still replaces what is on screen, which is what keeps a request made
 * yesterday from showing as un-requested today; the cache only decides what is visible while that
 * happens. It also decides what is visible when the answer never comes: with the server unreachable
 * a cached discography is a great deal more use than an empty page.
 *
 * [AcquirableRelease.payload] is deliberately not stored. It is the provider's entire JSON resource,
 * which for a tracked artist is kilobytes per row, and nothing needs it from a cached row: an album
 * with an internal id is requested by that id, and one without is looked up by MusicBrainz id at the
 * moment it is asked for.
 *
 * Reads and writes touch the filesystem, so call them off the main thread.
 */
object DiscographyCache {

    private const val TAG = "DiscographyCache"

    /**
     * Not in `cacheDir`: Android empties that whenever storage is tight, which would silently
     * return the page to spending a second blank on every open.
     */
    private const val FILE_NAME = "discography_cache.json"

    private const val VERSION = 1

    /** Enough that going back and forth across a session's worth of artists always hits. */
    private const val MAX_ARTISTS = 32

    /**
     * A discography is served from disk for this long, and it is a long time on purpose: what is
     * being served is the first paint of a page that is about to be replaced by fresh data anyway.
     * The age limit exists so a genuinely abandoned entry is not still on screen a year later.
     */
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1_000

    private data class Entry(val storedAt: Long, val releases: List<AcquirableRelease>)

    private val lock = Any()

    /** Access-ordered, so the eldest entry evicted is the one least recently looked at. */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) =
            size > MAX_ARTISTS
    }

    private var loaded = false

    /** The stored discography for an artist, or null when there is none worth showing. */
    fun read(context: Context, providerId: String, artistId: String): List<AcquirableRelease>? {
        if (artistId.isBlank()) return null
        synchronized(lock) {
            ensureLoaded(context)
            val entry = entries[key(providerId, artistId)] ?: return null
            if (System.currentTimeMillis() - entry.storedAt > MAX_AGE_MS) return null
            return entry.releases.ifEmpty { null }
        }
    }

    /** Records a freshly assembled discography. An empty list is not stored; it says nothing. */
    fun write(
        context: Context,
        providerId: String,
        artistId: String,
        releases: List<AcquirableRelease>,
    ) {
        if (artistId.isBlank() || releases.isEmpty()) return
        synchronized(lock) {
            ensureLoaded(context)
            entries[key(providerId, artistId)] = Entry(System.currentTimeMillis(), releases)
            save(context)
        }
    }

    private fun key(providerId: String, artistId: String) =
        providerId.lowercase() + ":" + artistId.lowercase()

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            // A format change is a cache miss, not a migration. The contents are re-fetchable.
            if (root.optInt("version") != VERSION) return
            val artists = root.optJSONArray("artists") ?: return
            val now = System.currentTimeMillis()
            for (index in 0 until artists.length()) {
                val artist = artists.optJSONObject(index) ?: continue
                val key = artist.optString("key").takeIf { it.isNotBlank() } ?: continue
                val storedAt = artist.optLong("storedAt")
                if (now - storedAt > MAX_AGE_MS) continue
                val releases = artist.optJSONArray("releases").toReleases()
                if (releases.isNotEmpty()) entries[key] = Entry(storedAt, releases)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read the discography cache", e)
        }
    }

    private fun save(context: Context) {
        try {
            val artists = JSONArray()
            entries.forEach { (key, entry) ->
                artists.put(JSONObject().apply {
                    put("key", key)
                    put("storedAt", entry.storedAt)
                    put("releases", entry.releases.toJson())
                })
            }
            val root = JSONObject().apply {
                put("version", VERSION)
                put("artists", artists)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString())
        } catch (e: Exception) {
            Log.d(TAG, "Could not write the discography cache", e)
        }
    }

    private fun List<AcquirableRelease>.toJson() = JSONArray().also { array ->
        forEach { release ->
            array.put(JSONObject().apply {
                put("providerId", release.providerId)
                put("id", release.id)
                put("title", release.title)
                put("artist", release.artist)
                release.year?.let { put("year", it) }
                release.totalTracks?.let { put("totalTracks", it) }
                release.artworkUrl?.let { put("artworkUrl", it) }
                release.releaseType?.let { put("releaseType", it) }
                put("availability", release.availability.name)
                if (release.internalId != 0) put("internalId", release.internalId)
            })
        }
    }

    private fun JSONArray?.toReleases(): List<AcquirableRelease> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            val row = optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AcquirableRelease(
                providerId = row.optString("providerId"),
                id = id,
                title = row.optString("title").ifBlank { "Untitled" },
                artist = row.optString("artist"),
                year = row.optInt("year").takeIf { it > 0 },
                totalTracks = row.optInt("totalTracks").takeIf { it > 0 },
                artworkUrl = row.optString("artworkUrl").takeIf { it.isNotBlank() },
                releaseType = row.optString("releaseType").takeIf { it.isNotBlank() },
                availability = runCatching {
                    ReleaseAvailability.valueOf(row.optString("availability"))
                }.getOrDefault(ReleaseAvailability.NOT_REQUESTED),
                internalId = row.optInt("internalId"),
            )
        }
    }
}
