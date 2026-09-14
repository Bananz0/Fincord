package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.datasource.cache.ContentMetadata
import org.akanework.gramophone.logic.GramophoneDownloadService
import org.akanework.gramophone.logic.getUri
import java.util.concurrent.Executors

/**
 * Offline downloads, stored in the same [JellyfinMediaCache] the player streams through.
 *
 * That sharing is the whole point: a downloaded track is simply a fully populated cache entry, so
 * playback needs no offline branch at all - the existing [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * finds every byte locally and never reaches the network. It also means a track that was streamed
 * recently may already be partly downloaded.
 */
@OptIn(UnstableApi::class)
object JellyfinDownloadManager {

    private const val TAG = "JellyfinDownloadManager"

    /**
     * media3 downloads one item at a time by default. Three keeps an album moving without saturating
     * a phone's uplink or the server, which for a self-hosted Jellyfin is usually the weaker end.
     */
    private const val PARALLEL_DOWNLOADS = 3

    @Volatile
    private var manager: DownloadManager? = null

    fun get(context: Context): DownloadManager {
        manager?.let { return it }
        return synchronized(this) {
            manager ?: run {
                val appContext = context.applicationContext
                DownloadManager(
                    appContext,
                    JellyfinMediaCache.databaseProvider(appContext),
                    JellyfinMediaCache.get(appContext),
                    // Downloads ask for the download quality, never the streaming one. Keeping
                    // music offline is a question about disk, and somebody capping playback on
                    // mobile data has said nothing about what they want stored - nor should a
                    // download started on the train be permanently worse than one started at home.
                    StreamQualityResolver.factory(
                        context = appContext,
                        upstream = OkHttpDataSource.Factory(JellyfinClientHolder.mediaHttpClient()),
                        quality = { StreamQuality.downloadQuality(appContext) },
                    ),
                    Executors.newFixedThreadPool(PARALLEL_DOWNLOADS)
                ).apply {
                    maxParallelDownloads = PARALLEL_DOWNLOADS
                }.also { manager = it }
            }
        }
    }

    /**
     * Queues [items] for download, skipping any that are already complete.
     *
     * The download id is the media id, which is the interned Jellyfin GUID - stable across launches,
     * so a download survives process death and can be matched back to a library item later.
     *
     * Reads the download index, so it must be called off the main thread.
     */
    fun download(context: Context, items: List<MediaItem>) {
        val existing = completedIds(context)
        items.forEach { item ->
            val id = item.mediaId
            if (id in existing) return@forEach
            val uri = item.getUri() ?: return@forEach
            enqueue(context, id, uri)
        }
    }

    private fun enqueue(context: Context, id: String, uri: Uri) {
        try {
            DownloadService.sendAddDownload(
                context,
                GramophoneDownloadService::class.java,
                DownloadRequest.Builder(id, uri)
                    // Stated rather than derived from the URI. The resolver keys each quality
                    // separately, and everything that spares downloads - the cache ceiling, the
                    // eviction after a server-side edit - recognises them by cache key. A download
                    // whose key nobody could predict would be the first thing trimmed away.
                    .setCustomCacheKey(downloadCacheKey(context, uri))
                    .build(),
                /* foreground = */ false
            )
        } catch (e: Exception) {
            // Queueing goes through startService, which the platform refuses in some background
            // states. A download that failed to start must not take the UI down with it.
            Log.w(TAG, "Could not queue download for $id", e)
        }
    }

    /** The cache key a download will occupy, at whatever quality downloads are set to. */
    private fun downloadCacheKey(context: Context, uri: Uri): String =
        StreamQualityResolver.resolve(
            androidx.media3.datasource.DataSpec(uri),
            StreamQuality.downloadQuality(context),
        ).key ?: uri.toString()

