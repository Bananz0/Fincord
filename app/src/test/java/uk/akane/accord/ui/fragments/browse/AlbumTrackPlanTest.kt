package uk.akane.accord.ui.fragments.browse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumTrackPlanTest {

    private fun tracks(entries: List<AlbumTrackPlan.Entry>) =
        entries.filterIsInstance<AlbumTrackPlan.Entry.Track>()

    private fun discs(entries: List<AlbumTrackPlan.Entry>) =
        entries.filterIsInstance<AlbumTrackPlan.Entry.Disc>().map { it.disc }

    @Test
    fun aSingleDiscAlbumGetsNoHeadingAtAll() {
        val plan = AlbumTrackPlan.of(listOf(1, 1, 1, 1))

        assertEquals(4, plan.size)
        assertTrue(discs(plan).isEmpty())
        assertEquals(listOf(0, 1, 2, 3), tracks(plan).map { it.index })
    }

    @Test
    fun anUntaggedAlbumIsAlsoOneDisc() {
        // Every track defaulted to disc one by the caller; nothing distinguishes them.
        val plan = AlbumTrackPlan.of(List(3) { 1 })

        assertTrue(discs(plan).isEmpty())
    }

    @Test
    fun eachDiscGetsOneHeadingInOrder() {
        // Hardwired…To Self-Destruct: six tracks a side.
        val plan = AlbumTrackPlan.of(List(6) { 1 } + List(6) { 2 })

        assertEquals(listOf(1, 2), discs(plan))
        assertEquals(14, plan.size)
        assertTrue(plan.first() is AlbumTrackPlan.Entry.Disc)
        assertEquals(AlbumTrackPlan.Entry.Disc(2), plan[7])
    }

    @Test
    fun trackIndicesStillAddressTheAlbumAndNotTheRows() {
        // The whole point: a heading shifts every row below it, and playback must not shift with
        // it. Track index 6 is the first track of disc two whatever row it happens to occupy.
        val plan = AlbumTrackPlan.of(List(6) { 1 } + List(6) { 2 })

        assertEquals((0..11).toList(), tracks(plan).map { it.index })
        assertEquals(AlbumTrackPlan.Entry.Track(6, endsDisc = false), plan[8])
    }

    @Test
    fun theRuleIsDroppedUnderTheLastTrackOfEachDisc() {
        val plan = AlbumTrackPlan.of(listOf(1, 1, 2, 2, 3))

        assertEquals(listOf(1, 3, 4), tracks(plan).filter { it.endsDisc }.map { it.index })
    }

    @Test
    fun aThreeDiscAnniversaryEditionIsHandledLikeAnyOther() {
        // Music Box: 30th Anniversary Edition - uneven discs, and one of them a single track.
        val plan = AlbumTrackPlan.of(List(7) { 1 } + listOf(2) + List(4) { 3 })

        assertEquals(listOf(1, 2, 3), discs(plan))
        assertEquals(12, tracks(plan).size)
        assertEquals(15, plan.size)
    }

    @Test
    fun anEmptyAlbumPlansNothing() {
        assertTrue(AlbumTrackPlan.of(emptyList()).isEmpty())
    }
}
