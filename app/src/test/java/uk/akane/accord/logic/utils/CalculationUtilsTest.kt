package uk.akane.accord.logic.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculationUtilsTest {
    @Test
    fun longDurationsUseHoursInsteadOfUnboundedMinutes() {
        assertEquals("1:02:03", CalculationUtils.convertDurationToTimeStamp(3_723_000L))
    }

    @Test
    fun shortDurationsKeepThePlayerFormat() {
        assertEquals("2:03", CalculationUtils.convertDurationToTimeStamp(123_000L))
        assertEquals("02:03", CalculationUtils.convertDurationToTimeStamp(123_000L, true))
    }
}
