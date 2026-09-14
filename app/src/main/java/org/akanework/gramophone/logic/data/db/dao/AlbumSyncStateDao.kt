package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.ALBUM_SYNC_STATE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.AlbumSyncState

@Dao
interface AlbumSyncStateDao {

    @Query("SELECT * FROM $ALBUM_SYNC_STATE_TABLE_NAME")
    fun getAll(): List<AlbumSyncState>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(states: List<AlbumSyncState>)

    @Query("DELETE FROM $ALBUM_SYNC_STATE_TABLE_NAME")
    fun deleteAll()

    @Query("DELETE FROM $ALBUM_SYNC_STATE_TABLE_NAME WHERE albumJellyfinId IN (:albumIds)")
    fun deleteByAlbumIds(albumIds: List<String>)

    /**
     * Replaces the recorded state wholesale, for a full sync.
     *
     * One transaction, for the same reason the song cache uses one: a kill part-way through must
     * not leave a table that claims some albums are up to date when their tracks were never
     * written.
     */
    @Transaction
    fun replaceAll(states: List<AlbumSyncState>) {
        deleteAll()
        states.chunked(CHUNK_SIZE).forEach { upsertAll(it) }
    }

    companion object {
        private const val CHUNK_SIZE = 500
    }
}
