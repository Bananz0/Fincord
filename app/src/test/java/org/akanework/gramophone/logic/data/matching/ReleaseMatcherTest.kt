package org.akanework.gramophone.logic.data.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMatcherTest {

    @Test
    fun punctuationAndAmpersandsDoNotStopATitleMatching() {
        // The case from the field: the two catalogues spell this identically, but the old matcher
        // compared stripped strings that disagreed about "&" and gave up.
        val wanted = release("U, Me & My Ego", "Chxrry22")
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Chxrry22", album = "U, Me & My Ego"),
            listOf(release("The Devil Is Afraid of Music", "U. S. Maritime Service"), wanted),
        )

        assertEquals(wanted, match?.release)
    }

    @Test
    fun unrelatedResultsAreNotOfferedAsAMatch() {
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Chxrry22", album = "U, Me & My Ego"),
            listOf(
                release("u can find me escaping reality in", "SPARKLEWOLF RADIO"),
                release("U R My Paradise / Shake Me Up", "DJ ZET"),
                release("BEAT MY ASS OR SHUT THE FUCK UP", "staysie atoms"),
            ),
        )

        assertNull(match)
    }

    @Test
    fun searchResultsDropTheNoiseTheServerThrewIn() {
        val ranked = ReleaseMatcher.rank(
            ReleaseQuery.freeText("U, Me & My Ego"),
            listOf(
                release("The Devil Is Afraid of Music", "U. S. Maritime Service"),
                release("U, Me & My Ego", "Chxrry22"),
                release("BEAT MY ASS OR SHUT THE FUCK UP", "staysie atoms"),
            ),
        )

        assertEquals("U, Me & My Ego", ranked.first().release.title)
        assertFalse(ranked.any { it.release.artist == "staysie atoms" })
    }

    @Test
    fun shortWordsDoNotMatchInsideUnrelatedWords() {
        // Live Lidarr answers "U, Me & My Ego" with this and nothing else. Matching the query's
        // words against the name with its spaces removed made it look like a hit: "ego" is inside
        // "I've Got" once that becomes "ivegot", and "and" is inside "Band".
        val ranked = ReleaseMatcher.rank(
            ReleaseQuery.freeText("U, Me & My Ego"),
            listOf(
                release(
                    "The Devil Is Afraid of Music / I’ve Got My Love to Keep Me Warm / " +
                        "Cradle Song / I Know That You Know",
                    "U. S. Maritime Service Training Station Band",
                    year = 1945,
                )
            ),
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun relevanceSeparatesAFoundRecordFromANearestMiss() {
        val query = ReleaseQuery.freeText("Halsey")
        // An artist match is highly relevant even though it says nothing about which album is meant.
        val found = ReleaseMatcher.rank(query, listOf(release("Manic", "Halsey"))).first()
        assertTrue(found.relevance >= 0.9)

        // Filler is not, which is the signal that the search needs a second opinion.
        val miss = ReleaseMatcher.rank(
            ReleaseQuery.freeText("Chxrry"),
            listOf(release("Cherry Bomb", "Tyler, The Creator")),
        ).firstOrNull()
        assertTrue(miss == null || miss.relevance < 0.6)
    }

    @Test
    fun aWrongAlbumByTheRightArtistIsNeverRequested() {
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Halsey", album = "The Great Impersonator"),
            listOf(release("Manic", "Halsey")),
        )

        assertNull(match)
    }

    @Test
    fun aReleaseGroupIdSettlesItOutright() {
        val id = "5c1a2b3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d"
        val match = ReleaseMatcher.best(
            ReleaseQuery(
                artist = "credited differently",
                album = "titled differently",
                musicBrainzReleaseGroupId = id,
            ),
            listOf(release("Manic", "Halsey"), release("BADLANDS", "Halsey", mbid = id)),
        )

        assertEquals(MatchConfidence.EXACT, match?.confidence)
        assertEquals("BADLANDS", match?.release?.title)
    }

    @Test
    fun aCompilationMayDisagreeAboutTheArtistButIsNotRequestedUnasked() {
        val query = ReleaseQuery(
            artist = "Ariana Grande",
            album = "Now That's What I Call Music! 100",
        )
        val compilation = release("Now That's What I Call Music! 100", "Various Artists")

        // Shown, because the album title is unmistakable...
        assertEquals(compilation, ReleaseMatcher.rank(query, listOf(compilation)).first().release)
        // ...but not sent, because the credit does not agree and a compilation is a big download.
        assertNull(ReleaseMatcher.best(query, listOf(compilation)))
    }

    @Test
    fun editionDecorationDoesNotBreakTheMatchAndDeluxeWins() {
        val deluxe = release("Scarlet (Deluxe Edition)", "Doja Cat", year = 2024)
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Doja Cat", album = "Scarlet"),
            listOf(release("Scarlet", "Doja Cat", year = 2023), deluxe),
        )

        assertEquals(deluxe, match?.release)
    }

    @Test
    fun theRequestedYearBreaksATieBetweenPressings() {
        val original = release("Blue Weekend", "Wolf Alice", year = 2021)
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Wolf Alice", album = "Blue Weekend", year = 2021),
            listOf(release("Blue Weekend", "Wolf Alice", year = 2022), original),
        )

        assertEquals(original, match?.release)
    }

    @Test
    fun aBareArtistQueryRanksThatArtistsRecordsAboveAnAlbumNamedAfterThem() {
        val ranked = ReleaseMatcher.rank(
            ReleaseQuery.freeText("Halsey"),
            listOf(release("Halsey", "LibraH"), release("Manic", "Halsey")),
        )

        assertEquals("Halsey", ranked.first().release.artist)
    }

    @Test
    fun aCombinedPhraseRanksTheExactRecordFirst() {
        val ranked = ReleaseMatcher.rank(
            ReleaseQuery.freeText("Halsey Badlands"),
            listOf(
                release("Halsey X Magnum", "Halsey"),
                release("BADLANDS", "Halsey"),
                release("Bedlands: Lullaby Renditions of Halsey Songs", "Sparrow Sleeps"),
            ),
        )

        assertEquals("BADLANDS", ranked.first().release.title)
    }

    @Test
    fun typedTextIsNeverRequestedAutomatically() {
        val exact = release("BADLANDS", "Halsey")
        val ranked = ReleaseMatcher.rank(ReleaseQuery.freeText("Halsey BADLANDS"), listOf(exact))

        assertEquals(exact, ranked.first().release)
        // Free text is a guess about what someone meant; they pick from the list themselves.
        assertNull(ReleaseMatcher.best(ReleaseQuery.freeText("Halsey BADLANDS"), listOf(exact)))
    }

    @Test
    fun aTrackWithNoAlbumMatchesTheSingleNamedAfterIt() {
        val single = release("Nights", "Frank Ocean")
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Frank Ocean", track = "Nights"),
            listOf(release("Blonde", "Frank Ocean"), single),
        )

        assertEquals(single, match?.release)
    }

    @Test
    fun aTrackWithNoAlbumAndNoArtistIsNotGuessedAt() {
        assertNull(
            ReleaseMatcher.best(
                ReleaseQuery(track = "Intro"),
                listOf(release("Intro", "Somebody Else")),
            )
        )
    }

    @Test
    fun aShortArtistNameDoesNotMatchEveryRecordItAppearsInside() {
        // "Ye" is inside "Kanye West"; substring matching used to call that agreement.
        val scored = ReleaseMatcher.rank(
            ReleaseQuery(artist = "Ye", album = "Donda"),
            listOf(release("Yeezus", "Kanye West")),
        )

        assertTrue(scored.isEmpty())
    }

    @Test
    fun accentsAndTypographicApostrophesAreFolded() {
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Beyonce", album = "Don't Hurt Yourself"),
            listOf(release("Don’t Hurt Yourself", "Beyoncé")),
        )

        assertNotNull(match)
    }

    @Test
    fun aSequelIsNotTheRecordItIsASequelTo() {
        // From the field: the library holds only SremmLife 2, so the discography row for the
        // original SremmLife matched it - was labelled "In your library" and opened the sequel.
        assertNull(
            ReleaseMatcher.best(
                ReleaseQuery(artist = "Rae Sremmurd", album = "SremmLife"),
                listOf(release("SremmLife 2", "Rae Sremmurd")),
            )
        )
        assertNull(
            ReleaseMatcher.best(
                ReleaseQuery(artist = "Rae Sremmurd", album = "SremmLife 2"),
                listOf(release("SremmLife", "Rae Sremmurd")),
            )
        )
    }

    @Test
    fun theRealSequelStillMatchesWhenBothArePresent() {
        val sequel = release("SremmLife 2", "Rae Sremmurd")
        val match = ReleaseMatcher.best(
            ReleaseQuery(artist = "Rae Sremmurd", album = "SremmLife 2"),
            listOf(release("SremmLife", "Rae Sremmurd"), sequel),
        )

        assertEquals(sequel, match?.release)
    }

    private fun release(
        title: String,
        artist: String,
        year: Int? = null,
        totalTracks: Int? = null,
        mbid: String? = null,
    ) = object : ReleaseCandidate {
        override val title = title
        override val artist = artist
        override val year = year
        override val totalTracks = totalTracks
        override val musicBrainzId = mbid
        override fun toString() = "$artist - $title"
    }
}
