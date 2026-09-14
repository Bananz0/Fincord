package org.akanework.gramophone.logic.data.automix

/**
 * Where the sections of a track are.
 *
 * The thing Automix is missing most. It can find the tempo, the grid and the key, and with those it
 * can put two records in time with each other - but it has no idea what part of a track it is
 * mixing. A transition out of a breakdown and a transition out of a final chorus need the same beat
 * matching and completely different musical decisions, and right now both get whatever falls at the
 * last phrase boundary before the end.
 *
 * Deliberately a small vocabulary. Naming every section of every genre is a research problem; the
 * three positions below are the ones a transition actually asks about, and anything that cannot
 * answer them confidently should return null rather than guess.
 */
data class TrackStructure(
    /**
     * Loudness over the track, one value per [ENERGY_WINDOW_MS], normalised to its own peak.
     *
     * Kept as a curve rather than reduced to labels because the useful questions are comparative -
     * is this quieter than what came before, is it still falling - and a label throws that away.
     */
    val energy: FloatArray,

    /**
     * Where the track's energy last rises sharply, in ms, or null if it never does.
     *
     * The drop, in the sense the word is used about dance music. A transition wants to arrive
     * *before* one on the incoming track and to leave *after* the last one on the outgoing track.
     */
    val lastDropMs: Long?,

    /**
     * Where the outro begins, in ms, or null if the track does not have one.
     *
     * Defined here as the point after the last drop where energy falls and stays fallen, which is
     * the natural place to mix out of - and is usually much earlier than "the end minus the overlap"
     * that the transition currently uses.
     */
    val outroStartMs: Long?,
) {

    override fun equals(other: Any?): Boolean =
        this === other || (other is TrackStructure && lastDropMs == other.lastDropMs &&
            outroStartMs == other.outroStartMs && energy.contentEquals(other.energy))

    override fun hashCode(): Int =
        (energy.contentHashCode() * 31 + lastDropMs.hashCode()) * 31 + outroStartMs.hashCode()

    companion object {
        /**
         * The window energy is measured over.
         *
         * Two seconds is about a bar at most dance tempos, which is short enough to see a drop
         * arrive and long enough that individual kicks do not read as structure.
         */
        const val ENERGY_WINDOW_MS = 2_000L
    }
}
