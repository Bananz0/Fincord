package org.akanework.gramophone.logic.data.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Takes the low end off the outgoing track across a transition.
 *
 * What a DJ's left hand is doing while the crossfader moves. Two basslines and two kick drums in
 * the same octave do not blend: where the notes disagree the sum is muddy, and where the kicks are
 * not exactly together the low end pumps. Level alone cannot fix that, because at the midpoint of a
 * crossfade both tracks are still half there. Removing one track's bass entirely hands the bottom
 * of the mix to the other, and leaves the first audible as melody and vocal over it.
 *
 * A second-order Butterworth high-pass, applied once per channel as the tail is rendered, so none
 * of this is on the audio thread. Butterworth because its passband is flat: a resonant filter would
 * put a bump just above the cutoff, which on a bassline is a boost on exactly the notes being
 * removed.
 *
 * The cutoff sweeps rather than switching. Dropping the bass in one step is audible as a click on
 * anything with energy down there, and the sweep is also what a real filter knob does.
 *
 * This is the offline form, for a tail rendered ahead of time. The ghost player renders its tail as
 * it plays, so it filters through [BassSwapAudioProcessor] instead - which is the same filter with
 * the same coefficients, kept in [HighPassBiquad] so there is one implementation of it and not two.
 */
class BassSwap(
    private val sampleRate: Int,
    private val targetHz: Float,
) {

    /**
     * Filters [samples] in place, sweeping the cutoff from nothing up to [targetHz].
     *
     * The sweep is spread across the first half of the tail rather than all of it: by the midpoint
     * of a crossfade the incoming track should already own the low end, and a filter still opening
     * at the end of the overlap means both tracks had bass through the part that matters most.
     */
    fun applyTo(samples: FloatArray, channels: Int) {
        if (targetHz <= 0f || channels <= 0 || sampleRate <= 0) return
        val frames = samples.size / channels
        if (frames <= 0) return

        val sweepFrames = (frames / 2).coerceAtLeast(1)

        for (channel in 0 until channels) {
            val filter = HighPassBiquad(sampleRate)
            for (frame in 0 until frames) {
                if (frame <= sweepFrames) {
                    // Recomputing per frame is a handful of trig on a buffer that is rendered
                    // once, off the audio thread. Doing it per block instead would step the
                    // cutoff, and a stepped filter is exactly the click this avoids.
                    val progress = frame.toFloat() / sweepFrames
                    filter.setCutoff(HighPassBiquad.cutoffFor(targetHz, progress))
                }
                val index = frame * channels + channel
                samples[index] = filter.process(samples[index])
            }
        }
    }
}

/**
 * One channel of a second-order Butterworth high-pass, in Direct Form I.
 *
 * Split out because the same filter runs in two places - offline over a rendered tail, and on the
 * ghost player's audio thread - and a bass swap that sounds different depending on which path it
 * took would be a bug nobody would think to look for.
 */
internal class HighPassBiquad(private val sampleRate: Int) {

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    init {
        setCutoff(MIN_CUTOFF_HZ)
    }

    /** Sets the corner frequency. Cheap enough to call per frame, and per block is plenty. */
    fun setCutoff(cutoffHz: Float) {
        val cutoff = cutoffHz.coerceIn(MIN_CUTOFF_HZ, sampleRate / 2.2f)
        val omega = 2.0 * PI * cutoff / sampleRate
        val cosOmega = cos(omega)
        val sinOmega = sin(omega)
        // Q of 1/sqrt(2) is what makes this Butterworth rather than something with a resonant peak.
        val alpha = sinOmega / (2.0 * (1.0 / sqrt(2.0)))
        val a0 = 1.0 + alpha
        b0 = (((1.0 + cosOmega) / 2.0) / a0).toFloat()
        b1 = ((-(1.0 + cosOmega)) / a0).toFloat()
        b2 = b0
        a1 = ((-2.0 * cosOmega) / a0).toFloat()
        a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(sample: Float): Float {
        val y0 = b0 * sample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = sample
        y2 = y1
        y1 = y0
        return y0
    }

    /** Clears the delay line. A filter carrying state from a different stream rings on the seam. */
    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }

    companion object {

        /**
         * Where a sweep starts.
         *
         * Below about 20 Hz the filter is doing nothing audible, and the coefficients degenerate as
         * the cutoff approaches zero, so this is both the musical and the numerical floor.
         */
        const val MIN_CUTOFF_HZ = 20f

        /** The cutoff [progress] of the way from [MIN_CUTOFF_HZ] to [targetHz]. */
        fun cutoffFor(targetHz: Float, progress: Float): Float =
            MIN_CUTOFF_HZ + (targetHz - MIN_CUTOFF_HZ) * progress.coerceIn(0f, 1f)
    }
}
