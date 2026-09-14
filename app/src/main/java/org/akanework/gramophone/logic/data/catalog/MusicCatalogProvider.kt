package org.akanework.gramophone.logic.data.catalog

import android.content.Context

/**
 * A place music can be read from by link: Spotify, Deezer, and whatever comes next.
 *
 * A provider is asked two things - "is this yours?" and "what is behind it?" - and answers in the
 * shared vocabulary of [ExternalTrack] and [ExternalRelease]. Nothing outside this package needs to
 * know which service a link came from, which is the point: adding one means writing a provider and
 * listing it in [MusicCatalogProviders], and nothing else in the app changes.
 */
interface MusicCatalogProvider {

    /** Stable identifier used in logs and stored alongside imported tracks. */
    val id: String

    /** What to call this service when talking to the user. */
    val displayName: String

    /** True when [url] belongs to this service, whether or not it can actually be read. */
    fun recognises(url: String): Boolean

    /** Reads whatever [url] points at. Blocking network work; call off the main thread. */
    suspend fun resolve(context: Context, url: String): CatalogResolution
}

/** What kind of thing a link turned out to be. Requests treat each differently. */
enum class CatalogKind { PLAYLIST, ALBUM, TRACK, ARTIST }

sealed interface CatalogResolution {

    /**
     * [releases] is populated when the link named a release outright, in which case it is what to
     * request. For a playlist it is empty and the albums are inferred from [tracks].
     */
    data class Resolved(
        val label: String,
        val kind: CatalogKind,
        val tracks: List<ExternalTrack> = emptyList(),
        val releases: List<ExternalRelease> = emptyList(),
    ) : CatalogResolution

    /** This provider owns the link but could not read it - not signed in, private, offline. */
    data class Failed(val provider: String, val reason: String) : CatalogResolution

    /** This provider owns the link and never will be able to read it. */
    data class Unsupported(val provider: String, val reason: String) : CatalogResolution

    /** No provider recognised the link. */
    data object Unrecognised : CatalogResolution
}
