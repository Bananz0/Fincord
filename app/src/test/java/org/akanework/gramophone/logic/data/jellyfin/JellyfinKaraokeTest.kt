package org.akanework.gramophone.logic.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Test

class JellyfinKaraokeTest {

    @Test
    fun `public server uses same-origin reverse proxy path`() {
        val endpoints = JellyfinKaraoke.endpointCandidates(
            "https://jellyfin.glenmuthoka.com/",
        )

        assertEquals(listOf("https://jellyfin.glenmuthoka.com/accord-karaoke/"), endpoints.map { it.toString() })
    }

    @Test
    fun `direct LAN server prefers adjacent service port`() {
        val endpoints = JellyfinKaraoke.endpointCandidates("http://192.168.1.192:8096/")

        assertEquals("http://192.168.1.192:8097/", endpoints.first().toString())
        assertEquals("http://192.168.1.192:8096/accord-karaoke/", endpoints.last().toString())
    }

}
