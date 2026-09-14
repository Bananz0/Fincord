package org.akanework.gramophone.logic.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinMediaSharerTest {

    @Test
    fun `supported original containers keep their real file type`() {
        val expected = mapOf(
            "aac" to SharedAudioFormat("aac", "audio/aac"),
            "ogg" to SharedAudioFormat("ogg", "audio/ogg"),
            "vorbis" to SharedAudioFormat("ogg", "audio/ogg"),
            "flac" to SharedAudioFormat("flac", "audio/flac"),
            "mp3" to SharedAudioFormat("mp3", "audio/mpeg"),
        )

        expected.forEach { (container, format) ->
            assertEquals(format, sharedAudioFormat(container, "ignored.bin"))
        }
    }

    @Test
    fun `path extension is used when Jellyfin omitted container metadata`() {
        assertEquals(
            SharedAudioFormat("flac", "audio/flac"),
            sharedAudioFormat(null, "track.FLAC"),
        )
    }
}
