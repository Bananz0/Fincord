package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.JELLYFIN_ID_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.JellyfinId

@Dao
interface JellyfinIdDao {

    /**
     * Reads the whole mapping in one go. The library loader interns thousands of IDs per sync, so
     * it holds the map in memory for the duration rather than hitting the database per item.
     */
    @Query("SELECT * FROM $JELLYFIN_ID_TABLE_NAME")
    fun getAll(): List<JellyfinId>

    /**
     * Inserts newly seen GUIDs. Existing rows are left untouched so their [JellyfinId.localId]
     * never changes.
     */
    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(ids: List<JellyfinId>)

    @Query("SELECT * FROM $JELLYFIN_ID_TABLE_NAME WHERE ${JellyfinId.LOCAL_ID_COLUMN} = :localId")
    fun getByLocalId(localId: Long): JellyfinId?

    @Query("DELETE FROM $JELLYFIN_ID_TABLE_NAME")
    fun clear()
}
