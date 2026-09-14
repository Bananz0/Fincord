package org.akanework.gramophone.logic.data.automix

import org.junit.Assert.assertEquals
import org.junit.Test

class GhostTransitionMathTest {

    @Test
    fun fadeWaitsAtZeroWhileIncomingDecoderHasNotReachedCue() {
        assertEquals(0f, ghostFadeProgress(positionMs = 0L, cueMs = 12_000L, fadeEndMs = 20_000L))
        assertEquals(0f, ghostFadeProgress(positionMs = 12_000L, cueMs = 12_000L, fadeEndMs = 20_000L))
    }

    @Test
    fun fadeTracksIncomingTimelineAndClampsAtEnd() {
        assertEquals(
            0.5f,
            ghostFadeProgress(positionMs = 16_000L, cueMs = 12_000L, fadeEndMs = 20_000L),
        )
        assertEquals(
            1f,
            ghostFadeProgress(positionMs = 25_000L, cueMs = 12_000L, fadeEndMs = 20_000L),
        )
    }

    @Test
    fun constantPowerCurveKeepsCombinedPowerFlat() {
        for (step in 0..20) {
            val progress = step / 20f
            val outgoing = FadeCurve.CONSTANT_POWER.gainAt(progress)
            val incoming = FadeCurve.CONSTANT_POWER.incomingGainAt(progress)
            assertEquals(1f, outgoing * outgoing + incoming * incoming, 0.0001f)
        }
    }

    @Test
    fun correlatedLapUsesComplementaryLinearGains() {
        for (step in 0..20) {
            val progress = step / 20f
            val outgoing = FadeCurve.LINEAR.gainAt(progress)
            val ghost = FadeCurve.LINEAR.incomingGainAt(progress)
            assertEquals(1f, outgoing + ghost, 0.0001f)
        }
    }
}
