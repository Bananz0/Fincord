package org.akanework.gramophone.logic.data.catalog

import android.content.Context
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore

/**
 * Reads Spotify links.
 *
 * Playlists were the only shape this understood, which is why sharing an album from Spotify - the
 * obvious thing to do when you want that album - answered "that link does not point at a playlist".
 * Albums, singles, tracks and artists all resolve now, and a link naming a release outright is the
 * best possible input to a request: no matching required, the record is already named.
 */
class SpotifyCatalogProvider : MusicCatalogProvider {

    override val id = SpotifyClient.PROVIDER_ID
    override val displayName = "Spotify"

    override fun recognises(url: String): Boolean {
        val text = url.trim()
        return text.contains("open.spotify.com", ignoreCase = true) ||
            text.contains("spotify.link", ignoreCase = true) ||
            text.startsWith("spotify:", ignoreCase = true)
    }

    override suspend fun resolve(context: Context, url: String): CatalogResolution {
        val reference = SpotifyLinks.parse(url)
            ?: return CatalogResolution.Failed(
                displayName,
                "That Spotify link does not name a playlist, album, track or artist.",
            )

        val store = SpotifyCredentialStore(context)
        if (!store.hasClientId()) {
            return CatalogResolution.Failed(displayName, "Add a Spotify client id in Settings first.")
        }
        if (!store.isLinked()) {
            return CatalogResolution.Failed(displayName, "Sign in to Spotify in Settings first.")
        }

        val client = SpotifyClient(store)
        val now = System.currentTimeMillis()
        return try {
            when (reference.kind) {
                CatalogKind.PLAYLIST -> {
                    val tracks = client.playlistTracks(context, reference.id, now)
                    CatalogResolution.Resolved(
                        label = "Spotify playlist",
                        kind = CatalogKind.PLAYLIST,
                        tracks = tracks,
                    )
                }

                CatalogKind.ALBUM -> {
                    val album = client.album(context, reference.id, now)
                    CatalogResolution.Resolved(
                        label = album.title,
                        kind = CatalogKind.ALBUM,
                        tracks = album.tracks,
                        releases = listOf(album),
                    )
                }

                CatalogKind.TRACK -> {
                    val track = client.track(context, reference.id, now)
                    CatalogResolution.Resolved(
                        label = track.title,
                        kind = CatalogKind.TRACK,
                        tracks = listOf(track),
                    )
                }

                CatalogKind.ARTIST -> {
                    val releases = client.artistReleases(context, reference.id, now)
                    CatalogResolution.Resolved(
                        label = client.artistName(context, reference.id, now) ?: "Spotify artist",
                        kind = CatalogKind.ARTIST,
                        releases = releases,
                    )
                }
            }
        } catch (e: Exception) {
            CatalogResolution.Failed(displayName, e.message ?: "Could not read that Spotify link.")
        }
    }

    /** What a Spotify URL or URI points at. */
    internal data class Reference(val kind: CatalogKind, val id: String)

    internal object SpotifyLinks {

        /**
         * Handles both forms Spotify hands out - `https://open.spotify.com/album/<id>?si=…` from
         * the share sheet and `spotify:album:<id>` from the desktop client - plus the localised
         * paths (`/intl-de/album/<id>`) the mobile app now produces, which a naive
         * `open.spotify.com/album/` check misses entirely.
         */
        fun parse(url: String): Reference? {
            val match = PATTERN.find(url.trim()) ?: return null
            val kind = when (match.groupValues[1].lowercase()) {
                "playlist" -> CatalogKind.PLAYLIST
                "album" -> CatalogKind.ALBUM
                "track" -> CatalogKind.TRACK
                "artist" -> CatalogKind.ARTIST
                else -> return null
            }
            return Reference(kind, match.groupValues[2])
        }

        private val PATTERN = Regex(
            """(?i)(?:^|[:/])(playlist|album|track|artist)[:/]([A-Za-z0-9]{16,})"""
        )
    }
}
