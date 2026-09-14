package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.preference.PreferenceManager
import java.io.File

/**
 * The on-disk media cache shared by streaming and offline downloads.
 *
 * media3 throws if two [SimpleCache] instances are constructed over the same directory, so both
 * the playback service and (later) the download manager must go through this single instance.
 */
@OptIn(UnstableApi::class)
object JellyfinMediaCache {

    private const val TAG = "JellyfinMediaCache"

    private const val CACHE_DIR_NAME = "jellyfin_media"

    /**
     * Where the cache used to live. Android clears cacheDir under storage pressure without warning,
     * which would silently delete tracks the user downloaded for offline use, so the cache moved to
     * filesDir. The old directory is removed once so it does not sit there occupying space.
     */
    private const val LEGACY_CACHE_DIR_NAME = "jellyfin_media"

    /**
     * Downloads and the streaming cache live in one directory. media3's own evictor cannot tell the
     * two apart and would happily delete a track the user explicitly downloaded for offline use, so
     * it is left disabled and [trimToLimit] does the eviction instead - same least-recently-used
     * rule, but skipping anything the download index claims.
     */
    private const val PREF_KEY_CACHE_LIMIT = "cache_size_limit"

    /**
     * 5 GB, matching the fifth entry of `@array/cache_limit_val` and the preference's own default.
     *
     * Deliberately not 0. Zero is the array's "no ceiling" option, and defaulting to it meant
     * [trimToLimit] returned immediately on every fresh install - the eviction was written and
     * correct but nothing ever asked for it, so a streamed library grew on disk without bound
     * until somebody found this setting. Downloads are exempt from the sweep, so a ceiling costs
     * nothing that was explicitly kept offline.
     */
    private const val DEFAULT_CACHE_LIMIT_BYTES = 5_368_709_120L

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var databaseProvider: StandaloneDatabaseProvider? = null

