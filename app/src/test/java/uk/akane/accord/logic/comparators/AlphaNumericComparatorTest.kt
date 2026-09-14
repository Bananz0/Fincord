package uk.akane.accord.logic.comparators

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaNumericComparatorTest {
    private val comparator = AlphaNumericComparator()

    @Test
    fun missingNamesSortAfterRealNames() {
        assertTrue(comparator.compare(null, "Album") > 0)
        assertTrue(comparator.compare("", "Album") > 0)
        assertTrue(comparator.compare("Album", null) < 0)
        assertEquals(0, comparator.compare(" ", null))
    }

    @Test
    fun alphanumericOrderingRemainsNatural() {
        val sorted = listOf("Track 10", "Track 2", "Track 1").sortedWith(comparator)
        assertEquals(listOf("Track 1", "Track 2", "Track 10"), sorted)
    }

    @Test
    fun ascendingComparatorsKeepTheirFallback() {
        data class Entry(val name: String?, val index: Int)

        val entries = listOf(Entry("Same", 2), Entry("Same", 1))
        val withFallback = SupportComparator.createAlphanumericComparator(
            cnv = Entry::name,
            fallback = compareBy(Entry::index),
        )

        assertEquals(listOf(1, 2), entries.sortedWith(withFallback).map(Entry::index))
    }
}
