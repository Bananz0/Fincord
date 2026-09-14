package org.akanework.gramophone.logic.data.matching

import kotlin.math.abs

/**
 * Ranks whatever a downloader offered against what was actually asked for.
 *
 * Two questions, deliberately kept apart. [rank] orders results for a person to look at and is
 * generous. [best] answers "may this be added without asking?" and is not: it returns a match only
 * when the evidence is strong enough that a wrong album would be a surprise. The old matcher
 * conflated the two - it took the first result whose title merely *contained* the query - which is
 * how a request for one album quietly fetched a different one.
 */
object ReleaseMatcher {

    /** Below this, two names are not the same name, whatever else agrees. */
    private const val PLAUSIBLE = 0.60

    /** At or above this, the names agree closely enough to act on. */
    private const val CONVINCING = 0.82

    /** At or above this, the names are the same but for spelling. */
    private const val CERTAIN = 0.95

    /** An artist credit this close counts as agreement; compilations are handled separately. */
    private const val ARTIST_AGREES = 0.55

    /** Nothing below this is worth showing at all - it is noise the server threw in. */
    private const val NOISE_FLOOR = 0.25

    /** Shorter words carry no evidence on their own; "u", "me" and "my" are in half the catalogue. */
    private const val SUBSTANTIAL_WORD = 4

    /** Sentinel for "the query did not say", kept out of the 0..1 range on purpose. */
    internal const val UNKNOWN = -1.0

    /** Ordered best first, with the server's noise dropped. */
    fun <T : ReleaseCandidate> rank(
        query: ReleaseQuery,
        candidates: List<T>,
    ): List<ScoredRelease<T>> {
        val scored = candidates.map { score(query, it) }
        val meaningful = scored.filter { it.confidence != MatchConfidence.NONE }
        val pool = meaningful.ifEmpty {
            // Free text can legitimately match nothing precisely - a misremembered title still
            // deserves the closest few rows rather than a bare "nothing found". Structured queries
            // get no such licence: a wrong album is worse than no album.
            if (query.freeText != null) {
                scored.filter { it.relevance >= NOISE_FLOOR }
            } else {
                emptyList()
            }
        }
        return pool.sortedWith(ordering(query))
    }

    /**
     * The one release safe to request unattended, or null.
     *
     * Only [MatchConfidence.EXACT] and [MatchConfidence.STRONG] qualify. Everything else is shown
     * to the user instead of acted on, which is the difference between a request that works and a
     * library full of albums nobody asked for.
     */
    fun <T : ReleaseCandidate> best(query: ReleaseQuery, candidates: List<T>): ScoredRelease<T>? =
        rank(query, candidates).firstOrNull(ScoredRelease<T>::isAutomatic)

    internal fun <T : ReleaseCandidate> score(query: ReleaseQuery, candidate: T): ScoredRelease<T> {
        val artistSimilarity = if (query.artist.isBlank()) {
            UNKNOWN
        } else {
            MusicText.similarity(query.artist, candidate.artist)
        }

        val titleSimilarity = when {
            query.albumKnown -> nameSimilarity(query.album.orEmpty(), candidate.title)
            !query.track.isNullOrBlank() -> nameSimilarity(query.track, candidate.title)
            else -> nameSimilarity(query.freeText.orEmpty(), candidate.title)
        }

        // Free text may be an artist, an album, or both in either order, so resemblance has to be
        // measured against every reading of it before deciding the result is filler.
        val relevance = if (query.freeText != null) {
            val phrase = query.freeText
            maxOf(
                titleSimilarity,
                MusicText.similarity(phrase, candidate.artist + " " + candidate.title),
                MusicText.similarity(phrase, candidate.title + " " + candidate.artist),
                MusicText.similarity(phrase, candidate.artist),
            )
        } else {
            maxOf(titleSimilarity, if (artistSimilarity == UNKNOWN) 0.0 else artistSimilarity)
        }

        val confidence = when {
            identifierMatches(query, candidate) -> MatchConfidence.EXACT
            query.albumKnown ->
                albumConfidence(titleSimilarity, artistSimilarity).atMostWeakIfDifferentVolume(
                    query.album.orEmpty(),
                    candidate.title,
                )
            !query.track.isNullOrBlank() -> trackConfidence(titleSimilarity, artistSimilarity)
            else -> freeTextConfidence(query, candidate, relevance)
        }

        return ScoredRelease(candidate, confidence, titleSimilarity, artistSimilarity, relevance)
    }

