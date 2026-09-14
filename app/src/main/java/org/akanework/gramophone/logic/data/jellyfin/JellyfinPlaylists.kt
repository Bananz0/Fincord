package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.delay
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.undashed
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaylistUserPermissions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists that live on the Jellyfin server.
 *
 * The app's own library has no writable playlist store - every song in it belongs to the server -
 * so "add to a playlist" has to mean the server's playlists, or it means nothing the user will see
 * again from any other client.
 *
 * All server operations use the typed Jellyfin SDK. Creation still performs a read-back: a 2xx
 * response alone is not proof that the server retained the supplied items.
 */
object JellyfinPlaylists {

    /** One of the server's playlists, as much of it as a picker needs. */
    data class RemotePlaylist(
        val id: String,
        val name: String,
        val songCount: Int,
        /** Null when the playlist has no artwork of its own, which is the case until it has items. */
        val imageUrl: String?,
    )

    /**
     * Every audio playlist the signed-in user can see, newest last.
     *
     * Returns empty rather than throwing when signed out or unreachable: the picker still has to
     * open and offer "New Playlist", which is the more useful half of it anyway.
     */
    suspend fun list(context: Context? = null): List<RemotePlaylist> {
        val (api, userId) = apiAndUser() ?: return emptyList()
        return runCatching {
            val items = api.libraryApi.getItems(
                userId = userId,
                includeItemTypes = setOf(BaseItemKind.PLAYLIST),
                recursive = true,
                fields = setOf(ItemFields.CHILD_COUNT),
                sortBy = setOf(ItemSortBy.SORT_NAME),
                limit = MAX_PLAYLIST_ITEMS,
            ).content.items
            items.mapNotNull { item ->
                // Empty and older playlists frequently report MediaType as "Unknown". They are
                // still real playlists and must not disappear from the browser because of that.
                val id = item.id.toString().undashed()
                RemotePlaylist(
                    id = id,
                    name = item.name?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                    songCount = item.childCount ?: 0,
                    imageUrl = item.imageTags?.get(ImageType.PRIMARY)
                        ?.let { tag ->
                            api.imageApi.getItemImageUrl(
                                itemId = item.id,
                                imageType = ImageType.PRIMARY,
                                tag = tag,
                                maxWidth = COVER_MAX_WIDTH,
                                quality = COVER_QUALITY,
                            )
                        },
                )
            }.also { playlists -> context?.let { cacheList(it, playlists) } }
        }.getOrElse {
            Log.w(TAG, "Could not load playlist list", it)
            emptyList()
        }
    }

    /**
     * Creates a playlist holding [mediaIds], and returns its id.
     *
     * The songs go in on creation rather than in a second call - the server accepts them there, and
     * a playlist that briefly exists empty is a playlist that shows up wrong if the second call
     * fails.
     */
    suspend fun create(context: Context, name: String, mediaIds: List<String>): String? {
        val (api, userId) = apiAndUser() ?: return null
        val remoteIds = mediaIds.mapNotNull { mediaId ->
            JellyfinItemResolver.remoteIdForMediaId(context, mediaId)?.toSdkUuidOrNull()
        }.distinct()
        if (remoteIds.isEmpty()) {
            Log.w(TAG, "Not creating \"$name\": none of ${mediaIds.size} ids resolved")
            return null
        }
        return runCatching {
            val id = api.playlistApi.createPlaylist(
                CreatePlaylistDto(
                    name = name,
                    ids = remoteIds,
                    userId = userId,
                    mediaType = MediaType.AUDIO,
                    users = listOf(PlaylistUserPermissions(userId = userId, canEdit = true)),
                    isPublic = false,
                )
            ).content.id.takeIf(String::isNotBlank) ?: return null
            if (verifyCreatedPlaylist(api, userId, id, remoteIds.size)) {
                Log.i(TAG, "Created and verified \"$name\" with ${remoteIds.size} items")
                id
            } else {
                Log.w(TAG, "Created \"$name\" as $id, but Jellyfin read-back did not contain its items")
                null
            }
        }.getOrElse {
            Log.w(TAG, "Creating \"$name\" failed", it)
            null
        }
    }

    /** A successful create response is not enough: only report success once Jellyfin reads it back. */
    private suspend fun verifyCreatedPlaylist(
        api: ApiClient,
        userId: UUID,
        playlistId: String,
        expectedCount: Int,
    ): Boolean {
        val id = playlistId.toSdkUuidOrNull() ?: return false
        repeat(3) { attempt ->
            val count = runCatching {
                val result = api.playlistApi.getPlaylistItems(
                    playlistId = id,
                    userId = userId,
                    limit = MAX_PLAYLIST_ITEMS,
                ).content
                result.totalRecordCount.takeIf { it > 0 } ?: result.items.size
            }.getOrNull()
            if (count != null && count >= expectedCount) return true
            if (attempt < 2) delay(PLAYLIST_READ_BACK_DELAY_MS)
        }
        return false
    }

