package org.akanework.gramophone.logic.data.automix

/**
 * How a transition is performed, as a DJ would describe it rather than as the code sees it.
 *
 * Each of these is a different answer to the same problem: two records playing at once occupy the
 * same frequencies, and something has to give. A fade gives level, a bass swap gives the low end
 * only, a cut gives time. Which is right depends on the music, and none of them is right for
 * everything - which is why this is a choice and not a constant.
 */
enum class MixStyle(
    /** Bars the two tracks are heard together. Longer is a blend, shorter is closer to a cut. */
    val bars: Int,
    val fade: FadeCurve,
    /**
     * How the outgoing track's low end is treated across the overlap.
     *
     * The single most important part of the whole feature, and the thing a DJ's left hand is
     * actually doing. Two basslines and two kick drums in the same octave do not blend, they
     * interfere: the sum is muddy where the notes clash and it pumps where the kicks are not quite
     * together. Taking the low end off the outgoing track leaves its melody and vocal audible while
     * handing the bottom of the mix to the incoming one, which is the difference between a
     * transition sounding mixed and sounding merely faded.
     */
    val bassCutHz: Float,
) {

    /**
     * A long, even blend with the outgoing bass pulled early.
     *
     * The default, and the closest to how a beat-matched club mix is done: the two run together for
     * eight bars, the low end changes hands almost immediately, and the outgoing track leaves as a
     * melody over the incoming one's rhythm section.
     */
    BLEND(bars = 8, fade = FadeCurve.CONSTANT_POWER, bassCutHz = 200f),

    /**
     * Half the overlap, bass swapped harder.
     *
     * For busier material where eight bars of two vocals is too much. Four bars is still enough for
     * the beat match to read as deliberate rather than as an accident.
     */
    SHORT_BLEND(bars = 4, fade = FadeCurve.CONSTANT_POWER, bassCutHz = 300f),

    /**
     * Almost a cut, with just enough overlap to hide the seam.
     *
     * For tracks whose keys clash, or whose endings are too busy to sit under anything. The
     * outgoing track is gone well before the window ends - see [FadeCurve.QUICK] - so this is a
     * transition rather than a mix, which is sometimes the honest answer.
     */
    CUT(bars = 2, fade = FadeCurve.QUICK, bassCutHz = 400f),

    /**
     * A long blend with both tracks left full range.
     *
     * For ambient, downtempo and anything without a kick drum, where there is no bass to clash and
     * filtering the outgoing track only makes it thin. `bassCutHz` of zero disables the filter
     * entirely rather than setting it low, so nothing touches the audio at all.
     */
    WASH(bars = 8, fade = FadeCurve.LINEAR, bassCutHz = 0f);

    companion object {

        /** What to use when nobody has chosen. */
        val DEFAULT = BLEND

        /** Reads the persisted choice, falling back to [DEFAULT] for anything unrecognised. */
        fun fromPreference(value: String?): MixStyle =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
