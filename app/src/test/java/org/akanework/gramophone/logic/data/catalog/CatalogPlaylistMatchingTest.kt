package org.akanework.gramophone.logic.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogPlaylistMatchingTest {

    @Test
    fun meaningfulVersionSuffixIsNotCollapsed() {
        val source = track(title = "Flowers (Live)", album = "Live at the Wiltern")
        val candidates = listOf(
            library("studio", "Flowers", album = "Endless Summer Vacation"),
            library("live", "Flowers (Live)", album = "Live at the Wiltern"),
        )

        assertEquals("live", CatalogPlaylistImporter.selectMatch(source, candidates))
    }

    @Test
    fun remasterLabelMayDifferWithoutChangingTheRecording() {
        val source = track(title = "The Invisible Man - Remastered 2011", album = "The Miracle")
        val candidate = library("match", "The Invisible Man", album = "The Miracle")

        assertEquals(
            "match",
            CatalogPlaylistImporter.selectMatch(source, listOf(candidate)),
        )
    }

    @Test
    fun deluxeReleaseWinsOverStandardForTheSameRecording() {
        val source = track(
            title = "Agora Hills",
            album = "Scarlet",
            durationMs = 265_400L,
            releaseYear = 2023,
            trackNumber = 10,
        )
        val candidates = listOf(
            library(
                id = "standard",
                title = "Agora Hills",
                album = "Scarlet",
                durationMs = 265_360L,
                releaseYear = 2023,
                trackNumber = 10,
                albumTrackCount = 15,
            ),
            library(
                id = "deluxe",
                title = "Agora Hills",
                album = "Scarlet (Deluxe Edition)",
                durationMs = 265_404L,
                releaseYear = 2024,
                trackNumber = 10,
                albumTrackCount = 24,
            ),
        )

        assertEquals("deluxe", CatalogPlaylistImporter.selectMatch(source, candidates))
    }

    @Test
    fun sameTitleAndArtistTieIsRejectedInsteadOfPickingFirst() {
        val source = track(title = "Intro", album = null, durationMs = null)
        val candidates = listOf(
            library("one", "Intro", album = "One", durationMs = null),
            library("two", "Intro", album = "Two", durationMs = null),
        )

        assertNull(CatalogPlaylistImporter.selectMatch(source, candidates))
    }

    @Test
    fun compilationCreditRequiresAlbumAndDurationEvidence() {
        val source = track(
            title = "Rain on Me",
            album = "Chromatica",
            durationMs = 182_200L,
        )
        val compilation = library(
            id = "compilation",
            title = "Rain on Me",
            artist = "Various Artists",
            album = "Chromatica",
            durationMs = 182_100L,
        )

        assertEquals(
            "compilation",
            CatalogPlaylistImporter.selectMatch(source, listOf(compilation)),
        )
    }

    private fun track(
        title: String,
        album: String?,
        durationMs: Long? = 200_000L,
        releaseYear: Int? = 2024,
        trackNumber: Int? = 1,
    ) = ExternalTrack(
        title = title,
        artist = "Doja Cat",
        album = album,
        durationMs = durationMs,
        albumReleaseYear = releaseYear,
        discNumber = 1,
        trackNumber = trackNumber,
    )

    private fun library(
        id: String,
        title: String,
        artist: String = "Doja Cat",
        album: String?,
        durationMs: Long? = 200_000L,
        releaseYear: Int? = 2024,
        trackNumber: Int? = 1,
        albumTrackCount: Int = 12,
    ) = CatalogPlaylistImporter.LibraryTrack(
        mediaId = id,
        title = title,
        artist = artist,
        album = album,
        releaseYear = releaseYear,
        discNumber = 1,
        trackNumber = trackNumber,
        durationMs = durationMs,
        albumIdentity = id,
        albumTrackCount = albumTrackCount,
    )
}
