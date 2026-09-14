package org.akanework.gramophone.logic.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link shapes people actually paste. Every one of these but the playlist used to be refused.
 */
class SpotifyLinkTest {

    private val provider = SpotifyCatalogProvider()

    @Test
    fun theShareSheetsAlbumLinkIsAnAlbum() {
        val reference = SpotifyCatalogProvider.SpotifyLinks.parse(
            "https://open.spotify.com/album/2nLOHgzXzwFEpl62zAgCEC?si=9f1c2b3d4e5f6a7b"
        )

        assertEquals(CatalogKind.ALBUM, reference?.kind)
        assertEquals("2nLOHgzXzwFEpl62zAgCEC", reference?.id)
    }

    @Test
    fun aLocalisedPathStillNamesTheSameAlbum() {
        val reference = SpotifyCatalogProvider.SpotifyLinks.parse(
            "https://open.spotify.com/intl-de/album/2nLOHgzXzwFEpl62zAgCEC"
        )

        assertEquals(CatalogKind.ALBUM, reference?.kind)
        assertEquals("2nLOHgzXzwFEpl62zAgCEC", reference?.id)
    }

    @Test
    fun theDesktopClientsUriIsUnderstood() {
        val reference =
            SpotifyCatalogProvider.SpotifyLinks.parse("spotify:track:1301WleyT98MSxVHPZCA6M")

        assertEquals(CatalogKind.TRACK, reference?.kind)
        assertEquals("1301WleyT98MSxVHPZCA6M", reference?.id)
    }

    @Test
    fun playlistsAndArtistsAreRecognisedToo() {
        assertEquals(
            CatalogKind.PLAYLIST,
            SpotifyCatalogProvider.SpotifyLinks
                .parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")?.kind,
        )
        assertEquals(
            CatalogKind.ARTIST,
            SpotifyCatalogProvider.SpotifyLinks
                .parse("https://open.spotify.com/artist/06HL4z0CvFAxyc27GXpf02")?.kind,
        )
    }

    @Test
    fun somethingThatIsNotAReferenceIsRejected() {
        assertNull(SpotifyCatalogProvider.SpotifyLinks.parse("https://open.spotify.com/"))
    }

    @Test
    fun theRegistryRoutesLinksToTheProviderThatOwnsThem() {
        assertTrue(provider.recognises("https://open.spotify.com/album/2nLOHgzXzwFEpl62zAgCEC"))
        assertEquals(
            "spotify",
            MusicCatalogProviders
                .providerFor("https://open.spotify.com/album/2nLOHgzXzwFEpl62zAgCEC")?.id,
        )
        assertEquals(
            "deezer",
            MusicCatalogProviders.providerFor("https://www.deezer.com/en/album/12345")?.id,
        )
        assertEquals(
            "apple-music",
            MusicCatalogProviders.providerFor("https://music.apple.com/us/album/x/12345")?.id,
        )
    }

    @Test
    fun aTypedSearchTermIsNotMistakenForALink() {
        assertFalse(MusicCatalogProviders.looksLikeLink("U, Me & My Ego"))
        assertTrue(MusicCatalogProviders.looksLikeLink("spotify:album:2nLOHgzXzwFEpl62zAgCEC"))
        assertTrue(
            MusicCatalogProviders.looksLikeLink("https://open.spotify.com/album/2nLOHgzXzwFEpl6")
        )
    }
}
