package org.akanework.gramophone.logic.data.acquisition

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDiscography
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.logic.data.matching.MusicBrainzResolver
import org.akanework.gramophone.logic.data.matching.ReleaseQuery

/**
 * Lidarr as an [AcquisitionProvider].
 *
 * All the Lidarr-specific judgement lives here: which phrases are worth asking it, and how to turn
 * its answers into something the shared matcher can rank. Above this class nothing knows what Lidarr
 * is, which is what makes a second provider a new file rather than a rewrite.
 */
class LidarrAcquisitionProvider : AcquisitionProvider {

    override val id = "lidarr"
    override val displayName = "Lidarr"

    override fun readiness(context: Context): AcquisitionProvider.Readiness {
        val store = LidarrCredentialStore(context)
        return when {
            store.serverUrl.isNullOrBlank() || store.apiKey.isNullOrBlank() ->
                AcquisitionProvider.Readiness.UNCONFIGURED

            !store.isConfigured() -> AcquisitionProvider.Readiness.INCOMPLETE
            else -> AcquisitionProvider.Readiness.READY
        }
    }

    override suspend fun search(
        context: Context,
        query: ReleaseQuery,
    ): List<AcquirableRelease> {
        val client = LidarrClient(LidarrCredentialStore(context))

        // An id is not a search. When one is known, ask for exactly it and stop.
        query.musicBrainzReleaseGroupId?.let { mbid ->
            client.lookupByMusicBrainzId(mbid)?.let { return withAvailability(client, listOf(it)) }
        }

        val found = LinkedHashMap<String, LidarrClient.AlbumResult>()
        for (term in searchTerms(query)) {
            client.lookupAlbums(term).forEach { found.putIfAbsent(it.foreignAlbumId, it) }
            // One good phrase is usually enough; the extra passes exist for when it is not.
            if (found.size >= ENOUGH_CANDIDATES) break
        }
        return withAvailability(client, found.values.toList())
    }

    /**
     * Fills in what each result's state actually is.
     *
     * The download queue is fetched once for the whole page, and only when something in it is
     * tracked at all - for a search full of records the user does not own, there is nothing a queue
     * could say.
     */
    private suspend fun withAvailability(
        client: LidarrClient,
        albums: List<LidarrClient.AlbumResult>,
    ): List<AcquirableRelease> {
        val anyTracked = albums.any { it.lidarrId > 0 }
        val downloading = if (anyTracked) client.downloadingAlbumIds() else emptySet()
        return albums.map { it.toRelease(downloading) }
    }

    override suspend fun searchArtists(context: Context, term: String): List<AcquirableArtist> =
        LidarrClient(LidarrCredentialStore(context)).lookupArtists(term).map {
            AcquirableArtist(
                providerId = id,
                id = it.foreignArtistId,
                name = it.artistName,
                disambiguation = it.disambiguation,
                artworkUrl = it.imageUrl,
                alreadyPresent = it.lidarrId > 0,
                internalId = it.lidarrId,
            )
        }

    /**
     * An artist's discography, from whichever source can actually supply one.
     *
     * Lidarr knows the full picture - including what has been downloaded - only for artists it
     * already tracks. For everyone else it has nothing to list, so the discography comes from
     * MusicBrainz, which it shares a database with; those rows carry no payload, and the album
     * resource is fetched by id at the moment one is actually requested.
     */
    override suspend fun releasesOf(
        context: Context,
        artist: AcquirableArtist,
        onPartial: suspend (List<AcquirableRelease>) -> Unit,
    ): List<AcquirableRelease> = coroutineScope {
        // Last time's answer, immediately, so the page opens with something on it. Everything below
        // still runs and still replaces this; see [DiscographyCache] for why that is the trade.
        val cached = DiscographyCache.read(context, id, artist.id)
        if (!cached.isNullOrEmpty()) onPartial(cached)
        val client = LidarrClient(LidarrCredentialStore(context))
        // Both at once: neither depends on the other and each is a round trip.
        val discography = async { discographyOf(context, artist.id) }
        val tracked = async {
            if (artist.internalId > 0) {
                coroutineScope {
                    // The queue describes the same already-tracked set but does not depend on the
                    // album response, so paying these two local-server round trips serially only
                    // delays the first useful snapshot.
                    val albums = async { client.albumsOfTrackedArtist(artist.internalId) }
                    val downloading = async { client.downloadingAlbumIds() }
                    albums.await().map { it.toRelease(downloading.await()) }
                }
            } else {
                emptyList()
            }
        }

        val trackedReleases = tracked.await()
        // Lidarr's own database is normally much faster than the complete MusicBrainz
        // discography. Put those albums on screen immediately instead of keeping a useful answer
        // hidden behind the slowest service involved.
        if (trackedReleases.isNotEmpty()) onPartial(trackedReleases)
        val known = trackedReleases.associateBy { it.id.lowercase() }
        val complete = discography.await().map { group ->
            // Lidarr's copy wins where it has one: only it knows what has been downloaded. The
            // metadata service supplies everything else, which for a tracked artist is usually
            // most of the discography - Lidarr's metadata profile filters singles and EPs out.
            known[group.id.lowercase()] ?: AcquirableRelease(
                providerId = id,
                id = group.id,
                title = group.title,
                artist = group.artist.ifBlank { artist.name },
                year = group.year,
                artworkUrl = coverArtUrl(group.id),
                releaseType = group.primaryType,
            )
        }
        // Anything Lidarr tracks that the discography did not mention still belongs on the page.
        val extra = known.values.filterNot { row ->
            complete.any { it.id.equals(row.id, ignoreCase = true) }
        }
        val assembled = complete + extra
        // Nothing came back at all: both services are unreachable, or the artist genuinely has no
        // discography. Either way what is already on screen beats replacing it with an empty page.
        if (assembled.isEmpty()) return@coroutineScope cached.orEmpty()
        DiscographyCache.write(context, id, artist.id, assembled)
        assembled
    }

