package uk.akane.accord.logic

import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.utils.splitArtistTag

/** A consistent interpretation of album ownership and per-track guest credits. */
object ArtistCredits {

    fun primaryArtist(item: MediaItem): String =
        albumArtists(item).firstOrNull()
            ?: trackArtists(item).firstOrNull()
            ?: "(Unknown Artist)"

    fun trackArtists(item: MediaItem): List<String> {
        val extras = item.mediaMetadata.extras
        val explicit = extras
            ?.getStringArrayList(JellyfinLibraryLoader.EXTRA_TRACK_ARTISTS)
            .orEmpty()
        return (explicit.flatMap(::split)
            .ifEmpty { split(item.mediaMetadata.artist?.toString()) })
            .distinctBy { it.normalised() }
    }

    fun albumArtists(item: MediaItem): List<String> {
        val extras = item.mediaMetadata.extras
        val explicit = extras
            ?.getStringArrayList(JellyfinLibraryLoader.EXTRA_ALBUM_ARTISTS)
            .orEmpty()
        return (explicit.flatMap(::split)
            .ifEmpty { split(item.mediaMetadata.albumArtist?.toString()) })
            .distinctBy { it.normalised() }
    }

    fun featuredArtists(item: MediaItem): List<String> {
        val primary = primaryArtist(item).normalised()
        return (albumArtists(item).drop(1) + trackArtists(item)).filter { credit ->
            val key = credit.normalised()
            // The first album artist owns the release. Additional names on either the album or
            // track credit are appearances, not separate composite discography owners.
            key != primary
        }.distinctBy { it.normalised() }
    }

    fun isPrimaryArtist(item: MediaItem, artist: String): Boolean =
        primaryArtist(item).equals(artist, ignoreCase = true)

    fun isFeaturedArtist(item: MediaItem, artist: String): Boolean =
        featuredArtists(item).any { it.equals(artist, ignoreCase = true) }

    /**
     * The acts one credit names.
     *
     * A Jellyfin artist id was once taken as proof that a credit was one server entity and left
     * unread. It is not: the server mints an entity per value of the artist tag, so a file tagged
     * "Asake; DJ Snake" in one field becomes one artist under that name with an id of its own.
     * Reading every credit the same way keeps this in step with [org.akanework.gramophone.logic
     * .utils.LibraryGrouper], which the browse lists are built from - when the two disagreed, an
     * album's rows could not find the artist page they belonged to.
     */
    private fun split(value: String?): List<String> {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return emptyList()
        return text.splitArtistTag()
            .map { it.trim(',', '·').trim() }
            .filter(String::isNotBlank)
            .distinctBy { it.normalised() }
    }

    private fun String.normalised(): String =
        lowercase().filter { it.isLetterOrDigit() }
}
