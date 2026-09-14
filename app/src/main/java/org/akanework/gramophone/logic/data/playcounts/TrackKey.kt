package org.akanework.gramophone.logic.data.playcounts

import java.text.Normalizer

/**
 * Turns an artist and title into something two different services can agree on.
 *
 * Nothing external identifies a track the way Jellyfin does. Last.fm, a Spotify export and an Apple
 * CSV all hand back free text, written by whoever tagged the file that was played at the time - so
 * matching is string work, and the only question is how much difference to forgive.
 *
 * Three keys, tried in order, each looser than the last. Being explicit about the order matters:
 * the loose forms exist to catch tagging drift, and used first they would happily merge two real
 * songs that share a title.
 */
object TrackKey {

    private const val SEPARATOR = '\u0000'

    /** Combining marks left behind after decomposition, so "Bjork" can match "Björk". */
    private val DIACRITICS = "\\p{Mn}+".toRegex()

    /**
     * Credits appended to a title.
     *
     * Services disagree about whether a featured artist belongs in the title, in the artist field,
     * or in both, and the same song is routinely tagged all three ways.
     */
    private val FEATURING = "[(\\[]?\\s*\\b(?:feat|ft|featuring|with)\\b\\.?\\s+[^)\\]]*[)\\]]?"
        .toRegex(RegexOption.IGNORE_CASE)

    /**
     * Reissue and edition markers.
     *
     * Deliberately narrow. "Live", "Acoustic", "Demo" and "Radio Edit" name genuinely different
     * recordings that a listener would not want merged, so only markers that describe the same
     * performance are stripped.
     */
    private val EDITION = (
        "[(\\[-]\\s*(?:\\d{4}\\s+)?(?:digital\\s+)?" +
            "(?:re-?master(?:ed)?|remaster(?:ed)?\\s+\\d{4}|deluxe(?:\\s+edition)?|" +
            "bonus\\s+track|expanded(?:\\s+edition)?|anniversary(?:\\s+edition)?|" +
            "single\\s+version|album\\s+version|original\\s+mix)" +
            "(?:\\s+\\d{4})?\\s*[)\\]]?"
        ).toRegex(RegexOption.IGNORE_CASE)

    /** Everything after the first of these is a second credited artist rather than part of a name. */
    private val ARTIST_SPLIT = "\\s*(?:,|;|/|&|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bwith\\b|\\bx\\b|\\band\\b|\\bvs\\b\\.?)\\s*"
        .toRegex(RegexOption.IGNORE_CASE)

    /**
     * Exact key: both fields normalised, nothing dropped.
     *
     * What almost everything matches on, and the only key safe to trust on its own.
     */
    fun exact(artist: String, title: String): String =
        normalise(artist) + SEPARATOR + normalise(title)

    /**
     * Same, with featured credits and reissue markers removed from the title.
     *
     * Catches the common case where one side has "Song (feat. X) - 2011 Remaster" and the other
     * simply has "Song".
     */
    fun loose(artist: String, title: String): String =
        normalise(primaryArtist(artist)) + SEPARATOR + normalise(stripTitleNoise(title))

    /**
     * Title only, stripped.
     *
     * The last resort, and the one that can be wrong: two artists can release songs with the same
     * name. Callers must only use it where the candidate set is already narrow - and must treat a
     * key that more than one library track claims as no match at all.
     */
    fun titleOnly(title: String): String = normalise(stripTitleNoise(title))

    /** The first credited artist, for collaborations tagged differently on each side. */
    fun primaryArtist(artist: String): String =
        artist.split(ARTIST_SPLIT).firstOrNull()?.takeIf { it.isNotBlank() } ?: artist

    private fun stripTitleNoise(title: String): String =
        title.replace(FEATURING, " ").replace(EDITION, " ")

    /**
     * Folds away everything that is presentation rather than identity.
     *
     * Punctuation and spacing go entirely, so "Don't Stop Me Now" and "Dont Stop Me Now" collapse
     * together, and accents are decomposed first so a stripped-accent tag still matches.
     */
    private fun normalise(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(DIACRITICS, "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
}
