package org.akanework.gramophone.logic.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.akanework.gramophone.logic.data.db.dao.AlbumSyncStateDao
import org.akanework.gramophone.logic.data.db.dao.AnalysedTrackDao
import org.akanework.gramophone.logic.data.db.dao.CachedSongDao
import org.akanework.gramophone.logic.data.db.dao.ImportContributionDao
import org.akanework.gramophone.logic.data.db.dao.JellyfinIdDao
import org.akanework.gramophone.logic.data.db.dao.LyricsDao
import org.akanework.gramophone.logic.data.db.dao.MediaItemDao
import org.akanework.gramophone.logic.data.db.dao.OwnPlayDao
import org.akanework.gramophone.logic.data.db.dao.PendingScrobbleDao
import org.akanework.gramophone.logic.data.db.dao.PlaylistDao
import org.akanework.gramophone.logic.data.db.entity.ALBUM_SYNC_STATE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.ANALYSED_TRACK_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.AlbumSyncState
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import org.akanework.gramophone.logic.data.db.entity.CACHED_SONG_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.CachedSong
import org.akanework.gramophone.logic.data.db.entity.IMPORT_CONTRIBUTION_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.ImportContribution
import org.akanework.gramophone.logic.data.db.entity.JELLYFIN_ID_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.JellyfinId
import org.akanework.gramophone.logic.data.db.entity.LYRICS_INDEX_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.LYRICS_STATE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.LyricsIndex
import org.akanework.gramophone.logic.data.db.entity.LyricsState
import org.akanework.gramophone.logic.data.db.entity.MediaItem
import org.akanework.gramophone.logic.data.db.entity.OWN_PLAY_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.OwnPlay
import org.akanework.gramophone.logic.data.db.entity.PENDING_SCROBBLE_TABLE_NAME
import org.akanework.gramophone.logic.data.db.entity.PendingScrobble
import org.akanework.gramophone.logic.data.db.entity.Playlist
import org.akanework.gramophone.logic.data.db.entity.PlaylistMediaItemCrossRef

const val APP_DATABASE_FILE_NAME = "app.db"

