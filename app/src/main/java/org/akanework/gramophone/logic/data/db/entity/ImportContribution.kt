package org.akanework.gramophone.logic.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

const val IMPORT_CONTRIBUTION_TABLE_NAME = "importContributionTable"

/**
 * What one external service has contributed to one track's play count on Jellyfin.
 *
 * Jellyfin stores a single number per track, so an import that simply wrote to it could never be
 * run twice: a second pass would either double the history or discard whatever had been played in
 * between. Recording each source's share separately makes the total reconstructable -
 *
 *     total = baseline + sum(contributions)
 *
 * where the baseline is whatever Jellyfin holds that no import accounts for, recomputed on every
 * run as `current server count - sum of the contributions from last time`. Plays that happened
 * through Jellyfin since the previous import therefore survive, and re-importing a source only
 * ever revises that source's own row.
 *
 * Keyed by track and source together, so Last.fm and a Spotify export can both hold a share of the
 * same song without either being able to overwrite the other.
 */
@Entity(
    tableName = IMPORT_CONTRIBUTION_TABLE_NAME,
    primaryKeys = ["jellyfinId", "source"],
    indices = [Index("source")],
)
data class ImportContribution(
    @ColumnInfo(name = "jellyfinId")
    val jellyfinId: String,
    /** Stable identifier for the service, from [org.akanework.gramophone.logic.data.playcounts.PlayCountSource]. */
    @ColumnInfo(name = "source")
    val source: String,
    /** Plays this source has accounted for. Never negative. */
    @ColumnInfo(name = "count")
    val count: Int,
    /**
     * The newest play this source has been read up to, in epoch seconds.
     *
     * The next run asks the service only for what happened after this, so a repeat import costs
     * one page rather than the whole history, and cannot re-add plays already counted.
     */
    @ColumnInfo(name = "throughSeconds")
    val throughSeconds: Long,
    /** When this row was last written, for display on the import screen. */
    @ColumnInfo(name = "importedAt")
    val importedAt: Long,
)
