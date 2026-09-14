package org.akanework.gramophone.logic.data.automix

import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition decision, and the choice of which incoming analysis it is made from.
 *
 * Both are arithmetic over rows, which is the reason [TransitionPlan] was separated from the
 * machinery that performs it: everything below would otherwise need a decoder, an audio thread and
 * a device to ask.
 */
class TransitionPlanTest {

    /**
     * A row with a constant grid at [bpm] covering [seconds], starting at [firstBeatMs].
     *
     * Whole tracks are generated rather than fixtures pasted in because the tests are about where
     * the arithmetic lands, and a generated grid can be asked for the awkward cases - an intro of
     * an odd length, a tempo that does not divide the duration - without anyone hand-counting beats.
     */
    private fun track(
        id: String = "t",
        bpm: Float,
        seconds: Int = 200,
        firstBeatMs: Int = 0,
        confidence: Float = 0.5f,
        downbeatIndex: Int = 0,
    ): AnalysedTrack {
        val periodMs = 60_000f / bpm
        val beats = IntArray((seconds * 1000f / periodMs).toInt()) { index ->
            firstBeatMs + (index * periodMs).toInt()
        }
        return AnalysedTrack(
            jellyfinId = id,
            bpm = bpm,
            tempoConfidence = confidence,
            beatsMs = AnalysedTrack.packBeats(beats),
            beatsPerBar = 4,
            downbeatIndex = downbeatIndex,
            downbeatConfidence = 0.5f,
            keyPitchClass = 0,
            keyIsMajor = true,
            keyStrength = 0.5f,
            analysedSeconds = seconds.toFloat(),
            analysedAt = 0L,
            analyserVersion = AnalysedTrack.ANALYSER_VERSION,
        )
    }

    /**
     * A duration whose last phrase boundary leaves a usable overlap at 120 BPM.
     *
     * Not arbitrary, and not interchangeable with a round four minutes: see
     * [aPhraseGridThatOvershootsTheCapIsDeclinedRatherThanMixedForNoTime].
     */
    private val playableDurationMs = 236_000L

    private fun planOf(
        outgoing: AnalysedTrack,
        incoming: AnalysedTrack,
        durationMs: Long = playableDurationMs,
    ): TransitionPlan {
        val outcome = TransitionPlan.between(outgoing, incoming, durationMs)
        assertTrue(
            "expected a mixable pair, got $outcome",
            outcome is TransitionPlan.Outcome.Mixable,
        )
        return (outcome as TransitionPlan.Outcome.Mixable).plan
    }

    /**
     * The fade is read off the incoming player's own position while that player runs at the plan's
     * speed, so the distance from the cue to the end of the fade is the overlap *stretched*. At
     * equal tempos the two are the same number and the bug this covers is invisible; that is why
     * the identity case is asserted separately from the stretched ones.
     */
    @Test
    fun fadeEndsAtTheCuePlusTheOverlapWhenNothingIsStretched() {
        val plan = planOf(track(id = "out", bpm = 120f), track(id = "in", bpm = 120f))
        assertEquals(1f, plan.speed, 0.0001f)
        assertEquals(plan.cueMs + plan.overlapMs, plan.resumeAtMs)
    }

    @Test
    fun fadeEndFollowsTheIncomingTimelineWhenItIsSpedUp() {
        // 120 into 116 asks the incoming track for a shade over 3%, inside the stretch budget.
        val plan = planOf(track(id = "out", bpm = 120f), track(id = "in", bpm = 116f))
        assertTrue("expected the incoming track to be sped up", plan.speed > 1f)

        val fadeSpanMs = plan.resumeAtMs - plan.cueMs
        assertEquals(plan.overlapMs * plan.speed, fadeSpanMs.toFloat(), 1f)
        assertTrue(
            "a sped-up incoming track covers more of itself than the overlap lasts",
            fadeSpanMs > plan.overlapMs,
        )
    }

    @Test
    fun fadeEndFollowsTheIncomingTimelineWhenItIsSlowedDown() {
        val plan = planOf(track(id = "out", bpm = 120f), track(id = "in", bpm = 124f))
        assertTrue("expected the incoming track to be slowed down", plan.speed < 1f)

        val fadeSpanMs = plan.resumeAtMs - plan.cueMs
        assertEquals(plan.overlapMs * plan.speed, fadeSpanMs.toFloat(), 1f)
        assertTrue(
            "a slowed incoming track covers less of itself than the overlap lasts",
            fadeSpanMs < plan.overlapMs,
        )
    }

