package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import org.akanework.gramophone.logic.data.db.entity.PENDING_SCROBBLE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.PendingScrobble

@Dao
interface PendingScrobbleDao {

    /** Oldest first, so a backlog is submitted in the order it was listened to. */
    @Query("SELECT * FROM $PENDING_SCROBBLE_TABLE_NAME ORDER BY timestampSeconds ASC LIMIT :limit")
    fun oldest(limit: Int): List<PendingScrobble>

    @Query("SELECT COUNT(*) FROM $PENDING_SCROBBLE_TABLE_NAME")
    fun count(): Int

    @Insert
    fun insert(scrobble: PendingScrobble): Long

    @Delete
    fun delete(scrobbles: List<PendingScrobble>)

    /**
     * Keeps only the [limit] most recent rows.
     *
     * A server that stays unreachable would otherwise grow this table without bound. Last.fm also
     * rejects scrobbles older than roughly two weeks, so the oldest rows are the ones least likely
     * to ever be accepted - dropping those first loses the least.
     */
    @Query(
        "DELETE FROM $PENDING_SCROBBLE_TABLE_NAME WHERE id NOT IN " +
                "(SELECT id FROM $PENDING_SCROBBLE_TABLE_NAME ORDER BY timestampSeconds DESC LIMIT :limit)"
    )
    fun trimTo(limit: Int)

    @Query("DELETE FROM $PENDING_SCROBBLE_TABLE_NAME")
    fun deleteAll()
}
