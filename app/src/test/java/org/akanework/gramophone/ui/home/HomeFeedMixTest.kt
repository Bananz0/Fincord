package org.akanework.gramophone.ui.home

import org.akanework.gramophone.logic.data.matching.MusicText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedMixTest {

    private data class Track(
        val id: String,
        val artist: String,
        val album: String,
        val title: String,
        val plays: Int = 0,
    )

    private fun List<Track>.spread() = with(HomeFeed) {
        spreadBy { listOf(it.artist, it.album) }
    }

    private fun List<Track>.dedupe() = with(HomeFeed) {
        dedupeBy(
            key = { "${it.artist}\u0000${MusicText.recordingKey(it.title)}" },
            prefer = compareBy<Track> { it.plays }.thenByDescending { it.id },
        )
    }

    @Test
    fun consecutiveTracksDoNotShareAnArtistOrAlbum() {
        val ordered = listOf(
            Track("1", "Rae Sremmurd", "SremmLife", "No Flex Zone"),
            Track("2", "Rae Sremmurd", "SremmLife", "No Type"),
            Track("3", "Rae Sremmurd", "SremmLife", "Throw Sum Mo"),
            Track("4", "Migos", "Culture", "Bad and Boujee"),
            Track("5", "Future", "DS2", "Thought It Was a Drought"),
        ).spread()

        assertEquals(5, ordered.size)
        // Only the tail, where nothing but the one artist is left, may repeat.
        assertTrue(ordered[0].artist != ordered[1].artist)
        assertTrue(ordered[1].artist != ordered[2].artist)
    }

    @Test
    fun aSingleArtistMixIsLeftAloneRatherThanRefused() {
        val tracks = (1..4).map { Track("$it", "Halsey", "Badlands", "Track $it") }
        assertEquals(tracks.map(Track::id), tracks.spread().map(Track::id))
    }

    @Test
    fun theSameRecordingOnThreeReleasesAppearsOnce() {
        // The library case: a song on its single, its album, and a remastered reissue.
        val kept = listOf(
            Track("single", "Halsey", "Badlands Single", "Colors", plays = 1),
            Track("album", "Halsey", "Badlands", "Colors", plays = 9),
            Track("reissue", "Halsey", "Badlands (Deluxe)", "Colors - 2016 Remaster", plays = 0),
        ).dedupe()

        assertEquals(1, kept.size)
        // The copy the user actually plays is the one that survives.
        assertEquals("album", kept.single().id)
    }

    @Test
    fun aLiveTakeIsADifferentRecordingAndSurvives() {
        val kept = listOf(
            Track("studio", "Halsey", "Badlands", "Colors"),
            Track("live", "Halsey", "Live at Webster Hall", "Colors (Live)"),
        ).dedupe()

        assertEquals(2, kept.size)
    }

    @Test
    fun dedupingKeepsThePositionOfTheFirstCopy() {
        val kept = listOf(
            Track("a", "Halsey", "Badlands", "Colors", plays = 1),
            Track("b", "Migos", "Culture", "Bad and Boujee"),
            Track("c", "Halsey", "Hopeless Fountain Kingdom", "Colors", plays = 5),
        ).dedupe()

        assertEquals(listOf("c", "b"), kept.map(Track::id))
    }
}