    /**
     * The specific regression: at the far end of the stretch budget the old `cue + overlap` was
     * wrong by most of a bar, which is a ghost cut off before the outgoing track's last downbeat.
     */
    @Test
    fun theStretchedFadeSpanDiffersFromTheOverlapByMoreThanABeat() {
        val plan = planOf(
            track(id = "out", bpm = 127f),
            track(id = "in", bpm = 120f),
            durationMs = 240_000L,
        )
        val beatMs = 60_000f / 120f
        val error = (plan.resumeAtMs - plan.cueMs) - plan.overlapMs
        assertTrue("expected over a beat of difference, got ${error}ms", error > beatMs)
    }

    /**
     * The handover *is* the mix-in. Anything else means the ghost is asked to carry a tail that is
     * a different length from the fade running over it.
     */
    @Test
    fun theHandoverIsWhereTheOverlapBegins() {
        for (style in MixStyle.entries) {
            val outcome = TransitionPlan.between(
                track(id = "out", bpm = 120f),
                track(id = "in", bpm = 120f),
                playableDurationMs,
                style,
            )
            if (outcome !is TransitionPlan.Outcome.Mixable) continue
            val plan = outcome.plan
            assertEquals(
                "$style: the fade must end where the outgoing track does",
                playableDurationMs,
                plan.handoverMs + plan.overlapMs,
            )
        }
    }

    /**
     * Eight bars of 4/4 is `1920000 / bpm` milliseconds, so a phrase is longer than the fifteen
     * second cap on anything under 128 BPM. The loop that walks the mix-in forward therefore steps
     * by more than the whole cap, and a step from just over it lands just short of the end of the
     * track - at 120 BPM over four minutes, exactly on it.
     *
     * That produced a plan reporting a zero-length overlap as mixable, which armed the ghost and
     * ran a lap to cross-fade nothing. Declining is the honest answer; how often it has to is a
     * separate problem and a bigger one.
     */
    @Test
    fun aPhraseGridThatOvershootsTheCapIsDeclinedRatherThanMixedForNoTime() {
        val outcome = TransitionPlan.between(
            track(id = "out", bpm = 120f),
            track(id = "in", bpm = 120f),
            240_000L,
        )
        assertTrue("expected a decline, got $outcome", outcome is TransitionPlan.Outcome.Declined)
        assertTrue(
            "the reason should name the overlap it could not reach: " +
                (outcome as TransitionPlan.Outcome.Declined).reason,
            outcome.reason.contains("overlap"),
        )
    }

    /** The cue is the incoming track's first downbeat, wherever its intro puts it. */
    @Test
    fun theCueIsTheIncomingFirstDownbeat() {
        val incoming = track(id = "in", bpm = 120f, firstBeatMs = 1_092, downbeatIndex = 2)
        val plan = planOf(track(id = "out", bpm = 120f), incoming)
        assertEquals(incoming.beatTimesMs()[2].toLong(), plan.cueMs)
    }

    @Test
    fun tooFarApartInTempoIsDeclinedWithTheNumbers() {
        val outcome = TransitionPlan.between(
            track(id = "out", bpm = 128f),
            track(id = "in", bpm = 90f),
            240_000L,
        )
        assertTrue(outcome is TransitionPlan.Outcome.Declined)
        assertTrue(
            "the reason should carry both tempos: ${(outcome as TransitionPlan.Outcome.Declined).reason}",
            outcome.reason.contains("128") && outcome.reason.contains("90"),
        )
    }

