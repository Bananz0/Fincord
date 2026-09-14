package org.akanework.gramophone.logic.data.automix

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack

/**
 * Resequences a queue the listener already has so that more of its transitions can be mixed.
 *
 * The counterpart to a station arriving already sequenced. A station is built somewhere that knows
 * the whole library and can choose freely; a playlist somebody made by hand is a fixed set of
 * tracks, and the only freedom left is the order they are heard in. Both end at the same place -
 * adjacent pairs that mix - and they share [MixCompatibility] to get there, so a queue reordered on
 * the phone and a mix built off the phone cannot disagree about which pairs those are.
 *
 * Three rules, and they are what make this safe to offer on a queue somebody made deliberately:
 *
 * - **Nothing already heard moves.** Only the items after the current one are candidates. Moving
 *   the playing track, or anything behind it, would change what "next" meant halfway through it.
 * - **Nothing is added or dropped.** The result is a permutation of the upcoming items, so the
 *   listener's playlist is still their playlist.
 * - **Unanalysed tracks keep their relative order.** They are placed, not filtered, and where the
 *   ordering has nothing to go on it falls back to the order it was given - so a queue of tracks
 *   nobody has analysed comes back untouched rather than shuffled.
 */
object AutomixQueueOrdering {

    /**
     * SQLite takes 999 bind parameters by default, and Room expands an `IN` list into one each.
     *
     * A queue longer than this is unusual and a query that throws on it is not: the limit is a
     * property of the database rather than of the feature, so it is chunked here rather than
     * declared a maximum queue length somewhere far away from the reason.
     */
    private const val QUERY_CHUNK = 500

    /**
     * The upcoming items' original queue indices in the order they should be played, or `null` to
     * change nothing.
     *
     * Indices rather than items, deliberately. A queue may hold the same track twice, and two
     * entries of one track are indistinguishable as items - so a caller handed items back would
     * have to guess which entry a given position meant, and `moveMediaItem` takes positions
     * anyway. Positions are what the player speaks and what cannot be ambiguous.
     *
     * Null rather than the unchanged order so the caller can tell "already the best I can find"
     * from "reordered" and skip the work in the common case. Applying a no-op reorder is not free:
     * every move is a timeline change, and a timeline change marks the stored queue dirty and
     * re-encodes it.
     */
    @WorkerThread
    fun reorder(context: Context, queue: List<MediaItem>, currentIndex: Int): List<Int>? {
        if (currentIndex < 0 || currentIndex >= queue.size) return null
        val upcoming = (currentIndex + 1 until queue.size).toList()
        if (upcoming.size < 2) return null

        val analyses = analysesFor(context, queue)
        val playing = analyses[queue[currentIndex].mediaId]
        val ordered = MixCompatibility.order(
            tracks = upcoming,
            analysisOf = { analyses[queue[it].mediaId] },
            first = playing,
        )
        if (ordered == upcoming) return null
        return ordered
    }

    /**
     * Applies [order] - upcoming indices in their new order - through [move], one move at a time.
     *
     * A permutation cannot be handed to a player wholesale; it has to be walked, and each move
     * shifts everything between its endpoints. So the positions are tracked as they actually are
     * after each move rather than as they were when the plan was made, which is the difference
     * between this working and it scrambling a queue in a way that looks almost right.
     *
     * Separated from [reorder] and from any player so it can be tested as the arithmetic it is.
     */
    fun applyOrder(queueSize: Int, currentIndex: Int, order: List<Int>, move: (Int, Int) -> Unit) {
        // live[position] is the index that item started at, updated as the moves happen.
        val live = MutableList(queueSize) { it }
        order.forEachIndexed { offset, wanted ->
            val position = currentIndex + 1 + offset
            val from = live.indexOf(wanted)
            if (from != position && from >= 0) {
                move(from, position)
                live.add(position, live.removeAt(from))
            }
        }
    }

    /**
     * How many of [queue]'s upcoming transitions would mix, before and after reordering.
     *
     * For telling the listener what a reorder bought them, and for saying so honestly when the
     * answer is nothing. A count is the only figure here that means anything on its own -
     * [MixCompatibility.Cost] is an ordering key and its absolute value is not a quantity.
     */
    @WorkerThread
    fun mixableAdjacencies(context: Context, queue: List<MediaItem>): Int {
        if (queue.size < 2) return 0
        val analyses = analysesFor(context, queue)
        return queue.zipWithNext().count { (from, to) ->
            val a = analyses[from.mediaId] ?: return@count false
            val b = analyses[to.mediaId] ?: return@count false
            MixCompatibility.mixable(a, b)
        }
    }

    /**
     * The stored analyses for everything in [queue], by media id.
     *
     * Only what is already stored. This deliberately does not analyse anything: a queue of two
     * hundred tracks is two hundred decodes, which is minutes of CPU and a great deal of network
     * for a button press, and the index that makes this worth doing at all belongs somewhere that
     * has the files locally. Until it exists, this orders over whatever playback happens to have
     * analysed, which is few tracks and is the honest amount of good it can do.
     */
    private fun analysesFor(context: Context, queue: List<MediaItem>): Map<String, AnalysedTrack> {
        val dao = AppDatabase.getInstance(context).analysedTrackDao()
        val ids = queue.mapNotNull { it.mediaId.takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(QUERY_CHUNK)
            .flatMap { dao.getAll(it, AnalysedTrack.ANALYSER_VERSION) }
            .associateBy { it.jellyfinId }
    }
}
