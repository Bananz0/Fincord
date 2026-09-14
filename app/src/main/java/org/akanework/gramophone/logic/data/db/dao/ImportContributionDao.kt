package org.akanework.gramophone.logic.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.akanework.gramophone.logic.data.db.entity.IMPORT_CONTRIBUTION_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.ImportContribution

@Dao
interface ImportContributionDao {

    @Query("SELECT * FROM $IMPORT_CONTRIBUTION_TABLE_NAME")
    fun getAll(): List<ImportContribution>

    @Query("SELECT * FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun getBySource(source: String): List<ImportContribution>

    /** Everything any source has contributed to these tracks, for rebuilding the baseline. */
    @Query("SELECT * FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE jellyfinId IN (:jellyfinIds)")
    fun getForTracks(jellyfinIds: List<String>): List<ImportContribution>

    /** How far each source has been read, so the next run can ask only for what is new. */
    @Query("SELECT MAX(throughSeconds) FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun watermarkFor(source: String): Long?

    @Query("SELECT COUNT(*) FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun trackCountFor(source: String): Int

    @Query("SELECT SUM(count) FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun playCountFor(source: String): Int?

    @Query("SELECT MAX(importedAt) FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun lastImportFor(source: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(contributions: List<ImportContribution>)

    /**
     * Forgets one source entirely.
     *
     * Undoing an import is not this alone - the counts already written to Jellyfin stay written.
     * Removing the rows only means the next run treats those plays as part of the baseline, so
     * callers that offer this must subtract from the server first.
     */
    @Query("DELETE FROM $IMPORT_CONTRIBUTION_TABLE_NAME WHERE source = :source")
    fun deleteSource(source: String)

    @Transaction
    fun record(contributions: List<ImportContribution>) {
        contributions.chunked(CHUNK_SIZE).forEach { upsertAll(it) }
    }

    companion object {
        private const val CHUNK_SIZE = 500
    }
}
