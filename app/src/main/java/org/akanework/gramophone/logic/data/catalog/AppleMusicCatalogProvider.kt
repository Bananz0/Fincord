package org.akanework.gramophone.logic.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONObject

/**
 * Reads Apple Music links.
 *
 * The Apple Music API proper needs a developer token signed with a private key from a paid account,
 * which an open-source client cannot ship - which is why these links used to be recognised and then
 * refused. The iTunes Search API, however, is public, unauthenticated, and indexes the same
 * catalogue under the same numeric ids that appear in every Apple Music URL. Albums, songs and
 * artists resolve through it.
 *
 * Playlists are the one thing it cannot reach: Apple identifies those by an opaque `pl.` token that
 * exists only in the authenticated API.
 */
class AppleMusicCatalogProvider : MusicCatalogProvider {

    override val id = "apple-music"
    override val displayName = "Apple Music"

    override fun recognises(url: String): Boolean =
        url.contains("music.apple.com", ignoreCase = true) ||
            url.contains("itunes.apple.com", ignoreCase = true)

    override suspend fun resolve(context: Context, url: String): CatalogResolution {
        val trimmed = url.trim()
        if (PLAYLIST.containsMatchIn(trimmed)) {
            return CatalogResolution.Unsupported(
                displayName,
                "Apple identifies playlists only inside its authenticated API, which needs a paid" +
                    " developer account's signing key. Albums, songs and artists work.",
            )
        }

        // `?i=<id>` names one song on an album page, and is what the share sheet produces when a
        // song rather than the album is shared. It wins over the album id in the path.
        val songId = SONG_PARAMETER.find(trimmed)?.groupValues?.get(1)
        val pathMatch = PATH.find(trimmed)
        val kind = pathMatch?.groupValues?.get(1)?.lowercase()
        val pathId = pathMatch?.groupValues?.get(2)

        return try {
            when {
                songId != null -> song(songId)
                kind == "album" && pathId != null -> album(pathId)
                kind == "song" && pathId != null -> song(pathId)
                kind == "artist" && pathId != null -> artist(pathId)
                else -> CatalogResolution.Failed(
                    displayName,
                    "That Apple Music link does not name an album, song or artist.",
                )
            }
        } catch (e: Exception) {
            CatalogResolution.Failed(displayName, e.message ?: "Could not reach Apple Music.")
        }
    }

    private suspend fun album(collectionId: String): CatalogResolution {
        val rows = lookup("id=$collectionId&entity=song&limit=200")
            ?: return CatalogResolution.Failed(displayName, "Could not reach Apple Music.")
        val collection = rows.firstOrNull { it.optString("wrapperType") == "collection" }
            ?: return CatalogResolution.Failed(displayName, "Apple Music does not know that album.")
        val release = releaseOf(collection)
        val tracks = rows
            .filter { it.optString("wrapperType") == "track" }
            .map { trackOf(it, release) }
        return CatalogResolution.Resolved(
            label = release.title,
            kind = CatalogKind.ALBUM,
            tracks = tracks,
            releases = listOf(release.copy(tracks = tracks)),
        )
    }

    private suspend fun song(trackId: String): CatalogResolution {
        val row = lookup("id=$trackId&entity=song")?.firstOrNull()
            ?: return CatalogResolution.Failed(displayName, "Apple Music does not know that song.")
        val track = trackOf(row, null)
        return CatalogResolution.Resolved(track.title, CatalogKind.TRACK, tracks = listOf(track))
    }

    private suspend fun artist(artistId: String): CatalogResolution {
        val rows = lookup("id=$artistId&entity=album&limit=200")
            ?: return CatalogResolution.Failed(displayName, "Could not reach Apple Music.")
        val name = rows.firstOrNull { it.optString("wrapperType") == "artist" }
            ?.optString("artistName").orEmpty().ifBlank { "Apple Music artist" }
        val releases = rows
            .filter { it.optString("wrapperType") == "collection" }
            .map(::releaseOf)
        return CatalogResolution.Resolved(name, CatalogKind.ARTIST, releases = releases)
    }

    private fun releaseOf(json: JSONObject) = ExternalRelease(
        title = json.optString("collectionName").ifBlank { "Untitled" },
        artist = json.optString("artistName"),
        year = json.optString("releaseDate").take(4).toIntOrNull(),
        totalTracks = json.optInt("trackCount").takeIf { it > 0 },
        type = json.optString("collectionType").takeIf { it.isNotBlank() },
        artworkUrl = json.optString("artworkUrl100").takeIf { it.isNotBlank() }
            // The API only offers 100px art; the same path at 600px is the documented convention.
            ?.replace("100x100", "600x600"),
        providerId = id,
        providerReleaseId = json.optString("collectionId").takeIf { it.isNotBlank() },
    )

    private fun trackOf(json: JSONObject, release: ExternalRelease?) = ExternalTrack(
        title = json.optString("trackName").ifBlank { json.optString("trackCensoredName") },
        artist = json.optString("artistName"),
        album = json.optString("collectionName").takeIf { it.isNotBlank() } ?: release?.title,
        albumArtist = json.optString("collectionArtistName").takeIf { it.isNotBlank() }
            ?: release?.artist,
        durationMs = json.optLong("trackTimeMillis").takeIf { it > 0L },
        albumReleaseYear = json.optString("releaseDate").take(4).toIntOrNull() ?: release?.year,
        discNumber = json.optInt("discNumber").takeIf { it > 0 },
        trackNumber = json.optInt("trackNumber").takeIf { it > 0 },
        albumTotalTracks = json.optInt("trackCount").takeIf { it > 0 } ?: release?.totalTracks,
        albumType = release?.type,
        providerId = id,
        providerTrackId = json.optString("trackId").takeIf { it.isNotBlank() },
    )

    private suspend fun lookup(query: String): List<JSONObject>? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://itunes.apple.com/lookup?$query").build()
        val body = runCatching {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull()
            ?: return@withContext null
        (0 until results.length()).mapNotNull(results::optJSONObject)
    }

    private val http: OkHttpClient by lazy { JellyfinClientHolder.apiHttpClient() }

    private companion object {
        private val PLAYLIST = Regex("""(?i)/playlist/""")
        /** The slug between kind and id is decorative, and newer share links leave it out. */
        private val PATH = Regex("""(?i)/(album|song|artist)/(?:[^/?#]*/)?(\d+)""")
        private val SONG_PARAMETER = Regex("""(?i)[?&]i=(\d+)""")
    }
}
