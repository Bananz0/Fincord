package org.akanework.gramophone.logic.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KaraokeMediaItemsLogicTest {

    @Test
    fun `stored original wins while karaoke stream is active`() {
        assertEquals(
            "https://jellyfin.example/original.flac",
            KaraokeMediaItems.originalUriString(
                stored = "https://jellyfin.example/original.flac",
                current = "https://jellyfin.example/instrumental.flac",
            ),
        )
    }

    @Test
    fun `current stream is original before karaoke is active`() {
        assertEquals(
            "https://jellyfin.example/original.flac",
            KaraokeMediaItems.originalUriString(null, "https://jellyfin.example/original.flac"),
        )
        assertNull(KaraokeMediaItems.originalUriString("", ""))
    }
}
