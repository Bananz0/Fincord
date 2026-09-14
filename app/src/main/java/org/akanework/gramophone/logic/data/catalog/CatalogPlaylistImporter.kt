package org.akanework.gramophone.logic.data.catalog

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists
import org.akanework.gramophone.logic.data.matching.MusicText

/**
 * Recreates an outside playlist on Jellyfin out of the user's own Jellyfin tracks.
 *
 * Nothing is copied from the source but names: each track is looked up in the library, and the
 * playlist that results points at files the user already owns. Anything not in the library is
 * reported as missing, so it can be requested.
 *
 * The source is deliberately not named anywhere below - it takes [ExternalTrack], so a Deezer or
 * Apple Music playlist imports through exactly this code.
 */
object CatalogPlaylistImporter {

    private const val TAG = "CatalogPlaylistImporter"

    /**
     * [missingTracks] carries the tracks that found no local match, so the caller can offer to
     * request them - the whole point of noticing they are missing.
     */
    data class Result(
        val playlistName: String,
        val matched: Int,
        val missing: Int,
        val missingTracks: List<ExternalTrack> = emptyList(),
        val remotePlaylistId: String? = null,
    )

    /**
     * Matches [tracks] against [library] and stores the result as a private playlist.
     *
     * Runs blocking database work, so call it off the main thread.
     */
    suspend fun import(
        context: Context,
        playlistName: String,
        tracks: List<ExternalTrack>,
        library: List<MediaItem>,
        replaceExisting: Boolean = false,
    ): Result {
        val index = buildIndex(library)
        val matched = LinkedHashSet<String>()
        val missingTracks = mutableListOf<ExternalTrack>()

        tracks.forEach { track ->
            val mediaId = index.find(track)
            if (mediaId == null) {
                missingTracks += track
            } else {
                // A source playlist can list the same track twice; an imported playlist should not.
                matched.add(mediaId)
            }
        }

        val remoteId = matched.takeIf { it.isNotEmpty() }?.let { mediaIds ->
            val existing = JellyfinPlaylists.list(context)
                .firstOrNull { it.name.equals(playlistName, ignoreCase = true) }
            if (existing == null) {
                JellyfinPlaylists.create(context, playlistName, mediaIds.toList())
            } else {
                val current = JellyfinPlaylists.items(context, existing.id, library)
                    .map(MediaItem::mediaId)
                if (current == mediaIds.toList()) {
                    existing.id
                } else if (replaceExisting) {
                    // Exact replacement is user-confirmed in the controller. This is what removes
                    // stale IDs selected by an older, less precise matcher.
                    JellyfinPlaylists.replace(
                        context = context,
                        playlistId = existing.id,
                        name = playlistName,
                        mediaIds = mediaIds.toList(),
                    )
                } else {
                    // Keep the non-destructive option for playlists the user edits in Jellyfin.
                    val currentIds = current.toHashSet()
                    val additions = mediaIds.filterNot(currentIds::contains)
                    if (additions.isEmpty() || JellyfinPlaylists.addTo(
                            context,
                            existing.id,
                            additions,
                        )
                    ) existing.id else null
                }
            }
        }
        if (matched.isNotEmpty() && remoteId == null) error("Jellyfin could not create $playlistName")

        Log.d(TAG, "Imported $playlistName: ${matched.size} matched, ${missingTracks.size} missing")
        return Result(playlistName, matched.size, missingTracks.size, missingTracks, remoteId)
    }

    private fun buildIndex(library: List<MediaItem>) = LibraryIndex(library)

    /**
     * Metadata-based lookup into the library.
     *
     * Jellyfin can legitimately contain the same recording on several releases. Keep all those
     * candidates and rank them using the source's release metadata; a map keyed only by artist/title
     * silently made whichever edition Jellyfin returned first win every playlist import.
     */
    private class LibraryIndex(library: List<MediaItem>) {

        private val byRecordingTitle: Map<String, List<LibraryTrack>>

        init {
            val raw = library.mapNotNull(::libraryTrack)
            val albumSizes = raw.groupingBy(LibraryTrack::albumIdentity).eachCount()
            byRecordingTitle = raw
                .map { it.copy(albumTrackCount = albumSizes[it.albumIdentity] ?: 1) }
                .groupBy { it.title.recordingKey() }
        }

        fun find(track: ExternalTrack): String? = selectMatch(
            track,
            byRecordingTitle[track.title.recordingKey()].orEmpty(),
        )

        private fun libraryTrack(item: MediaItem): LibraryTrack? {
            val metadata = item.mediaMetadata
            val id = item.mediaId.takeIf(String::isNotBlank) ?: return null
            val title = metadata.title?.toString()?.takeIf(String::isNotBlank) ?: return null
            val artist = metadata.artist?.toString().orEmpty()
            val album = metadata.albumTitle?.toString()
            val extras = metadata.extras
            val albumId = extras?.getLong("AlbumId", Long.MIN_VALUE)
                ?.takeIf { it != Long.MIN_VALUE }
            val albumIdentity = albumId?.let { "id:$it" } ?: listOf(
                album.orEmpty().strictKey(),
                metadata.albumArtist?.toString().orEmpty().strictKey(),
                metadata.releaseYear?.toString().orEmpty(),
            ).joinToString("|")
            return LibraryTrack(
                mediaId = id,
                title = title,
                artist = artist,
                album = album,
                releaseYear = metadata.releaseYear,
                discNumber = metadata.discNumber,
                trackNumber = metadata.trackNumber,
                durationMs = extras?.getLong("Duration", -1L)?.takeIf { it > 0L },
                albumIdentity = albumIdentity,
            )
        }
    }