    private fun identifierMatches(query: ReleaseQuery, candidate: ReleaseCandidate): Boolean {
        val wanted = query.musicBrainzReleaseGroupId?.takeIf { it.isNotBlank() } ?: return false
        return wanted.equals(candidate.musicBrainzId, ignoreCase = true)
    }

    /** An album name we were given is the strongest text evidence there is; treat it as such. */
    private fun albumConfidence(title: Double, artist: Double): MatchConfidence {
        val artistAgrees = artist == UNKNOWN || artist >= ARTIST_AGREES
        return when {
            title >= CERTAIN && (artist == UNKNOWN || artist >= CONVINCING) -> MatchConfidence.EXACT
            title >= CONVINCING && artistAgrees -> MatchConfidence.STRONG
            // A compilation credits "Various Artists" while the track credits the performer, so an
            // unmistakable album title stands on its own - but only as a suggestion.
            title >= CERTAIN -> MatchConfidence.WEAK
            title >= PLAUSIBLE && artistAgrees -> MatchConfidence.WEAK
            else -> MatchConfidence.NONE
        }
    }

    /**
     * No album name, only a track. The candidate's title is a *release* title, so it agrees only
     * when the track was released as a single - which is common enough to be worth catching, and
     * never certain enough to skip the artist check.
     */
    private fun trackConfidence(title: Double, artist: Double): MatchConfidence = when {
        artist == UNKNOWN -> MatchConfidence.NONE
        artist >= CONVINCING && title >= CONVINCING -> MatchConfidence.STRONG
        artist >= CONVINCING -> MatchConfidence.WEAK
        artist >= ARTIST_AGREES && title >= CONVINCING -> MatchConfidence.WEAK
        else -> MatchConfidence.NONE
    }

    /**
     * A typed phrase, which may be an artist, an album, or both in either order. Never automatic:
     * the user is looking at the list and picks for themselves.
     */
    private fun freeTextConfidence(
        query: ReleaseQuery,
        candidate: ReleaseCandidate,
        relevance: Double,
    ): MatchConfidence = when {
        relevance >= PLAUSIBLE -> MatchConfidence.WEAK
        allTermsPresent(query.freeText.orEmpty(), candidate) -> MatchConfidence.WEAK
        else -> MatchConfidence.NONE
    }

    /**
     * Whether every word of [phrase] appears as a word of [candidate].
     *
     * Words, not substrings, and this distinction is the whole point. Matching against the name
     * with its spaces removed let "ego" match inside "I've Got" - "ivegot" - which is how a 1945
     * military band recording came back as the closest thing to "U, Me & My Ego". Words are matched
     * by prefix so "badland" still finds "Badlands", and at least one of them has to be long enough
     * to mean something: "u", "me" and "my" appear in half the catalogue between them.
     */
    private fun allTermsPresent(phrase: String, candidate: ReleaseCandidate): Boolean {
        val terms = MusicText.tokens(phrase)
        if (terms.isEmpty() || terms.none { it.length >= SUBSTANTIAL_WORD }) return false
        val words = MusicText.tokens(candidate.artist + " " + candidate.title)
        return terms.all { term -> words.any { it.startsWith(term) } }
    }

