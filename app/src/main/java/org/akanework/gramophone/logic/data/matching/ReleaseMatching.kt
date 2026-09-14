package org.akanework.gramophone.logic.data.matching

/**
 * What we are looking for, in terms every catalogue and every downloader understands.
 *
 * The identity fields matter more than the text ones. A MusicBrainz release group id or an ISRC
 * settles the question outright; artist and album names only ever suggest an answer, and how
 * strongly they suggest it is what [ReleaseMatcher] decides.
 */
data class ReleaseQuery(
    val artist: String = "",
    val album: String? = null,
    /** Used only when [album] is unknown - a single still lives on a release somewhere. */
    val track: String? = null,
    val year: Int? = null,
    val totalTracks: Int? = null,
    val isrc: String? = null,
    val musicBrainzReleaseGroupId: String? = null,
    /** Set when the user typed a phrase rather than picking a track; ranking only, never auto-add. */
    val freeText: String? = null,
) {
    val albumKnown: Boolean get() = !album.isNullOrBlank()

    /** The name being matched on: the album when there is one, otherwise the track. */
    val subject: String
        get() = album?.takeIf { it.isNotBlank() }
            ?: track?.takeIf { it.isNotBlank() }
            ?: freeText.orEmpty()

    companion object {
        /** A raw search box. Nothing is claimed about which half is the artist. */
        fun freeText(text: String) = ReleaseQuery(freeText = text.trim())
    }
}

/** The minimum a search result has to state before it can be ranked. */
interface ReleaseCandidate {
    val title: String
    val artist: String
    val year: Int?
    val totalTracks: Int?

    /** Release group id where the provider knows one, so an id match can short-circuit text. */
    val musicBrainzId: String?
}

/** How much of a match this is - the caller decides what it is allowed to do with each tier. */
enum class MatchConfidence {
    /** Identifier match, or names that agree outright. Safe to add without asking. */
    EXACT,

    /** Names agree closely and nothing contradicts. Safe to add without asking. */
    STRONG,

    /** Plausible. Fine to show in a list; never added on the user's behalf. */
    WEAK,

    /** Not a match. */
    NONE,
}

data class ScoredRelease<T : ReleaseCandidate>(
    val release: T,
    val confidence: MatchConfidence,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    /**
     * How much this result looks like what was asked for at all, 0..1, regardless of which field
     * carried the resemblance.
     *
     * Confidence answers "is this the right record"; relevance answers the blunter question "did
     * the search find anything, or is this filler". A typed artist name matching an artist exactly
     * is highly relevant while telling you nothing about which of their albums was meant, so the
     * two genuinely differ and both are needed.
     */
    val relevance: Double,
) {
    val isAutomatic: Boolean
        get() = confidence == MatchConfidence.EXACT || confidence == MatchConfidence.STRONG
}
