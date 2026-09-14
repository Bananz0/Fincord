package org.akanework.gramophone.logic.data.library

import androidx.media3.common.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist

/**
 * Where the Accord screens get their library from.
 *
 * Upstream Accord reads MediaStore directly through libPhonograph's `FlowReader`, which every one
 * of its fragments and adapters collects from. This app is a Jellyfin client and nothing else, so
 * that dependency is turned into an interface: the screens collect these flows, and the server
 * fills them.
 */
interface LibraryReader {
    val songListFlow: Flow<List<MediaItem>>
    val albumListFlow: Flow<List<Album>>
    val albumArtistListFlow: Flow<List<Artist>>
    val artistListFlow: Flow<List<Artist>>
    /** Release owners derived from structured Jellyfin album-artist credits. */
    val primaryArtistListFlow: Flow<List<Artist>>
    /** Guests derived from structured per-track credits. */
    val featuredArtistListFlow: Flow<List<Artist>>
    val genreListFlow: Flow<List<Genre>>
    val dateListFlow: Flow<List<Date>>
    val playlistListFlow: Flow<List<Playlist>>

    /** Re-syncs the library from the server. */
    suspend fun refresh()
}

/**
 * Returns a usable one-shot library for menu actions.
 *
 * The reader emits an empty placeholder before the cached and server sources are ready. Calling
 * `songListFlow.first()` therefore always returned that placeholder and made Create station,
 * whole-library shuffle and other one-shot controls appear inert.
 */
suspend fun LibraryReader.songListSnapshot(timeoutMillis: Long = 2_000L): List<MediaItem> =
    withTimeoutOrNull(timeoutMillis) {
        songListFlow.first { it.isNotEmpty() }
    }.orEmpty()