    /** Titles are compared as written, with edition decoration removed, and with any volume
     *  numeral written as a figure so "Culture II" meets "Culture 2"; the kindest wins. */
    private fun nameSimilarity(wanted: String, candidate: String): Double = maxOf(
        MusicText.similarity(wanted, candidate),
        MusicText.similarity(
            MusicText.releaseFamilyKey(wanted),
            MusicText.releaseFamilyKey(candidate),
        ),
        MusicText.similarity(MusicText.seriesKey(wanted), MusicText.seriesKey(candidate)),
    )

    /**
     * Keeps a sequel from passing as the record it is a sequel to.
     *
     * "SremmLife" and "SremmLife 2" are the same name to every measure there is - one is a prefix
     * of the other, one token apart, one character apart - so an album query for the original
     * scored the sequel as STRONG and was acted on. A library holding only the sequel then
     * answered for the original: the discography row read "In your library" and opened the wrong
     * album. The pair is still shown, because a numbered series is exactly what someone browsing
     * one volume may be looking for; it is only never chosen on their behalf.
     */
    private fun MatchConfidence.atMostWeakIfDifferentVolume(
        wanted: String,
        candidate: String,
    ): MatchConfidence =
        if (MusicText.seriesNumber(wanted) == MusicText.seriesNumber(candidate)) this
        else maxOf(this, MatchConfidence.WEAK)

    private fun <T : ReleaseCandidate> ordering(query: ReleaseQuery): Comparator<ScoredRelease<T>> =
        compareBy<ScoredRelease<T>> { it.confidence.ordinal }
            .thenBy { freeTextTier(query, it.release) }
            .thenByDescending(ScoredRelease<T>::relevance)
            .thenBy { tieBreak(query, it.release) }

    /**
     * Free text keeps the tier ladder the search box has always used, because it encodes something
     * similarity cannot: typing an artist's name should surface that artist's records ahead of an
     * unrelated album that happens to be named after them.
     */
    private fun freeTextTier(query: ReleaseQuery, candidate: ReleaseCandidate): Int {
        val phrase = query.freeText ?: return 0
        val needle = MusicText.compactKey(phrase)
        if (needle.isEmpty()) return 0
        val family = MusicText.compactKey(MusicText.releaseFamilyKey(phrase))
        val title = MusicText.compactKey(candidate.title)
        val artist = MusicText.compactKey(candidate.artist)
        val titleFamily = MusicText.compactKey(MusicText.releaseFamilyKey(candidate.title))
        return when {
            needle == artist + title || needle == title + artist ||
                family == artist + titleFamily || family == titleFamily + artist -> 0
            needle == artist -> 1
            needle == title || family == titleFamily -> 2
            title.startsWith(needle) -> 3
            artist.startsWith(needle) -> 4
            title.contains(needle) -> 5
            artist.contains(needle) -> 6
            allTermsPresent(phrase, candidate) -> 7
            else -> 8
        }
    }

    /**
     * Which pressing, once the record itself is settled: the edition the user most likely wants,
     * then the one whose year and track count line up with what was asked for, newest first.
     */
    private fun tieBreak(query: ReleaseQuery, candidate: ReleaseCandidate): Long {
        val candidateYear = candidate.year
        val candidateTracks = candidate.totalTracks
        val edition = if (MusicText.isPreferredEdition(candidate.title)) 0L else 1L
        val year = when {
            query.year == null || candidateYear == null -> 2L
            query.year == candidateYear -> 0L
            abs(query.year - candidateYear) <= 1 -> 1L
            else -> 3L
        }
        val tracks = when {
            query.totalTracks == null || candidateTracks == null -> 2L
            query.totalTracks == candidateTracks -> 0L
            abs(query.totalTracks - candidateTracks) <= 2 -> 1L
            else -> 3L
        }
        val recency = (9999L - (candidateYear ?: 0).toLong()).coerceIn(0L, 9999L)
        return (((edition * 4 + year) * 4 + tracks) * 10_000L) + recency
    }
}
