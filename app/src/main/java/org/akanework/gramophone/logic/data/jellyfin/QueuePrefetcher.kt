package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Warms up the next few tracks so skipping does not wait on the network.
 *
 * Every track streams from Jellyfin, and nothing beyond the one playing was ever fetched ahead. On
 * a shuffled library that means each skip is a fresh connection before a note is heard and before
 * the artwork exists - which is the pause that reads as the app hanging.
 *
 * Only the beginning of each track is fetched. That is what a skip needs: enough for playback to
 * start immediately while the rest streams normally behind it. Fetching whole tracks ahead would be
 * a download, not a prefetch, and the user did not ask for one.
 */
@OptIn(UnstableApi::class)
object QueuePrefetcher {

    /** How many tracks ahead to warm. Beyond this, a listener has usually skipped somewhere else. */
    private const val LOOKAHEAD = 5

    /**
     * How many to warm on a metered connection.
     *
     * Prefetching is spending someone's data on music they have not asked for yet, so on mobile it
     * covers only the very next track - the one a skip is most likely to want - rather than five.
     * Artwork is still warmed for all of them: it is a few kilobytes against a track's megabytes,
     * and a missing cover is the part of a skip people actually see.
     */
    private const val METERED_LOOKAHEAD = 1

    /** Roughly the first few seconds of a lossless track - enough to start instantly. */
    private const val PREFETCH_BYTES = 1_500_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Media ids already warmed, so a queue that shuffles back does not refetch. */
    private val warmed = mutableSetOf<String>()

    /**
     * Media ids whose artwork has been asked for, tracked separately from [warmed].
     *
     * The audio budget is smaller than the artwork one, so a single set cannot serve both: an item
     * skipped by the audio budget would otherwise be recorded as done and never have its cover
     * requested at all. Kept because the lookahead window slides by one per track, so consecutive
     * transitions ask for almost the same covers - without this, skipping ten tracks enqueued the
     * same handful of images dozens of times, and because none of them are cancelled any more they
     * all reached the network before any could populate the cache.
     */
    private val artworkWarmed = mutableSetOf<String>()

    /**
     * Warms [upcoming], which should be the tracks after the one playing, nearest first.
     *
     * Cancels any prefetch still running: the queue has moved on, and finishing the old one would
     * compete with the track that is actually playing for the same connection pool.
     */
    /**
     * The pixel size the player asks its cover for, published so a prefetch can share its cache
     * key. Zero until the player has been laid out, in which case the prefetch stays unsized.
     */
    @Volatile
    var artworkTargetPx: Int = 0

    /**
     * Warms the covers of the first few tracks of a collection the user is looking at.
     *
     * Pressing play on a station or playlist showed the placeholder first: the cover took ~230ms to
     * arrive against a 90ms slide-in deadline, so every start flashed an empty sleeve before the
     * artwork caught up. [prefetch] cannot help there - it only ever sees a queue that is already
     * playing - but a detail screen is looked at for a second or more before anything is tapped,
     * which is ample time to have the first covers ready.
     *
     * Deliberately only the first few. The whole point of a station is that it is long, and asking
     * for fifty covers at once is the thundering herd that starves the player's own request.
     */
    fun warmArtwork(
        context: Context,
        items: List<MediaItem>,
        imageLoader: ImageLoader,
        limit: Int = ARTWORK_WARM_AHEAD,
    ) {
        val appContext = context.applicationContext
        items.take(limit).forEach { item ->
            if (!artworkWarmed.add(item.mediaId)) return@forEach
            item.mediaMetadata.artworkUri?.let { uri ->
                imageLoader.enqueue(
                    ImageRequest.Builder(appContext).data(uri).apply {
                        val target = artworkTargetPx
                        if (target > 0) size(target, target)
                    }.build()
                )
            }
        }
    }