    /** Appends [mediaIds] to an existing playlist. Returns whether the server took them. */
    suspend fun addTo(context: Context, playlistId: String, mediaIds: List<String>): Boolean {
        val (api, userId) = apiAndUser() ?: return false
        val playlistUuid = playlistId.toSdkUuidOrNull() ?: return false
        val remoteIds = mediaIds.mapNotNull { mediaId ->
            JellyfinItemResolver.remoteIdForMediaId(context, mediaId)?.toSdkUuidOrNull()
        }.distinct()
        if (remoteIds.isEmpty()) return false
        return runCatching {
            api.playlistApi.addItemToPlaylist(
                playlistId = playlistUuid,
                ids = remoteIds,
                userId = userId,
            )
            true
        }.getOrElse {
            Log.w(TAG, "Add to $playlistId failed", it)
            false
        }
    }

    /**
     * Replaces an imported playlist after the user explicitly chose exact mirroring.
     *
     * Create and verify the corrected copy before deleting the stale one. If removing the old copy
     * fails, roll the new copy back so a failed replacement never leaves two identically named
     * playlists behind.
     */
    suspend fun replace(
        context: Context,
        playlistId: String,
        name: String,
        mediaIds: List<String>,
    ): String? {
        val replacementId = create(context, name, mediaIds) ?: return null
        if (delete(playlistId)) return replacementId
        Log.w(TAG, "Could not remove stale playlist $playlistId; rolling back $replacementId")
        delete(replacementId)
        return null
    }

    /** Deletes a playlist from Jellyfin. The tracks themselves are never deleted. */
    suspend fun delete(playlistId: String): Boolean {
        val api = JellyfinClientHolder.api() ?: return false
        val id = playlistId.toSdkUuidOrNull() ?: return false
        return runCatching {
            api.libraryApi.deleteItem(id)
            Log.i(TAG, "Deleted playlist ${playlistId.undashed()}")
            true
        }.getOrElse {
            Log.w(TAG, "Deleting playlist failed", it)
            false
        }
    }

    /** Last successful server list, used immediately while a fresh request is in flight. */
    fun cachedList(context: Context): List<RemotePlaylist> {
        val raw = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(CACHE_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                RemotePlaylist(
                    id = item.optString("id").ifBlank { return@mapNotNull null },
                    name = item.optString("name").ifBlank { return@mapNotNull null },
                    songCount = item.optInt("songCount", 0),
                    imageUrl = null,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun cacheList(context: Context, playlists: List<RemotePlaylist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("songCount", playlist.songCount)
            )
        }
        context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CACHE_KEY, array.toString())
            .apply()
    }

    /** Resolves a server playlist, in server order, onto the already loaded playable library. */
    suspend fun items(
        context: Context,
        playlistId: String,
        library: List<MediaItem>,
    ): List<MediaItem> {
        val (api, userId) = apiAndUser() ?: return emptyList()
        val id = playlistId.toSdkUuidOrNull() ?: return emptyList()
        val remoteIds = runCatching {
            api.playlistApi.getPlaylistItems(
                playlistId = id,
                userId = userId,
                limit = MAX_PLAYLIST_ITEMS,
            ).content.items.map { it.id.toString() }
        }.getOrElse {
            Log.w(TAG, "Could not load items for playlist $playlistId", it)
            return emptyList()
        }
        val remoteToLocal = org.akanework.gramophone.logic.data.db.AppDatabase
            .getInstance(context)
            .jellyfinIdDao()
            .getAll()
            .associate { it.jellyfinId.normalizedId() to it.localId }
        val byLocalId = library.associateBy { it.mediaId.toLongOrNull() }
        return remoteIds.mapNotNull { id ->
            remoteToLocal[id.normalizedId()]?.let(byLocalId::get)
        }
    }

    private fun apiAndUser(): Pair<ApiClient, UUID>? {
        val api = JellyfinClientHolder.api() ?: return null
        val userId = JellyfinClientHolder.credentials.userId?.toSdkUuidOrNull() ?: return null
        return api to userId
    }

    private fun String.toSdkUuidOrNull(): UUID? = runCatching {
        UUID.fromString(toDashedUuid())
    }.getOrNull()

    private fun String.normalizedId(): String = replace("-", "").lowercase()

    private const val TAG = "JellyfinPlaylists"
    private const val COVER_MAX_WIDTH = 512
    private const val COVER_QUALITY = 90
    private const val MAX_PLAYLIST_ITEMS = 10_000
    private const val PLAYLIST_READ_BACK_DELAY_MS = 200L
    private const val CACHE_PREFS = "jellyfin_playlist_cache"
    private const val CACHE_KEY = "playlists"
}
