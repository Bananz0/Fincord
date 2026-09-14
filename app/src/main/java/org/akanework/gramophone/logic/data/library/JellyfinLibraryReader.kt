package org.akanework.gramophone.logic.data.library

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinIdMap
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import org.akanework.gramophone.logic.data.jellyfin.JellyfinEndpoints
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.libphonograph.items.Date
import uk.akane.libphonograph.items.Genre
import uk.akane.libphonograph.items.Playlist
import android.net.Uri

/**
 * The Jellyfin library, shaped the way the Accord screens expect it.
 *
 * The sync itself used to live inside the old MainActivity, which meant nothing could reach the
 * library without that activity being alive. It moves here so it belongs to the application and any
 * screen can collect it.
 *
 * Progress is reported through [syncProgress] rather than drawn directly, so whichever UI is on top
 * decides how to show it.
 */
class JellyfinLibraryReader(private val context: Context) : LibraryReader {

    /** null until the first load finishes, so callers can tell "empty" from "not loaded yet". */
    private val store = MutableStateFlow<MediaStoreUtils.LibraryStoreClass?>(null)

    /** `loaded to total` while a server sync runs, null when idle. */
    val syncProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Set when a sync fails and nothing was cached, so the UI can say the server is unreachable. */
    val lastErrorUnreachable = MutableStateFlow(false)

    private val refreshLock = Mutex()
    private var loadedFromCache = false

