package org.akanework.gramophone.logic.data.acquisition

import org.akanework.gramophone.logic.data.catalog.ExternalTrack
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What gets asked for, and how it is phrased, before any network call happens. */
class RequestPlanningTest {

    @Test
    fun everyTrackFromOneAlbumBecomesOneRequest() {
        val queries = (1..12).map {
            ExternalTrack(
                title = "Track $it",
                artist = "Chxrry22",
                album = "U, Me & My Ego",
                trackNumber = it,
            ).toReleaseQuery()
        }

        assertEquals(1, MusicRequestService.collapse(queries).size)
    }

    @Test
    fun editionsOfOneRecordCollapseTogether() {
        val queries = listOf(
            ReleaseQuery(artist = "Doja Cat", album = "Scarlet"),
            ReleaseQuery(artist = "Doja Cat", album = "Scarlet (Deluxe Edition)"),
        )

        assertEquals(1, MusicRequestService.collapse(queries).size)
    }

    @Test
    fun theCopyCarryingAnIsrcIsTheOneKept() {
        val queries = listOf(
            ReleaseQuery(artist = "Chxrry22", album = "U, Me & My Ego"),
            ReleaseQuery(artist = "Chxrry22", album = "U, Me & My Ego", isrc = "USUG12403891"),
        )

        assertEquals("USUG12403891", MusicRequestService.collapse(queries).single().isrc)
    }

    @Test
    fun twoSinglesByOneArtistStayTwoRequests() {
        val queries = listOf(
            ReleaseQuery(artist = "Frank Ocean", track = "Nights"),
            ReleaseQuery(artist = "Frank Ocean", track = "Pink + White"),
        )

        assertEquals(2, MusicRequestService.collapse(queries).size)
    }

    @Test
    fun nothingIsAskedForOnAnEmptyDescription() {
        assertTrue(MusicRequestService.collapse(listOf(ReleaseQuery())).isEmpty())
    }

    @Test
    fun theAlbumAloneIsAskedForWhenTheCombinedPhraseFails() {
        val terms = LidarrAcquisitionProvider().searchTerms(
            ReleaseQuery(artist = "Chxrry22", album = "U, Me & My Ego")
        )

        assertEquals(listOf("Chxrry22 U, Me & My Ego", "U, Me & My Ego"), terms)
    }

    @Test
    fun withNoAlbumTheTrackTitleIsTheOnlyDescriptionThereIs() {
        val terms = LidarrAcquisitionProvider().searchTerms(
            ReleaseQuery(artist = "Frank Ocean", track = "Nights")
        )

        assertEquals(listOf("Frank Ocean Nights", "Frank Ocean"), terms)
    }

    @Test
    fun typedTextIsAskedForExactlyAsTyped() {
        assertEquals(
            listOf("U, Me & My Ego"),
            LidarrAcquisitionProvider().searchTerms(ReleaseQuery.freeText("U, Me & My Ego")),
        )
    }
}
