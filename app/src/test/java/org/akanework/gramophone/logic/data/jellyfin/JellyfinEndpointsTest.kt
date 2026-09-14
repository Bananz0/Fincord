package org.akanework.gramophone.logic.data.jellyfin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinEndpointsTest {

    @Test
    fun `private IP and wifi hostname migrate as local`() {
        assertTrue(JellyfinEndpoints.isLocalAddress("http://192.168.1.192:8096"))
        assertTrue(JellyfinEndpoints.isLocalAddress("jellyfin:8096"))
        assertTrue(JellyfinEndpoints.isLocalAddress("http://musicbox.local:8096"))
    }

    @Test
    fun `public reverse proxy migrates as remote`() {
        assertFalse(JellyfinEndpoints.isLocalAddress("https://music.example.com/jellyfin"))
    }
}
