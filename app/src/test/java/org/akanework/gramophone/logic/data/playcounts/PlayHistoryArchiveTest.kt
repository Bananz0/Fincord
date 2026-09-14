package org.akanework.gramophone.logic.data.playcounts

import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.RowOutcome
import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.appleRow
import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.splitCsv
import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.spotifyRow
import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.toEpochSecondsOrZero
import org.akanework.gramophone.logic.data.playcounts.PlayHistoryArchive.youTubeRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decisions each export format forces, stated without a file.
 *
 * These are undocumented formats in several vintages, and no real export had ever been through
 * this code. The readers themselves need a device - they are streams over Android's JSON pull
 * parser - but every judgement they make about a row is here, which is where the doubt actually
 * was: which column wins, what counts as a play rather than a skip, and what has to come off a
 * title before it will match anything in the library.
 */
class PlayHistoryArchiveTest {

    // ---------------------------------------------------------------- Spotify

    @Test
    fun spotifyExtendedHistoryUsesTheMasterMetadataFields() {
        val row = spotifyRow(
            msPlayed = 210_000L,
            trackName = "Nights",
            albumArtist = "Frank Ocean",
            albumName = "Blonde",
            timestamp = "2023-07-14T21:03:11Z",
        )
        assertEquals(
            RowOutcome.Play("Frank Ocean", "Nights", "Blonde", 1_689_368_591L),
            row,
        )
    }

    @Test
    fun spotifyLegacyHistoryHasNoAlbumAndAMinuteResolutionTimestamp() {
        val row = spotifyRow(
            msPlayed = 200_000L,
            trackName = "Redbone",
            albumArtist = "Childish Gambino",
            albumName = null,
            timestamp = "2021-03-14 09:26",
        )
        val play = row as RowOutcome.Play
        assertEquals("Childish Gambino", play.artist)
        assertEquals("Redbone", play.title)
        assertEquals(null, play.album)
        assertEquals(1_615_713_960L, play.atSeconds)
    }

    @Test
    fun spotifySkipsAnythingUnderThirtySeconds() {
        // Spotify's own definition of a stream, and its export records every start including the
        // ones flicked past after two seconds.
        assertEquals(RowOutcome.Skip, spotifyRow(29_999L, "Nights", "Frank Ocean", null, null))
        assertEquals(
            RowOutcome.Play("Frank Ocean", "Nights", null, 0L),
            spotifyRow(30_000L, "Nights", "Frank Ocean", null, null),
        )
    }

    @Test
    fun spotifyRowsWithoutAPlayDurationAreNotHistoryAtAll() {
        // A podcast row, or a file that merely happens to be JSON: not a skip, not counted, and
        // not evidence that this file was understood.
        assertEquals(RowOutcome.NotHistory, spotifyRow(null, "Nights", "Frank Ocean", null, null))
    }

    @Test
    fun spotifyRowsMissingATitleOrArtistAreSkipped() {
        assertEquals(RowOutcome.Skip, spotifyRow(60_000L, null, "Frank Ocean", null, null))
        assertEquals(RowOutcome.Skip, spotifyRow(60_000L, "Nights", null, null, null))
        assertEquals(RowOutcome.Skip, spotifyRow(60_000L, "  ", "Frank Ocean", null, null))
    }

    // ---------------------------------------------------------------- YouTube

    @Test
    fun takeoutMusicRowsLoseTheWatchedPrefixAndTheTopicSuffix() {
        val row = youTubeRow(
            header = "YouTube Music",
            rawTitle = "Watched Kill Bill",
            channel = "SZA - Topic",
            time = "2023-01-02T18:00:00.000Z",
        )
        assertEquals(RowOutcome.Play("SZA", "Kill Bill", null, 1_672_682_400L), row)
    }

    @Test
    fun takeoutOrdinaryYouTubeRowsAreNotMusicHistory() {
        // One file holds both, and only the header tells them apart.
        assertEquals(
            RowOutcome.NotHistory,
            youTubeRow("YouTube", "Watched Some vlog", "A channel", null),
        )
        assertEquals(RowOutcome.NotHistory, youTubeRow(null, "Watched Some vlog", "A channel", null))
    }

    @Test
    fun aNonEnglishTakeoutKeepsItsTitleRatherThanLosingIt() {
        // The prefix is a localised verb, so a German export's title is left whole - matching on
        // "Angesehen: Kill Bill" fails, but silently dropping the row would be worse.
        val row = youTubeRow("YouTube Music", "Angesehen: Kill Bill", "SZA - Topic", null)
        assertEquals(RowOutcome.Play("SZA", "Angesehen: Kill Bill", null, 0L), row)
    }

    @Test
    fun takeoutRowsThatAreOnlyALinkAreSkipped() {
        // A deleted or private video leaves its URL as the title, which matches nothing.
        assertEquals(
            RowOutcome.Skip,
            youTubeRow("YouTube Music", "https://www.youtube.com/watch?v=abc", "SZA - Topic", null),
        )
    }

