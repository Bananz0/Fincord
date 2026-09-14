package org.akanework.gramophone.logic.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

const val PENDING_SCROBBLE_TABLE_NAME = "pendingScrobbleTable"

/**
 * A play that has not reached Last.fm yet.
 *
 * Scrobbling has to survive being offline: phones lose signal on trains, in lifts and in aeroplane
 * mode, and a play that vanished because the radio was off is exactly the kind of gap that makes
 * people stop trusting a scrobbler. Every qualifying play is written here first and only deleted
 * once Last.fm has acknowledged it.
 *
 * The metadata is denormalised rather than referencing the library, because a scrobble describes
 * what was played at a moment in time - it must still submit correctly after the track has been
 * renamed on the server, or removed from it entirely.
 */
@Entity(tableName = PENDING_SCROBBLE_TABLE_NAME)
data class PendingScrobble(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val artist: String,
    val title: String,
    val album: String?,
    val albumArtist: String?,
    val durationSeconds: Int?,
    val trackNumber: Int?,
    /** When playback of this track began, in Unix seconds - the value Last.fm scrobbles against. */
    val timestampSeconds: Long,
)