@Database(
    entities = [
        Playlist::class,
        MediaItem::class,
        PlaylistMediaItemCrossRef::class,
        JellyfinId::class,
        CachedSong::class,
        PendingScrobble::class,
        AlbumSyncState::class,
        ImportContribution::class,
        OwnPlay::class,
        LyricsIndex::class,
        LyricsState::class,
        AnalysedTrack::class,
    ],
    version = 11,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun jellyfinIdDao(): JellyfinIdDao
    abstract fun cachedSongDao(): CachedSongDao
    abstract fun pendingScrobbleDao(): PendingScrobbleDao
    abstract fun albumSyncStateDao(): AlbumSyncStateDao
    abstract fun importContributionDao(): ImportContributionDao
    abstract fun ownPlayDao(): OwnPlayDao
    abstract fun lyricsDao(): LyricsDao
    abstract fun analysedTrackDao(): AnalysedTrackDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Adds the Jellyfin GUID interning table. This is a plain additive migration - destructive
         * fallback is not an option here, because dropping the table would invalidate every ID
         * already written into the saved playback queue and the user's private playlists.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$JELLYFIN_ID_TABLE_NAME` (" +
                            "`${JellyfinId.LOCAL_ID_COLUMN}` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`${JellyfinId.JELLYFIN_ID_COLUMN}` TEXT NOT NULL, " +
                            "`${JellyfinId.ITEM_TYPE_COLUMN}` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_${JELLYFIN_ID_TABLE_NAME}_${JellyfinId.JELLYFIN_ID_COLUMN}` " +
                            "ON `$JELLYFIN_ID_TABLE_NAME` (`${JellyfinId.JELLYFIN_ID_COLUMN}`)"
                )
            }
        }

        /**
         * Adds the library metadata cache. Purely additive; the table starts empty and the next
         * sync fills it.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$CACHED_SONG_TABLE_NAME` (" +
                            "`localId` INTEGER PRIMARY KEY NOT NULL, " +
                            "`jellyfinId` TEXT NOT NULL, " +
                            "`title` TEXT, `artist` TEXT, `artistId` INTEGER, " +
                            "`album` TEXT, `albumId` INTEGER, `albumArtist` TEXT, " +
                            "`genre` TEXT, `genreId` INTEGER, `albumYear` INTEGER, " +
                            "`trackNumber` INTEGER, `discNumber` INTEGER, " +
                            "`durationMs` INTEGER, `addDate` INTEGER, `path` TEXT, " +
                            "`container` TEXT, `mediaSourceId` TEXT, " +
                            "`albumJellyfinId` TEXT, `albumImageTag` TEXT, `ownImageTag` TEXT, " +
                            "`playCount` INTEGER NOT NULL, `isFavourite` INTEGER NOT NULL, " +
                            "`lastPlayed` INTEGER)"
                )
            }
        }

        /**
         * Adds the offline scrobble queue. Additive; it starts empty.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$PENDING_SCROBBLE_TABLE_NAME` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`artist` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`album` TEXT, `albumArtist` TEXT, " +
                            "`durationSeconds` INTEGER, `trackNumber` INTEGER, " +
                            "`timestampSeconds` INTEGER NOT NULL)"
                )
            }
        }

        /** Retains Jellyfin's complete track credit instead of collapsing it to the first artist. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `$CACHED_SONG_TABLE_NAME` ADD COLUMN `trackArtists` TEXT")
            }
        }

        /**
         * Adds the per-album sync state used to re-pull only what changed on the server.
         *
         * Additive and deliberately starts empty: with no recorded state every album reads as
         * dirty, so the first sync after upgrading is a full one and fills the table. That is the
         * correct answer rather than a cost, because nothing here knows what the server looked
         * like when the existing cache was written.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_ALBUM_SYNC_STATE)
            }
        }

        /**
         * Renames the album probe's second column, which was named for `DateLastSaved` but only
         * ever held `DateCreated` - the SDK exposes no property for the former, so nothing could
         * read it even when the server was asked.
         *
         * Recreated rather than altered. This table is derived state: emptying it makes the next
         * refresh a full sync, which repopulates it correctly, so there is nothing to preserve and
         * no reason to write a column-copying migration for it.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `$ALBUM_SYNC_STATE_TABLE_NAME`")
                db.execSQL(CREATE_ALBUM_SYNC_STATE)
            }
        }

        /**
         * Adds the play-count import ledger and the log of plays this app reported itself.
         *
         * Purely additive, and both tables start empty: with no contributions recorded, the first
         * import treats every count Jellyfin holds as baseline, which is exactly right - none of
         * it came from an import.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_IMPORT_CONTRIBUTION)
                db.execSQL(CREATE_IMPORT_CONTRIBUTION_SOURCE_INDEX)
                db.execSQL(CREATE_OWN_PLAY)
                db.execSQL(CREATE_OWN_PLAY_TIME_INDEX)
                db.execSQL(CREATE_OWN_PLAY_ITEM_INDEX)
            }
        }

        /**
         * Adds the searchable lyric index.
         *
         * Additive and empty. The index is derived from the server, so there is nothing to migrate
         * and nothing lost by rebuilding it - the background pass fills it when the phone is next
         * charging on wifi.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_LYRICS_INDEX)
                db.execSQL(CREATE_LYRICS_STATE)
            }
        }

        /** Preserves Jellyfin artist identity instead of reconstructing credits from display text. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `$CACHED_SONG_TABLE_NAME` ADD COLUMN `trackArtistIds` TEXT")
                db.execSQL("ALTER TABLE `$CACHED_SONG_TABLE_NAME` ADD COLUMN `albumArtists` TEXT")
                db.execSQL("ALTER TABLE `$CACHED_SONG_TABLE_NAME` ADD COLUMN `albumArtistIds` TEXT")
                // The old cache cannot manufacture the missing GUIDs from display strings. Mark
                // the derived album probe stale so the next requested sync repopulates every row
                // from Jellyfin instead of declaring the legacy rows up to date forever.
                db.execSQL("DELETE FROM `$ALBUM_SYNC_STATE_TABLE_NAME`")
            }
        }

        /**
         * Adds the Automix beat and key analysis cache.
         *
         * Additive and empty. Every row is derived from audio the phone can decode again, so an
         * empty table costs one re-analysis of the next track rather than anything lost - and each
         * row carries the version of the analyser that wrote it, so a later change to the DSP
         * invalidates rows without needing a migration at all.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_ANALYSED_TRACK)
            }
        }

        private const val CREATE_ANALYSED_TRACK =
            "CREATE TABLE IF NOT EXISTS `$ANALYSED_TRACK_TABLE_NAME` (" +
                    "`jellyfinId` TEXT NOT NULL, " +
                    "`bpm` REAL NOT NULL, " +
                    "`tempoConfidence` REAL NOT NULL, " +
                    "`beatsMs` BLOB NOT NULL, " +
                    "`beatsPerBar` INTEGER NOT NULL, " +
                    "`downbeatIndex` INTEGER NOT NULL, " +
                    "`downbeatConfidence` REAL NOT NULL, " +
                    "`keyPitchClass` INTEGER NOT NULL, " +
                    "`keyIsMajor` INTEGER NOT NULL, " +
                    "`keyStrength` REAL NOT NULL, " +
                    "`analysedSeconds` REAL NOT NULL, " +
                    "`analysedAt` INTEGER NOT NULL, " +
                    "`analyserVersion` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`jellyfinId`))"

        /**
         * The virtual table Room generates for an @Fts4 entity. Written out by hand here because a
         * migration runs raw SQL, and it has to match what Room expects byte for byte or the
         * identity check on the next open fails.
         */
        private const val CREATE_LYRICS_INDEX =
            "CREATE VIRTUAL TABLE IF NOT EXISTS `$LYRICS_INDEX_TABLE_NAME` " +
                    // No tokenize clause: simple is the default and Room does not emit one, so
                    // stating it makes the table differ from the entity and fails validation.
                    "USING FTS4(`jellyfinId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "notindexed=`jellyfinId`)"

        private const val CREATE_LYRICS_STATE =
            "CREATE TABLE IF NOT EXISTS `$LYRICS_STATE_TABLE_NAME` (" +
                    "`jellyfinId` TEXT NOT NULL, " +
                    "`hasLyrics` INTEGER NOT NULL, " +
                    "`fetchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`jellyfinId`))"

        private const val CREATE_IMPORT_CONTRIBUTION =
            "CREATE TABLE IF NOT EXISTS `$IMPORT_CONTRIBUTION_TABLE_NAME` (" +
                    "`jellyfinId` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`count` INTEGER NOT NULL, " +
                    "`throughSeconds` INTEGER NOT NULL, " +
                    "`importedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`jellyfinId`, `source`))"

        private const val CREATE_IMPORT_CONTRIBUTION_SOURCE_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_${IMPORT_CONTRIBUTION_TABLE_NAME}_source` " +
                    "ON `$IMPORT_CONTRIBUTION_TABLE_NAME` (`source`)"

        private const val CREATE_OWN_PLAY =
            "CREATE TABLE IF NOT EXISTS `$OWN_PLAY_TABLE_NAME` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`jellyfinId` TEXT NOT NULL, " +
                    "`trackKey` TEXT NOT NULL, " +
                    "`playedAtSeconds` INTEGER NOT NULL)"

        private const val CREATE_OWN_PLAY_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_${OWN_PLAY_TABLE_NAME}_playedAtSeconds` " +
                    "ON `$OWN_PLAY_TABLE_NAME` (`playedAtSeconds`)"

        private const val CREATE_OWN_PLAY_ITEM_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_${OWN_PLAY_TABLE_NAME}_jellyfinId` " +
                    "ON `$OWN_PLAY_TABLE_NAME` (`jellyfinId`)"

        private const val CREATE_ALBUM_SYNC_STATE =
            "CREATE TABLE IF NOT EXISTS `$ALBUM_SYNC_STATE_TABLE_NAME` (" +
                    "`albumJellyfinId` TEXT PRIMARY KEY NOT NULL, " +
                    "`dateLastMediaAdded` INTEGER, " +
                    "`dateCreated` INTEGER, " +
                    "`etag` TEXT)"

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    APP_DATABASE_FILE_NAME
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11,
                    )
                    .build()
                    .apply { instance = this }
            }
        }
    }
}
