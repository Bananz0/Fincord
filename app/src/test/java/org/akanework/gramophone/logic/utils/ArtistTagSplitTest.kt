package org.akanework.gramophone.logic.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides how many acts one artist credit names.
 *
 * Every case here is a real credit from a Jellyfin library, because the delimiters that are safe
 * to split on can only be settled against names people actually have.
 */
class ArtistTagSplitTest {

    @Test
    fun aSemicolonSeparatesTheActsAServerHandsBackAsOneArtist() {
        assertEquals(listOf("Asake", "DJ Snake"), "Asake; DJ Snake".splitArtistTag())
        assertEquals(
            listOf("YG", "Isaiah falls", "Odeal", "Sasha Keable"),
            "YG; Isaiah falls; Odeal; Sasha Keable".splitArtistTag(),
        )
    }

    @Test
    fun aFeaturingRunSeparatesTheGuestFromTheOwner() {
        assertEquals(listOf("Jorja Smith", "Devlin"), "Jorja Smith Feat. Devlin".splitArtistTag())
        assertEquals(listOf("Skepta", "JME"), "Skepta ft JME".splitArtistTag())
        assertEquals(listOf("Burna Boy", "Dave"), "Burna Boy featuring Dave".splitArtistTag())
    }

    @Test
    fun commasAndAmpersandsBelongToTheNameAndAreLeftAlone() {
        // Splitting on these would invent more artists than the rule merges.
        assertEquals(listOf("Tyler, The Creator"), "Tyler, The Creator".splitArtistTag())
        assertEquals(listOf("Earth, Wind & Fire"), "Earth, Wind & Fire".splitArtistTag())
        assertEquals(listOf("Kabza De Small"), "Kabza De Small".splitArtistTag())
    }

    @Test
    fun aDelimiterInsideAWordIsNotADelimiter() {
        assertEquals(listOf("Daft Punk"), "Daft Punk".splitArtistTag())
        assertEquals(listOf("Featurette"), "Featurette".splitArtistTag())
        assertEquals(listOf("Left Foot Forward"), "Left Foot Forward".splitArtistTag())
    }

    @Test
    fun spacingAndEmptyValuesDoNotProduceBlankArtists() {
        assertEquals(listOf("Asake", "Tiakola"), "Asake;Tiakola".splitArtistTag())
        assertEquals(listOf("Asake", "Tiakola"), " Asake ;  Tiakola ".splitArtistTag())
        assertEquals(listOf("Asake"), "Asake;".splitArtistTag())
        assertEquals(emptyList<String>(), " ; ".splitArtistTag())
    }
}
