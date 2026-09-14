package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.akanework.gramophone.logic.data.db.entity.ANALYSED_TRACK_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack

@Dao
interface AnalysedTrackDao {

    /**
     * The stored analysis for [jellyfinId], if one was written by analyser [version].
     *
     * The version is part of the query rather than checked afterwards, because a row from an older
     * analyser is not a stale answer to be used with caution - it is a different answer, and the
     * only correct thing to do with it is run the analysis again.
     */
    @Query(
        "SELECT * FROM $ANALYSED_TRACK_TABLE_NAME " +
            "WHERE jellyfinId = :jellyfinId AND analyserVersion = :version"
    )
    fun get(jellyfinId: String, version: Int): AnalysedTrack?

    /**
     * The stored analyses for [jellyfinIds] that analyser [version] wrote, in no particular order.
     *
     * For ordering a queue, where the alternative is one query per track. Rows that do not exist
     * are simply absent rather than null - a track nobody has analysed is a track the ordering
     * knows nothing about, which it already has to handle.
     *
     * SQLite's variable limit is 999 by default and Room expands the list into that many bind
     * parameters, so the caller chunks. See [org.akanework.gramophone.logic.data.automix.AutomixQueueOrdering].
     */
    @Query(
        "SELECT * FROM $ANALYSED_TRACK_TABLE_NAME " +
            "WHERE jellyfinId IN (:jellyfinIds) AND analyserVersion = :version"
    )
    fun getAll(jellyfinIds: List<String>, version: Int): List<AnalysedTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(track: AnalysedTrack)

    /** Drops rows any other analyser wrote. Runs once at startup, so nothing has to check twice. */
    @Query("DELETE FROM $ANALYSED_TRACK_TABLE_NAME WHERE analyserVersion != :version")
    fun deleteOtherVersions(version: Int)

    @Query("DELETE FROM $ANALYSED_TRACK_TABLE_NAME")
    fun deleteAll()
}