    /**
     * An artist's release groups, from the server's cached copy where there is one.
     *
     * The Jellyfin plugin holds one copy for every client on the server, which is both faster than
     * MusicBrainz and the polite way to use it. A server without the plugin, or one that cannot
     * answer, falls straight through to asking MusicBrainz directly - the two return the same
     * thing, parsed by the same code.
     */
    private suspend fun discographyOf(
        context: Context,
        artistId: String,
    ): List<MusicBrainzResolver.ReleaseGroup> {
        JellyfinDiscography.releaseGroupsOfArtist(context, artistId)?.let { fromServer ->
            // Hand it to the resolver's own cache as well, so a second page for this artist in
            // this session does not go back out to the server either.
            MusicBrainzResolver.remember(artistId, fromServer)
            return fromServer
        }
        return MusicBrainzResolver.releaseGroupsOfArtist(artistId)
    }

    override suspend fun request(context: Context, release: AcquirableRelease): Boolean {
        if (release.providerId != id) return false
        val client = LidarrClient(LidarrCredentialStore(context))
        // Lidarr already holds a record for this one, so adding it again would be rejected as a
        // duplicate. Start monitoring it and ask for a search instead.
        if (release.internalId > 0) return client.monitorExistingAlbum(release.internalId)
        // A row that came from the metadata service carries no Lidarr resource, and POST /album
        // needs the whole thing - its images especially. Fetch it by id first.
        if (release.payload.isBlank()) {
            val resolved = client.lookupByMusicBrainzId(release.id) ?: return false
            return client.addAlbum(resolved)
        }
        return client.addAlbum(
            LidarrClient.AlbumResult(
                foreignAlbumId = release.id,
                title = release.title,
                artistName = release.artist,
                foreignArtistId = "",
                year = release.year,
                coverUrl = release.artworkUrl,
                alreadyAdded = release.alreadyPresent,
                lidarrId = 0,
                lidarrJson = release.payload,
                totalTracks = release.totalTracks,
            )
        )
    }

    /**
     * The phrases worth asking Lidarr, best first.
     *
     * Artist and album together is the precise question and usually the only one needed. The album
     * alone is the fallback that matters, because the two catalogues frequently disagree about how
     * an artist is credited - a featured name, a stylisation, a "Various Artists" compilation - and
     * a combined phrase that includes a name Lidarr files differently finds nothing at all.
     */
    internal fun searchTerms(query: ReleaseQuery): List<String> {
        query.freeText?.takeIf { it.isNotBlank() }?.let { return listOf(it.trim()) }
        val artist = query.artist.trim()
        val album = query.album?.trim().orEmpty()
        val track = query.track?.trim().orEmpty()
        if (album.isNotBlank()) {
            return listOfNotNull(
                listOf(artist, album).filter(String::isNotBlank).joinToString(" "),
                album,
            ).distinct()
        }
        // With no album name, the track title is the only description of the record there is; a
        // single is filed under its own name and this finds it. The artist alone comes last, and
        // only when there is nothing else - it is a discography, not an answer.
        return listOfNotNull(
            listOf(artist, track).filter(String::isNotBlank).joinToString(" ")
                .takeIf { it.isNotBlank() },
            artist.takeIf { it.isNotBlank() },
        ).distinct()
    }

    private fun LidarrClient.AlbumResult.toRelease(
        downloading: Set<Int> = emptySet(),
    ) = AcquirableRelease(
        providerId = this@LidarrAcquisitionProvider.id,
        id = foreignAlbumId,
        title = title,
        artist = artistName,
        year = year,
        totalTracks = totalTracks,
        artworkUrl = coverUrl,
        releaseType = albumType,
        availability = when {
            // Files on disk beat everything: whatever the queue says, this one already arrived.
            (trackFileCount ?: 0) > 0 -> ReleaseAvailability.DOWNLOADED
            lidarrId > 0 && lidarrId in downloading -> ReleaseAvailability.DOWNLOADING
            // Known but unmonitored is not requested - it is the state every album lands in when
            // its artist gets added, and calling it "requested" made it impossible to ask for.
            lidarrId > 0 && monitored -> ReleaseAvailability.REQUESTED
            else -> ReleaseAvailability.NOT_REQUESTED
        },
        internalId = lidarrId,
        payload = lidarrJson,
    )

    /**
     * Cover art for a release the downloader does not track.
     *
     * Lidarr only carries images for records it holds, so a discography assembled from metadata
     * would otherwise be a column of blank squares. The Cover Art Archive is keyed by the same
     * release group id, needs no account, and is the source Lidarr's own artwork ultimately comes
     * from. It answers 404 for records nobody has uploaded art for and is occasionally flaky, both
     * of which the image loader treats as "no picture" - which is what it was anyway.
     */
    private fun coverArtUrl(releaseGroupId: String) =
        "https://coverartarchive.org/release-group/$releaseGroupId/front-500"

    private companion object {
        private const val ENOUGH_CANDIDATES = 25
    }
}
