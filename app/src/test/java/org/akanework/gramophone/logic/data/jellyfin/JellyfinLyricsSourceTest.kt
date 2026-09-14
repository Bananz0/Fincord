package org.akanework.gramophone.logic.data.jellyfin

import org.jellyfin.sdk.model.api.LyricLine
import org.jellyfin.sdk.model.api.LyricLineCue
import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinLyricsSourceTest {

    @Test
    fun serverCueObjectsAreRestoredAsEnhancedLrcMarkers() {
        val line = LyricLine(
            text = "Hello world",
            start = 100_000_000L,
            cues = listOf(
                LyricLineCue(
                    position = 0,
                    endPosition = 6,
                    start = 100_000_000L,
                    end = 110_000_000L,
                ),
                LyricLineCue(
                    position = 6,
                    endPosition = 11,
                    start = 110_000_000L,
                    end = 120_000_000L,
                ),
            ),
        )

        assertEquals(
            "<00:10.00>Hello <00:11.00>world<00:12.00>",
            JellyfinLyricsSource.renderCues(line.text, line),
        )
    }
}
