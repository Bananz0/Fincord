package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.LruCache
import androidx.annotation.WorkerThread
import org.akanework.gramophone.logic.data.db.AppDatabase

/**
 * Resolves the interned [Long] IDs used throughout the app back to Jellyfin GUIDs.
 *
 * [JellyfinIdMap] is the write side, owned by a library sync. This is the read side, used by
 * anything that needs to talk to the server about a track it only knows by `mediaId` - playback
 * reporting and favourites. Lookups are backed by the database and memoised, because the same few
 * tracks get asked about repeatedly while one of them is playing.
 */
object JellyfinItemResolver {

    private val cache = LruCache<Long, String>(512)

    /**
     * The Jellyfin GUID for [localId], or null if it was never interned.
     *
     * Hits the database on a miss, so must not be called from the main thread.
     */
    @WorkerThread
    fun remoteId(context: Context, localId: Long): String? {
        cache.get(localId)?.let { return it }
        val row = AppDatabase.getInstance(context).jellyfinIdDao().getByLocalId(localId) ?: return null
        cache.put(localId, row.jellyfinId)
        return row.jellyfinId
    }

    /** Convenience for the common case of a [androidx.media3.common.MediaItem.mediaId]. */
    @WorkerThread
    fun remoteIdForMediaId(context: Context, mediaId: String?): String? {
        val localId = mediaId?.toLongOrNull() ?: return null
        return remoteId(context, localId)
    }

    fun clear() = cache.evictAll()
}