    internal data class LibraryTrack(
        val mediaId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val releaseYear: Int? = null,
        val discNumber: Int? = null,
        val trackNumber: Int? = null,
        val durationMs: Long? = null,
        val albumIdentity: String = album.orEmpty(),
        val albumTrackCount: Int = 1,
    )

    /** Chooses a release deterministically, or refuses to guess when the best evidence is tied. */
    internal fun selectMatch(
        track: ExternalTrack,
        candidates: List<LibraryTrack>,
    ): String? {
        if (candidates.isEmpty()) return null
        val sourceArtist = track.artist.strictKey()
        val artistMatches = candidates.filter { candidate ->
            val artist = candidate.artist.strictKey()
            sourceArtist.isNotEmpty() && artist.isNotEmpty() &&
                (artist == sourceArtist || artist.contains(sourceArtist) || sourceArtist.contains(artist))
        }
        val pool = artistMatches.ifEmpty {
            // A compilation can disagree on artist credit, but title alone is only safe when album
            // and duration both agree. Never resurrect the old arbitrary title-only fallback.
            candidates.filter { candidate ->
                albumScore(track.album, candidate.album) <= 1 &&
                    durationScore(track.durationMs, candidate.durationMs) <= 1
            }
        }
        if (pool.isEmpty()) return null

        val ranked = pool.map { it to score(track, it) }.sortedBy { it.second }
        val best = ranked.first()
        if (ranked.getOrNull(1)?.second == best.second) return null
        return best.first.mediaId
    }

    private fun score(track: ExternalTrack, candidate: LibraryTrack) = MatchScore(
        title = if (track.title.strictKey() == candidate.title.strictKey()) 0 else 1,
        artist = artistScore(track.artist, candidate.artist),
        album = albumScore(track.album, candidate.album),
        duration = durationScore(track.durationMs, candidate.durationMs),
        // Explicit deluxe/expanded labels win first. When Jellyfin's MusicBrainz metadata gives
        // both editions the same title, the release with more tracks is the best available signal.
        edition = if (candidate.album.isPreferredEdition()) 0 else 1,
        albumSize = -candidate.albumTrackCount,
        position = positionScore(track, candidate),
        releaseYear = releaseYearScore(track.albumReleaseYear, candidate.releaseYear),
        newestRelease = -(candidate.releaseYear ?: 0),
    )

    private data class MatchScore(
        val title: Int,
        val artist: Int,
        val album: Int,
        val duration: Int,
        val edition: Int,
        val albumSize: Int,
        val position: Int,
        val releaseYear: Int,
        val newestRelease: Int,
    ) : Comparable<MatchScore> {
        override fun compareTo(other: MatchScore): Int = compareValuesBy(
            this,
            other,
            MatchScore::title,
            MatchScore::artist,
            MatchScore::album,
            MatchScore::duration,
            MatchScore::edition,
            MatchScore::albumSize,
            MatchScore::position,
            MatchScore::releaseYear,
            MatchScore::newestRelease,
        )
    }

    private fun artistScore(source: String, candidate: String): Int {
        val a = source.strictKey()
        val b = candidate.strictKey()
        return when {
            a.isNotEmpty() && a == b -> 0
            a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a)) -> 1
            else -> 2
        }
    }

    private fun albumScore(source: String?, candidate: String?): Int {
        if (source.isNullOrBlank()) return 2
        if (candidate.isNullOrBlank()) return 3
        return when {
            source.strictKey() == candidate.strictKey() ||
                source.albumFamilyKey() == candidate.albumFamilyKey() -> 0
            else -> 3
        }
    }

    private fun durationScore(source: Long?, candidate: Long?): Int {
        if (source == null || candidate == null) return 2
        val difference = kotlin.math.abs(source - candidate)
        return when {
            difference <= 1_500L -> 0
            difference <= 5_000L -> 1
            else -> 4
        }
    }

    private fun positionScore(track: ExternalTrack, candidate: LibraryTrack): Int {
        val trackMatches = track.trackNumber != null && track.trackNumber == candidate.trackNumber
        val discMatches = track.discNumber != null && track.discNumber == candidate.discNumber
        return when {
            trackMatches && discMatches -> 0
            trackMatches -> 1
            track.trackNumber == null || candidate.trackNumber == null -> 2
            else -> 3
        }
    }

    private fun releaseYearScore(source: Int?, candidate: Int?): Int = when {
        source == null || candidate == null -> 2
        source == candidate -> 0
        kotlin.math.abs(source - candidate) <= 1 -> 1
        else -> 3
    }

    private fun String.recordingKey(): String = MusicText.recordingKey(this)

    private fun String.albumFamilyKey(): String = MusicText.releaseFamilyKey(this)

    private fun String?.isPreferredEdition(): Boolean = MusicText.isPreferredEdition(this)

    private fun String.strictKey(): String = MusicText.compactKey(this)
}
