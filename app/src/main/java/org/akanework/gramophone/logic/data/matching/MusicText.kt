package org.akanework.gramophone.logic.data.matching

import java.text.Normalizer

/**
 * The one place that decides when two pieces of music text mean the same thing.
 *
 * Every matcher in the app used to carry its own private normaliser, and they disagreed: one folded
 * "&" away, another kept it; one stripped "(Deluxe Edition)", another treated it as part of the
 * title. That is how "U, Me & My Ego" failed to match "U, Me & My Ego" - the two sides normalised
 * to different strings and the comparison never got a chance.
 *
 * Similarity here is a ratio in 0..1, never a `contains` test. Substring matching is what let a
 * two-letter artist name match every album in a catalogue.
 */
object MusicText {

    /** Lowercased, accent-folded, punctuation-collapsed. Words survive; noise does not. */
    fun normalise(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace("&", " and ")
        .replace("+", " and ")
        .replace(TYPOGRAPHIC_QUOTES, "'")
        .replace(APOSTROPHES, "")
        .replace(NON_WORD, " ")
        .trim()
        .replace(SPACES, " ")

    /** [normalise] with the spaces removed - for equality tests, never for similarity. */
    fun compactKey(text: String): String = normalise(text).replace(" ", "")

    fun tokens(text: String): List<String> =
        normalise(text).split(' ').filter(String::isNotEmpty)

    /**
     * A title with edition, feature and remaster decoration removed.
     *
     * "Badlands (Deluxe Edition)" and "Badlands" are the same record for the purpose of asking a
     * downloader for it; which pressing arrives is the quality profile's business.
     */
    fun releaseFamilyKey(text: String): String = normalise(
        text.replace(BRACKETED_DECORATION, " ")
            .replace(TRAILING_DECORATION, " ")
    ).ifBlank { normalise(text) }

    /**
     * The volume this title is, when it is one of a numbered series - or null when it is not.
     *
     * "SremmLife 2" is 2 and "Culture II" is 2; "SremmLife" is null. Every text measure there is
     * calls those two names the same name - one is a nine-character prefix of the other - so
     * without this a library holding only the sequel answers for the original.
     *
     * A number is only a volume when something precedes it, so "1999" is a record rather than the
     * nineteen-hundred-and-ninety-ninth of anything, and a disc suffix is a pressing detail rather
     * than a sequel. Roman numerals are read from a fixed list because the letters that spell them
     * also spell words: "mix" is a valid numeral and never means one thousand and nine.
     */
    fun seriesNumber(text: String): Int? {
        val words = tokens(text.replace(DISC_SUFFIX, " "))
        if (words.size < 2) return null
        val last = words.last()
        return last.toIntOrNull() ?: ROMAN_VOLUMES[last]
    }

    /** [normalise] with any volume numeral written as a figure, so "Culture II" meets "Culture 2". */
    fun seriesKey(text: String): String {
        val volume = seriesNumber(text) ?: return normalise(text)
        val words = tokens(text.replace(DISC_SUFFIX, " "))
        return (words.dropLast(1) + volume.toString()).joinToString(" ")
    }

    /** A recording title with credits removed but live/remix/acoustic markers kept - those are
     *  different recordings, not different spellings of one. */
    fun recordingKey(text: String): String = normalise(
        text.replace(FEATURE_CREDIT, " ").replace(REMASTER_CREDIT, " ")
    ).ifBlank { normalise(text) }

    fun isPreferredEdition(text: String?): Boolean =
        text != null && PREFERRED_EDITION.containsMatchIn(text)

    /**
     * How alike two names are, 0..1.
     *
     * Two measures, because they fail in opposite directions. Token overlap handles reordering and
     * extra words ("Halsey - Badlands" vs "Badlands"); edit distance handles typos and spelling
     * drift ("Chxrry22" vs "Chxrry 22") that token overlap scores as a total miss. The kinder of the
     * two wins, and a containment bonus lifts the case where one side is a clean prefix of the
     * other.
     */
    fun similarity(left: String, right: String): Double {
        val a = normalise(left)
        val b = normalise(right)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val compactA = a.replace(" ", "")
        val compactB = b.replace(" ", "")
        if (compactA == compactB) return 1.0

        val byTokens = tokenSetRatio(a, b)
        val byCharacters = editRatio(compactA, compactB)
        val containment = when {
            // Only meaningful once there is enough text for containment to say something. A
            // three-character needle is inside half the catalogue.
            compactA.length >= 4 && compactB.contains(compactA) -> 0.9
            compactB.length >= 4 && compactA.contains(compactB) -> 0.9
            else -> 0.0
        }
        return maxOf(byTokens, byCharacters, containment)
    }

    /** Overlap of the two word sets, weighted by word length so "the" counts for little. */
    internal fun tokenSetRatio(left: String, right: String): Double {
        val a = left.split(' ').filter(String::isNotEmpty).toSet()
        val b = right.split(' ').filter(String::isNotEmpty).toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val weight = { words: Set<String> -> words.sumOf { it.length.coerceAtLeast(1) }.toDouble() }
        val shared = weight(a intersect b)
        val union = weight(a union b)
        return if (union == 0.0) 0.0 else shared / union
    }

    /** 1 - normalised Levenshtein distance, computed on one rolling row. */
    internal fun editRatio(left: String, right: String): Double {
        val longest = maxOf(left.length, right.length)
        if (longest == 0) return 1.0
        // Guard against pathological input; nothing musical is this long and the matrix is O(n*m).
        if (longest > MAX_EDIT_LENGTH) return if (left == right) 1.0 else 0.0
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            for (j in 1..right.length) {
                val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1.0 - previous[right.length].toDouble() / longest
    }

    private const val MAX_EDIT_LENGTH = 256

    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val TYPOGRAPHIC_QUOTES = Regex("[‘’‛ʼ]")
    private val APOSTROPHES = Regex("'")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")

    private const val DECORATION_WORDS =
        """deluxe|expanded|anniversary|special|complete|bonus(?:\s+tracks?)?|platinum""" +
            """|remaster(?:ed)?|explicit|clean|edited|original\s+motion\s+picture""" +
            """|reissue|re-?issue|mono|stereo"""

    private val BRACKETED_DECORATION = Regex(
        """(?i)[\(\[]\s*(?:the\s+)?(?:$DECORATION_WORDS)[^)\]]*[\)\]]"""
    )
    private val TRAILING_DECORATION = Regex(
        """(?i)\s*[-–—]\s*(?:$DECORATION_WORDS)(?:\s+(?:edition|version))?\s*$"""
    )
    private val FEATURE_CREDIT = Regex(
        """(?i)\s*(?:[\(\[]|-)?\s*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]*(?:[\)\]]|$)"""
    )
    private val REMASTER_CREDIT = Regex(
        """(?i)\s*(?:[\(\[]|-)?\s*(?:(?:19|20)\d{2}\s+)?remaster(?:ed)?(?:\s+(?:19|20)\d{2})?\s*(?:[\)\]])?"""
    )
    private val DISC_SUFFIX = Regex("""(?i)[\(\[]?\s*(?:disc|disk|cd)\s*\d+\s*[\)\]]?\s*$""")

    /**
     * Numerals a volume is plausibly written as. Single letters are left out on purpose - "I", "V"
     * and "X" end ordinary titles far more often than they number them, and a title ending in one
     * still differs from a title ending in "II", which is all this has to decide.
     */
    private val ROMAN_VOLUMES = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9,
        "xi" to 11, "xii" to 12, "xiii" to 13, "xiv" to 14, "xv" to 15,
    )

    private val PREFERRED_EDITION = Regex(
        """(?i)\b(deluxe|expanded|anniversary|special\s+edition|complete\s+edition|bonus\s+track|platinum)\b"""
    )
}
