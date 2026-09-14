package uk.akane.accord.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.AcquirableRelease
import org.akanework.gramophone.logic.data.matching.ReleaseCandidate
import org.akanework.gramophone.logic.data.matching.ReleaseMatcher
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import uk.akane.libphonograph.items.Album

/**
 * Which of a downloader's results the user already owns.
 *
 * The answer is worked out once per list and then looked up by id, which is the whole point of this
 * class. Deciding per row - as the first version did, straight from the adapter's bind - meant
 * running the matcher across every album in the library each time a row scrolled into view: several
 * thousand string comparisons, on the main thread, sixty times a second. That is what made the list
 * stutter.
 *
 * Matching goes through [ReleaseMatcher] rather than comparing titles, because the downloader's
 * metadata and the media server's tags come from the same place but rarely survive the round trip
 * identically - an edition suffix here, a different artist credit there.
 */
class LibraryReleaseIndex {

    @Volatile
    private var albums: List<Album> = emptyList()

    @Volatile
    private var releases: List<AcquirableRelease> = emptyList()

    @Volatile
    private var matches: Map<String, Album> = emptyMap()

    /** The album this release is in the user's library, or null. Cheap enough to call per bind. */
    fun match(release: AcquirableRelease): Album? = matches[release.id]

    fun isInLibrary(release: AcquirableRelease): Boolean = matches.containsKey(release.id)

    /**
     * Recomputes for a new set of results. Returns true when anything changed, so the caller can
     * decide whether the list needs rebinding at all.
     */
    suspend fun index(releases: List<AcquirableRelease>): Boolean {
        this.releases = releases
        return recompute()
    }

    /** Recomputes because the library itself changed underneath the results already on screen. */
    suspend fun onLibraryChanged(albums: List<Album>): Boolean {
        this.albums = albums
        return recompute()
    }

    private suspend fun recompute(): Boolean {
        val albums = this.albums
        val releases = this.releases
        if (albums.isEmpty() || releases.isEmpty()) {
            val wasEmpty = matches.isEmpty()
            matches = emptyMap()
            return !wasEmpty
        }
        val computed = withContext(Dispatchers.Default) {
            val candidates = albums.map(::LibraryAlbumCandidate)
            releases.mapNotNull { release ->
                val query = ReleaseQuery(
                    artist = release.artist,
                    album = release.title,
                    year = release.year,
                )
                ReleaseMatcher.best(query, candidates)?.release?.album?.let { release.id to it }
            }.toMap()
        }
        if (computed.keys == matches.keys) return false
        matches = computed
        return true
    }

    /** Adapts a library album to the shared matcher, so one matcher serves both directions. */
    private class LibraryAlbumCandidate(val album: Album) : ReleaseCandidate {
        override val title get() = album.title.orEmpty()
        override val artist get() = album.albumArtist.orEmpty()
        override val year: Int? get() = null
        override val totalTracks: Int? get() = album.songList.size.takeIf { it > 0 }
        override val musicBrainzId: String? get() = null
    }
}
