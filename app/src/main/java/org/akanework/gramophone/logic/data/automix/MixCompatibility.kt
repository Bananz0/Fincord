package org.akanework.gramophone.logic.data.automix

import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import uk.akane.accord.automix.Camelot
import kotlin.math.abs

/**
 * Which tracks will mix into which, and in what order to play them so that as many as possible do.
 *
 * The engine's own conclusion, recorded on device: four consecutive pairs in a real library needed
 * stretches of 25%, 21.7%, 31.8% and 30.9% against a 6% cap, and every one was correctly refused. A
 * transition engine cannot fix that - the tracks either mix or they do not. What can fix it is
 * choosing the running order, which is what both reference projects do and what this is.
 *
 * Pure arithmetic over stored rows, deliberately, for the same reason [TransitionPlan] is: it needs
 * no decoder, no audio thread and no device, so the whole decision is testable. It is also the
 * reason this can be lifted somewhere else later. Whether the index it runs over lives on the phone
 * or on the server, the comparison is identical - see the Automix notes in TODO.md.
 */
object MixCompatibility {

    /**
     * What a pair would cost to mix, where lower is better.
     *
     * Not a probability and not a percentage: it is an ordering key, and its only job is to be
     * comparable against another pair's. The absolute value means nothing.
     */
    data class Cost(
        /** How far the incoming track has to be moved, as a fraction. */
        val stretch: Float,
        /** Moves around the Camelot wheel, or `null` when either key is unknown. */
        val keyDistance: Int?,
    ) : Comparable<Cost> {

        /**
         * Tempo first, key as the tiebreak, and an unknown key priced as a safe move.
         *
         * The weighting is a judgement rather than a measurement and is worth stating plainly:
         * tempo is normalised against the cap, so a pair at the very limit of what can be stretched
         * scores 1.0, and one wheel move is worth a quarter of that. That puts a perfectly
         * beat-matched pair in clashing keys behind a nearly-matched pair in the same key, which is
         * the right way round - a listener hears a key clash long before they hear a 2% stretch.
         *
         * An unknown key scores as [Camelot.SAFE_DISTANCE] rather than as zero or as the worst
         * case. Zero would let unanalysed keys win every comparison; the worst case would bury
         * every ambient and live track, which are exactly the ones a key finder gives up on.
         */
        val value: Float
            get() = abs(stretch) / TransitionPlan.MAX_STRETCH +
                (keyDistance ?: Camelot.SAFE_DISTANCE) * KEY_WEIGHT

        override fun compareTo(other: Cost): Int = value.compareTo(other.value)
    }

    /** How much one move around the Camelot wheel is worth against the whole stretch budget. */
    private const val KEY_WEIGHT = 0.25f

    /**
     * What mixing [outgoing] into [incoming] would cost, or `null` if it cannot be done.
     *
     * The gates are [TransitionPlan]'s own, read from it rather than restated, and that matters
     * more than it looks. An ordering that optimises for something the planner then refuses is
     * worse than no ordering at all: it would produce a queue that looks carefully sequenced and
     * mixes no better than a shuffle, and nothing in the logs would say why.
     *
     * What it deliberately does *not* check is everything in the planner that depends on the
     * outgoing track's duration - where the phrase boundaries fall, whether one lands inside the
     * overlap window. Those are properties of one transition rather than of a pair, they need the
     * duration and the full grid, and a pair that clears the tempo gate will clear them often
     * enough that refusing here would throw away good orderings for a reason that is not about
     * compatibility at all.
     */
    fun cost(outgoing: AnalysedTrack, incoming: AnalysedTrack): Cost? {
        // Written as "must be within range" rather than "must not be out of it", because that is
        // the only form that rejects NaN - see the same note in TransitionPlan.between, whose
        // gates these are and whose answers these must match. A NaN slipping through here would
        // sort a track with no measurable pulse to the front of a queue.
        if (!(outgoing.bpm > 0f) || !(incoming.bpm > 0f)) return null
        if (!(outgoing.tempoConfidence >= TransitionPlan.MIN_TEMPO_CONFIDENCE)) return null
        if (!(incoming.tempoConfidence >= TransitionPlan.MIN_TEMPO_CONFIDENCE)) return null

        val stretch = outgoing.bpm / incoming.bpm - 1f
        if (!(abs(stretch) <= TransitionPlan.MAX_STRETCH)) return null

        return Cost(
            stretch = stretch,
            keyDistance = Camelot.distance(
                outgoing.keyPitchClass,
                outgoing.keyIsMajor,
                incoming.keyPitchClass,
                incoming.keyIsMajor,
            ),
        )
    }

