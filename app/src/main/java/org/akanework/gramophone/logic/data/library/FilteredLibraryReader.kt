package org.akanework.gramophone.logic.data.library

import android.net.Uri
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Genre

/**
 * Applies the blacklist to everything the screens read.
 *
 * Wrapping [LibraryReader] rather than filtering per screen is what makes the setting mean
 * something: browse lists, the home feed, search, stations and whole-library shuffle all collect
 * these same flows, so a blocked artist disappears from every one of them at once, and a screen
 * added later cannot forget to check.
 *
 * Playlists are deliberately left alone. A playlist is an explicit collection somebody built, and
 * [uk.akane.libphonograph.items.Playlist] has subclasses carrying their own remote state that
 * cannot be rebuilt from the outside without losing it.
 */
class FilteredLibraryReader(
    private val delegate: LibraryReader,
    private val blacklist: BlacklistStore,
) : LibraryReader {

    override val songListFlow: Flow<List<MediaItem>> =
        delegate.songListFlow.filtered { songs, artists, ids ->
            songs.filterNot { it.isBlocked(artists, ids) }
        }

    override val albumListFlow: Flow<List<Album>> =
        delegate.albumListFlow.filtered { albums, artists, ids ->
            albums.mapNotNull { album ->
                val kept = album.songList.filterNot { it.isBlocked(artists, ids) }
                when {
                    kept.isEmpty() -> null
                    kept.size == album.songList.size -> album
                    else -> FilteredAlbum(album, kept)
                }
            }
        }

    override val albumArtistListFlow: Flow<List<Artist>> = delegate.albumArtistListFlow.artists()
    override val artistListFlow: Flow<List<Artist>> = delegate.artistListFlow.artists()
    override val primaryArtistListFlow: Flow<List<Artist>> =
        delegate.primaryArtistListFlow.artists()
    override val featuredArtistListFlow: Flow<List<Artist>> =
        delegate.featuredArtistListFlow.artists()

    override val genreListFlow: Flow<List<Genre>> =
        delegate.genreListFlow.filtered { genres, artists, ids ->
            genres.mapNotNull { genre ->
                val kept = genre.songList.filterNot { it.isBlocked(artists, ids) }
                if (kept.isEmpty()) null else genre.copy(songList = kept)
            }
        }

    override val dateListFlow = delegate.dateListFlow
    override val playlistListFlow = delegate.playlistListFlow

    override suspend fun refresh() = delegate.refresh()

    private fun Flow<List<Artist>>.artists(): Flow<List<Artist>> =
        filtered { list, blockedArtists, ids ->
            list.mapNotNull { artist ->
                if (BlacklistStore.normaliseArtist(artist.title.orEmpty()) in blockedArtists) {
                    return@mapNotNull null
                }
                val kept = artist.songList.filterNot { it.isBlocked(blockedArtists, ids) }
                if (kept.isEmpty()) null else artist.copy(songList = kept)
            }
        }

    /** Re-runs [transform] whenever the library or either blacklist changes. */
    private fun <T> Flow<List<T>>.filtered(
        transform: (List<T>, Set<String>, Set<String>) -> List<T>,
    ): Flow<List<T>> =
        combine(this, blacklist.artists, blacklist.songs) { items, artists, songs ->
            if (artists.isEmpty() && songs.isEmpty()) items
            else transform(items, artists, songs)
        }

    /**
     * A track is blocked by its own id, or by any artist credited on it - the featured artist
     * matters as much as the primary one when the point is not to hear somebody.
     */
    private fun MediaItem.isBlocked(artists: Set<String>, songs: Set<String>): Boolean {
        if (mediaId in songs) return true
        if (artists.isEmpty()) return false
        val extras = mediaMetadata.extras
        val credits = buildList {
            addAll(extras?.getStringArrayList(JellyfinLibraryLoader.EXTRA_TRACK_ARTISTS).orEmpty())
            addAll(extras?.getStringArrayList(JellyfinLibraryLoader.EXTRA_ALBUM_ARTISTS).orEmpty())
            if (isEmpty()) {
                mediaMetadata.artist?.toString()?.let(::add)
                mediaMetadata.albumArtist?.toString()?.let(::add)
            }
        }
        return credits.any { BlacklistStore.normaliseArtist(it) in artists }
    }

    /** An album minus its blocked tracks, keeping the original's identity and artwork. */
    private class FilteredAlbum(
        private val source: Album,
        override val songList: List<MediaItem>,
    ) : Album {
        override val id: Long? get() = source.id
        override val title: String? get() = source.title
        override val albumArtist: String? get() = source.albumArtist
        override val albumArtistId: Long? get() = source.albumArtistId
        override val albumYear: Int? get() = source.albumYear
        override val albumAddDate: Long? get() = source.albumAddDate
        override val albumModifiedDate: Long? get() = source.albumModifiedDate
        override val cover: Uri? get() = source.cover
    }
}
