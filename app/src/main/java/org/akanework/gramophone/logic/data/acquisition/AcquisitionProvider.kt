package org.akanework.gramophone.logic.data.acquisition

import android.content.Context
import org.akanework.gramophone.logic.data.matching.ReleaseCandidate
import org.akanework.gramophone.logic.data.matching.ReleaseQuery

/**
 * A service that can go and get music the library does not have: Lidarr today, whatever the user
 * runs tomorrow.
 *
 * The contract is deliberately small - "find releases like this" and "get that one" - because that
 * is all any of them really do, and because everything above this line is then written once. The
 * screens, the link importer and the matcher all speak [AcquirableRelease] and none of them mention
 * Lidarr.
 *
 * [AcquirableRelease.payload] is the escape hatch that makes this possible: a provider round-trips
 * its own representation through it, so nothing in between has to model a provider's API.
 */
interface AcquisitionProvider {

    val id: String

    val displayName: String

    /** Whether this provider can be used, and if not, how far off it is. */
    fun readiness(context: Context): Readiness

    /**
     * Releases this provider could fetch, in its own order.
     *
     * Providers should return candidates generously and leave the judging to
     * [org.akanework.gramophone.logic.data.matching.ReleaseMatcher] - a provider that filters
     * strictly hides the one right answer behind its own idea of relevance.
     */
    suspend fun search(context: Context, query: ReleaseQuery): List<AcquirableRelease>

    /** Asks for [release]. True when it is now on its way, or was already being tracked. */
    suspend fun request(context: Context, release: AcquirableRelease): Boolean

    /**
     * Artists matching [term].
     *
     * Optional, because not every downloader has a notion of an artist separate from a release.
     * Where one does, this is what turns "I want more of this person" into one screen rather than a
     * series of guesses at album titles.
     */
    suspend fun searchArtists(context: Context, term: String): List<AcquirableArtist> = emptyList()

    /**
     * Everything [artist] has released, whether or not this provider tracks any of it yet.
     *
     * A provider may publish a useful local/server subset through [onPartial] while a slower
     * catalogue is still assembling the complete discography. Callers must treat it as a snapshot,
     * not append it: the final result can replace or enrich the same releases.
     */
    suspend fun releasesOf(
        context: Context,
        artist: AcquirableArtist,
        onPartial: suspend (List<AcquirableRelease>) -> Unit = {},
    ): List<AcquirableRelease> = emptyList()

    enum class Readiness {
        /** No address or key. Nothing works, not even searching. */
        UNCONFIGURED,

        /** Reachable, but missing the settings an actual request needs. Searching works. */
        INCOMPLETE,

        READY,
        ;

        val canSearch: Boolean get() = this != UNCONFIGURED
        val canRequest: Boolean get() = this == READY
    }
}

/**
 * How far along a release is, from the downloader's point of view.
 *
 * "Already requested" was the only distinction this used to draw, which told the user the wrong
 * thing about the majority of results: a record whose files are sitting in the library is not
 * something you might request, it is something you can play.
 */
enum class ReleaseAvailability {
    /** The downloader has never heard of it. Asking for it is what a tap means. */
    NOT_REQUESTED,

    /** Tracked and monitored, but nothing has arrived yet. */
    REQUESTED,

    /** Being fetched right now. */
    DOWNLOADING,

    /** Files exist on the server, so this is in the library or on its way into it. */
    DOWNLOADED,
}

/**
 * An artist some provider knows about.
 *
 * [id] is a MusicBrainz artist id wherever the provider is built on MusicBrainz, which is what lets
 * a discography be fetched for someone the downloader has never tracked.
 */
data class AcquirableArtist(
    val providerId: String,
    val id: String,
    val name: String,
    /** MusicBrainz's short clarifier - "Canadian R&B artist" - for telling namesakes apart. */
    val disambiguation: String? = null,
    val artworkUrl: String? = null,
    /** True when the downloader already tracks this artist. */
    val alreadyPresent: Boolean = false,
    /** The provider's internal id, where it has one. Zero when the artist is not tracked. */
    val internalId: Int = 0,
)

/**
 * A release some provider is offering to fetch.
 *
 * Implements [ReleaseCandidate] so the shared matcher can rank it without knowing where it came
 * from.
 */
data class AcquirableRelease(
    val providerId: String,
    /** The provider's own identifier - a MusicBrainz release group id, in Lidarr's case. */
    val id: String,
    override val title: String,
    override val artist: String,
    override val year: Int?,
    override val totalTracks: Int? = null,
    val artworkUrl: String? = null,
    /**
     * What kind of record this is - "Album", "EP", "Single" - in whichever vocabulary the provider
     * uses, or null when it did not say.
     *
     * Track counts were standing in for this and are not a substitute: a discography assembled from
     * metadata carries no track counts at all, so every row looked like an album, and the carousel
     * meant for albums filled up with singles.
     */
    val releaseType: String? = null,
    val availability: ReleaseAvailability = ReleaseAvailability.NOT_REQUESTED,
    /**
     * The provider's internal id, where it already holds a record for this release. Non-zero means
     * asking for it is a change of mind about something known, not a fresh add.
     */
    val internalId: Int = 0,
    /** Opaque to everyone but the provider that produced it. */
    val payload: String = "",
) : ReleaseCandidate {

    /** True when asking for this again would do nothing. */
    val alreadyPresent: Boolean get() = availability != ReleaseAvailability.NOT_REQUESTED
    override val musicBrainzId: String? get() = id.takeIf { MBID.matches(it) }

    private companion object {
        private val MBID =
            Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
