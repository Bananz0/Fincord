package org.akanework.gramophone.logic.data.lidarr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.matching.MusicText
import org.akanework.gramophone.logic.data.matching.ReleaseCandidate
import org.akanework.gramophone.logic.data.matching.ReleaseMatcher
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the user's Lidarr instance.
 *
 * This is the bridge between "a playlist mentions a song I do not own" and "that song is in my
 * Jellyfin library": Lidarr looks the album up, grabs it from the user's own indexers, and imports
 * it into the music folder Jellyfin already watches.
 *
 * Only lookup and add are implemented. Deleting or re-downloading is destructive and belongs in
 * Lidarr's own interface, not behind a menu item in a music player.
 */
class LidarrClient(
    private val store: LidarrCredentialStore,
    private val http: OkHttpClient = JellyfinClientHolder.apiHttpClient(),
) {

    class LidarrException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * An album as Lidarr's metadata source describes it, before it exists locally.
     *
     * [foreignAlbumId] is a MusicBrainz release group id - Lidarr is built on MusicBrainz - which is
     * why it doubles as [musicBrainzId] and lets a resolved id settle a match outright.
     */
    data class AlbumResult(
        val foreignAlbumId: String,
        override val title: String,
        val artistName: String,
        val foreignArtistId: String,
        override val year: Int?,
        val coverUrl: String?,
        /** True when Lidarr already tracks this album, so requesting it again is pointless. */
        val alreadyAdded: Boolean,
        /** Complete resource returned by Lidarr; POST /album requires its metadata and images. */
        val lidarrJson: String,
        override val totalTracks: Int? = null,
        /** Lidarr's own word for what kind of record this is: "Album", "EP", "Single", "Other". */
        val albumType: String? = null,
        /** Lidarr's internal id, once it tracks this album. Zero means it does not. */
        val lidarrId: Int = 0,
        /** How many of its tracks are actually on disk. Null when Lidarr does not say. */
        val trackFileCount: Int? = null,
        /**
         * Whether Lidarr is actually chasing this album. Adding an artist pulls in their whole
         * discography unmonitored, so "Lidarr knows about it" and "Lidarr is fetching it" are very
         * different things - and treating the first as the second made those albums unrequestable.
         */
        val monitored: Boolean = false,
    ) : ReleaseCandidate {
        override val artist: String get() = artistName
        override val musicBrainzId: String get() = foreignAlbumId
    }

    data class RootFolder(val path: String, val freeSpaceBytes: Long?)
    data class Profile(val id: Int, val name: String)

    /** Verifies the address and key, returning the instance version. */
    suspend fun testConnection(): String {
        val json = get("/api/v1/system/status")
        return json.optString("version").ifBlank { "unknown" }
    }

    suspend fun rootFolders(): List<RootFolder> =
        getArray("/api/v1/rootfolder").map { item ->
            RootFolder(
                path = item.optString("path"),
                freeSpaceBytes = item.optLong("freeSpace").takeIf { it > 0 },
            )
        }

    suspend fun qualityProfiles(): List<Profile> =
        getArray("/api/v1/qualityprofile").map {
            Profile(it.optInt("id"), it.optString("name"))
        }

    suspend fun metadataProfiles(): List<Profile> =
        getArray("/api/v1/metadataprofile").map {
            Profile(it.optInt("id"), it.optString("name"))
        }

    /**
     * Every album Lidarr can offer for [term], unranked.
     *
     * Three sources, because each misses what the others find. `/album/lookup` is the album search
     * proper and is the only one that reliably surfaces a record by its own title - it is what the
     * "Add New Album" box in Lidarr's own UI calls, and searching without it was the reason typing
     * an album name returned five unrelated records. `/search` is the mixed index, which is better
     * at artists and at partial names. And when the mixed index recognises the artist exactly, their
     * full album list is pulled in, because otherwise a bare artist name returns only whichever
     * handful ranked in the server's top twenty.
     */
    suspend fun lookupAlbums(term: String): List<AlbumResult> {
        if (term.isBlank()) return emptyList()
        val albums = mutableListOf<JSONObject>()

        // Both go out at once. Each is a round trip to Lidarr's metadata proxy and neither depends
        // on the other, so running them in sequence simply doubled how long the list took to appear.
        // A failure in one must not lose the other's results: a MusicBrainz id the proxy has never
        // heard of answers 404 here, which is not fatal.
        val (lookupRows, searchRows) = coroutineScope {
            val lookup = async {
                runCatching { getArray("/api/v1/album/lookup", mapOf("term" to term)) }
                    .getOrDefault(emptyList())
            }
            val search = async {
                runCatching { getArray("/api/v1/search", mapOf("term" to term)) }
                    .getOrDefault(emptyList())
            }
            lookup.await() to search.await()
        }
        albums += lookupRows
        albums += searchRows.mapNotNull { it.optJSONObject("album") }

        val needle = MusicText.compactKey(term)
        val exactArtistId = searchRows.asSequence()
            .mapNotNull { it.optJSONObject("artist") }
            .firstOrNull { MusicText.compactKey(it.optString("artistName")) == needle }
            ?.optInt("id", 0)
            ?.takeIf { it > 0 }
        if (exactArtistId != null) {
            runCatching {
                albums += getArray("/api/v1/album", mapOf("artistId" to exactArtistId.toString()))
            }
        }

        return albums.mapNotNull(::albumResult).distinctBy(AlbumResult::foreignAlbumId)
    }

    /**
     * Looks an album up by its MusicBrainz release group id.
     *
     * Lidarr's metadata proxy accepts `lidarr:<mbid>` in place of a search phrase and answers with
     * that exact release group. When an id is known this is the whole of matching: no names, no
     * ranking, no chance of the wrong record.
     */
    suspend fun lookupByMusicBrainzId(releaseGroupId: String): AlbumResult? {
        if (!MBID.matches(releaseGroupId)) return null
        return runCatching {
            getArray("/api/v1/album/lookup", mapOf("term" to "lidarr:$releaseGroupId"))
                .mapNotNull(::albumResult)
                .firstOrNull { it.foreignAlbumId.equals(releaseGroupId, ignoreCase = true) }
        }.getOrNull()
    }

    /** An artist as Lidarr's metadata source describes them. */
    data class ArtistResult(
        val foreignArtistId: String,
        val artistName: String,
        val disambiguation: String?,
        val imageUrl: String?,
        /** Lidarr's internal id once it tracks them; zero means it does not. */
        val lidarrId: Int,
    )

    /**
     * Artists matching [term], best first as Lidarr ranked them.
     *
     * Accepts `lidarr:<mbid>` in place of a name, same as the album lookup, so an artist can be
     * reached by identity when their name is spelled in a way no search will find.
     */
    suspend fun lookupArtists(term: String): List<ArtistResult> {
        if (term.isBlank()) return emptyList()
        return runCatching { getArray("/api/v1/artist/lookup", mapOf("term" to term)) }
            .getOrDefault(emptyList())
            .mapNotNull { item ->
                val foreignArtistId = item.optString("foreignArtistId").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ArtistResult(
                    foreignArtistId = foreignArtistId,
                    artistName = item.optString("artistName").ifBlank { "Unknown artist" },
                    disambiguation = item.optString("disambiguation").takeIf { it.isNotBlank() },
                    imageUrl = artistImageUrl(item),
                    lidarrId = item.optInt("id", 0),
                )
            }
            .distinctBy(ArtistResult::foreignArtistId)
    }

    /**
     * A picture of an artist, from whichever of Lidarr's two answers applies.
     *
     * For an artist it does not track, Lidarr hands back `remoteUrl`s pointing at its own image
     * cache. For one it does, it hands back container paths like `/config/MediaCover/253/poster.jpg`
     * instead - which are meaningless to a phone, and were the reason a tracked artist's page came
     * up with an empty header. Those are served over the API, so the path is rebuilt against the
     * server.
     *
     * The key goes in the query string here rather than a header because an image loader fetches
     * the URL on its own and cannot be given one. It never leaves the device: this is the user's
     * own server, addressed directly.
     */
    private fun artistImageUrl(item: JSONObject): String? {
        val images = item.optJSONArray("images") ?: return null
        val ordered = (0 until images.length())
            .mapNotNull { images.optJSONObject(it) }
            // Poster first: fanart is a wide banner and crops badly into a portrait header.
            .sortedBy { if (it.optString("coverType") == "poster") 0 else 1 }

        val lidarrId = item.optInt("id", 0).takeIf { it > 0 }
        val coverType = ordered.firstOrNull()?.optString("coverType")?.takeIf(String::isNotBlank)
            ?: "poster"
        // A tracked artist already has this image in Lidarr's MediaCover cache. Prefer that local
        // hop over downloading the original again from fanart.tv/MusicBrainz through the phone.
        if (lidarrId != null) localMediaCoverUrl("artist", lidarrId, coverType)?.let { return it }

        return ordered.firstNotNullOfOrNull {
            it.optString("remoteUrl").takeIf(String::isNotBlank)
        }
    }

    /** The albums Lidarr already tracks for one of its own artists, with their file counts. */
    suspend fun albumsOfTrackedArtist(lidarrArtistId: Int): List<AlbumResult> =
        runCatching { getArray("/api/v1/album", mapOf("artistId" to lidarrArtistId.toString())) }
            .getOrDefault(emptyList())
            .mapNotNull(::albumResult)

    /**
     * The albums Lidarr is downloading right now, by its own album id.
     *
     * Its lookup results say nothing about progress, so "requested" and "arriving" look identical
     * without this. One cheap call covers a whole page of results.
     */
    suspend fun downloadingAlbumIds(): Set<Int> = runCatching {
        val queue = get("/api/v1/queue", mapOf("pageSize" to "200"))
        val records = queue.optJSONArray("records") ?: return emptySet()
        (0 until records.length())
            .mapNotNull { records.optJSONObject(it) }
            .mapNotNull { record ->
                record.optInt("albumId", 0).takeIf { it > 0 }
                    ?: record.optJSONObject("album")?.optInt("id", 0)?.takeIf { it > 0 }
            }
            .toSet()
    }.getOrDefault(emptySet())

    /** [lookupAlbums] ordered for a person reading a list of results. */
    suspend fun searchAlbums(term: String): List<AlbumResult> =
        ReleaseMatcher.rank(ReleaseQuery.freeText(term), lookupAlbums(term)).map { it.release }

    private fun albumResult(item: JSONObject): AlbumResult? {
        val foreignAlbumId = item.optString("foreignAlbumId").takeIf { it.isNotBlank() }
            ?: return null
        val artist = item.optJSONObject("artist")
        return AlbumResult(
            foreignAlbumId = foreignAlbumId,
            title = item.optString("title").ifBlank { "Untitled" },
            artistName = artist?.optString("artistName").orEmpty(),
            foreignArtistId = artist?.optString("foreignArtistId").orEmpty(),
            year = item.optString("releaseDate").take(4).toIntOrNull(),
            coverUrl = albumImageUrl(item),
            albumType = item.optString("albumType").takeIf { it.isNotBlank() },
            // Lidarr gives an album an internal id once it is tracked; zero means it is not.
            alreadyAdded = item.optInt("id", 0) > 0,
            lidarrId = item.optInt("id", 0),
            trackFileCount = item.optJSONObject("statistics")?.optInt("trackFileCount"),
            monitored = item.optBoolean("monitored", false),
            lidarrJson = item.toString(),
            totalTracks = item.optJSONObject("statistics")?.optInt("trackCount")?.takeIf { it > 0 }
                ?: item.optJSONArray("releases")?.let { releases ->
                    (0 until releases.length())
                        .mapNotNull { releases.optJSONObject(it)?.optInt("trackCount") }
                        .filter { it > 0 }
                        .maxOrNull()
                },
        )
    }

    /** Uses Lidarr's already-downloaded cover for tracked albums, falling back to remote artwork. */
    private fun albumImageUrl(item: JSONObject): String? {
        val images = item.optJSONArray("images")
        val ordered = if (images == null) emptyList() else (0 until images.length())
            .mapNotNull { images.optJSONObject(it) }
            .sortedBy { if (it.optString("coverType") == "cover") 0 else 1 }
        val lidarrId = item.optInt("id", 0).takeIf { it > 0 }
        val coverType = ordered.firstOrNull()?.optString("coverType")?.takeIf(String::isNotBlank)
            ?: "cover"
        if (lidarrId != null) localMediaCoverUrl("album", lidarrId, coverType)?.let { return it }
        return ordered.firstNotNullOfOrNull {
            it.optString("remoteUrl").takeIf(String::isNotBlank)
        }
    }

    private fun localMediaCoverUrl(kind: String, id: Int, coverType: String): String? {
        val base = store.serverUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = store.apiKey?.takeIf { it.isNotBlank() } ?: return null
        return "$base/api/v1/mediacover/$kind/$id/$coverType.jpg?apikey=$key"
    }

    /**
     * Adds an album and asks Lidarr to start searching for it.
     *
     * The artist has to be included even when adding a single album: Lidarr's model hangs albums off
     * artists, and an artist it does not track yet has to be created in the same call. `monitor:
     * specificAlbum` keeps it to the one album rather than pulling in the whole discography, which
     * is what a request from a playlist means.
     */
    suspend fun addAlbum(album: AlbumResult): Boolean {
        if (album.alreadyAdded) return true
        val rootFolder = store.rootFolderPath
            ?: throw LidarrException("No root folder chosen")
        val payload = JSONObject(album.lidarrJson).apply {
            // A lookup result can carry a zero placeholder, but POST treats a real id as an update.
            remove("id")
            put("monitored", true)
            put("addOptions", JSONObject().put("searchForNewAlbum", true))
            val artist = optJSONObject("artist") ?: JSONObject().apply {
                put("foreignArtistId", album.foreignArtistId)
            }
            put("artist", artist.apply {
                put("qualityProfileId", store.qualityProfileId)
                put("metadataProfileId", store.metadataProfileId)
                put("rootFolderPath", rootFolder)
                put("monitored", true)
                // Do not start monitoring everything this artist releases from now on; the user
                // asked for one album.
                put("monitorNewItems", "none")
                put("addOptions", JSONObject().apply {
                    // "none" rather than anything more specific: Lidarr's MonitorTypes has no
                    // per-album value (that is Sonarr's vocabulary, and sending it fails
                    // validation), and it ignores this field entirely when albumsToMonitor is
                    // populated - which is what actually limits the request to one album.
                    put("monitor", "none")
                    put("albumsToMonitor", JSONArray().put(album.foreignAlbumId))
                    put("searchForMissingAlbums", false)
                })
            })
        }
        return try {
            post("/api/v1/album", payload)
            true
        } catch (e: LidarrException) {
            // Lidarr answers 400 with a validation message when the album is already tracked. That
            // is the desired end state, not a failure worth showing the user.
            if (e.message?.contains("already", ignoreCase = true) == true) {
                Log.d(TAG, "Album ${album.title} already tracked by Lidarr")
                true
            } else {
                throw e
            }
        }
    }

    /**
     * Starts chasing an album Lidarr already knows about.
     *
     * Adding an artist brings their whole discography in unmonitored, so most of what an artist
     * page lists is in this state: known, and being ignored. POST would be rejected as a duplicate,
     * so the album is switched to monitored and a search is asked for explicitly - which is exactly
     * what pressing the button in Lidarr's own UI does.
     */
    suspend fun monitorExistingAlbum(lidarrAlbumId: Int): Boolean {
        if (lidarrAlbumId <= 0) return false
        val current = get("/api/v1/album/$lidarrAlbumId")
        put("/api/v1/album/$lidarrAlbumId", current.put("monitored", true))
        post(
            "/api/v1/command",
            JSONObject()
                .put("name", "AlbumSearch")
                .put("albumIds", JSONArray().put(lidarrAlbumId)),
        )
        return true
    }

    private suspend fun put(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path).newBuilder()
            .put(body.toString().toRequestBody(JSON))
            .build()
        executeRaw(request)
    }

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            JSONObject(executeRaw(buildRequest(path, query)))
        }

    private suspend fun getArray(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): List<JSONObject> = withContext(Dispatchers.IO) {
        val array = JSONArray(executeRaw(buildRequest(path, query)))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private suspend fun post(path: String, body: JSONObject): String = withContext(Dispatchers.IO) {
        val request = buildRequest(path).newBuilder()
            .post(body.toString().toRequestBody(JSON))
            .build()
        executeRaw(request)
    }

    private fun buildRequest(path: String, query: Map<String, String> = emptyMap()): Request {
        val base = store.serverUrl?.takeIf { it.isNotBlank() }
            ?: throw LidarrException("Lidarr address is not set")
        val key = store.apiKey?.takeIf { it.isNotBlank() }
            ?: throw LidarrException("Lidarr API key is not set")
        val url = StringBuilder(base).append(path)
        query.entries.forEachIndexed { index, (name, value) ->
            url.append(if (index == 0) '?' else '&')
                .append(name).append('=')
                .append(java.net.URLEncoder.encode(value, "UTF-8"))
        }
        return Request.Builder()
            .url(url.toString())
            // Lidarr accepts the key as a header or a query parameter; the header keeps it out of
            // its own request log.
            .header("X-Api-Key", key)
            .build()
    }

    private fun executeRaw(request: Request): String {
        val (code, text) = try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw LidarrException("Could not reach Lidarr", e)
        }
        if (code !in 200..299) {
            throw LidarrException(describeError(code, text))
        }
        return text
    }

    /**
     * Lidarr reports validation failures as an array of {errorMessage} objects and auth failures as
     * a bare status, so the useful part has to be dug out rather than shown raw.
     */
    private fun describeError(code: Int, body: String): String {
        if (code == 401) return "Lidarr rejected the API key"
        val fromArray = runCatching {
            val array = JSONArray(body)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optString("errorMessage") }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
        val fromObject = runCatching {
            JSONObject(body).optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        // Schema failures come back in a third shape - {"errors": {"$.field": ["why"]}} - which the
        // two above miss entirely, leaving a bare "HTTP 400" that says nothing about what was wrong.
        val fromValidation = runCatching {
            val errors = JSONObject(body).optJSONObject("errors") ?: return@runCatching null
            errors.keys().asSequence().firstNotNullOfOrNull { field ->
                errors.optJSONArray(field)?.optString(0)?.takeIf { it.isNotBlank() }
                    ?.let { "$field: $it" }
            }
        }.getOrNull()
        return fromArray ?: fromObject ?: fromValidation ?: "Lidarr returned HTTP $code"
    }

    companion object {
        private const val TAG = "LidarrClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val MBID =
            Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
