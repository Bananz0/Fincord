package org.akanework.gramophone.logic.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val JELLYFIN_ID_TABLE_NAME = "jellyfinIdTable"

/**
 * Maps a Jellyfin GUID onto a stable synthetic [Long].
 *
 * Jellyfin identifies everything by 32 character GUIDs, but this app was built against MediaStore
 * and assumes numeric IDs throughout: `MediaItem.mediaId` is parsed with `toLong()`, and
 * `LastPlayedManager` serialises `AlbumId` / `ArtistId` / `GenreId` with `writeLong`. Interning
 * GUIDs here lets all of that stay exactly as it is.
 *
 * These rows must be persisted rather than rebuilt per launch: `LastPlayedManager` restores the
 * queue from IDs written during a previous process, so a mapping that changed between launches
 * would silently resurrect the wrong songs.
 */
@Entity(
    tableName = JELLYFIN_ID_TABLE_NAME,
    indices = [Index(value = [JellyfinId.JELLYFIN_ID_COLUMN], unique = true)]
)
data class JellyfinId(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = LOCAL_ID_COLUMN)
    val localId: Long = 0,
    @ColumnInfo(name = JELLYFIN_ID_COLUMN)
    val jellyfinId: String,
    @ColumnInfo(name = ITEM_TYPE_COLUMN)
    val itemType: String,
) {
    companion object {
        const val LOCAL_ID_COLUMN = "localId"
        const val JELLYFIN_ID_COLUMN = "jellyfinId"
        const val ITEM_TYPE_COLUMN = "itemType"

        const val TYPE_AUDIO = "AUDIO"
        const val TYPE_ALBUM = "ALBUM"
        const val TYPE_ARTIST = "ARTIST"
        const val TYPE_GENRE = "GENRE"
    }
}
