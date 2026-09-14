package uk.akane.accord.automix

/**
 * The wheel DJs use to decide whether two records will clash.
 *
 * `1A`..`12A` is minor and `1B`..`12B` is major, arranged so that neighbouring numbers are a fifth
 * apart and A and B at the same number are relative keys. The whole point of the notation is that
 * the question "do these two keys sit together" becomes an integer comparison rather than a table
 * of intervals, which is why it is worth converting into rather than carrying pitch classes around.
 *
 * Shared between [TrackAnalysis], which reports one track, and the pairing that compares two, so
 * the two cannot end up with different opinions about the same key.
 */
object Camelot {

    /**
     * The wheel number for a pitch class, or `null` when no key was found.
     *
     * Camelot numbers walk the circle of fifths, so a step of seven semitones is a step of one
     * code. Multiplying by 7 inverts that - 7 * 7 is 1 modulo 12 - and the constants are then fixed
     * by the two anchors everyone knows: 8B for C major and 8A for A minor.
     */
    fun number(pitchClass: Int, isMajor: Boolean): Int? {
        if (pitchClass !in 0..11) return null
        return if (isMajor) {
            ((pitchClass * 7 + 7) % 12) + 1
        } else {
            ((pitchClass * 7 + 4) % 12) + 1
        }
    }

    /** The code as it is written, e.g. `8B`. `null` when no key was found. */
    fun code(pitchClass: Int, isMajor: Boolean): String? =
        number(pitchClass, isMajor)?.let { "$it${if (isMajor) "B" else "A"}" }

    /**
     * How far apart two keys are on the wheel, as the moves a DJ would count.
     *
     * `0` is the same key. `1` is any of the three moves that are held to be safe: one step around
     * the wheel in either direction in the same mode, or across to the relative major or minor at
     * the same number. Anything else is `2` or more, and grows with the distance around the wheel -
     * not because a five-step move is exactly five times worse than a one-step move, but because
     * the ordering needs *an* order and the circle is the only defensible one.
     *
     * `null` when either key is unknown, which is a different thing from far apart and must not be
     * silently treated as a clash: a track whose key could not be found is a track about which
     * nothing is known, and refusing to sequence it would drop most ambient and much live material.
     */
    fun distance(
        pitchClassA: Int,
        isMajorA: Boolean,
        pitchClassB: Int,
        isMajorB: Boolean,
    ): Int? {
        val a = number(pitchClassA, isMajorA) ?: return null
        val b = number(pitchClassB, isMajorB) ?: return null
        // Around a twelve-position circle, so eleven steps clockwise is one step the other way.
        val around = ((a - b) % 12 + 12) % 12
        val steps = minOf(around, 12 - around)
        if (isMajorA == isMajorB) return steps
        // Across the modes. The relative key sits at the same number and is the one free crossing;
        // every other crossing costs that move plus the distance around.
        return if (steps == 0) 1 else steps + 1
    }

    /** The wheel moves held to be safe: the same key, a neighbour, or the relative key. */
    const val SAFE_DISTANCE = 1
}
