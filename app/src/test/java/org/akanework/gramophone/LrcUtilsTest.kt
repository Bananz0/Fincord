package org.akanework.gramophone

import org.akanework.gramophone.logic.utils.LrcUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LrcUtilsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptyInEmptyOut() {
        assertTrue(LrcUtils.parseLrcString("", false).isEmpty())
    }

    @Test
    fun enhancedLrcCreatesAProgressiveRangeForEveryWord() {
        val lyrics = LrcUtils.parseLrcString(
            """
            [00:10.00]<00:10.00>Hello <00:11.00>world
            [00:12.00]Next line
            """.trimIndent(),
            true,
        )

        assertEquals("Hello world", lyrics[0].content)
        assertEquals(
            listOf(
                Triple(6, 10_000L, 11_000L),
                Triple(11, 11_000L, 12_000L),
            ),
            lyrics[0].wordTimestamps,
        )
        assertEquals(12_000L, lyrics[0].endTimestamp)
    }

    @Test
    fun enhancedLrcEstimatesTheLastWordWhenThereIsNoFollowingLine() {
        val lyric = LrcUtils.parseLrcString(
            "[00:01]<00:01>Hi",
            true,
        ).single()

        assertEquals(listOf(Triple(2, 1_000L, 1_700L)), lyric.wordTimestamps)
    }

    @Test
    fun enhancedSidecarTakesPriorityOverPlainLrc() {
        val audio = temporaryFolder.newFile("song.flac")
        temporaryFolder.newFile("song.lrc").writeText("[00:01]Plain")
        temporaryFolder.newFile("song.elrc").writeText("[00:01]<00:01>Enhanced")

        val lyric = LrcUtils.loadAndParseLyricsFile(audio, true)!!.single()

        assertEquals("Enhanced", lyric.content)
        assertTrue(lyric.wordTimestamps.isNotEmpty())
    }

}
