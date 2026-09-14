package org.akanework.gramophone.logic.data.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the bass swap has to do, stated as measurements rather than as coefficients.
 *
 * The filter runs in two places - offline over a rendered tail, and on the ghost player's audio
 * thread - from one implementation, so testing that implementation covers both. A wrong sign or a
 * transposed coefficient still produces plausible-looking audio, and the only way to know a
 * high-pass is a high-pass is to put tones through it and measure what comes out.
 */
class BassSwapFilterTest {

    @Test
    fun passesTheTopAndRemovesTheBottom() {
        // 40 Hz is a bassline and a kick drum; 2 kHz is where the vocal and the melody live. A
        // transition that keeps the second and loses the first is the whole point of the feature.
        val bass = attenuationAt(toneHz = 40.0, cutoffHz = 200f)
        val top = attenuationAt(toneHz = 2_000.0, cutoffHz = 200f)

        assertTrue("40 Hz should be well down, was $bass", bass < 0.1f)
        assertTrue("2 kHz should pass, was $top", top > 0.95f)
    }

    @Test
    fun cutsHigherWhenAskedTo() {
        // The styles differ by where they put the corner, so a higher cutoff has to take more of
        // the low end - otherwise CUT and BLEND would sound the same and the choice would be a lie.
        val at200 = attenuationAt(toneHz = 150.0, cutoffHz = 200f)
        val at400 = attenuationAt(toneHz = 150.0, cutoffHz = 400f)

        assertTrue("400 Hz cutoff should take more of 150 Hz than 200 Hz does", at400 < at200)
    }

    @Test
    fun sweepStartsWhereTheFilterIsInaudibleAndEndsAtTheTarget() {
        assertEquals(HighPassBiquad.MIN_CUTOFF_HZ, HighPassBiquad.cutoffFor(200f, 0f), 0.001f)
        assertEquals(200f, HighPassBiquad.cutoffFor(200f, 1f), 0.001f)
        // Progress arrives from a fade clock that can overshoot at either end.
        assertEquals(200f, HighPassBiquad.cutoffFor(200f, 4f), 0.001f)
        assertEquals(HighPassBiquad.MIN_CUTOFF_HZ, HighPassBiquad.cutoffFor(200f, -1f), 0.001f)
    }

    @Test
    fun resetClearsTheDelayLine() {
        val filter = HighPassBiquad(SAMPLE_RATE)
        filter.setCutoff(200f)
        repeat(1_000) { filter.process(1f) }
        filter.reset()

        // A filter still carrying a settled DC input rings across the seam it was reset for.
        assertEquals(0f, filter.process(0f), 1e-6f)
    }

    /** Steady-state output/input RMS for a sine at [toneHz] through a high-pass at [cutoffHz]. */
    private fun attenuationAt(toneHz: Double, cutoffHz: Float): Float {
        val filter = HighPassBiquad(SAMPLE_RATE)
        filter.setCutoff(cutoffHz)

        val frames = SAMPLE_RATE / 2
        // The first tenth is discarded: a biquad starting from silence has a transient, and
        // measuring through it reports the settling rather than the response.
        val settleFrames = frames / 10
        var inputPower = 0.0
        var outputPower = 0.0
        for (frame in 0 until frames) {
            val input = sin(2.0 * PI * toneHz * frame / SAMPLE_RATE).toFloat()
            val output = filter.process(input)
            if (frame >= settleFrames) {
                inputPower += input.toDouble() * input
                outputPower += output.toDouble() * output
            }
        }
        return (sqrt(outputPower) / sqrt(inputPower)).toFloat()
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
