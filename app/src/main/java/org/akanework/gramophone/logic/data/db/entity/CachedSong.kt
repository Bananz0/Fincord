package org.akanework.gramophone.logic.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

const val CACHED_SONG_TABLE_NAME = "cachedSongTable"

/**
 * A song's metadata, cached so the library survives a restart.
 *
 * Without this, every cold start refetches the whole library - roughly 40 seconds and 18 requests
 * for an 8,700 track collection - before the user can see anything. Cached rows let the UI populate
 * immediately while a refresh runs in the background.
 *
 * Stream and artwork URLs are deliberately *not* stored: they embed the access token, which changes
 * whenever the user signs in again, so they are rebuilt from these identifiers at load time.
 */
@Entity(tableName = CACHED_SONG_TABLE_NAME)
data class CachedSong(
    /** The interned local ID, which is also the media3 `mediaId`. */
    @PrimaryKey
    @ColumnInfo(name = "localId")
    val localId: Long,
    @ColumnInfo(name = "jellyfinId")
    val jellyfinId: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "artist")
    val artist: String?,
    /** Every track artist from Jellyfin, separated by an ASCII unit separator. */
    @ColumnInfo(name = "trackArtists")
    val trackArtists: String?,
    /** Local Jellyfin artist IDs aligned one-for-one with [trackArtists]. */
    @ColumnInfo(name = "trackArtistIds")
    val trackArtistIds: String?,
    @ColumnInfo(name = "artistId")
    val artistId: Long?,
    @ColumnInfo(name = "album")
    val album: String?,
    @ColumnInfo(name = "albumId")
    val albumId: Long?,
    @ColumnInfo(name = "albumArtist")
    val albumArtist: String?,
    /** Structured album artists; [albumArtist] remains the server's display string. */
    @ColumnInfo(name = "albumArtists")
    val albumArtists: String?,
    /** Local Jellyfin artist IDs aligned one-for-one with [albumArtists]. */
    @ColumnInfo(name = "albumArtistIds")
    val albumArtistIds: String?,
    @ColumnInfo(name = "genre")
    val genre: String?,
    @ColumnInfo(name = "genreId")
    val genreId: Long?,
    @ColumnInfo(name = "albumYear")
    val albumYear: Int?,
    @ColumnInfo(name = "trackNumber")
    val trackNumber: Int?,
    @ColumnInfo(name = "discNumber")
    val discNumber: Int?,
    @ColumnInfo(name = "durationMs")
    val durationMs: Long?,
    @ColumnInfo(name = "addDate")
    val addDate: Long?,
    @ColumnInfo(name = "path")
    val path: String?,
    @ColumnInfo(name = "container")
    val container: String?,
    @ColumnInfo(name = "mediaSourceId")
    val mediaSourceId: String?,
    /** Album GUID plus its image tag, used to rebuild the artwork URL. */
    @ColumnInfo(name = "albumJellyfinId")
    val albumJellyfinId: String?,
    @ColumnInfo(name = "albumImageTag")
    val albumImageTag: String?,
    @ColumnInfo(name = "ownImageTag")
    val ownImageTag: String?,
    @ColumnInfo(name = "playCount")
    val playCount: Int,
    @ColumnInfo(name = "isFavourite")
    val isFavourite: Boolean,
    @ColumnInfo(name = "lastPlayed")
    val lastPlayed: Long?,
)
