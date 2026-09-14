package org.akanework.gramophone.logic.data.automix

import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import kotlin.math.abs
import kotlin.math.floor

/**
 * What a mixed transition between two analysed tracks would look like, worked out before any audio
 * is touched.
 *
 * Separate from the machinery that performs it so the decision can be tested on its own. Every
 * question here - is this pair mixable, where does the outgoing track hand over, how far do the
 * grids have to be nudged to agree - is arithmetic over two rows from the database, and none of it
 * needs a decoder or an audio thread to answer.
 */
data class TransitionPlan(
    /** Position where the outgoing stream is lapped to the ghost and the queue player advances. */
    val handoverMs: Long,
    /** How long the two are heard together, in ms. */
    val overlapMs: Long,
    /**
     * Where the stable queue player starts the incoming track: its first downbeat.
     *
     * The intro before that is skipped, which is what a DJ does with a record - they drop it on the
     * one rather than playing the lead-in. The outgoing ghost masks the queue player's seek and
     * decoder setup, so the cue can be applied in the same operation that advances the queue.
     */
    val cueMs: Long,

    /**
     * Where the incoming fade ends in that track's timeline.
     *
     * The queue player begins at [cueMs] while the ghost carries the outgoing tail. This is the
     * point at which the incoming reaches full gain and the ghost can be retired.
     *
     * In the incoming track's *own* timeline, which is not the wall clock: it plays at [speed] for
     * the overlap, so it covers `overlapMs * speed` of itself in the [overlapMs] the ghost has left
     * to play. Reading the fade off its position and ending at `cueMs + overlapMs` therefore
     * finished the crossfade early on a track being sped up and late on one being slowed down, by
     * up to the whole stretch budget - nearly a second of a fifteen-second blend, spent either
     * cutting the outgoing track off before its last bar or holding a fully faded ghost past it.
     */
    val resumeAtMs: Long,
    /** What to multiply the incoming player's speed by so it runs at the outgoing track's tempo. */
    val speed: Float,
    val style: MixStyle,
) {

    /**
     * Whether a pair can be mixed, and if not, why not.
     *
     * The reason is carried rather than logged here so the decision stays a pure function of two
     * database rows - and because "it declined" without "because the incoming tempo confidence was
     * 0.17" is not a diagnosis, it is a shrug. Declining is the common answer, so the reason is the
     * part anyone actually needs.
     */
    sealed interface Outcome {
        data class Mixable(val plan: TransitionPlan) : Outcome
        data class Declined(val reason: String) : Outcome
    }

    companion object {

        /**
         * The longest two tracks are heard together.
         *
         * Eight bars at 128 BPM is fifteen seconds, which is a long blend by radio standards and a
         * short one by club standards. It is capped in time rather than in bars so that a slow
         * track does not produce a minute-long overlap.
         */
        private const val MAX_OVERLAP_MS = 15_000L

        /** Below this there is not enough overlap for a mix to be worth the machinery. */
        private const val MIN_OVERLAP_MS = 4_000L

        /**
         * How far apart two tempos may be and still be matched.
         *
         * A 6% stretch is roughly the point where Sonic starts to sound like a stretch rather than
         * like a tempo, and it is also about as far as a listener will accept a familiar track
         * being moved. Beyond it the honest answer is a plain transition, not a worse mix.
         */
        internal const val MAX_STRETCH = 0.06f

        /**
         * Confidence below which the beat grid is not trusted enough to mix on.
         *
         * Deliberately permissive, and provisional. This started at 0.30, which was a guess made
         * before the distribution was known; measured across real tracks it runs 0.00, 0.12, 0.12,
         * 0.13, 0.14, 0.14, 0.15, 0.17, 0.17, 0.18, 0.22, 0.27, 0.30, 0.34, 0.34, 0.35, 0.44, 0.51,
         * 0.71, 0.75 - a median near 0.18, so 0.30 refused about two thirds of the library and no
         * transition ever ran. It is set low enough to admit everything except a tracker that
         * plainly never settled, because the only way to calibrate it is to hear what a mix at 0.15
         * actually sounds like. Raise it once that is known.
         *
         * Note what it is not: aubio's confidence is its own number, untouched by the grid fitting,
         * and it is poorly calibrated - the same figure means different things on different
         * material. A better gate probably is not a threshold on this at all, but that is the
         * neural beat tracker's job.
         */
        internal const val MIN_TEMPO_CONFIDENCE = 0.12f

        /**
         * Works out how [outgoing] should hand over to [incoming], or null if it should not.
         *
         * Null is the common and correct answer. Most pairs of tracks in a library are not
         * beat-matchable, and Automix declining to mix them is the feature working.
         */
        fun between(
            outgoing: AnalysedTrack,
            incoming: AnalysedTrack,
            outgoingDurationMs: Long,
            style: MixStyle = MixStyle.DEFAULT,
            outgoingStructure: TrackStructure? = null,
        ): Outcome {
            /*
             * Every gate below is written as "must be within range" rather than "must not be out
             * of it", and that is not a style choice - it is the only form that rejects NaN.
             *
             * A NaN tempo confidence was observed coming out of aubio on real audio (fixed at the
             * source too, but a stored row may already carry one and this is where the decision is
             * made). IEEE says every comparison against NaN is false, so `confidence < minimum`
             * does not refuse it, it admits it - a track with no measurable pulse would sail
             * through the one check that exists to catch exactly that. Reversing the test makes
             * the unknown case fail closed.
             */
            if (!(outgoing.bpm > 0f) || !(incoming.bpm > 0f)) {
                return Outcome.Declined("no tempo: ${outgoing.bpm} to ${incoming.bpm} BPM")
            }
            /*
             * Three decimals, not two, and the reason is on device rather than pedantic: a tail
             * confidence of 0.11635 against a threshold of 0.12 printed as "0.12 under 0.12",
             * which reads as a broken comparison rather than as a refusal. A message that makes a
             * correct decision look like a bug costs more than the two characters it saved.
             */
            if (!(outgoing.tempoConfidence >= MIN_TEMPO_CONFIDENCE)) {
                return Outcome.Declined(
                    "outgoing tempo confidence %.3f under %.3f"
                        .format(outgoing.tempoConfidence, MIN_TEMPO_CONFIDENCE)
                )
            }
            if (!(incoming.tempoConfidence >= MIN_TEMPO_CONFIDENCE)) {
                return Outcome.Declined(
                    "incoming tempo confidence %.3f under %.3f"
                        .format(incoming.tempoConfidence, MIN_TEMPO_CONFIDENCE)
                )
            }

            /*
             * The incoming track is the one stretched. It plays at the outgoing track's tempo for
             * the overlap and reverts to the listener's normal speed once the ghost fades out -
             * inaudible at the one percent this usually comes to, and the argument for the tempo
             * ramp on the list when it is not.
             */
            val speed = outgoing.bpm / incoming.bpm
            if (!(abs(speed - 1f) <= MAX_STRETCH)) {
                return Outcome.Declined(
                    "%.2f to %.2f BPM needs a %.1f%% stretch, over %.0f%%"
                        .format(outgoing.bpm, incoming.bpm, (speed - 1f) * 100f, MAX_STRETCH * 100f)
                )
            }

            val outgoingBeats = outgoing.beatTimesMs()
            val incomingBeats = incoming.beatTimesMs()
            if (outgoingBeats.size < outgoing.beatsPerBar || incomingBeats.isEmpty()) {
                return Outcome.Declined(
                    "not enough grid: ${outgoingBeats.size} and ${incomingBeats.size} beats"
                )
            }

            /*
             * The overlap is a whole number of the outgoing track's bars. Ending mid-bar is what
             * makes a crossfade sound like a fade rather than like a mix, because the incoming
             * track's bar line arrives somewhere inside the outgoing one's.
             */
            val barMs = 60_000.0 / outgoing.bpm * outgoing.beatsPerBar
            /*
             * The style asks for a number of bars; the cap turns that back into time. A style
             * wanting eight bars gets eight of them at 128 BPM and fewer on something slow, because
             * eight bars of a 70 BPM track is nearly half a minute of two records at once.
             */
            val bars = minOf(style.bars, (MAX_OVERLAP_MS / barMs).toInt()).coerceAtLeast(1)
            val askedForMs = (bars * barMs).toLong()
            if (askedForMs < MIN_OVERLAP_MS) {
                return Outcome.Declined("$bars bars is only ${askedForMs}ms of overlap")
            }

            /*
             * Hand over on one of the outgoing track's phrase boundaries, counted forward rather
             * than looked up - see phraseStartAtOrBefore. The stored grid stops at the end of the
             * analysed window and the handover is near the end of the track, so looking it up would
             * put every long track's transition at about two minutes.
             */
            /*
             * The mix begins one overlap before the outgoing track ends, on one of its phrase
             * boundaries.
             *
             * Not where the outro starts, which is what this used to aim at. The outgoing track is
             * no longer cut short audibly - its ghost plays to the end while the queue advances -
             * so the freedom is how long before that end the incoming track starts underneath it.
             * The structure is still worth having: an outro that begins
             * *later* than the mix-in point means the blend runs over a section still in full flow,
             * which is a reason to shorten it rather than to move it.
             */
            val phraseMs = 60_000.0 / outgoing.bpm * outgoing.beatsPerBar * BARS_PER_PHRASE
            var mixIn = phraseStartAtOrBefore(outgoing, outgoingBeats, outgoingDurationMs - askedForMs)
            if (mixIn == null || mixIn <= 0L) {
                return Outcome.Declined("no phrase boundary before the last ${askedForMs}ms")
            }
            /*
             * Snapping back to a phrase lengthens the overlap, and left alone it can nearly double
             * it - the first plan built this way asked for fifteen seconds and produced eighteen
             * and a half. Step forward a phrase at a time until it fits, which keeps the mix-in on
             * a phrase boundary while respecting the cap the style asked for.
             */
            while (outgoingDurationMs - mixIn > MAX_OVERLAP_MS && phraseMs > 0.0) {
                mixIn += phraseMs.toLong()
            }

            /*
             * What the phrase grid actually offered, which is the number every other field is built
             * from. It is not [askedForMs] and it is not bounded by it: snapping moves the mix-in,
             * so the overlap the listener gets is whatever is left of the track from there.
             *
             * Checked here rather than above, because above is the wrong place and used to be the
             * only one. A phrase is longer than [MAX_OVERLAP_MS] on anything under 128 BPM - eight
             * bars of 4/4 is 1920000/bpm milliseconds - so the loop steps by more than the whole
             * cap, and a step from just over the cap lands just short of the end of the track. At
             * 120 BPM over four minutes it landed *exactly* on it: a plan with a zero-length
             * overlap, reported as mixable, arming the ghost and the lap to cross-fade nothing.
             */
            val overlapMs = outgoingDurationMs - mixIn
            if (overlapMs < MIN_OVERLAP_MS) {
                return Outcome.Declined(
                    "the last phrase boundary leaves only ${overlapMs}ms of overlap"
                )
            }
            if (outgoingStructure?.outroStartMs?.let { it > mixIn + phraseMs } == true) {
                return Outcome.Declined(
                    "the outro starts at ${outgoingStructure.outroStartMs}ms, after the mix would"
                )
            }

            /*
             * The queue player starts the incoming track at its first downbeat. Its seek/reprepare
             * is hidden by the outgoing ghost, which is why the grids can meet without exposing the
             * AudioTrack gap that a single-player handover produced.
             */
            val firstIncomingDownbeat = incomingBeats
                .getOrNull(incoming.downbeatIndex)
                ?.toLong()
                ?: return Outcome.Declined("incoming downbeat index ${incoming.downbeatIndex} is off its grid")

            /*
             * The mix-in is the handover: the moment the ghost takes the outgoing stream is the
             * moment the incoming one starts underneath it, and there is nothing in between.
             *
             * These used to be worked out separately, and the handover carried a
             * `coerceAtMost(askedForMs)` that let them disagree - a style asking for two bars over a
             * grid that offered twelve seconds got a handover twelve seconds after its own overlap
             * began. The fade then outran the ghost's remaining audio and was cut short by the
             * end-of-stream check in `driveFade`, which is a self-correcting bug and therefore one
             * nobody would ever have gone looking for.
             */
            return Outcome.Mixable(
                TransitionPlan(
                    handoverMs = mixIn,
                    overlapMs = overlapMs,
                    cueMs = firstIncomingDownbeat,
                    resumeAtMs = firstIncomingDownbeat + (overlapMs * speed).toLong(),
                    speed = speed,
                    style = style,
                )
            )
        }

        /**
         * Bars to a phrase.
         *
         * Dance music is written in eight-bar phrases, and a mix that lands anywhere else lands in
         * the middle of a musical sentence - the beats line up and it still sounds wrong, because
         * the incoming track's phrase starts halfway through the outgoing one's. Handing over on
         * any downbeat, which is what this did first, is beat-matched but not phrase-matched.
         *
         * The idea of snapping to phrases rather than to bars is from kckDeepak's AI DJ Mixing
         * System (github.com/kckDeepak/AI-DJ-Mixing-System, MIT), which counts a phrase as 32 beats.
         */
        private const val BARS_PER_PHRASE = 8

        /**
         * The last phrase boundary of [track] at or before [limitMs], extended past the window.
         *
         * Walking the stored beats would be wrong, and wrong in a way that ruins the feature: the
         * analysis covers the first two minutes and the transition happens at the *end* of the
         * track, so on anything longer than the window the last stored downbeat is around 2:00.
         * Handing over there does not mix a track, it truncates it.
         *
         * Counting bars forward from the first downbeat is a stopgap, not a fix, and it is worth
         * being honest about the size of the error. The grid is a fitted constant tempo, so it
         * continues indefinitely by construction - but extrapolating it inherits the *tempo
         * estimate's* error, multiplied by how far it is carried. At 128 BPM, 0.04 BPM out - the
         * best accuracy the test matrix achieves - is about 75 ms of drift across four minutes,
         * which is a fifth of a beat and audible as a mix that is not tight. On a track that drifts
         * or changes feel, the tempo measured over the first two minutes is simply the wrong number
         * for the last thirty seconds, and no amount of arithmetic recovers that.
         *
         * The real answer is to analyse the outgoing track's tail, which is where the transition
         * actually happens, reusing the tail decode that prepares the ghost handover. See the
         * Automix notes in TODO.md.
         */
        private fun phraseStartAtOrBefore(
            track: AnalysedTrack,
            beats: IntArray,
            limitMs: Long,
        ): Long? {
            val firstDownbeat = beats.getOrNull(track.downbeatIndex)?.toLong() ?: return null
            val phraseMs = 60_000.0 / track.bpm * track.beatsPerBar * BARS_PER_PHRASE
            if (phraseMs <= 0.0) return null
            /*
             * Phrases are counted from the first downbeat, which assumes the track's phrasing
             * starts where its bars do. That is right for most produced music and wrong for
             * anything with an odd-length intro, and there is no way to tell the two apart without
             * the structural analysis that is still on the list.
             */
            val phrases = floor((limitMs - firstDownbeat) / phraseMs)
            if (phrases < 0.0) return null
            return firstDownbeat + (phrases * phraseMs).toLong()
        }
    }
}
