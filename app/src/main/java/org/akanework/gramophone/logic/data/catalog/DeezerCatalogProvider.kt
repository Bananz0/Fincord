package org.akanework.gramophone.logic.data.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.json.JSONObject

/**
 * Reads Deezer links.
 *
 * Deezer's catalogue API is public - no account, no key - which makes it the easiest way to hand
 * this app a playlist. Its track objects carry ISRCs, so a Deezer link is just as precise a request
 * as a Spotify one.
 */
class DeezerCatalogProvider : MusicCatalogProvider {

    override val id = "deezer"
    override val displayName = "Deezer"

    override fun recognises(url: String): Boolean =
        url.contains("deezer.", ignoreCase = true) || url.contains("dzr.page.link", ignoreCase = true)

    override suspend fun resolve(context: Context, url: String): CatalogResolution {
        val expanded = expandShortLink(url)
        val reference = parse(expanded)
            ?: return CatalogResolution.Failed(
                displayName,
                "That Deezer link does not name a playlist, album, track or artist.",
            )
        return try {
            when (reference.first) {
                CatalogKind.PLAYLIST -> playlist(reference.second)
                CatalogKind.ALBUM -> album(reference.second)
                CatalogKind.TRACK -> track(reference.second)
                CatalogKind.ARTIST -> artist(reference.second)
            }
        } catch (e: Exception) {
            CatalogResolution.Failed(displayName, e.message ?: "Could not reach Deezer.")
        }
    }

    private suspend fun playlist(id: String): CatalogResolution {
        val json = api("playlist/$id") ?: return offline()
        json.errorReason()?.let { return CatalogResolution.Failed(displayName, it) }
        val items = json.optJSONObject("tracks")?.optJSONArray("data")
            ?: return CatalogResolution.Failed(displayName, "That playlist has no tracks.")
        val tracks = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(::trackOf)
        }
        return CatalogResolution.Resolved(
            label = json.optString("title").ifBlank { "Deezer playlist" },
            kind = CatalogKind.PLAYLIST,
            tracks = tracks,
        )
    }

    private suspend fun album(id: String): CatalogResolution {
        val json = api("album/$id") ?: return offline()
        json.errorReason()?.let { return CatalogResolution.Failed(displayName, it) }
        val release = releaseOf(json)
        val items = json.optJSONObject("tracks")?.optJSONArray("data")
        val tracks = (0 until (items?.length() ?: 0)).mapNotNull { index ->
            items?.optJSONObject(index)?.let { trackOf(it, release) }
        }
        return CatalogResolution.Resolved(
            label = release.title,
            kind = CatalogKind.ALBUM,
            tracks = tracks,
            releases = listOf(release.copy(tracks = tracks)),
        )
    }

    private suspend fun track(id: String): CatalogResolution {
        val json = api("track/$id") ?: return offline()
        json.errorReason()?.let { return CatalogResolution.Failed(displayName, it) }
        val track = trackOf(json)
        return CatalogResolution.Resolved(track.title, CatalogKind.TRACK, tracks = listOf(track))
    }

    private suspend fun artist(id: String): CatalogResolution {
        val json = api("artist/$id/albums?limit=100") ?: return offline()
        json.errorReason()?.let { return CatalogResolution.Failed(displayName, it) }
        val items = json.optJSONArray("data")
        val releases = (0 until (items?.length() ?: 0)).mapNotNull { index ->
            items?.optJSONObject(index)?.let(::releaseOf)
        }
        val name = api("artist/$id")?.optString("name").orEmpty().ifBlank { "Deezer artist" }
        return CatalogResolution.Resolved(name, CatalogKind.ARTIST, releases = releases)
    }

    private fun releaseOf(json: JSONObject) = ExternalRelease(
        title = json.optString("title").ifBlank { "Untitled" },
        artist = json.optJSONObject("artist")?.optString("name").orEmpty(),
        year = json.optString("release_date").take(4).toIntOrNull(),
        totalTracks = json.optInt("nb_tracks").takeIf { it > 0 },
        type = json.optString("record_type").takeIf { it.isNotBlank() },
        artworkUrl = json.optString("cover_xl").takeIf { it.isNotBlank() }
            ?: json.optString("cover_big").takeIf { it.isNotBlank() },
        providerId = id,
        providerReleaseId = json.optString("id").takeIf { it.isNotBlank() },
    )

    /** Deezer reports durations in seconds; everything downstream works in milliseconds. */
    private fun trackOf(json: JSONObject, release: ExternalRelease? = null): ExternalTrack {
        val album = json.optJSONObject("album")
        return ExternalTrack(
            title = json.optString("title").ifBlank { json.optString("title_short") },
            artist = json.optJSONObject("artist")?.optString("name").orEmpty()
                .ifBlank { release?.artist.orEmpty() },
            album = album?.optString("title")?.takeIf { it.isNotBlank() } ?: release?.title,
            albumArtist = release?.artist,
            isrc = json.optString("isrc").takeIf { it.isNotBlank() },
            durationMs = json.optLong("duration").takeIf { it > 0L }?.times(1_000L),
            albumReleaseYear = album?.optString("release_date")?.take(4)?.toIntOrNull()
                ?: release?.year,
            discNumber = json.optInt("disk_number").takeIf { it > 0 },
            trackNumber = json.optInt("track_position").takeIf { it > 0 },
            albumTotalTracks = release?.totalTracks,
            albumType = release?.type,
            providerId = id,
            providerTrackId = json.optString("id").takeIf { it.isNotBlank() },
        )
    }

    private fun offline() = CatalogResolution.Failed(displayName, "Could not reach Deezer.")

    private fun JSONObject.errorReason(): String? = optJSONObject("error")
        ?.optString("message")?.takeIf { it.isNotBlank() }
        ?: if (has("error")) "Deezer could not open that link - is it public?" else null

    private suspend fun api(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://api.deezer.com/$path").build()
        val body = runCatching {
            http.newCall(request).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: return@withContext null
        runCatching { JSONObject(body) }.getOrNull()
    }

    /** The share sheet hands out `deezer.page.link/…`; the real path is behind the redirect. */
    private suspend fun expandShortLink(url: String): String {
        if (!url.contains("page.link", ignoreCase = true)) return url
        return withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(url.trim()).build()).execute()
                    .use { it.request.url.toString() }
            }.getOrDefault(url)
        }
    }

    private fun parse(url: String): Pair<CatalogKind, String>? {
        val match = PATTERN.find(url.trim()) ?: return null
        val kind = when (match.groupValues[1].lowercase()) {
            "playlist" -> CatalogKind.PLAYLIST
            "album" -> CatalogKind.ALBUM
            "track" -> CatalogKind.TRACK
            "artist" -> CatalogKind.ARTIST
            else -> return null
        }
        return kind to match.groupValues[2]
    }

    private val http: OkHttpClient by lazy { JellyfinClientHolder.apiHttpClient() }

    private companion object {
        /** `deezer.com/en/album/123` and `deezer.com/album/123` alike. */
        private val PATTERN = Regex("""(?i)/(playlist|album|track|artist)/(\d+)""")
    }
}
