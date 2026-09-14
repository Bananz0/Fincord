package uk.akane.accord.ui.components.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLineTest {

    /** Two words, each held for a full second - far longer than either is sung for. */
    private val line = LyricsLine(
        timestamp = 1_000L,
        agent = null,
        text = "Hello world",
        background = null,
        wordTimings = listOf(
            LyricsWordTiming(endOffset = 6, startTimestamp = 1_000L, endTimestamp = 2_000L),
            LyricsWordTiming(endOffset = 11, startTimestamp = 2_000L, endTimestamp = 3_000L),
        ),
    )

    /** A word sung quickly, well inside the fill window. */
    private val brisk = LyricsLine(
        timestamp = 0L,
        agent = null,
        text = "Go now",
        background = null,
        wordTimings = listOf(
            LyricsWordTiming(endOffset = 3, startTimestamp = 0L, endTimestamp = 200L),
            LyricsWordTiming(endOffset = 6, startTimestamp = 200L, endTimestamp = 400L),
        ),
    )

    @Test
    fun aWordFillsAcrossItsCharactersAsItIsSung() {
        assertEquals(0f, line.highlightOffsetAt(1_000L), 0.001f)
        // "Hello " is six characters, so its window is 6 * 140 = 840 ms and half of it is 420.
        assertEquals(3f, line.highlightOffsetAt(1_420L), 0.001f)
    }

    @Test
    fun aProlongedWordIsLitWholeRatherThanWipedSlowly() {
        // The note runs to 2_000, but the word is fully lit once its window is up and stays that
        // way. Sweeping across the whole second is what left the boundary crawling through it;
        // rushing it in a flat 300 ms, as this used to, is what read as a stutter instead.
        assertEquals(6f, line.highlightOffsetAt(1_840L), 0.001f)
        assertEquals(6f, line.highlightOffsetAt(1_999L), 0.001f)
        // "world" is five characters: a 700 ms window from 2_000.
        assertEquals(8.5f, line.highlightOffsetAt(2_350L), 0.001f)
        assertEquals(11f, line.highlightOffsetAt(2_700L), 0.001f)
    }

    @Test
    fun theWindowScalesWithTheWordSoAPauseDoesNotCrawlAndAHeldNoteDoesNotRush() {
        val mixed = LyricsLine(
            timestamp = 0L,
            agent = null,
            text = "Ohhhhhhh no",
            background = null,
            wordTimings = listOf(
                // Genuinely held: eight characters, sung across 1_000 ms, well inside 8 * 140.
                LyricsWordTiming(endOffset = 8, startTimestamp = 0L, endTimestamp = 1_000L),
                // Enhanced LRC has no end times, so "no" appears to last four seconds when in
                // truth the band plays the rest. Its window is 3 * 140 = 420 ms.
                LyricsWordTiming(endOffset = 11, startTimestamp = 1_000L, endTimestamp = 5_000L),
            ),
        )
        // The held note sweeps across its own duration rather than finishing in 300 ms.
        assertEquals(4f, mixed.highlightOffsetAt(500L), 0.001f)
        assertEquals(8f, mixed.highlightOffsetAt(1_000L), 0.001f)
        // The apparent four second word lights promptly and then simply stays lit.
        assertEquals(11f, mixed.highlightOffsetAt(1_420L), 0.001f)
        assertEquals(11f, mixed.highlightOffsetAt(4_999L), 0.001f)
    }

    @Test
    fun aBriskWordStillFillsOverItsOwnDurationNotTheWindow() {
        // Shorter than the window, so nothing is capped and the fill tracks the note exactly.
        assertEquals(1.5f, brisk.highlightOffsetAt(100L), 0.001f)
        assertEquals(3f, brisk.highlightOffsetAt(200L), 0.001f)
        assertEquals(4.5f, brisk.highlightOffsetAt(300L), 0.001f)
    }

    @Test
    fun theFillHoldsAtAWordBoundaryUntilTheNextWordBegins() {
        val gapped = LyricsLine(
            timestamp = 0L,
            agent = null,
            text = "One two",
            background = null,
            wordTimings = listOf(
                LyricsWordTiming(endOffset = 4, startTimestamp = 0L, endTimestamp = 200L),
                LyricsWordTiming(endOffset = 7, startTimestamp = 5_000L, endTimestamp = 5_200L),
            ),
        )
        // Through the instrumental gap the fill sits at the end of the word already sung.
        assertEquals(4f, gapped.highlightOffsetAt(1_000L), 0.001f)
        assertEquals(4f, gapped.highlightOffsetAt(4_999L), 0.001f)
        assertEquals(7f, gapped.highlightOffsetAt(5_200L), 0.001f)
    }

    @Test
    fun highlightClampsBeforeAndAfterTheLine() {
        assertEquals(0f, line.highlightOffsetAt(0L), 0.001f)
        assertEquals(11f, line.highlightOffsetAt(5_000L), 0.001f)
    }

    @Test
    fun glyphAnimationMatchesTheReferenceLiftScaleAndBlurCurves() {
        val atStart = lyricsGlyphTransform(1_000L, 1_000L, 2_000L, 0, 5, 3f)
        assertEquals(-3f, atStart.translateY, 0.001f)
        assertEquals(1f, atStart.scale, 0.001f)
        assertEquals(0f, atStart.blurRadius, 0.001f)

        // Halfway through a glyph's own window it has crossed the baseline and reached the peak of
        // both the swell and the bounce blur. The curves are the reference renderer's; their
        // strengths are Fincord's own, tuned down from the reference's 10% swell and twelve-pixel
        // halo, which together read as a letter lit from behind rather than one being sung.
        val atMidpoint = lyricsGlyphTransform(1_400L, 1_000L, 2_000L, 0, 5, 3f)
        assertEquals(1.5f, atMidpoint.translateY, 0.001f)
        assertEquals(1.07f, atMidpoint.scale, 0.001f)
        assertEquals(4f, atMidpoint.blurRadius, 0.001f)

        val settled = lyricsGlyphTransform(2_000L, 1_000L, 2_000L, 4, 5, 3f)
        assertEquals(0f, settled.translateY, 0.001f)
        assertEquals(1f, settled.scale, 0.001f)
        assertEquals(0f, settled.blurRadius, 0.001f)
    }

    private fun plain(timestamp: Long, text: String) =
        LyricsLine(timestamp = timestamp, agent = null, text = text, background = null)

    @Test
    fun aLongIntroGetsCountingDotsBeforeTheFirstLine() {
        val counted = Lyrics(listOf(plain(12_000L, "First"), plain(15_000L, "Second")))
            .withInterludes()
        assertEquals(3, counted.lyrics.size)
        val intro = counted.lyrics.first()
        assertTrue(intro.isInterlude)
        assertEquals(0L, intro.timestamp)
        assertEquals(12_000L, intro.interludeEndMs)
        // The short gap between the two real lines is left alone.
        assertFalse(counted.lyrics[2].isInterlude)
    }

    @Test
    fun aSongThatStartsSingingImmediatelyGetsNoDots() {
        val lyrics = Lyrics(listOf(plain(900L, "Straight in"), plain(3_000L, "And on")))
        assertEquals(lyrics, lyrics.withInterludes())
    }

    @Test
    fun aLongGapBetweenVersesGetsItsOwnDots() {
        val counted = Lyrics(listOf(plain(0L, "Verse"), plain(20_000L, "Chorus")))
            .withInterludes()
        assertEquals(3, counted.lyrics.size)
        val gap = counted.lyrics[1]
        assertTrue(gap.isInterlude)
        assertEquals(0L, gap.timestamp)
        assertEquals(20_000L, gap.interludeEndMs)
    }

    @Test
    fun aGapIsMeasuredFromTheEndOfTheWordsWhenItIsKnown() {
        // Word timings say the first line is still being sung until 15_000, so what follows is a
        // three second gap and not a fifteen second one.
        val sung = LyricsLine(
            timestamp = 0L,
            agent = null,
            text = "Held",
            background = null,
            wordTimings = listOf(
                LyricsWordTiming(endOffset = 4, startTimestamp = 0L, endTimestamp = 15_000L),
            ),
        )
        val lyrics = Lyrics(listOf(sung, plain(18_000L, "Next")))
        assertEquals(lyrics, lyrics.withInterludes())
    }

    @Test
    fun theDotsCountAcrossTheWholeGap() {
        val intro = Lyrics(listOf(plain(10_000L, "First"))).withInterludes().lyrics.first()
        assertEquals(0f, intro.interludeProgressAt(0L), 0.001f)
        assertEquals(0.5f, intro.interludeProgressAt(5_000L), 0.001f)
        assertEquals(1f, intro.interludeProgressAt(10_000L), 0.001f)
        // Clamped either side; a seek backwards must not drive it negative.
        assertEquals(0f, intro.interludeProgressAt(-2_000L), 0.001f)
        assertEquals(1f, intro.interludeProgressAt(12_000L), 0.001f)
    }
}
