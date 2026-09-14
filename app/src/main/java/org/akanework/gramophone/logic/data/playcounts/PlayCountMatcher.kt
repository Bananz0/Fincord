package org.akanework.gramophone.logic.data.playcounts

import org.akanework.gramophone.logic.data.db.entity.CachedSong

/**
 * Resolves free-text artist/title pairs from an external service to tracks in the library.
 *
 * Built once per import from the cached library and then queried per track, because an import is
 * tens of thousands of lookups against ten thousand songs and anything per-query would be quadratic.
 *
 * The three [TrackKey] forms are tried in order and the first hit wins. A looser key that more than
 * one library track answers to is treated as no match: the point of the loose passes is to forgive
 * tagging differences, not to guess which of two songs was meant, and a wrong guess here writes a
 * wrong play count to the server where the user has no way to see it happened.
 */
class PlayCountMatcher private constructor(
    private val exact: Map<String, String>,
    private val loose: Map<String, String>,
    private val titleOnly: Map<String, String>,
    private val albumQualified: Map<String, String>,
    val librarySize: Int,
) {

    /** How a track was resolved, kept so the preview can show the user what it is trusting. */
    enum class Confidence { EXACT, LOOSE, ALBUM, TITLE_ONLY }

    data class Match(val jellyfinId: String, val confidence: Confidence)

    fun match(artist: String, title: String, album: String? = null): Match? {
        exact[TrackKey.exact(artist, title)]?.let { return Match(it, Confidence.EXACT) }
        loose[TrackKey.loose(artist, title)]?.let { return Match(it, Confidence.LOOSE) }
        // An album name narrows a shared title far better than the artist does, and services that
        // record one are usually right about it even when the artist is credited differently.
        if (!album.isNullOrBlank()) {
            albumQualified[albumKey(album, title)]?.let { return Match(it, Confidence.ALBUM) }
        }
        titleOnly[TrackKey.titleOnly(title)]?.let { return Match(it, Confidence.TITLE_ONLY) }
        return null
    }

    companion object {

        /**
         * Marks a key as claimed by more than one track.
         *
         * Ambiguity has to be recorded rather than resolved. Dropping the second track to arrive
         * would leave the first silently winning every lookup, which is the same wrong answer given
         * with more confidence.
         */
        private const val AMBIGUOUS = ""

        fun from(library: List<CachedSong>): PlayCountMatcher {
            val exact = HashMap<String, String>(library.size)
            val loose = HashMap<String, String>(library.size)
            val titleOnly = HashMap<String, String>(library.size)
            val albumQualified = HashMap<String, String>(library.size)

            library.forEach { song ->
                val title = song.title ?: return@forEach
                val artist = song.artist ?: song.albumArtist ?: ""
                val id = song.jellyfinId

                // Standard, deluxe, live and remastered editions can share an artist/title pair.
                // Treat that as ambiguous too: silently letting the last row win transfers plays
                // and favourites to an arbitrary Jellyfin item, which then contaminates mixes.
                exact.claim(TrackKey.exact(artist, title), id)
                loose.claim(TrackKey.loose(artist, title), id)
                titleOnly.claim(TrackKey.titleOnly(title), id)
                song.album?.takeIf { it.isNotBlank() }?.let {
                    albumQualified.claim(albumKey(it, title), id)
                }
                // Jellyfin keeps the full credit for a collaboration while a service may list only
                // one name, so each credited artist is registered as a way in.
                song.trackArtists?.split(ARTIST_LIST)
                    ?.map(String::trim)
                    ?.filter { it.isNotBlank() && it != artist }
                    ?.forEach { credited -> loose.claim(TrackKey.loose(credited, title), id) }
            }

            return PlayCountMatcher(
                exact = exact,
                loose = loose.withoutAmbiguous(),
                titleOnly = titleOnly.withoutAmbiguous(),
                albumQualified = albumQualified.withoutAmbiguous(),
                librarySize = library.size,
            )
        }

        private val ARTIST_LIST = "\\s*[,;/]\\s*".toRegex()

        private fun albumKey(album: String, title: String): String =
            TrackKey.exact(album, title)

        /** Records [id] under [key], unless another track already holds it. */
        private fun HashMap<String, String>.claim(key: String, id: String) {
            if (key.isBlank()) return
            val existing = this[key]
            when {
                existing == null -> this[key] = id
                existing == id -> Unit
                else -> this[key] = AMBIGUOUS
            }
        }

        private fun HashMap<String, String>.withoutAmbiguous(): Map<String, String> =
            filterValues { it != AMBIGUOUS }
    }
}
