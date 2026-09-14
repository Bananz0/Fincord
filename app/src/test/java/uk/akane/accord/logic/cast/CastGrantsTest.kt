package uk.akane.accord.logic.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastGrantsTest {
    @Test
    fun `renewal margin supports every configured grant lifetime`() {
        assertEquals(minutes(6), castGrantRenewalMarginMs(hours(1)))
        assertEquals(minutes(12), castGrantRenewalMarginMs(hours(2)))
        assertEquals(minutes(72), castGrantRenewalMarginMs(hours(12)))
        assertEquals(hours(2), castGrantRenewalMarginMs(hours(48)))
    }

    private fun minutes(value: Long): Long = value * 60 * 1000

    private fun hours(value: Long): Long = minutes(value * 60)
}
