package org.akanework.gramophone.logic.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

const val ALBUM_SYNC_STATE_TABLE_NAME = "album_sync_state"

/**
 * What an album looked like on the server the last time its tracks were pulled.
 *
 * A full sync of a large library is dozens of sequential page requests over minutes, which is a
 * heavy price for a session where one album was retagged. Jellyfin will answer a much cheaper
 * question - give me every album with these three fields - and comparing the answer against this
 * table says exactly which albums need their tracks fetched again.
 *
 * Three signals rather than one, because they move at different times:
 *  - [etag] is Jellyfin's own change token and moves on any write, which is what makes it the one
 *    that actually catches a retag,
 *  - [dateLastMediaAdded] moves when a file is added to the album,
 *  - [dateCreated] is near-constant, and is here to catch an album replaced wholesale under a
 *    reused id rather than to detect edits.
 *
 * `DateLastSaved` would be the most direct signal for a metadata write, and Jellyfin will accept
 * it as a requested field - but `BaseItemDto` in the SDK does not expose a property for it, so
 * there is nothing to read even when it is asked for. The etag covers the same ground.
 *
 * Any of the three differing marks the album dirty. Missing values count as differing: an album
 * the server declines to describe is one we cannot claim is unchanged.
 */
@Entity(tableName = ALBUM_SYNC_STATE_TABLE_NAME)
data class AlbumSyncState(
    /** Jellyfin's album GUID, undashed, matching `CachedSong.albumJellyfinId`. */
    @PrimaryKey val albumJellyfinId: String,
    val dateLastMediaAdded: Long?,
    val dateCreated: Long?,
    val etag: String?,
) {
    /** True when the server's current description differs from what was stored. */
    fun differsFrom(other: AlbumSyncState): Boolean =
        dateLastMediaAdded != other.dateLastMediaAdded ||
            dateCreated != other.dateCreated ||
            etag != other.etag
}
