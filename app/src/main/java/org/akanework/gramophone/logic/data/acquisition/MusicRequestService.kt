package org.akanework.gramophone.logic.data.acquisition

import android.content.Context
import android.util.Log
import org.akanework.gramophone.logic.data.catalog.ExternalRelease
import org.akanework.gramophone.logic.data.catalog.ExternalTrack
import org.akanework.gramophone.logic.data.matching.MusicBrainzResolver
import org.akanework.gramophone.logic.data.matching.MusicText
import org.akanework.gramophone.logic.data.matching.ReleaseMatcher
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import org.akanework.gramophone.logic.data.matching.ScoredRelease

/**
 * Turns "music I do not have" into requests, whoever is going to fetch it.
 *
 * Downloaders work in releases, not tracks - they grab an album, not a song - so a list of missing
 * tracks collapses to the set of records they came from. Twenty missing tracks off one album is one
 * request, which is also what a person would have done by hand.
 *
 * The part that matters is what happens when the names do not settle it. The old code took the
 * first result whose title contained the query and sent that, which is why requests arrived as the
 * wrong album or as nothing at all. Here a request is only sent when the match is convincing; when
 * it is not, MusicBrainz is asked to turn the track's ISRC into a release group id, and the
 * downloader is asked for that id instead. Anything still unresolved is reported rather than
 * guessed at.
 */
object MusicRequestService {

    private const val TAG = "MusicRequestService"

    /**
     * Below this, the best thing the downloader offered does not resemble the request closely
     * enough to call it found, and the metadata service is worth the extra second.
     */
    private const val RECOGNISABLE = 0.6

    /** One thing that could not be matched, in terms worth showing a person. */
    data class Unresolved(val artist: String, val title: String)

    data class Outcome(
        val requested: Int = 0,
        /** Already tracked by the downloader; the user has nothing left to do about these. */
        val alreadyPresent: Int = 0,
        val unresolved: List<Unresolved> = emptyList(),
        val failureReason: String? = null,
    ) {
        val notFound: Int get() = unresolved.size
        val didSomething: Boolean get() = requested > 0 || alreadyPresent > 0
    }

    /** Requests the releases behind [tracks]. Blocking network work; call off the main thread. */
    suspend fun requestTracks(
        context: Context,
        tracks: List<ExternalTrack>,
        provider: AcquisitionProvider = AcquisitionProviders.active(context),
    ): Outcome = request(context, tracks.map(ExternalTrack::toReleaseQuery), provider)

    /** Requests [releases] named outright - a shared album link, an artist's discography. */
    suspend fun requestReleases(
        context: Context,
        releases: List<ExternalRelease>,
        provider: AcquisitionProvider = AcquisitionProviders.active(context),
    ): Outcome = request(context, releases.map(ExternalRelease::toReleaseQuery), provider)

    suspend fun request(
        context: Context,
        queries: List<ReleaseQuery>,
        provider: AcquisitionProvider = AcquisitionProviders.active(context),
    ): Outcome {
        if (queries.isEmpty()) return Outcome()
        if (!provider.readiness(context).canRequest) {
            return Outcome(
                unresolved = queries.map { Unresolved(it.artist, it.subject) },
                failureReason = "${provider.displayName} is not set up yet",
            )
        }

        var requested = 0
        var alreadyPresent = 0
        val unresolved = mutableListOf<Unresolved>()
        val handled = mutableSetOf<String>()

        collapse(queries).forEach { query ->
            val match = try {
                resolve(context, provider, query)
            } catch (e: Exception) {
                Log.w(TAG, "Lookup failed for ${query.artist} - ${query.subject}", e)
                null
            }
            if (match == null) {
                unresolved += Unresolved(query.artist, query.subject)
                return@forEach
            }
            // Two tracks can name different albums that resolve to the same release.
            if (!handled.add(match.id)) return@forEach
            if (match.alreadyPresent) {
                alreadyPresent++
                return@forEach
            }
            try {
                if (provider.request(context, match)) requested++
                else unresolved += Unresolved(query.artist, query.subject)
            } catch (e: Exception) {
                Log.w(TAG, "Could not request ${match.title}", e)
                unresolved += Unresolved(query.artist, query.subject)
            }
        }

        Log.d(TAG, "Requested $requested, already had $alreadyPresent, ${unresolved.size} unresolved")
        return Outcome(requested, alreadyPresent, unresolved)
    }