    init {
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(context)
            val cacheDao = db.cachedSongDao()
            val loader = JellyfinLibraryLoader(api = null, idMap = JellyfinIdMap(db.jellyfinIdDao()))

            val fullCached = runCatching { loader.loadFromCache(cacheDao) }.getOrNull()
            if (fullCached != null) {
                store.value = fullCached
                loadedFromCache = true
            }
        }
    }

    override val songListFlow: Flow<List<MediaItem>> = store.map { it?.songList ?: emptyList() }
    override val albumListFlow: Flow<List<Album>> =
        store.map { s -> s?.albumList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val albumArtistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.albumArtistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val artistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.artistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val primaryArtistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.primaryArtistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val featuredArtistListFlow: Flow<List<Artist>> =
        store.map { s -> s?.featuredArtistList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val genreListFlow: Flow<List<Genre>> =
        store.map { s -> s?.genreList?.map { it.toLibPhonograph() } ?: emptyList() }
    override val dateListFlow: Flow<List<Date>> =
        store.map { s -> s?.dateList?.map { it.toLibPhonograph() } ?: emptyList() }
    /**
     * The server's playlists.
     *
     * The grouper appends a "recently added" pseudo-playlist to every library it builds. Converting
     * it like the rest stripped the type that identifies it, leaving a titleless playlist holding
     * thousands of songs: the list screen drew it as "(Untitled Playlist)", and the detail screen -
     * which matches by title - could never resolve it, so it opened claiming the whole library and
     * showing none of it. It is dropped here; the home screen already has a recently-added row.
     */
    override val playlistListFlow: Flow<List<Playlist>> =
        store.map { s ->
            s?.playlistList
                ?.filterNot { it is MediaStoreUtils.RecentlyAdded }
                ?.map { it.toLibPhonograph() }
                ?: emptyList()
        }

    override suspend fun refresh() = refresh(force = false)

    /**
     * @param force sync from the server even when "sync on startup" is off. An explicit refresh
     *   should always reach the server; a launch should be allowed not to.
     */
    suspend fun refresh(force: Boolean) = refreshLock.withLock {
        // The phone may have moved between home Wi-Fi and mobile data since the last refresh.
        JellyfinEndpoints.selectReachableStoredEndpoint()
        val api = JellyfinClientHolder.api()
        if (api == null) {
            // Signed out. Not an error - the sign-in screen is what gets us back here.
            store.value = store.value ?: EMPTY
            return@withLock
        }
        val db = AppDatabase.getInstance(context)
        val cacheDao = db.cachedSongDao()
        val loader = JellyfinLibraryLoader(api, JellyfinIdMap(db.jellyfinIdDao()))

        // Show the cache first. A full sync of a large library takes the better part of a minute,
        // and there is no reason to stare at an empty library while it runs.
        if (!loadedFromCache) {
            val cached = runCatching { loader.loadFromCache(cacheDao) }
                .onFailure { Log.e(TAG, "Reading library cache failed", it) }
                .getOrNull()
            if (cached != null) {
                store.value = cached
                loadedFromCache = true
                val syncOnStartup = PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("sync_on_startup", false)
                if (!force && !syncOnStartup) {
                    Log.d(TAG, "Skipping startup sync (disabled in settings)")
                    return@withLock
                }
            }
        }

        // An empty Room result means there is nothing useful on screen. Treating the non-null
        // wrapper itself as content disabled onPartial below, so first sign-in stayed blank until
        // the final server page arrived instead of filling as each page was fetched.
        val hadSomething = store.value?.songList?.isNotEmpty() == true

        // With a usable cache, ask the cheap question first: which albums moved? Re-pulling one
        // retagged record should not cost the same as fetching the library from scratch.
        if (hadSomething) {
            val outcome = runCatching {
                loader.syncChangedAlbums(cacheDao, db.albumSyncStateDao())
            }.onFailure { Log.e(TAG, "Incremental sync failed", it) }.getOrNull()

            when (outcome) {
                is JellyfinLibraryLoader.SyncOutcome.UpToDate -> {
                    lastErrorUnreachable.value = false
                    return@withLock
                }

                is JellyfinLibraryLoader.SyncOutcome.Updated -> {
                    store.value = outcome.library
                    lastErrorUnreachable.value = false
                    // Embedded lyrics and tags are read off the decoded stream at playback, not
                    // from anything the app caches separately, so the stale copy is the audio
                    // itself. Dropping it is what makes a retag actually reach the player.
                    JellyfinMediaCache.evictStale(context, outcome.staleStreamKeys)
                    Log.d(TAG, "Incremental sync updated ${outcome.changedAlbumIds.size} albums")
                    return@withLock
                }

                // Null, or a probe that could not be trusted, falls through to the full sync.
                else -> Unit
            }
        }

        syncProgress.value = 0 to 0
        val synced = runCatching {
            loader.load(
                dao = cacheDao,
                onProgress = { loaded, total -> syncProgress.value = loaded to total },
                // Only with nothing already on screen. A partial is by definition smaller than the
                // finished library, so publishing one over a cache that is already complete would
                // make albums vanish and come back while a routine refresh runs. With nothing
                // cached - a first sign-in - it is the difference between listening after the
                // first page and waiting out every one of them.
                onPartial = if (hadSomething) null else {
                    { partial -> store.value = partial }
                },
            )
        }.onFailure { Log.e(TAG, "Jellyfin library sync failed", it) }.getOrNull()
        syncProgress.value = null

        if (synced != null) {
            store.value = synced
            lastErrorUnreachable.value = false
            // Recorded after the tracks are cached, never before: state saved for a sync that then
            // failed would claim albums were current whose rows were never written, and the next
            // refresh would skip exactly the albums it should have fetched.
            runCatching { loader.recordAlbumStates(db.albumSyncStateDao()) }
                .onFailure { Log.w(TAG, "Could not record album sync state", it) }
        } else {
            // With a library already on screen a failed refresh is not worth shouting about: the
            // user has something usable and the next launch tries again.
            lastErrorUnreachable.value = !hadSomething
            store.value = store.value ?: EMPTY
        }
    }

    companion object {
        private const val TAG = "JellyfinLibraryReader"

        private val EMPTY = MediaStoreUtils.LibraryStoreClass(
            mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(), mutableListOf(),
            mutableListOf(), mutableListOf(),
            MediaStoreUtils.FileNode(""), MediaStoreUtils.FileNode(""), emptySet()
        )
    }
}

// --- Model bridge -------------------------------------------------------------------------------
//
// This app and libPhonograph describe a library with near-identical types that share no common
// supertype, so the Accord screens cannot read this app's model directly. These map one to the
// other. Where libPhonograph carries a field Jellyfin has no answer for - a file's add and modify
// timestamps - null is passed rather than inventing a value.

private class JellyfinAlbum(
    override val id: Long?,
    override val title: String?,
    override val songList: List<MediaItem>,
    override val albumArtist: String?,
    override val albumArtistId: Long?,
    override val albumYear: Int?,
    override val cover: Uri?
) : Album {
    override val albumAddDate: Long? = null
    override val albumModifiedDate: Long? = null
}

internal fun MediaStoreUtils.Album.toLibPhonograph(): Album = JellyfinAlbum(
    id = id,
    title = title,
    songList = songList,
    albumArtist = artist,
    albumArtistId = artistId,
    albumYear = albumYear,
    cover = cover
)

internal fun MediaStoreUtils.Artist.toLibPhonograph(): Artist = Artist(
    id = id,
    title = title,
    songList = songList,
    albumList = albumList.map { it.toLibPhonograph() }
)

internal fun MediaStoreUtils.Genre.toLibPhonograph(): Genre = Genre(id, title, songList)

internal fun MediaStoreUtils.Date.toLibPhonograph(): Date = Date(id, title, songList)

internal fun MediaStoreUtils.Playlist.toLibPhonograph(): Playlist = Playlist(
    id = id,
    title = title,
    // Server playlists have no file behind them and no gapless information to report.
    path = null,
    dateAdded = null,
    dateModified = null,
    hasGaps = false,
    songList = songList
)
