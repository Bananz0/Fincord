package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.akanework.gramophone.logic.data.db.entity.OWN_PLAY_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.OwnPlay

@Dao
interface OwnPlayDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(play: OwnPlay)

    /** Our own plays in a window, for matching against scrobbles coming back from a service. */
    @Query(
        "SELECT * FROM $OWN_PLAY_TABLE_NAME " +
            "WHERE playedAtSeconds >= :fromSeconds AND playedAtSeconds <= :toSeconds"
    )
    fun between(fromSeconds: Long, toSeconds: Long): List<OwnPlay>

    @Query("SELECT COUNT(*) FROM $OWN_PLAY_TABLE_NAME")
    fun count(): Int

    /**
     * Drops entries older than [beforeSeconds].
     *
     * This log exists only to recognise a play coming back from an external service. Once a source
     * has been imported past a point, nothing older can return, and the rows are dead weight.
     */
    @Query("DELETE FROM $OWN_PLAY_TABLE_NAME WHERE playedAtSeconds < :beforeSeconds")
    fun pruneBefore(beforeSeconds: Long)
}