    /**
     * Everything [provider] could offer for [query], best first.
     *
     * The downloader's own index is asked first, because it is fast and usually right. When it
     * comes back with nothing at all, MusicBrainz is asked instead and its answers are looked up by
     * id - and that is not a nicety. Lidarr's text search finds nothing whatsoever for "U, Me & My
     * Ego" or even for the artist "Chxrry", while MusicBrainz returns the record at full score and
     * Lidarr resolves the very same id happily. The two are built on the same database; only the
     * search in front of it disagrees.
     *
     * [useMetadataFallback] exists because that second path is rate limited to one call a second.
     * A search box firing on every keystroke should leave it off until the typing settles.
     */
    suspend fun find(
        context: Context,
        query: ReleaseQuery,
        provider: AcquisitionProvider = AcquisitionProviders.active(context),
        useMetadataFallback: Boolean = true,
    ): List<ScoredRelease<AcquirableRelease>> {
        val direct = ReleaseMatcher.rank(query, provider.search(context, query))
        if (!useMetadataFallback || foundSomethingReal(direct)) return direct
        // Whatever the downloader returned was filler. Keep it below the real answers rather than
        // discarding it - it cost nothing and the user may still recognise something.
        return (viaMusicBrainz(context, provider, query) + direct).distinctBy { it.release.id }
    }

    /**
     * Whether these results are near-misses rather than the thing that was asked for.
     *
     * An empty list is the obvious case, but the one that actually bites is a full list of
     * near-misses: Lidarr answers "U, Me & My Ego" with "U, Me and Madonna EP" and five more like
     * it, so a non-empty list is no evidence at all that the search worked. Callers that search as
     * the user types run the cheap pass first and use this to decide whether the slow, rate-limited
     * second opinion is worth taking.
     */
    fun needsSecondOpinion(ranked: List<ScoredRelease<AcquirableRelease>>): Boolean =
        !foundSomethingReal(ranked)

    private fun foundSomethingReal(ranked: List<ScoredRelease<AcquirableRelease>>): Boolean {
        val leader = ranked.firstOrNull() ?: return false
        return leader.isAutomatic || leader.relevance >= RECOGNISABLE
    }

    /**
     * Looks the query up in MusicBrainz and asks the downloader for the ids that come back.
     *
     * Results are scored against a query carrying the resolved id, so a match found this way is
     * [org.akanework.gramophone.logic.data.matching.MatchConfidence.EXACT] rather than an argument
     * about spelling.
     */
    private suspend fun viaMusicBrainz(
        context: Context,
        provider: AcquisitionProvider,
        query: ReleaseQuery,
    ): List<ScoredRelease<AcquirableRelease>> {
        val groups = buildList {
            query.isrc?.let { isrc -> MusicBrainzResolver.releaseGroupForIsrc(isrc)?.let(::add) }
            if (isEmpty() && query.albumKnown) {
                MusicBrainzResolver.releaseGroupFor(query.artist, query.album.orEmpty())?.let(::add)
            }
            if (isEmpty()) {
                val phrase = query.freeText
                    ?: listOf(query.artist, query.subject).filter(String::isNotBlank)
                        .joinToString(" ")
                addAll(MusicBrainzResolver.releaseGroupsFor(phrase))
            }
        }.distinctBy(MusicBrainzResolver.ReleaseGroup::id)

        return groups.flatMap { group ->
            // The names MusicBrainz holds are the ones the downloader's catalogue uses, so they
            // are better search terms than the streaming service's spelling.
            val byId = query.copy(
                musicBrainzReleaseGroupId = group.id,
                album = group.title.takeIf { it.isNotBlank() } ?: query.album,
                artist = group.artist.takeIf { it.isNotBlank() } ?: query.artist,
                freeText = null,
            )
            ReleaseMatcher.rank(byId, provider.search(context, byId))
        }.distinctBy { it.release.id }
    }

    /** The one release [query] means, or null when nothing is convincing enough to send unasked. */
    private suspend fun resolve(
        context: Context,
        provider: AcquisitionProvider,
        query: ReleaseQuery,
    ): AcquirableRelease? =
        find(context, query, provider).firstOrNull(ScoredRelease<AcquirableRelease>::isAutomatic)
            ?.release

    /**
     * One query per distinct release.
     *
     * Queries carrying an album name collapse by that album; the rest stay as they are, because two
     * different singles by one artist are two different requests.
     */
    internal fun collapse(queries: List<ReleaseQuery>): List<ReleaseQuery> {
        val byRelease = LinkedHashMap<String, ReleaseQuery>()
        val loose = mutableListOf<ReleaseQuery>()
        queries.forEach { query ->
            val artist = query.artist.trim()
            val album = query.album?.trim().orEmpty()
            if (artist.isBlank() && album.isBlank() && query.track.isNullOrBlank()) return@forEach
            if (album.isBlank()) {
                loose += query.copy(artist = artist)
                return@forEach
            }
            val key = MusicText.compactKey(artist) + "|" + MusicText.releaseFamilyKey(album)
            val existing = byRelease[key]
            byRelease[key] = when {
                existing == null -> query.copy(artist = artist)
                // Keep whichever copy carries an ISRC; it is the one that can be resolved exactly.
                existing.isrc == null && query.isrc != null -> existing.copy(isrc = query.isrc)
                else -> existing
            }
        }
        return byRelease.values + loose.distinctBy {
            MusicText.compactKey(it.artist) + "|" + MusicText.recordingKey(it.track.orEmpty())
        }
    }
}
