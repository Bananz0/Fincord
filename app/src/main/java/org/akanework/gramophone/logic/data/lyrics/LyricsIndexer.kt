package org.akanework.gramophone.logic.data.lyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.LyricsIndex
import org.akanework.gramophone.logic.data.db.entity.LyricsState
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.jellyfin.sdk.api.client.extensions.lyricApi
import java.util.UUID

/**
 * Builds the searchable lyric index by asking Jellyfin for each track's lyrics.
 *
 * Worth stating why this comes from the server rather than the files. The library's lyrics are
 * embedded in the audio, which normally would mean decoding every track to read them - but Jellyfin
 * has already extracted them during its own scan and serves them as a plain document per item. So
 * the phone downloads text, not music, and needs no tag parser and no plugin.
 *
 * The work is deliberately resumable and dull: a batch at a time, recording what it learned as it
 * goes, so being killed costs the current batch and nothing else.
 */
class LyricsIndexer(private val context: Context) {

    data class Progress(val done: Int, val remaining: Int)

    /**
     * Indexes up to [budget] tracks and reports how many are left.
     *
     * Bounded rather than run to completion because the caller is a background worker with a
     * deadline. Coming back for the next slice is free; being killed halfway through ten thousand
     * requests with nothing written would not be.
     */
    suspend fun indexSome(
        budget: Int = DEFAULT_BUDGET,
        onProgress: (Progress) -> Unit = {},
    ): Progress = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).lyricsDao()
        val api = JellyfinClientHolder.api()
        if (api == null) {
            Log.d(TAG, "Not signed in; nothing to index")
            return@withContext Progress(0, dao.unindexedCount())
        }

        var done = 0
        while (done < budget && currentCoroutineContext().isActive) {
            val batch = dao.unindexed(BATCH)
            if (batch.isEmpty()) break

            // A handful at a time. These are thousands of tiny requests where the round trip
            // dominates, so some concurrency is the difference between minutes and an hour - but a
            // self-hosted server is usually the weaker end, and this must never be what makes
            // playback stutter.
            val results = coroutineScope {
                batch.map { id -> async { id to fetch(api, id) } }.awaitAll()
            }

            val entries = mutableListOf<LyricsIndex>()
            val states = mutableListOf<LyricsState>()
            val now = System.currentTimeMillis()
            results.forEach { (id, text) ->
                if (!text.isNullOrBlank()) {
                    entries += LyricsIndex(jellyfinId = id, text = text)
                }
                states += LyricsState(
                    jellyfinId = id,
                    hasLyrics = !text.isNullOrBlank(),
                    fetchedAt = now,
                )
            }
            dao.record(entries, states)

            done += batch.size
            onProgress(Progress(done, dao.unindexedCount()))
        }

        val remaining = dao.unindexedCount()
        Log.d(TAG, "Indexed $done this pass, $remaining remaining")
        Progress(done, remaining)
    }

    /**
     * One track's lyrics as plain words.
     *
     * A 404 is the ordinary answer for a track without any, not a failure, so it returns null and
     * the state row remembers it - otherwise every pass would ask again for the same two thousand
     * instrumentals forever.
     */
    private suspend fun fetch(
        api: org.jellyfin.sdk.api.client.ApiClient,
        jellyfinId: String,
    ): String? = try {
        val lyric = api.lyricApi.getLyrics(UUID.fromString(jellyfinId.toDashedUuid())).content
        lyric.lyrics
            ?.mapNotNull { it.text?.trim()?.takeIf(String::isNotEmpty) }
            ?.joinToString(SEPARATOR)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // Includes the 404 for "this track has none", which is why this is not logged loudly.
        Log.v(TAG, "No lyrics for $jellyfinId: ${e.message}")
        null
    }

    suspend fun indexedCount(): Int = withContext(Dispatchers.IO) {
        runCatching { AppDatabase.getInstance(context).lyricsDao().indexedCount() }.getOrDefault(0)
    }

    suspend fun remainingCount(): Int = withContext(Dispatchers.IO) {
        runCatching { AppDatabase.getInstance(context).lyricsDao().unindexedCount() }.getOrDefault(0)
    }

    /** Throws the index away so the next pass rebuilds it from scratch. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).lyricsDao().clear()
    }

    companion object {
        private const val TAG = "LyricsIndexer"

        /** Lines are joined by a space: FTS wants words, and a newline is not a word boundary it needs. */
        private const val SEPARATOR = " "

        private const val BATCH = 8
        private const val DEFAULT_BUDGET = 600
    }
}