    fun prefetch(context: Context, upcoming: List<MediaItem>, imageLoader: ImageLoader) {
        job?.cancel()
        if (upcoming.isEmpty()) return
        val appContext = context.applicationContext
        val targets = upcoming.take(LOOKAHEAD)
        val audioBudget = audioLookahead(appContext)

        // Artwork is warmed outside the cancellable job, deliberately.
        //
        // Every transition calls this, and the first thing it does is cancel the previous run - so
        // while somebody skips, each batch of covers was cancelled about 30ms after being asked
        // for and none of them ever completed. The cache therefore stayed cold precisely when it
        // was needed most, and the player's own request had to fetch and decode from scratch; one
        // measured five seconds between the track appearing and its cover arriving.
        //
        // Cancelling is right for audio, which is large and genuinely wasted once the queue moves
        // on. A cover is small, already scoped to the size the player will ask for, and stays
        // useful even if the user skips past it and comes back, so it is left to finish.
        targets.forEach { item ->
            if (!artworkWarmed.add(item.mediaId)) return@forEach
            item.mediaMetadata.artworkUri?.let { uri ->
                imageLoader.enqueue(
                    ImageRequest.Builder(appContext).data(uri).apply {
                        // Coil keys its memory cache on the requested size as well as the URL, so a
                        // prefetch made at the default size and the player's request for its exact
                        // cover are two different entries - the warm-up would only ever fill the
                        // disk cache and every swipe would still pay for a fresh decode.
                        val target = artworkTargetPx
                        if (target > 0) size(target, target)
                    }.build()
                )
            }
        }

        job = scope.launch {
            targets.forEachIndexed { index, item ->
                ensureActive()
                if (index >= audioBudget) return@forEachIndexed
                if (!warmed.add(item.mediaId)) return@forEachIndexed
                ensureActive()
                warmAudio(appContext, item)
            }
            // Prefetching is the main way the cache grows, so it is also the natural place to hold
            // it to the ceiling. Runs after the warm-up so a skip never waits on an eviction sweep.
            ensureActive()
            runCatching { JellyfinMediaCache.trimToLimit(appContext) }
                .onFailure { Log.d(TAG, "Cache trim failed: $it") }
        }
    }

    /**
     * How many tracks may have their *audio* warmed on the connection currently in use.
     *
     * An unmetered network gets the full lookahead. Anything else - mobile data, or a hotspot the
     * user has flagged as metered - gets one, because the rest is speculative traffic they are
     * paying for.
     */
    private fun audioLookahead(context: Context): Int {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return METERED_LOOKAHEAD
        val capabilities = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull() ?: return METERED_LOOKAHEAD
        val unmetered =
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (unmetered) LOOKAHEAD else METERED_LOOKAHEAD
    }

    /** Pulls the first stretch of a track into the same cache playback reads from. */
    private fun warmAudio(context: Context, item: MediaItem) {
        val uri = item.localConfiguration?.uri ?: return
        runCatching {
            val source = JellyfinMediaCache.dataSourceFactory(context).createDataSource()
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(0)
                .setLength(PREFETCH_BYTES)
                // Leave key unset so Media3 derives the URI key used by ordinary playback and
                // downloads. A media-id key creates a second, unreachable cache entry.
                .build()
            CacheWriter(source as? CacheDataSource ?: return, spec, null, null).cache()
        }.onFailure {
            // A prefetch is best-effort by definition; the track still plays if this failed.
            Log.d(TAG, "Could not warm ${item.mediaMetadata.title}: $it")
            warmed.remove(item.mediaId)
            artworkWarmed.remove(item.mediaId)
        }
    }

    /** Drops the record of what has been warmed, for a sign-out or a cleared cache. */
    fun reset() {
        job?.cancel()
        warmed.clear()
        artworkWarmed.clear()
    }

    /** How many covers a collection screen warms before anything is played. */
    private const val ARTWORK_WARM_AHEAD = 5

    private const val TAG = "QueuePrefetcher"
}
