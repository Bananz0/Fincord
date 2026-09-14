package org.akanework.gramophone.logic.data.lastfm

import android.content.Context
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Matches Last.fm loved tracks onto playable items already present in the user's library. */
object LastFmLovedLibrary {
    private val refreshLock = Mutex()
    @Volatile private var memoryKeys: Set<String>? = null
    @Volatile private var lastRefreshMs = 0L

    fun cachedKeys(context: Context): Set<String> {
        memoryKeys?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TRACKS, null)
        val loaded = runCatching {
            if (raw == null) emptySet() else {
                val array = JSONArray(raw)
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptySet())
        memoryKeys = loaded
        return loaded
    }

    suspend fun refreshKeys(context: Context): Set<String> = refreshLock.withLock {
        val cached = cachedKeys(context)
        if (System.currentTimeMillis() - lastRefreshMs < REFRESH_INTERVAL_MS) return cached
        val refreshed = withContext(Dispatchers.IO) {
            val store = LastFmCredentialStore(context)
            val username = store.username
            if (!store.isLinked() || username.isNullOrBlank()) return@withContext null
            runCatching {
                LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl)
                    .getLovedTracks(username)
                    .map { trackKey(it.artist, it.title) }
                    .toSet()
            }.getOrNull()
        } ?: return cached

        memoryKeys = refreshed
        lastRefreshMs = System.currentTimeMillis()
        val array = JSONArray()
        refreshed.forEach(array::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TRACKS, array.toString())
            .apply()
        refreshed
    }

    fun isLoved(item: MediaItem, keys: Set<String>): Boolean {
        val title = item.mediaMetadata.title?.toString().orEmpty()
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val albumArtist = item.mediaMetadata.albumArtist?.toString().orEmpty()
        return trackKey(artist, title) in keys ||
            (albumArtist.isNotBlank() && trackKey(albumArtist, title) in keys)
    }

    private fun trackKey(artist: String, title: String): String =
        "${artist.normalise()}\u0000${title.normalise()}"

    private fun String.normalise(): String = lowercase().filter { it.isLetterOrDigit() }

    private const val PREFS = "lastfm_loved_cache"
    private const val KEY_TRACKS = "track_keys"
    private const val REFRESH_INTERVAL_MS = 10 * 60 * 1000L
}
