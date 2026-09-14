package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.CACHED_SONG_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.CachedSong

@Dao
interface CachedSongDao {

    @Query("SELECT * FROM $CACHED_SONG_TABLE_NAME")
    fun getAll(): List<CachedSong>

    @Query("SELECT * FROM $CACHED_SONG_TABLE_NAME LIMIT :limit")
    fun getInitialFast(limit: Int = 100): List<CachedSong>

    @Query("SELECT COUNT(*) FROM $CACHED_SONG_TABLE_NAME")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(songs: List<CachedSong>)

    @Query("DELETE FROM $CACHED_SONG_TABLE_NAME")
    fun deleteAll()

    @Query("DELETE FROM $CACHED_SONG_TABLE_NAME WHERE albumJellyfinId IN (:albumIds)")
    fun deleteByAlbumIds(albumIds: List<String>)

    /**
     * Swaps the tracks of specific albums, leaving the rest of the library alone.
     *
     * The delete and the insert are one transaction per batch: an album whose old rows were
     * removed but whose new ones were never written would read as an album that lost its tracks,
     * and the sync state saved alongside would claim it was up to date.
     */
    @Transaction
    fun replaceAlbums(albumIds: List<String>, songs: List<CachedSong>) {
        albumIds.chunked(CHUNK_SIZE).forEach { deleteByAlbumIds(it) }
        songs.chunked(CHUNK_SIZE).forEach { insertAll(it) }
    }

    /**
     * Swaps in a freshly synced library.
     *
     * Done in one transaction so a crash or a kill mid-write cannot leave a half-written library
     * that would then be trusted as complete on next launch.
     */
    @Transaction
    fun replaceAll(songs: List<CachedSong>) {
        deleteAll()
        songs.chunked(CHUNK_SIZE).forEach { insertAll(it) }
    }

    companion object {
        /** SQLite caps variables per statement; large libraries must go in batches. */
        private const val CHUNK_SIZE = 500
    }
}