    /**
     * The index database behind the cache.
     *
     * The download manager has to be handed this same instance: media3 keeps its cache index and its
     * download index in one database, and opening a second provider over the same file gives the two
     * halves inconsistent views of what is on disk.
     */
    fun databaseProvider(context: Context): StandaloneDatabaseProvider {
        databaseProvider?.let { return it }
        return synchronized(this) {
            databaseProvider ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProvider = it }
        }
    }

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: run {
                val appContext = context.applicationContext
                deleteLegacyCache(appContext)
                val dir = File(appContext.filesDir, CACHE_DIR_NAME)
                SimpleCache(dir, NoOpCacheEvictor(), databaseProvider(appContext))
                    .also { cache = it }
            }
        }
    }

    private fun deleteLegacyCache(context: Context) {
        val legacy = File(context.cacheDir, LEGACY_CACHE_DIR_NAME)
        if (legacy.isDirectory) {
            legacy.deleteRecursively()
        }
    }

    /**
     * The factory ExoPlayer should read through: cache first, Jellyfin over the shared OkHttp
     * connection pool otherwise.
     */
    /**
     * @param quality what to ask the server for. Evaluated per request rather than captured, so
     *   changing the setting or walking off wifi takes effect on the next track instead of
     *   requiring the library to be rebuilt.
     *
     *   Null rather than a default lambda, and this is not a style choice. A Kotlin default
     *   argument is evaluated inside the callee, so `= { streamingQuality(context) }` captured the
     *   *parameter* - which at both playback call sites is `GramophonePlaybackService` itself. The
     *   lambda is handed to [StreamQualityResolver.factory], which stores it in a
     *   `ResolvingDataSource.Factory`, which `ProgressiveMediaSource` keeps, which is reachable
     *   from the `Timeline` that media3's `MediaSessionStub` holds in `lastOriginalTimeline`. A
     *   binder stub outlives the service by design, so the service survived every
     *   `Service#onDestroy()` - which is the leak LeakCanary reported five times.
     */
    fun dataSourceFactory(
        context: Context,
        quality: (() -> StreamQuality)? = null,
    ): DataSource.Factory {
        val appContext = context.applicationContext
        // Resolved here, against the Application, so nothing longer-lived than the process ends up
        // inside a data source factory.
        val streamQuality = quality ?: { StreamQuality.streamingQuality(appContext) }
        val cacheFactory = CacheDataSource.Factory()
            .setCache(get(appContext))
            .setUpstreamDataSourceFactory(
                OkHttpDataSource.Factory(JellyfinClientHolder.mediaHttpClient())
            )
            // Without this a transient network error while part of the track is already cached
            // aborts playback instead of serving what we have.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // The resolver wraps the cache rather than sitting under it. CacheDataSource works out
        // which entry a request belongs to before it ever consults its upstream, so a resolver
        // placed underneath can rewrite the network URL but never the cache key - every quality
        // would land in the entry keyed by the original URL and the first one fetched would be
        // served to all of them. Resolving first means the cache sees the variant.
        return StreamQualityResolver.factory(
            context = appContext,
            upstream = cacheFactory,
            quality = streamQuality,
            cachedVariant = { itemId -> cachedVariantFor(appContext, itemId) },
        )
    }

    /**
     * The variant of [itemId] this device already holds, or null if it holds none.
     *
     * Local always beats the network. The downloader and the player write and read the same
     * [SimpleCache], so a track being downloaded right now is readable as it lands - media3 serves
     * the written spans and only fetches the holes, from the same variant. Before this, playback
     * keyed strictly on the *streaming* quality while the download keyed on the *download* quality;
     * whenever those two settings differed the two were separate cache entries and a track could be
     * fetched a second time over the network while a perfectly good copy of it was on disk.
     *
     * The download quality is tried first because that is the variant a deliberate download chose.
     * Cached spans rather than a completed download, so a partial entry counts: half a track on
     * disk is still half a track that does not need fetching.
     *
     * One consequence worth knowing: an item already cached at a higher quality will be finished at
     * that quality even on a metered connection. That is the instruction - prefer what is local -
     * and it only ever applies to a track this device already started keeping.
     */
    fun cachedVariantFor(context: Context, itemId: String): StreamQuality? {
        val appContext = context.applicationContext
        val cache = get(appContext)
        val preferred = StreamQuality.downloadQuality(appContext)
        val ordered = listOf(preferred) + StreamQuality.entries.filter { it != preferred }
        return ordered.firstOrNull {
            cache.getCachedSpans(StreamQualityResolver.cacheKey(itemId, it)).isNotEmpty()
        }
    }

    fun currentSizeBytes(context: Context): Long = get(context).cacheSpace

    /** The ceiling the user chose, in bytes. Zero means the cache may grow without limit. */
    fun cacheLimitBytes(context: Context): Long =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .getString(PREF_KEY_CACHE_LIMIT, null)
            ?.toLongOrNull()
            ?: DEFAULT_CACHE_LIMIT_BYTES

    /**
     * Evicts streamed audio, oldest touch first, until the cache fits under the chosen ceiling.
     *
     * Downloads are never evicted. A download is a fully populated cache entry sharing this
     * directory, so an ordinary size-based sweep would quietly delete music the user asked to keep
     * offline - the one thing a cache limit must not do. Anything the download index knows about is
     * therefore skipped, which means a library downloaded past the ceiling simply stays over it.
     *
     * Reads the cache index and deletes files, so it must not run on the main thread.
     *
     * @return how many bytes were reclaimed.
     */
    fun trimToLimit(context: Context): Long {
        val appContext = context.applicationContext
        val limit = cacheLimitBytes(appContext)
        if (limit <= 0L) return 0L

        val cache = get(appContext)
        if (cache.cacheSpace <= limit) return 0L

        val protectedKeys = downloadedCacheKeys(appContext)
        val evictable = cache.keys
            .filterNot { it in protectedKeys }
            .flatMap { key -> runCatching { cache.getCachedSpans(key) }.getOrDefault(emptySet()) }
            .filter { it.isCached }
            .sortedBy { it.lastTouchTimestamp }

        var freed = 0L
        for (span in evictable) {
            if (cache.cacheSpace <= limit) break
            val length = span.length
            runCatching { cache.removeSpan(span) }
                .onSuccess { freed += length }
                .onFailure { Log.w(TAG, "Could not evict a cache span", it) }
        }
        if (freed > 0L) Log.d(TAG, "Trimmed $freed bytes to stay under $limit")
        return freed
    }

    /**
     * Drops cached audio for tracks whose file changed on the server.
     *
     * Tags and embedded lyrics are read off the decoded stream when a track plays, so a track
     * already in the cache keeps playing the bytes fetched before the edit - the library shows the
     * new metadata while the player shows the old words. Removing the entry makes the next play
     * fetch the current file.
     *
     * Downloads are skipped, for the same reason [trimToLimit] skips them: deleting one silently
     * takes away music somebody chose to keep offline. A downloaded track therefore keeps its old
     * tags until it is removed and downloaded again, which is a trade worth stating rather than a
     * decision to make on the user's behalf.
     *
     * Reads the cache index and deletes files, so it must not run on the main thread.
     *
     * @return how many entries were dropped.
     */
    fun evictStale(context: Context, keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        val appContext = context.applicationContext
        val cache = get(appContext)
        val protectedKeys = downloadedCacheKeys(appContext)

        var dropped = 0
        // Every quality variant of every changed track. A track heard at 256 on the train and
        // again at full quality at home occupies two entries, and clearing only the one matching
        // today's setting would leave the other serving the tags the file had before it was edited.
        keys.flatMap { StreamQualityResolver.allCacheKeys(it) }.distinct().forEach { key ->
            if (key in protectedKeys) return@forEach
            runCatching { cache.removeResource(key) }
                .onSuccess { dropped++ }
                .onFailure { Log.w(TAG, "Could not evict stale entry", it) }
        }
        if (dropped > 0) Log.d(TAG, "Evicted $dropped stale cache entries after a server edit")
        return dropped
    }

    /**
     * Drops cached audio held at any quality other than [keep].
     *
     * Changing the setting orphans the old variants rather than replacing them: each quality is a
     * separate cache entry, so nothing will ever read the previous one again and it sits there
     * occupying the space the setting was probably changed to reclaim. This cache is disposable by
     * definition, so it goes without asking - anything dropped is refetched on the next play.
     *
     * Downloads are spared. They are the copy somebody chose to keep, and swapping their quality
     * is a decision with a data cost attached, so it is asked about separately rather than done
     * here as a side effect.
     *
     * Reads the cache index and deletes files, so it must not run on the main thread.
     *
     * @return how many entries were dropped.
     */
    fun dropOtherVariants(context: Context, keep: StreamQuality): Int {
        val appContext = context.applicationContext
        val cache = get(appContext)
        val protectedKeys = downloadedCacheKeys(appContext)
        val keepSuffix = keep.cacheSuffix

        var dropped = 0
        cache.keys.toList().forEach { key ->
            if (key in protectedKeys) return@forEach
            // Anything keyed by a bare URL predates these settings and can no longer be reached
            // either, since lookups now go through the item id.
            val stale = key.startsWith("http") || key.variantSuffix() != keepSuffix
            if (!stale) return@forEach
            runCatching { cache.removeResource(key) }
                .onSuccess { dropped++ }
                .onFailure { Log.w(TAG, "Could not drop a stale variant", it) }
        }
        if (dropped > 0) Log.d(TAG, "Dropped $dropped cache entries not at ${keep.preferenceValue}")
        return dropped
    }

    /** `|256` for a capped entry, empty for an untouched one. */
    private fun String.variantSuffix(): String =
        substringAfter('|', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "|$it" }

    /**
     * Cache keys belonging to downloads, in every state.
     *
     * Keyed by request URI rather than media id: playback, prefetch and downloads all leave the
     * DataSpec key unset, so media3 derives the key from the URI, and matching on anything else
     * would fail to protect the very entries this is meant to spare.
     */
    private fun downloadedCacheKeys(context: Context): Set<String> = try {
        buildSet {
            JellyfinDownloadManager.get(context).downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val request = cursor.download.request
                    // The stated key, falling back to the URI for downloads queued before quality
                    // settings existed and which therefore never carried one.
                    add(request.customCacheKey ?: request.uri.toString())
                }
            }
        }
    } catch (e: Exception) {
        // Failing open would evict downloads. Protect everything rather than guess.
        Log.w(TAG, "Could not read the download index; skipping this trim", e)
        cache?.keys.orEmpty()
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