    /** Whether a mix from [outgoing] into [incoming] is worth the machinery being armed for. */
    fun mixable(outgoing: AnalysedTrack, incoming: AnalysedTrack): Boolean =
        cost(outgoing, incoming) != null

    /**
     * Orders [tracks] so that as many adjacent pairs as possible will mix, starting from [first].
     *
     * Greedy nearest-neighbour: from wherever it is, take the cheapest track that will mix; when
     * nothing will, take the next track in the caller's own order and accept a plain transition
     * there. That is a heuristic and it is worth being honest about which one - this is a path
     * through a graph where the edges are mixable pairs, which is the travelling salesman with the
     * return leg removed, and nobody solves that exactly for a queue. Greedy gets most of the
     * available adjacencies for one pass over the list per position.
     *
     * Two properties matter more here than optimality:
     *
     * - **Every track is played, exactly once.** A sequencer that drops what it cannot place is not
     *   ordering a queue, it is filtering one, and a listener who queued an album expects the album.
     *   When nothing mixes, the ordering falls back to the order it was given rather than to
     *   nothing.
     * - **It is stable.** Ties resolve to the caller's order, so the same queue orders the same way
     *   every time and a track's position does not depend on the iteration order of a map.
     *
     * [first] is the track already playing, when there is one: the first transition is out of it,
     * so a sequence chosen without it starts by ignoring the only edge the listener is about to
     * hear. `null` means the caller has no such anchor and the given order's first track is used.
     */
    fun <T> order(
        tracks: List<T>,
        analysisOf: (T) -> AnalysedTrack?,
        first: AnalysedTrack? = null,
    ): List<T> {
        if (tracks.size < 2) return tracks

        val remaining = tracks.toMutableList()
        val ordered = ArrayList<T>(tracks.size)
        var current = first ?: analysisOf(remaining.removeAt(0).also { ordered.add(it) })

        while (remaining.isNotEmpty()) {
            val bestIndex = current?.let { from -> cheapestFrom(from, remaining, analysisOf) } ?: 0
            val picked = remaining.removeAt(bestIndex)
            ordered.add(picked)
            // An unanalysed track is a break in the chain rather than the end of it: the next
            // choice is made from the caller's order, and the one after that can mix again.
            current = analysisOf(picked)
        }
        return ordered
    }

    /**
     * The index in [candidates] of the cheapest track to mix into from [from], or `0` when none
     * will mix.
     *
     * Falling back to `0` rather than to a random or a "best of a bad lot" choice is what keeps the
     * ordering stable: where nothing is mixable, the caller's order is already the answer.
     */
    private fun <T> cheapestFrom(
        from: AnalysedTrack,
        candidates: List<T>,
        analysisOf: (T) -> AnalysedTrack?,
    ): Int {
        var bestIndex = 0
        var best: Cost? = null
        candidates.forEachIndexed { index, candidate ->
            val analysis = analysisOf(candidate) ?: return@forEachIndexed
            val cost = cost(from, analysis) ?: return@forEachIndexed
            // Strictly cheaper, so an equally good candidate later in the list never displaces an
            // earlier one and the caller's order breaks every tie.
            if (best == null || cost < best!!) {
                best = cost
                bestIndex = index
            }
        }
        return bestIndex
    }
}