    fun remove(context: Context, items: List<MediaItem>) {
        // Removal is the one state change the user sees immediately, so the snapshot cannot go on
        // claiming these are held. A download becoming complete is asynchronous and correctly
        // stays "not downloaded" here until the index is next read.
        completedIdsSnapshot?.let { snapshot ->
            completedIdsSnapshot = snapshot - items.map { it.mediaId }.toSet()
        }
        items.forEach { item ->
            try {
                DownloadService.sendRemoveDownload(
                    context,
                    GramophoneDownloadService::class.java,
                    item.mediaId,
                    /* foreground = */ false
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove download for ${item.mediaId}", e)
            }
        }
    }

    /**
     * Re-fetches downloads at whatever quality downloads are currently set to.
     *
     * Removed and re-queued rather than overwritten: the quality is part of the cache key, so the
     * new copy is a different entry and the old one has to be told to go. Removal is asynchronous
     * through the download service, and the re-add carries the new key, so the two do not collide
     * even if they overlap.
     */
    fun redownload(context: Context, mediaIds: List<String>) {
        if (mediaIds.isEmpty()) return
        val uris = mediaIds.mapNotNull { id ->
            val uri = knownUriFor(context, id) ?: return@mapNotNull null
            id to uri
        }
        mediaIds.forEach { id ->
            runCatching {
                DownloadService.sendRemoveDownload(
                    context, GramophoneDownloadService::class.java, id, false
                )
            }.onFailure { Log.w(TAG, "Could not remove $id before re-download", it) }
        }
        uris.forEach { (id, uri) -> enqueue(context, id, uri) }
        Log.d(TAG, "Re-queued ${uris.size} downloads at the current quality")
    }

    /** The stream URI a download was originally queued with. */
    private fun knownUriFor(context: Context, mediaId: String): Uri? = try {
        get(context).downloadIndex.getDownload(mediaId)?.request?.uri
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the download request for $mediaId", e)
        null
    }

    fun removeAll(context: Context) {
        try {
            DownloadService.sendRemoveAllDownloads(
                context,
                GramophoneDownloadService::class.java,
                /* foreground = */ false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear downloads", e)
        }
    }

    /** Media ids that are fully downloaded. Reads the index, so call it off the main thread. */
    fun completedIds(context: Context): Set<String> = try {
        buildSet {
            get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.download.request.id)
                }
            }
        }.also { completedIdsSnapshot = it }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read download index", e)
        emptySet()
    }

    @Volatile
    private var completedIdsSnapshot: Set<String>? = null

    /**
     * The last set [completedIds] read, or null if it has never been read in this process.
     *
     * Cheap enough for the main thread, which is the point: the first real read has to build the
     * DownloadManager and open its index, and on a large cache that is slow enough that a menu
     * waiting on it arrives after the user has given up and tapped something else. A screen that
     * only needs to label a row can render from this and let the index be read behind it.
     */
    fun cachedCompletedIds(): Set<String>? = completedIdsSnapshot

    /**
     * Media ids that can be played without reaching Jellyfin.
     *
     * Downloads and streamed/prefetched audio intentionally share one SimpleCache. The download
     * index only describes items added through DownloadService, so older downloads and fully
     * prefetched tracks must also be discovered from complete cache entries.
     */
    fun availableOfflineIds(
        context: Context,
        items: Collection<MediaItem>,
    ): Set<String> {
        if (items.isEmpty()) return emptySet()
        val completed = completedIds(context)
        val cache = JellyfinMediaCache.get(context)
        return buildSet {
            addAll(completed)
            items.forEach { item ->
                if (item.mediaId.isBlank() || item.mediaId in completed) return@forEach
                val uri = item.getUri()?.toString() ?: return@forEach
                // Any quality counts as available. A track fully cached at 256 plays offline just
                // as well as one cached untouched, and telling the user it is missing because the
                // setting has since changed would be a lie about what is on the device.
                val playable = StreamQualityResolver.allCacheKeys(uri).any { key ->
                    val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                    length > 0L && cache.isCached(key, 0L, length)
                }
                if (playable) add(item.mediaId)
            }
        }
    }

    /**
     * Whether every playable item in a collection is already available offline.
     *
     * This reads Media3's download index, so callers must invoke it away from the main thread.
     * Keeping the rule here ensures albums, artists, playlists, stations and individual-song
     * menus all agree about whether to offer Download or Delete from device.
     */
    fun areAllDownloaded(context: Context, items: Collection<MediaItem>): Boolean {
        if (items.isEmpty()) return false
        val ids = items.asSequence().map(MediaItem::mediaId).filter(String::isNotBlank).toSet()
        return ids.isNotEmpty() && completedIds(context).containsAll(ids)
    }

    /** Reads the same index for one row/player item. Call off the main thread. */
    fun isDownloaded(context: Context, item: MediaItem): Boolean =
        item.mediaId.isNotBlank() && item.mediaId in completedIds(context)

    /**
     * What is actually stored, per quality, with the bytes each takes.
     *
     * Downloads keep whatever quality they were fetched at, so changing the setting does not touch
     * them - and without somewhere to see this, the only way to find out you are holding 256 when
     * you asked for Original is to listen for it. Read from the stated cache key rather than the
     * file, which is why downloads carry one.
     *
     * Reads the download index, so call it off the main thread.
     */
    fun downloadsByQuality(context: Context): Map<StreamQuality, Pair<Int, Long>> = try {
        val totals = mutableMapOf<StreamQuality, Pair<Int, Long>>()
        get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                val quality = qualityOf(download.request.customCacheKey)
                val (count, bytes) = totals[quality] ?: (0 to 0L)
                totals[quality] = (count + 1) to (bytes + download.bytesDownloaded)
            }
        }
        totals
    } catch (e: Exception) {
        Log.w(TAG, "Could not summarise downloads by quality", e)
        emptyMap()
    }

    /** Media ids stored at anything other than [quality], for a re-download offer. */
    fun downloadsNotAt(context: Context, quality: StreamQuality): List<String> = try {
        buildList {
            get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    if (qualityOf(download.request.customCacheKey) != quality) {
                        add(download.request.id)
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not list downloads by quality", e)
        emptyList()
    }

    /**
     * The quality a stored download is at, from its cache key.
     *
     * A key with no suffix means the untouched file - which is also what downloads queued before
     * these settings existed look like, and they were indeed originals.
     */
    private fun qualityOf(customCacheKey: String?): StreamQuality {
        val suffix = customCacheKey?.substringAfter('|', missingDelimiterValue = "").orEmpty()
        return if (suffix.isEmpty()) StreamQuality.ORIGINAL
        else StreamQuality.fromPreference(suffix)
    }

    /** Bytes occupied by completed downloads, as opposed to incidentally cached streaming data. */
    fun downloadedBytes(context: Context): Long = try {
        var total = 0L
        get(context).downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                total += cursor.download.bytesDownloaded
            }
        }
        total
    } catch (e: Exception) {
        Log.w(TAG, "Could not measure downloads", e)
        0L
    }
}