    @Test
    fun takeoutRowsWithNoChannelAreSkipped() {
        assertEquals(RowOutcome.Skip, youTubeRow("YouTube Music", "Watched Kill Bill", null, null))
    }

    // ------------------------------------------------------------ Apple Music

    private val appleHeader = splitCsv(
        "Apple Music Subscription,Artist Name,Album Name,Song Name," +
            "Play Duration Milliseconds,Media Duration In Milliseconds,Event Start Timestamp"
    )

    private fun appleColumns(header: List<String> = appleHeader) =
        PlayHistoryArchive.AppleColumns(header)

    @Test
    fun appleColumnsAreFoundByNameWhateverTheirOrder() {
        val columns = appleColumns()
        assertEquals(3, columns.title)
        assertEquals(1, columns.artist)
        assertEquals(2, columns.album)
        assertEquals(4, columns.played)
        assertEquals(6, columns.time)
    }

    @Test
    fun appleReadsThePlayDurationAndNeverTheTrackLength() {
        // Media duration is how long the song is. Read as a threshold it would keep every skip of
        // a long song and drop every complete play of a short one.
        val columns = PlayHistoryArchive.AppleColumns(
            splitCsv("Song Name,Artist Name,Media Duration In Milliseconds")
        )
        assertEquals(-1, columns.played)
        assertEquals(
            RowOutcome.Play("Sabrina Carpenter", "Espresso", null, 0L),
            appleRow(columns, splitCsv("Espresso,Sabrina Carpenter,2000")),
        )
    }

    @Test
    fun anOlderAppleExportSpellsTheColumnsDifferently() {
        val columns = PlayHistoryArchive.AppleColumns(
            splitCsv("Content Name,Container Artist Name,Container Name,Play Date Time")
        )
        assertEquals(0, columns.title)
        assertEquals(1, columns.artist)
        assertEquals(2, columns.album)
        assertEquals(3, columns.time)
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheFirstColumn() {
        val columns = PlayHistoryArchive.AppleColumns(
            splitCsv("﻿Song Name,Artist Name".removePrefix("﻿"))
        )
        assertEquals(0, columns.title)
        assertEquals(1, columns.artist)
    }

    @Test
    fun appleSkipsAShortPlayAndKeepsOneWithNoDurationRecorded() {
        val columns = appleColumns()
        assertEquals(
            RowOutcome.Skip,
            appleRow(columns, splitCsv("Yes,Sabrina Carpenter,Short n' Sweet,Espresso,4000,175000,")),
        )
        // Some vintages leave the duration empty; dropping those would discard the whole export.
        val kept = appleRow(
            columns,
            splitCsv("Yes,Sabrina Carpenter,Short n' Sweet,Espresso,,175000,2024-05-01T10:00:00Z"),
        )
        assertEquals(
            RowOutcome.Play("Sabrina Carpenter", "Espresso", "Short n' Sweet", 1_714_557_600L),
            kept,
        )
    }

    @Test
    fun anAppleRowWithNoArtistIsSkippedRatherThanGuessed() {
        // Matching a bare "Intro" against the library would attach plays to the wrong record.
        val columns = appleColumns()
        assertEquals(
            RowOutcome.Skip,
            appleRow(columns, splitCsv("Yes,,Short n' Sweet,Intro,90000,90000,")),
        )
    }

    @Test
    fun aTruncatedAppleRowIsSkippedRatherThanThrowing() {
        val columns = appleColumns()
        assertEquals(RowOutcome.Skip, appleRow(columns, splitCsv("Yes,Sabrina Carpenter")))
    }

    // -------------------------------------------------------------------- CSV

    @Test
    fun csvSplittingSurvivesCommasAndQuotesInsideTitles() {
        assertEquals(
            listOf("Hello", "Wait, What?", "She said \"no\"", ""),
            splitCsv("Hello,\"Wait, What?\",\"She said \"\"no\"\"\","),
        )
    }

    // ------------------------------------------------------------- timestamps

    @Test
    fun timestampsDegradeToZeroRatherThanDroppingThePlay() {
        assertEquals(0L, "".toEpochSecondsOrZero())
        assertEquals(0L, (null as String?).toEpochSecondsOrZero())
        assertEquals(0L, "not a date".toEpochSecondsOrZero())
        // A date with no time at all still places the play in the right day.
        assertEquals(1_714_521_600L, "2024-05-01 lunchtime".toEpochSecondsOrZero())
    }

    @Test
    fun theThreeVintagesOfTimestampAllRead() {
        assertEquals(1_689_368_591L, "2023-07-14T21:03:11Z".toEpochSecondsOrZero())
        assertEquals(1_615_713_960L, "2021-03-14 09:26".toEpochSecondsOrZero())
        assertEquals(1_615_713_965L, "2021-03-14 09:26:05".toEpochSecondsOrZero())
    }
}
