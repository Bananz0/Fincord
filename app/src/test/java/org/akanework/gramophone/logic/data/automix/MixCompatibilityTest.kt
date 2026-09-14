package org.akanework.gramophone.logic.data.automix

import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.akane.accord.automix.Camelot

class MixCompatibilityTest {

    private fun track(
        id: String,
        bpm: Float,
        pitchClass: Int = 0,
        isMajor: Boolean = true,
        confidence: Float = 0.5f,
    ) = AnalysedTrack(
        jellyfinId = id,
        bpm = bpm,
        tempoConfidence = confidence,
        beatsMs = AnalysedTrack.packBeats(IntArray(64) { (it * 60_000f / bpm).toInt() }),
        beatsPerBar = 4,
        downbeatIndex = 0,
        downbeatConfidence = 0.5f,
        keyPitchClass = pitchClass,
        keyIsMajor = isMajor,
        keyStrength = 0.5f,
        analysedSeconds = 120f,
        analysedAt = 0L,
        analyserVersion = AnalysedTrack.ANALYSER_VERSION,
    )

    // --- the Camelot wheel -------------------------------------------------------------------

    @Test
    fun theWheelIsAnchoredWhereEveryChartSaysItIs() {
        assertEquals("8B", Camelot.code(pitchClass = 0, isMajor = true))
        assertEquals("8A", Camelot.code(pitchClass = 9, isMajor = false))
    }

    @Test
    fun aKeyIsNoDistanceFromItself() {
        assertEquals(0, Camelot.distance(0, true, 0, true))
    }

    @Test
    fun relativeMajorAndMinorAreOneSafeMoveApart() {
        // C major is 8B and A minor is 8A: the same number, the free crossing between modes.
        assertEquals(Camelot.SAFE_DISTANCE, Camelot.distance(0, true, 9, false))
    }

    @Test
    fun aFifthIsOneMoveAndTheWheelWrapsBothWays() {
        // C major (8B) to G major (9B) is up a fifth, and to F major (7B) is down one.
        assertEquals(1, Camelot.distance(0, true, 7, true))
        assertEquals(1, Camelot.distance(0, true, 5, true))
        // 12B to 1B is one move, not eleven.
        val twelveB = (0..11).first { Camelot.number(it, true) == 12 }
        val oneB = (0..11).first { Camelot.number(it, true) == 1 }
        assertEquals(1, Camelot.distance(twelveB, true, oneB, true))
    }

    @Test
    fun theWorstMoveAroundTheWheelIsSixSteps() {
        val worst = (0..11).maxOf { Camelot.distance(0, true, it, true)!! }
        assertEquals(6, worst)
    }

    @Test
    fun anUnknownKeyIsUnknownRatherThanFarAway() {
        assertNull(Camelot.distance(-1, true, 0, true))
        assertNull(Camelot.code(-1, true))
    }

    // --- pair cost ---------------------------------------------------------------------------

    @Test
    fun aPairInsideTheStretchBudgetMixes() {
        assertTrue(MixCompatibility.mixable(track("a", 128f), track("b", 126f)))
    }

    @Test
    fun aPairOutsideTheStretchBudgetDoesNot() {
        assertFalse(MixCompatibility.mixable(track("a", 128f), track("b", 96f)))
        assertNull(MixCompatibility.cost(track("a", 128f), track("b", 96f)))
    }

    /**
     * The gate has to be the planner's own. An ordering that optimises for pairs the planner then
     * refuses produces a queue that looks sequenced and mixes no better than a shuffle.
     */
    @Test
    fun theCompatibilityGateAgreesWithThePlanner() {
        for (bpm in 110..146) {
            val outgoing = track("a", 128f)
            val incoming = track("b", bpm.toFloat())
            val planned = TransitionPlan.between(outgoing, incoming, 236_000L)
            val declinedOnTempo = planned is TransitionPlan.Outcome.Declined &&
                planned.reason.contains("stretch")
            assertEquals(
                "128 into $bpm BPM",
                !declinedOnTempo,
                MixCompatibility.mixable(outgoing, incoming),
            )
        }
    }

    @Test
    fun anUntrustedGridIsNotMixedOn() {
        val unsure = track("b", 128f, confidence = 0f)
        assertFalse(MixCompatibility.mixable(track("a", 128f), unsure))
        assertFalse(MixCompatibility.mixable(unsure, track("a", 128f)))
    }

    @Test
    fun aCloserTempoIsCheaperThanADistantOneInTheSameKey() {
        val from = track("a", 128f)
        val near = MixCompatibility.cost(from, track("b", 127f))!!
        val far = MixCompatibility.cost(from, track("c", 122f))!!
        assertTrue("${near.value} should be under ${far.value}", near < far)
    }

    /** A key clash is heard long before a 2% stretch is, so it has to outweigh one. */
    @Test
    fun aKeyClashCostsMoreThanASmallStretch() {
        val from = track("a", 128f, pitchClass = 0, isMajor = true)
        val sameKeyStretched = MixCompatibility.cost(from, track("b", 125f, 0, true))!!
        val perfectTempoClashing = MixCompatibility.cost(from, track("c", 128f, 6, true))!!
        assertTrue(
            "${sameKeyStretched.value} should beat ${perfectTempoClashing.value}",
            sameKeyStretched < perfectTempoClashing,
        )
    }

    @Test
    fun anUnknownKeyIsPricedAsASafeMoveRatherThanAsAClash() {
        val from = track("a", 128f, pitchClass = 0, isMajor = true)
        val unknown = MixCompatibility.cost(from, track("b", 128f, pitchClass = -1))!!
        val clashing = MixCompatibility.cost(from, track("c", 128f, pitchClass = 6))!!
        val same = MixCompatibility.cost(from, track("d", 128f, pitchClass = 0))!!
        assertTrue(same < unknown)
        assertTrue(unknown < clashing)
    }

