package org.akanework.gramophone.logic.data.catalog

import org.akanework.gramophone.logic.data.matching.ReleaseQuery

/**
 * Music as an outside catalogue describes it - Spotify, Deezer, a shared link, anything.
 *
 * Deliberately not Spotify's shape. Every provider is asked to translate into these two types, so
 * matching, importing and requesting are written once instead of once per service. Adding a
 * provider is then a matter of writing a translation, not of touching the code that consumes it.
 */
data class ExternalTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    /**
     * The recording's globally unique code, when the source publishes one. This is the single most
     * valuable field here: it identifies the exact recording without anyone having to agree on
     * spelling.
     */
    val isrc: String? = null,
    val durationMs: Long? = null,
    val albumReleaseYear: Int? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val albumTotalTracks: Int? = null,
    val albumType: String? = null,
    /** Which provider said so, and its own id for the track. Diagnostics and de-duplication only. */
    val providerId: String? = null,
    val providerTrackId: String? = null,
) {
    /** What to ask a downloader for. The album when there is one; the track will do otherwise. */
    fun toReleaseQuery(): ReleaseQuery = ReleaseQuery(
        artist = albumArtist?.takeIf { it.isNotBlank() } ?: artist,
        album = album?.takeIf { it.isNotBlank() },
        track = title,
        year = albumReleaseYear,
        totalTracks = albumTotalTracks,
        isrc = isrc,
    )
}

/** A release - album, EP or single - as an outside catalogue describes it. */
data class ExternalRelease(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val totalTracks: Int? = null,
    val type: String? = null,
    val artworkUrl: String? = null,
    val providerId: String? = null,
    val providerReleaseId: String? = null,
    val tracks: List<ExternalTrack> = emptyList(),
) {
    fun toReleaseQuery(): ReleaseQuery = ReleaseQuery(
        artist = artist,
        album = title,
        year = year,
        totalTracks = totalTracks,
        // Any track on the release identifies it just as well, and an ISRC beats a name.
        isrc = tracks.firstNotNullOfOrNull { it.isrc },
    )
}
