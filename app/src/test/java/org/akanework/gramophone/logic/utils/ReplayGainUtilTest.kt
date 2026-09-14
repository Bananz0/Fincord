package org.akanework.gramophone.logic.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainUtilTest {
    @Test
    fun trackModeUsesTrackTags() {
        val result = ReplayGainUtil.calculateGain(
            tags = tags(trackGain = -6f, trackPeak = 0.8f, albumGain = -3f, albumPeak = 0.9f),
            mode = ReplayGainUtil.Mode.Track,
            rgGain = 0,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )

        assertEquals(ReplayGainUtil.dbToAmpl(-6f), result!!.first, 0.0001f)
        assertNull(result.second)
    }

    @Test
    fun albumModeFallsBackToTrackTags() {
        val result = ReplayGainUtil.calculateGain(
            tags = tags(trackGain = -4f, trackPeak = 0.75f),
            mode = ReplayGainUtil.Mode.Album,
            rgGain = 0,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )

        assertEquals(ReplayGainUtil.dbToAmpl(-4f), result!!.first, 0.0001f)
    }

    @Test
    fun positiveGainIsLimitedByPeak() {
        val result = ReplayGainUtil.calculateGain(
            tags = tags(trackGain = 6f, trackPeak = 0.8f),
            mode = ReplayGainUtil.Mode.Track,
            rgGain = 0,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )

        assertEquals(1.25f, result!!.first, 0.0001f)
        assertNull(result.second)
    }

    @Test
    fun missingTagsRemainDistinguishableFromDisabledMode() {
        val missing = ReplayGainUtil.calculateGain(
            tags = tags(),
            mode = ReplayGainUtil.Mode.Track,
            rgGain = 0,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )
        val disabled = ReplayGainUtil.calculateGain(
            tags = tags(),
            mode = ReplayGainUtil.Mode.None,
            rgGain = 0,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )

        assertNull(missing)
        assertEquals(1f, disabled!!.first, 0f)
    }

    private fun tags(
        trackGain: Float? = null,
        trackPeak: Float? = null,
        albumGain: Float? = null,
        albumPeak: Float? = null,
    ) = ReplayGainUtil.ReplayGainInfo(trackGain, trackPeak, albumGain, albumPeak)
}
