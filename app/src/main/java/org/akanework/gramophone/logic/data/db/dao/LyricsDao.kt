package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.CACHED_SONG_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.JELLYFIN_ID_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.LYRICS_INDEX_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.LYRICS_STATE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.LyricsIndex
import org.akanework.gramophone.logic.data.db.entity.LyricsState

@Dao
interface LyricsDao {

    /**
     * Tracks the indexer has not looked at yet.
     *
     * Asked of the database rather than worked out in memory so a resumed run picks up where it
     * stopped without holding ten thousand ids to compare against.
     */
    @Query(
        "SELECT jellyfinId FROM $CACHED_SONG_TABLE_NAME WHERE jellyfinId NOT IN " +
            "(SELECT jellyfinId FROM $LYRICS_STATE_TABLE_NAME) LIMIT :limit"
    )
    fun unindexed(limit: Int): List<String>

    @Query(
        "SELECT COUNT(*) FROM $CACHED_SONG_TABLE_NAME WHERE jellyfinId NOT IN " +
            "(SELECT jellyfinId FROM $LYRICS_STATE_TABLE_NAME)"
    )
    fun unindexedCount(): Int

    @Query("SELECT COUNT(*) FROM $LYRICS_STATE_TABLE_NAME WHERE hasLyrics = 1")
    fun indexedCount(): Int

    /**
     * Songs whose lyrics match [query], as an FTS MATCH.
     *
     * The caller supplies FTS syntax, so a bare word is a prefix-free term match and quoting makes
     * a phrase. Limited because a common word legitimately matches thousands of songs and the
     * search screen shows a handful.
     */
    @Query(
        "SELECT jellyfinId FROM $LYRICS_INDEX_TABLE_NAME " +
            "WHERE $LYRICS_INDEX_TABLE_NAME MATCH :query LIMIT :limit"
    )
    fun search(query: String, limit: Int): List<String>

    /**
     * A short fragment around each match, for showing why a song matched.
     *
     * Returning the complete lyrics for every hit made a search copy hundreds of kilobytes out of
     * SQLite before the UI threw nearly all of it away. FTS already knows where the matching terms
     * are, so let its snippet function do that work inside the database.
     */
    @Query(
        "SELECT ids.localId AS localId, " +
            "snippet($LYRICS_INDEX_TABLE_NAME, '', '', '…', 1, 24) AS text " +
            "FROM $LYRICS_INDEX_TABLE_NAME AS lyrics " +
            "INNER JOIN $JELLYFIN_ID_TABLE_NAME AS ids " +
            "ON ids.jellyfinId = lyrics.jellyfinId " +
            "WHERE $LYRICS_INDEX_TABLE_NAME MATCH :query LIMIT :limit"
    )
    fun searchWithText(query: String, limit: Int): List<LyricMatch>

    /** [localId] is the MediaItem id Accord uses; the FTS table itself stores Jellyfin GUIDs. */
    data class LyricMatch(val localId: Long, val text: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertIndex(entries: List<LyricsIndex>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertState(states: List<LyricsState>)

    @Query("DELETE FROM $LYRICS_INDEX_TABLE_NAME WHERE jellyfinId IN (:jellyfinIds)")
    fun deleteIndex(jellyfinIds: List<String>)

    @Query("DELETE FROM $LYRICS_STATE_TABLE_NAME WHERE jellyfinId IN (:jellyfinIds)")
    fun deleteState(jellyfinIds: List<String>)

    /**
     * Records a batch of results.
     *
     * One transaction, and the index is cleared for these ids first: a re-index after a server-side
     * edit must replace what was there rather than leave the old words alongside the new, which
     * would make a track findable by lyrics it no longer has.
     */
    @Transaction
    fun record(entries: List<LyricsIndex>, states: List<LyricsState>) {
        val ids = states.map { it.jellyfinId }
        if (ids.isNotEmpty()) deleteIndex(ids)
        if (entries.isNotEmpty()) insertIndex(entries)
        if (states.isNotEmpty()) insertState(states)
    }

    /** Forgets everything, so the next run re-reads the whole library. */
    @Transaction
    fun clear() {
        clearIndex()
        clearState()
    }

    @Query("DELETE FROM $LYRICS_INDEX_TABLE_NAME")
    fun clearIndex()

    @Query("DELETE FROM $LYRICS_STATE_TABLE_NAME")
    fun clearState()
}