    /**
     * Seen on device: `Window of 1960 from 0ms: 122.25 BPM, 72 beats, confidence NaN`, straight out
     * of aubio. Every comparison against NaN is false, so a gate written `confidence < minimum`
     * does not refuse it - it admits it, and a track with no measurable pulse reaches a mix through
     * the one check that exists to stop exactly that.
     */
    @Test
    fun aNaNConfidenceIsRefusedRatherThanAdmitted() {
        val sound = track(id = "out", bpm = 120f)
        val nan = track(id = "in", bpm = 120f, confidence = Float.NaN)

        val incomingNaN = TransitionPlan.between(sound, nan, playableDurationMs)
        assertTrue("a NaN incoming confidence must decline", incomingNaN is TransitionPlan.Outcome.Declined)
        val outgoingNaN = TransitionPlan.between(nan, sound, playableDurationMs)
        assertTrue("a NaN outgoing confidence must decline", outgoingNaN is TransitionPlan.Outcome.Declined)
        assertFalse(MixCompatibility.mixable(sound, nan))
        assertFalse(MixCompatibility.mixable(nan, sound))
    }

    @Test
    fun aNaNTempoIsRefusedRatherThanAdmitted() {
        val sound = track(id = "out", bpm = 120f)
        val nan = track(id = "in", bpm = Float.NaN)

        assertTrue(TransitionPlan.between(sound, nan, playableDurationMs) is TransitionPlan.Outcome.Declined)
        assertTrue(TransitionPlan.between(nan, sound, playableDurationMs) is TransitionPlan.Outcome.Declined)
        assertFalse(MixCompatibility.mixable(sound, nan))
        assertFalse(MixCompatibility.mixable(nan, sound))
    }

    /**
     * An infinite tempo passes `bpm > 0` and then makes the *stretch* NaN rather than large, so the
     * trap moves one gate along and has to be closed there too.
     *
     * Built by copying a sound row rather than through the fixture builder, which would divide by
     * infinity to a zero beat period and try to allocate `Int.MAX_VALUE` beats.
     */
    @Test
    fun anInfiniteTempoIsRefusedRatherThanAdmitted() {
        val infinite = track(id = "in", bpm = 120f).copy(bpm = Float.POSITIVE_INFINITY)
        val alsoInfinite = track(id = "out", bpm = 120f).copy(bpm = Float.POSITIVE_INFINITY)
        assertTrue(
            TransitionPlan.between(alsoInfinite, infinite, playableDurationMs)
                is TransitionPlan.Outcome.Declined,
        )
        assertFalse(MixCompatibility.mixable(alsoInfinite, infinite))
    }

    @Test
    fun theBlendWindowIsUsedWhenItReadTheAudio() {
        val stored = track(id = "in", bpm = 120f)
        val head = track(id = "in", bpm = 116f, seconds = WindowAnalyzer.HEAD_SECONDS)
        assertSame(head, preferredIncomingAnalysis(stored, head))
    }

    @Test
    fun theStoredAnalysisIsKeptWhenTheBlendWindowFoundNothing() {
        val stored = track(id = "in", bpm = 120f)
        assertSame(stored, preferredIncomingAnalysis(stored, null))
    }

    @Test
    fun theStoredAnalysisIsKeptWhenTheBlendWindowGridIsTooShort() {
        val stored = track(id = "in", bpm = 120f)
        // Four beats: two bars is the floor, and this is a tracker that never settled rather than a
        // track that is genuinely slower at its start.
        val head = track(id = "in", bpm = 116f, seconds = 2)
        assertTrue(head.beatTimesMs().size < TrackAnalyzer.MINIMUM_USEFUL_BEATS)
        assertSame(stored, preferredIncomingAnalysis(stored, head))
    }

    @Test
    fun theStoredAnalysisIsKeptWhenTheBlendWindowTempoIsNotOne() {
        val stored = track(id = "in", bpm = 120f)
        val head = track(id = "in", bpm = 240f, seconds = WindowAnalyzer.HEAD_SECONDS)
        assertSame(stored, preferredIncomingAnalysis(stored, head))
    }

    /**
     * A low-confidence blend window is still the blend window. The plan is where confidence is
     * weighed, and substituting the stored row's higher figure here would launder a number measured
     * over audio the mix never reaches into a decision about audio it does.
     */
    @Test
    fun aBlendWindowTheTrackerWasUnsureOfIsStillPreferred() {
        val stored = track(id = "in", bpm = 120f, confidence = 0.9f)
        val head = track(
            id = "in",
            bpm = 116f,
            seconds = WindowAnalyzer.HEAD_SECONDS,
            confidence = 0.01f,
        )
        assertSame(head, preferredIncomingAnalysis(stored, head))
    }
}