    // --- ordering ----------------------------------------------------------------------------

    private fun ids(tracks: List<AnalysedTrack>) = tracks.map { it.jellyfinId }

    @Test
    fun theOrderingPullsAMixableTrackForwards() {
        val playing = track("playing", 128f)
        val queue = listOf(track("far", 90f), track("near", 127f), track("other", 91f))
        val ordered = MixCompatibility.order(queue, { it }, first = playing)
        assertEquals("near", ordered.first().jellyfinId)
    }

    @Test
    fun everyTrackIsKeptExactlyOnce() {
        val queue = listOf(
            track("a", 128f), track("b", 90f), track("c", 127f),
            track("d", 174f), track("e", 126f),
        )
        val ordered = MixCompatibility.order(queue, { it }, first = track("playing", 128f))
        assertEquals(queue.size, ordered.size)
        assertEquals(ids(queue).toSet(), ids(ordered).toSet())
    }

    @Test
    fun aQueueThatCannotMixAtAllIsLeftAlone() {
        val queue = listOf(track("a", 80f), track("b", 128f), track("c", 174f))
        val ordered = MixCompatibility.order(queue, { it }, first = track("playing", 100f))
        assertEquals(ids(queue), ids(ordered))
    }

    @Test
    fun theOrderingIsStableWhenNothingDistinguishesTwoCandidates() {
        val queue = listOf(track("first", 128f), track("second", 128f), track("third", 128f))
        val ordered = MixCompatibility.order(queue, { it }, first = track("playing", 128f))
        assertEquals(ids(queue), ids(ordered))
    }

    @Test
    fun anUnanalysedTrackBreaksTheChainWithoutEndingIt() {
        val queue = listOf<AnalysedTrack?>(track("far", 90f), null, track("near", 127f))
        val ordered = MixCompatibility.order(queue, { it }, first = track("playing", 128f))
        assertEquals(queue.size, ordered.size)
        assertEquals("near", ordered.first()?.jellyfinId)
        // The unanalysed track is placed rather than dropped, and the chain resumes after it.
        assertTrue(ordered.contains(null))
    }

    @Test
    fun aQueueTooShortToReorderIsReturnedUntouched() {
        val one = listOf(track("a", 128f))
        assertEquals(one, MixCompatibility.order(one, { it }, first = track("playing", 128f)))
        assertEquals(
            emptyList<AnalysedTrack>(),
            MixCompatibility.order(emptyList<AnalysedTrack>(), { it }, first = null),
        )
    }

    @Test
    fun withoutAnAnchorTheGivenFirstTrackLeads() {
        val queue = listOf(track("given", 128f), track("mixable", 127f), track("not", 90f))
        val ordered = MixCompatibility.order(queue, { it }, first = null)
        assertEquals(listOf("given", "mixable", "not"), ids(ordered))
    }

    // --- applying an order to a player -------------------------------------------------------

    /**
     * Walks [order] the way the player would and returns what the queue ends up as, so the moves
     * are checked against a real timeline rather than against the plan that produced them.
     */
    private fun applied(queueSize: Int, currentIndex: Int, order: List<Int>): List<Int> {
        val timeline = MutableList(queueSize) { it }
        AutomixQueueOrdering.applyOrder(queueSize, currentIndex, order) { from, to ->
            timeline.add(to, timeline.removeAt(from))
        }
        return timeline
    }

    @Test
    fun applyingAnOrderPutsEveryItemWhereThePlanAsked() {
        val order = listOf(5, 2, 4, 3)
        assertEquals(listOf(0, 1, 5, 2, 4, 3), applied(6, currentIndex = 1, order = order))
    }

    @Test
    fun applyingAnOrderNeverTouchesWhatHasAlreadyBeenHeard() {
        val result = applied(6, currentIndex = 2, order = listOf(5, 3, 4))
        assertEquals(listOf(0, 1, 2), result.take(3))
    }

    @Test
    fun applyingAReversalIsStillAPermutation() {
        val size = 8
        val order = (size - 1 downTo 1).toList()
        val result = applied(size, currentIndex = 0, order = order)
        assertEquals(size, result.size)
        assertEquals((0 until size).toSet(), result.toSet())
        assertEquals(listOf(0) + order, result)
    }

    @Test
    fun applyingAnOrderThatChangesNothingMovesNothing() {
        var moves = 0
        AutomixQueueOrdering.applyOrder(5, currentIndex = 1, order = listOf(2, 3, 4)) { _, _ ->
            moves++
        }
        assertEquals(0, moves)
    }

    /** The whole point: more adjacent pairs mix after ordering than before it. */
    @Test
    fun orderingIncreasesTheNumberOfMixableAdjacencies() {
        val queue = listOf(
            track("a", 128f), track("b", 90f), track("c", 127f),
            track("d", 91f), track("e", 126f), track("f", 89f),
        )
        fun mixableAdjacencies(tracks: List<AnalysedTrack>) =
            tracks.zipWithNext().count { (from, to) -> MixCompatibility.mixable(from, to) }

        val ordered = MixCompatibility.order(queue, { it }, first = null)
        assertNotNull(ordered)
        assertTrue(
            "before ${mixableAdjacencies(queue)}, after ${mixableAdjacencies(ordered)}",
            mixableAdjacencies(ordered) > mixableAdjacencies(queue),
        )
    }
}
