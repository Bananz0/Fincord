package org.akanework.gramophone.logic.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

const val LYRICS_INDEX_TABLE_NAME = "lyricsIndexTable"
const val LYRICS_STATE_TABLE_NAME = "lyricsStateTable"

/**
 * Searchable lyrics, one row per track.
 *
 * Full-text rather than a LIKE over a plain column. A substring scan across nine thousand sets of
 * lyrics is tens of megabytes of string comparison per keystroke; an FTS index answers the same
 * question in under a millisecond, and it understands words - so "hold on" finds the line rather
 * than every song containing "old".
 *
 * [jellyfinId] is excluded from the index. FTS4 indexes every column by default, and a GUID sitting
 * in the same haystack as the words means a query could match an identifier.
 */
@Fts4(notIndexed = ["jellyfinId"])
@Entity(tableName = LYRICS_INDEX_TABLE_NAME)
data class LyricsIndex(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int? = null,
    @ColumnInfo(name = "jellyfinId")
    val jellyfinId: String,
    /** The words alone: timestamps are stripped before indexing, being noise to a text search. */
    @ColumnInfo(name = "text")
    val text: String,
)

/**
 * What the indexer already knows about a track, so a repeat pass is cheap.
 *
 * Separate from the index because most of what this records is an absence. A track with no lyrics
 * has nothing to put in an FTS table, and without somewhere to remember that, every run would ask
 * the server again for the same two thousand tracks that will never have any.
 */
@Entity(tableName = LYRICS_STATE_TABLE_NAME)
data class LyricsState(
    @PrimaryKey
    @ColumnInfo(name = "jellyfinId")
    val jellyfinId: String,
    @ColumnInfo(name = "hasLyrics")
    val hasLyrics: Boolean,
    @ColumnInfo(name = "fetchedAt")
    val fetchedAt: Long,
)
