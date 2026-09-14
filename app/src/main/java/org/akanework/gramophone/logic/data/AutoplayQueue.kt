package org.akanework.gramophone.logic.data

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import kotlin.random.Random

/**
 * Keeps playback going past the end of the queue, with music like what is already playing.
 *
 * The player's infinity button has always been drawn and never wired to anything. What it should
 * mean is that a queue never simply stops: when it is nearly done, more of the same kind of thing is
 * added.
 *
 * "The same kind of thing" is answered by Last.fm's similar-artists, which needs only the API key
 * the app already asks for - no linked account - and falls back to the current track's genre when
 * Last.fm has nothing to say or is not configured. Everything it queues comes from the user's own
 * library; this suggests, it does not fetch.
 */
object AutoplayQueue {

    /** How many unplayed tracks may remain before more are added. */
    const val TOP_UP_THRESHOLD = 3

    /** How many to add at a time. Enough to be worth a network call, short enough to stay relevant. */
    private const val BATCH = 20

    /**
     * Picks the next batch to append.
     *
     * @param seed the track the suggestions should resemble - normally the one playing.
     * @param library everything available to play.
     * @param exclude media ids already in the queue, so nothing is queued twice.
     */
    @WorkerThread
    fun nextBatch(
        context: Context,
        seed: MediaItem?,
        library: List<MediaItem>,
        exclude: Set<String>,
    ): List<MediaItem> {
        if (library.isEmpty()) return emptyList()
        val candidates = library.filter { it.mediaId !in exclude }
        if (candidates.isEmpty()) return emptyList()

        val seedArtist = seed?.mediaMetadata?.artist?.toString()
        val byArtist = similarArtists(context, seedArtist)

        // Ranked rather than filtered: an artist Last.fm named is best, the same artist is next, the
        // same genre after that. Filtering would leave nothing to play whenever the library and the
        // suggestions do not overlap, which is the normal case for a small library.
        val seedGenre = seed?.mediaMetadata?.genre?.toString()?.lowercase()
        val ranked = candidates.groupBy { item ->
            val artist = item.mediaMetadata.artist?.toString()
            when {
                artist != null && byArtist.contains(artist.lowercase()) -> 0
                artist != null && artist == seedArtist -> 1
                seedGenre != null &&
                        item.mediaMetadata.genre?.toString()?.lowercase() == seedGenre -> 2

                else -> 3
            }
        }

        val random = Random(seed?.mediaId?.hashCode()?.toLong() ?: 0L)
        val picked = mutableListOf<MediaItem>()
        for (tier in 0..3) {
            if (picked.size >= BATCH) break
            val bucket = ranked[tier] ?: continue
            picked += bucket.shuffled(random).take(BATCH - picked.size)
        }
        Log.d(TAG, "Queued ${picked.size} similar to ${seed?.mediaMetadata?.title}")
        return picked
    }

    /** Lowercased names of artists Last.fm considers similar, or empty when it cannot be asked. */
    @WorkerThread
    private fun similarArtists(context: Context, artist: String?): Set<String> {
        if (artist.isNullOrBlank()) return emptySet()
        cache[artist]?.let { return it }
        val store = LastFmCredentialStore(context)
        if (!store.hasApplicationCredentials()) return emptySet()
        return runCatching {
            kotlinx.coroutines.runBlocking {
                LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl)
                    .getSimilarArtists(artist)
                    .map { it.lowercase() }
                    .toSet()
            }
        }.getOrElse {
            Log.d(TAG, "Last.fm had nothing for $artist: $it")
            emptySet()
        }.also { cache[artist] = it }
    }

    /**
     * Held for the process's lifetime. The same artist is asked about repeatedly as a queue tops up
     * over and over, and the answer does not change between one track and the next.
     */
    private val cache = mutableMapOf<String, Set<String>>()

    private const val TAG = "AutoplayQueue"
}
