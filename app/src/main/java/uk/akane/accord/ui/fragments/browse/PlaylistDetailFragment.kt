package uk.akane.accord.ui.fragments.browse

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import org.akanework.gramophone.logic.data.jellyfin.QueuePrefetcher
import coil3.imageLoader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.getFile
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.browse.PlaylistAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import uk.akane.libphonograph.dynamicitem.Favorite
import uk.akane.libphonograph.items.Playlist
import uk.akane.libphonograph.manipulator.ItemManipulator
import uk.akane.libphonograph.manipulator.PlaylistSerializer
import java.io.File
import kotlin.random.Random
import uk.akane.accord.ui.components.CollectionPopupMenu
import uk.akane.accord.ui.components.CollageArtView
import uk.akane.accord.ui.components.NoToast as Toast
import uk.akane.accord.ui.components.TrackRowMenu
import uk.akane.accord.ui.components.TrackSwipeActions
import uk.akane.accord.ui.components.performPressHaptic
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinItemResolver
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.lastfm.LastFmLovedLibrary

class PlaylistDetailFragment : SwitcherPostponeFragment() {

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var navigationBar: NavigationBar
    private lateinit var contentRecycler: RecyclerView
    private var didLoadOnce = false
    private var shouldRefreshSuggestions = true
    private var currentPlaylist: Playlist? = null
    private var targetPlaylistId: Long = NO_ID
    private var headerTitle = ""
    private var headerSubtitle = ""
    private var allSongs: List<MediaItem> = emptyList()
    private var suggestedSongs: List<MediaItem> = emptyList()
    private var playlistSongs: MutableList<MediaItem> = mutableListOf()
    private var isFavoriteTarget = false
    private var remotePlaylistId: String? = null
    private var remoteArtworkUri: Uri? = null
    private var collectionDownloaded = false
    private val suggestedAdapter = SuggestedSongAdapter { song ->
        addSuggestedToPlaylist(song)
    }
    private val playlistSongsAdapter = PlaylistSongAdapter()
    private val headerAdapter = HeaderAdapter()
    private val footerAdapter = FooterAdapter()
    private lateinit var concatAdapter: ConcatAdapter

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_playlist, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        contentRecycler = rootView.findViewById(R.id.rvContent)
        targetPlaylistId = requireArguments().getLong(ARG_ID, NO_ID)
        remotePlaylistId = requireArguments().getString(ARG_REMOTE_ID)
        remoteArtworkUri = requireArguments().getString(ARG_ARTWORK_URI)?.toUri()

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        navigationBar.attach(contentRecycler, applyTopPadding = false)
        // The list also carries a header, a footer and the suggestions, so the swipe has to
        // ask what a row actually is before acting on it.
        TrackSwipeActions.attach(
            recyclerView = contentRecycler,
            activity = activity,
            trackAt = { index -> trackAtAdapterPosition(index) },
            trailing = TrackSwipeActions.Trailing(
                iconRes = R.drawable.ic_trash,
                colorRes = R.color.swipeDestructive,
                onAction = { index ->
                    trackAtAdapterPosition(index)?.let { removeFromPlaylist(it) }
                },
            ),
        )
        // The three dots on this screen did nothing whatsoever.
        navigationBar.setMenuEntries(
            entries = {
                CollectionPopupMenu.build(
                    resources,
                    withArtist = true,
                    withAlbum = true,
                    // Only a real playlist can be renamed or deleted; Favourites and
                    // Recently Added are generated and have nothing to edit.
                    withPlaylistManagement = isEditablePlaylist(),
                    withDownload = !collectionDownloaded,
                    withRemoveDownload = collectionDownloaded,
                    withServerDelete = remotePlaylistId != null,
                )
            },
            onClick = { entry ->
                when (CollectionPopupMenu.actionOf(entry)) {
                    CollectionPopupMenu.Action.RENAME -> promptRename()
                    CollectionPopupMenu.Action.CHANGE_PICTURE -> playlistCoverPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                    CollectionPopupMenu.Action.DELETE -> promptDelete()
                    CollectionPopupMenu.Action.DELETE_FROM_SERVER -> promptDeleteFromServer()
                    else -> CollectionPopupMenu.handle(
                        activity, entry, playlistSongs.toList(), headerTitle
                    )
                }
            },
        )

        concatAdapter = ConcatAdapter(headerAdapter, playlistSongsAdapter, footerAdapter)
        contentRecycler.layoutManager = LinearLayoutManager(requireContext())
        contentRecycler.adapter = concatAdapter

        val fallbackTitle = requireArguments().getString(ARG_TITLE).orEmpty()
        headerTitle = fallbackTitle
        headerSubtitle = getString(R.string.library_head_playlist)
        isFavoriteTarget =
            targetPlaylistId == NO_ID && headerTitle == PlaylistAdapter.FAVORITE_PLAYLIST_TITLE
        val initialCount = requireArguments().getInt(ARG_COUNT, 0)
        headerAdapter.update(
            headerTitle,
            headerSubtitle,
            resources.getString(R.string.artist_song_count, initialCount)
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                activity.reader.playlistListFlow.collectLatest { playlists ->
                    if (remotePlaylistId == null) {
                        resolvePlaylist(playlists)?.let { updateFromPlaylist(it) }
                    }
                    if (remotePlaylistId == null && !didLoadOnce) {
                        didLoadOnce = true
                        notifyContentLoaded()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                activity.reader.songListFlow.collectLatest { songs ->
                    allSongs = songs
                    val remoteId = remotePlaylistId
                    if (remoteId != null) {
                        val remoteSongs = withContext(Dispatchers.IO) {
                            JellyfinPlaylists.items(requireContext(), remoteId, songs)
                        }
                        applyPlaylistSongs(remoteSongs)
                        if (!didLoadOnce) {
                            didLoadOnce = true
                            notifyContentLoaded()
                        }
                    } else if (isFavoriteTarget) {
                        updateFromFavoriteSongs(songs)
                        LastFmLovedLibrary.refreshKeys(requireContext())
                        updateFromFavoriteSongs(songs)
                    }
                    refreshSuggestions()
                }
            }
        }

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
        if (!hidden) refreshDownloadState()
    }

    /**
     * Takes a track back out of the playlist being viewed.
     *
     * Local playlists are the only ones this can edit - a Jellyfin playlist is the server's,
     * and removing from one needs a call this app does not make yet - so the list is updated
     * in place and the user is told when it cannot be made to stick.
     */
    /**
     * Which track a row in the combined list is showing, or null when it is not a track.
     *
     * The list is a header, then the playlist's songs, then a footer with the suggestions, so a
     * position has to be offset by the header before it means anything.
     */
    private fun trackAtAdapterPosition(position: Int): MediaItem? {
        val index = position - headerAdapter.itemCount
        return playlistSongs.getOrNull(index)
    }

    private fun removeFromPlaylist(item: MediaItem) {
        val index = playlistSongs.indexOfFirst { it.mediaId == item.mediaId }
        if (index < 0) return
        playlistSongs.removeAt(index)
        applyPlaylistSongs(playlistSongs.toList())
        Toast.makeText(
            requireContext(),
            getString(R.string.removed_from_playlist, headerTitle),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Generated playlists - Favourites and the like - have no file to rename or delete. */
    private fun isEditablePlaylist(): Boolean {
        val playlist = currentPlaylist ?: return false
        return playlist !is Favorite && playlist.id != null && playlist.path != null
    }

    private val playlistCoverPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) setPlaylistCover(uri) }

    /**
     * Remembers a cover for this playlist.
     *
     * MediaStore playlists have no artwork of their own, so the choice is kept alongside the
     * others the list screen already reads, keyed by title.
     */
    private fun setPlaylistCover(uri: android.net.Uri) {
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        requireContext()
            .getSharedPreferences(PlaylistAdapter.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(PlaylistAdapter.coverKeyFor(headerTitle), uri.toString())
            .apply()
        headerAdapter.update(
            headerTitle,
            headerSubtitle,
            resources.getString(R.string.artist_song_count, playlistSongs.size)
        )
        Toast.makeText(requireContext(), R.string.playlist_cover_updated, Toast.LENGTH_SHORT)
            .show()
    }

    private fun promptRename() {
        val input = android.widget.EditText(requireContext()).apply {
            setText(headerTitle)
            setSingleLine()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_rename)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.playlist_rename_confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty() && name != headerTitle) renamePlaylist(name)
            }
            .show()
    }

    private fun renamePlaylist(newName: String) {
        val file = currentPlaylist?.path
        if (file == null) {
            Toast.makeText(requireContext(), R.string.playlist_rename_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val oldTitle = headerTitle
        val renamed = runCatching {
            ItemManipulator.renamePlaylist(requireContext(), file, newName)
        }.isSuccess
        if (!renamed) {
            Toast.makeText(requireContext(), R.string.playlist_rename_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        // The cover is filed under the title, so it has to move with it or the playlist
        // silently loses its picture.
        val prefs = requireContext()
            .getSharedPreferences(PlaylistAdapter.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.getString(PlaylistAdapter.coverKeyFor(oldTitle), null)?.let { cover ->
            prefs.edit()
                .remove(PlaylistAdapter.coverKeyFor(oldTitle))
                .putString(PlaylistAdapter.coverKeyFor(newName), cover)
                .apply()
        }
        headerTitle = newName
        navigationBar.setTitle(newName)
        headerAdapter.update(
            newName,
            headerSubtitle,
            resources.getString(R.string.artist_song_count, playlistSongs.size)
        )
        activity.updateLibrary()
    }

    private fun promptDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.playlist_delete_title, headerTitle))
            .setMessage(R.string.playlist_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.playlist_delete_confirm) { _, _ -> deletePlaylist() }
            .show()
    }

    private fun deletePlaylist() {
        val id = currentPlaylist?.id
        if (id == null) {
            Toast.makeText(requireContext(), R.string.playlist_delete_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        val request = runCatching { ItemManipulator.deletePlaylist(requireContext(), id) }
            .getOrNull()
        if (request == null) {
            Toast.makeText(requireContext(), R.string.playlist_delete_failed, Toast.LENGTH_SHORT)
                .show()
            return
        }
        requireContext()
            .getSharedPreferences(PlaylistAdapter.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(PlaylistAdapter.coverKeyFor(headerTitle))
            .apply()
        activity.updateLibrary()
        activity.fragmentSwitcherView.popBackTopFragmentIfExists()
    }

    private fun promptDeleteFromServer() {
        if (remotePlaylistId == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.collection_delete_server_title, headerTitle))
            .setMessage(R.string.collection_delete_server_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.playlist_delete_confirm) { _, _ ->
                val remoteId = remotePlaylistId ?: return@setPositiveButton
                viewLifecycleOwner.lifecycleScope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        JellyfinPlaylists.delete(remoteId)
                    }
                    if (!isAdded) return@launch
                    if (deleted) {
                        Toast.makeText(
                            requireContext(),
                            R.string.collection_deleted_from_server,
                            Toast.LENGTH_SHORT
                        ).show()
                        activity.updateLibrary()
                        activity.fragmentSwitcherView.popBackTopFragmentIfExists()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            R.string.collection_delete_server_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun resolvePlaylist(playlists: List<Playlist>): Playlist? {
        val targetId = requireArguments().getLong(ARG_ID, NO_ID)
        val targetTitle = requireArguments().getString(ARG_TITLE).orEmpty()
        val wantsFavorite = targetId == NO_ID && targetTitle == PlaylistAdapter.FAVORITE_PLAYLIST_TITLE
        return playlists.firstOrNull { playlist ->
            (targetId != NO_ID && playlist.id == targetId) ||
                (targetId == NO_ID && playlist.title == targetTitle) ||
                (wantsFavorite && playlist is Favorite)
        }
    }

    private fun updateFromPlaylist(playlist: Playlist) {
        currentPlaylist = playlist
        remotePlaylistId = null
        if (playlist.path == null) {
            playlist.id?.let { localId ->
                viewLifecycleOwner.lifecycleScope.launch {
                    remotePlaylistId = withContext(Dispatchers.IO) {
                        JellyfinItemResolver.remoteId(requireContext(), localId)
                    }
                }
            }
        }
        val title = playlist.title?.takeIf { it.isNotBlank() }
            ?: requireArguments().getString(ARG_TITLE).orEmpty()
        headerTitle = title
        if (playlist is Favorite || isFavoriteTarget) {
            updateFromFavoriteSongs(allSongs)
            return
        }
        applyPlaylistSongs(playlist.songList)
    }

    private fun updateFromFavoriteSongs(songs: List<MediaItem>) {
        val favoriteKeys = PlaylistAdapter.loadFavoriteKeys(requireContext())
        val lovedKeys = LastFmLovedLibrary.cachedKeys(requireContext())
        val ordered = songs.filter { song ->
            buildSongKey(song) in favoriteKeys ||
                song.mediaMetadata.extras?.getBoolean(
                    JellyfinLibraryLoader.EXTRA_IS_FAVOURITE,
                    false,
                ) == true ||
                LastFmLovedLibrary.isLoved(song, lovedKeys)
        }
        applyPlaylistSongs(ordered)
    }

    private fun applyPlaylistSongs(songs: List<MediaItem>) {
        val merged = mergePlaylistSongs(songs)
        playlistSongs = orderPlaylistSongs(merged)
        playlistSongsAdapter.submitList(ArrayList(playlistSongs))
        QueuePrefetcher.warmArtwork(requireContext(), playlistSongs, requireContext().imageLoader)
        refreshDownloadState(playlistSongs)
        headerAdapter.update(
            headerTitle,
            headerSubtitle,
            resources.getString(R.string.artist_song_count, playlistSongs.size)
        )
        if (suggestedSongs.isNotEmpty()) {
            val playlistKeys = playlistSongs.map { buildSongKey(it) }.toSet()
            val filtered = suggestedSongs.filter { buildSongKey(it) !in playlistKeys }
            if (filtered.size != suggestedSongs.size) {
                suggestedSongs = filtered
                suggestedAdapter.submitList(filtered)
            }
        }
        if (suggestedSongs.isEmpty() || shouldRefreshSuggestions) {
            refreshSuggestions(force = true)
        }
    }

    private fun refreshDownloadState(tracks: List<MediaItem> = playlistSongs) {
        if (!isAdded || tracks.isEmpty()) {
            collectionDownloaded = false
            return
        }
        val expectedIds = tracks.map(MediaItem::mediaId)
        viewLifecycleOwner.lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.areAllDownloaded(requireContext(), tracks)
            }
            if (playlistSongs.map(MediaItem::mediaId) == expectedIds) {
                collectionDownloaded = downloaded
            }
        }
    }

    private fun orderPlaylistSongs(songs: List<MediaItem>): MutableList<MediaItem> {
        if (songs.isEmpty()) return songs.toMutableList()
        return if (isFavoriteTarget || remotePlaylistId != null) {
            songs.toMutableList()
        } else {
            songs.asReversed().toMutableList()
        }
    }

    private fun refreshSuggestions(force: Boolean = false) {
        if (!force && !shouldRefreshSuggestions && suggestedSongs.isNotEmpty()) return
        if (allSongs.isEmpty()) {
            suggestedSongs = emptyList()
            suggestedAdapter.submitList(emptyList())
            shouldRefreshSuggestions = false
            return
        }

        val playlistKeys = playlistSongs
            .map { buildSongKey(it) }
            .toSet()
        val candidates = allSongs.filter { buildSongKey(it) !in playlistKeys }
        val newSuggestions = candidates
            .shuffled(Random(System.currentTimeMillis()))
            .take(SUGGESTED_COUNT)

        suggestedSongs = newSuggestions
        suggestedAdapter.submitList(newSuggestions)
        shouldRefreshSuggestions = false
    }

    private fun addSuggestedToPlaylist(song: MediaItem) {
        val songKey = buildSongKey(song)
        if (playlistSongs.any { buildSongKey(it) == songKey }) return
        if (currentPlaylist is Favorite || isFavoriteTarget) {
            addSuggestedToFavorites(song)
            return
        }

        remotePlaylistId?.let { remoteId ->
            viewLifecycleOwner.lifecycleScope.launch {
                val added = withContext(Dispatchers.IO) {
                    JellyfinPlaylists.addTo(requireContext(), remoteId, listOf(song.mediaId))
                }
                if (added) updateAfterSuggestionAdded(song)
            }
            return
        }

        val playlist = currentPlaylist
        val rawPlaylistId = playlist?.id ?: targetPlaylistId.takeIf { it != NO_ID }
        val playlistId = rawPlaylistId?.takeIf { it >= 0 }
        val playlistPath = playlist?.path ?: playlistId?.let { lookupPlaylistPath(it) }
        if (playlistId == null && playlistPath == null) return

        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                addSongToPlaylist(playlistId, playlistPath, song)
            }
            if (!added) return@launch
            updateAfterSuggestionAdded(song)
            activity.updateLibrary()
        }
    }

    private fun addSuggestedToFavorites(song: MediaItem) {
        val key = buildSongKey(song)
        val keys = PlaylistAdapter.loadFavoriteKeys(requireContext())
        if (keys.contains(key)) return
        keys.add(0, key)
        PlaylistAdapter.saveFavoriteKeys(requireContext(), keys)
        updateAfterSuggestionAdded(song)
        activity.updateLibrary()
    }

    private fun addSongToPlaylist(
        playlistId: Long?,
        playlistPath: File?,
        song: MediaItem
    ): Boolean {
        val outFile = playlistPath?.takeIf { it.exists() }
        if (outFile != null) {
            if (PlaylistSerializer.isAppPrivatePlaylist(requireContext(), outFile)) {
                val mediaId = parseMediaStoreId(song) ?: return false
                return addToPrivatePlaylist(outFile, mediaId)
            }
            val songFile = song.getFile() ?: return false
            return runCatching {
                ItemManipulator.addToPlaylist(requireContext(), outFile, listOf(songFile))
                true
            }.getOrDefault(false)
        }

        val resolvedPlaylistId = playlistId ?: return false
        val audioId = parseMediaStoreId(song) ?: return false
        val resolver = requireContext().contentResolver
        val membersUri =
            "content://media/external/audio/playlists/$resolvedPlaylistId/members".toUri()
        val playOrder = queryNextPlayOrder(resolver, membersUri, playlistSongs.size)

        val values = ContentValues().apply {
            put("audio_id", audioId)
            put("play_order", playOrder)
        }
        return runCatching {
            resolver.insert(membersUri, values) != null
        }.getOrDefault(false)
    }

    private fun addToPrivatePlaylist(outFile: File, mediaId: Long): Boolean {
        return runCatching {
            val existing = PlaylistSerializer.readPrivatePlaylistEntries(outFile)
            val entry = "${PlaylistSerializer.ACCORD_ID_PREFIX}$mediaId"
            if (existing.contains(entry)) return@runCatching true
            PlaylistSerializer.writePrivatePlaylistEntries(outFile, existing + entry)
            true
        }.getOrDefault(false)
    }

    private fun lookupPlaylistPath(playlistId: Long): File? {
        if (playlistId == NO_ID) return null
        val resolver = requireContext().contentResolver
        val playlistsUri = "content://media/external/audio/playlists".toUri()
        resolver.query(
            playlistsUri,
            arrayOf("_data"),
            "_id = ?",
            arrayOf(playlistId.toString()),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex("_data")
            if (index < 0) return null
            val path = cursor.getString(index)?.trim().orEmpty()
            if (path.isBlank()) return null
            return File(path)
        }
        return null
    }

    private fun updateAfterSuggestionAdded(song: MediaItem) {
        val songKey = buildSongKey(song)
        if (playlistSongs.none { buildSongKey(it) == songKey }) {
            playlistSongs.add(0, song)
            playlistSongsAdapter.submitList(ArrayList(playlistSongs))
            headerAdapter.update(
                headerTitle,
                headerSubtitle,
                resources.getString(R.string.artist_song_count, playlistSongs.size)
            )
        }

        val updatedSuggestions = suggestedSongs
            .filter { buildSongKey(it) != songKey }
            .toMutableList()

        if (updatedSuggestions.size < SUGGESTED_COUNT && allSongs.isNotEmpty()) {
            val excludeKeys = (playlistSongs.map { buildSongKey(it) } +
                updatedSuggestions.map { buildSongKey(it) }).toSet()
            val replacement = allSongs
                .filter { buildSongKey(it) !in excludeKeys }
                .shuffled(Random(System.currentTimeMillis()))
                .firstOrNull()
            if (replacement != null) {
                updatedSuggestions.add(replacement)
            }
        }

        suggestedSongs = updatedSuggestions
        suggestedAdapter.submitList(ArrayList(updatedSuggestions))
        shouldRefreshSuggestions = false
    }

    private fun parseMediaStoreId(item: MediaItem): Long? {
        val mediaId = item.mediaId
        val prefix = "MediaStore:"
        if (mediaId.startsWith(prefix)) {
            return mediaId.removePrefix(prefix).toLongOrNull()
        }
        return null
    }

    private fun queryNextPlayOrder(
        resolver: android.content.ContentResolver,
        membersUri: Uri,
        fallback: Int
    ): Int {
        val column = "play_order"
        resolver.query(
            membersUri,
            arrayOf(column),
            null,
            null,
            "$column DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0) {
                    return cursor.getInt(index) + 1
                }
            }
        }
        return fallback
    }

    private fun buildSongKey(item: MediaItem): String {
        val mediaId = item.mediaId
        if (mediaId.isNotBlank()) return mediaId
        return item.localConfiguration?.uri?.toString() ?: item.hashCode().toString()
    }

    private fun mergePlaylistSongs(fromLibrary: List<MediaItem>): MutableList<MediaItem> {
        val merged = fromLibrary.toMutableList()
        val mergedKeys = merged.map { buildSongKey(it) }.toMutableSet()
        playlistSongs.forEach { song ->
            val key = buildSongKey(song)
            if (mergedKeys.add(key)) {
                merged.add(song)
            }
        }
        return merged
    }

    private inner class SuggestedSongAdapter(
        private val onAddClicked: (MediaItem) -> Unit
    ) : ListAdapter<MediaItem, SuggestedSongAdapter.ViewHolder>(MediaItemDiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_suggested_song_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val artUri = item.mediaMetadata.artworkUri
            if (artUri != null) {
                holder.cover.load(artUri) {
                    crossfade(true)
                    size(120.dp.px.toInt(), 120.dp.px.toInt())
                }
            } else {
                holder.cover.setImageResource(R.drawable.default_cover)
            }
            holder.title.text = item.mediaMetadata.title?.toString().orEmpty()
            holder.subtitle.text = item.mediaMetadata.artist?.toString().orEmpty()
            holder.addButton.setOnClickListener {
                it.performPressHaptic()
                onAddClicked(item)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
            val addButton: ImageButton = view.findViewById(R.id.btnAdd)
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.ViewHolder>() {
        private var titleText = ""
        private var subtitleText = ""
        private var updatedText = ""

        fun update(title: String, subtitle: String, updated: String) {
            titleText = title
            subtitleText = subtitle
            updatedText = updated
            notifyItemChanged(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_playlist_detail_header, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.title.text = titleText
            holder.subtitle.text = subtitleText
            holder.updated.text = updatedText

            val storedCover = requireContext()
                .getSharedPreferences(PlaylistAdapter.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(PlaylistAdapter.coverKeyFor(headerTitle), null)
                ?.toUri()
            val singleCover = storedCover ?: remoteArtworkUri
            val collageCovers = playlistSongs
                .mapNotNull { it.mediaMetadata.artworkUri }
                .distinct()
                .take(6)

            when {
                singleCover != null -> {
                    holder.collage.visibility = View.GONE
                    holder.collage.setCovers(emptyList())
                    holder.icon.visibility = View.GONE
                    holder.cover.visibility = View.VISIBLE
                    holder.cover.load(singleCover) {
                        crossfade(true)
                        size(640.dp.px.toInt(), 640.dp.px.toInt())
                    }
                }
                collageCovers.isNotEmpty() -> {
                    holder.cover.visibility = View.GONE
                    holder.cover.setImageDrawable(null)
                    holder.icon.visibility = View.GONE
                    holder.collage.visibility = View.VISIBLE
                    holder.collage.setCovers(collageCovers)
                }
                else -> {
                    holder.cover.visibility = View.GONE
                    holder.cover.setImageDrawable(null)
                    holder.collage.visibility = View.GONE
                    holder.collage.setCovers(emptyList())
                    holder.icon.visibility = View.VISIBLE
                }
            }
        }

        override fun getItemCount(): Int = 1

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val subtitle: TextView = view.findViewById(R.id.tvArtist)
            val updated: TextView = view.findViewById(R.id.tvUpdated)
            val cover: ImageView = view.findViewById(R.id.coverImage)
            val collage: CollageArtView = view.findViewById(R.id.collageArt)
            val icon: ImageView = view.findViewById(R.id.musicIcon)
        }
    }

    private inner class FooterAdapter : RecyclerView.Adapter<FooterAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_playlist_detail_footer, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) = Unit

        override fun getItemCount(): Int = 1

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val refreshButton: ImageButton = view.findViewById(R.id.btnRefresh)
            private val suggestedRecycler: RecyclerView = view.findViewById(R.id.rvSuggested)

            init {
                suggestedRecycler.layoutManager = LinearLayoutManager(view.context)
                suggestedRecycler.adapter = suggestedAdapter
                refreshButton.setOnClickListener {
                    it.performPressHaptic()
                    shouldRefreshSuggestions = true
                    refreshSuggestions(force = true)
                }
            }
        }
    }

    private inner class PlaylistSongAdapter :
        ListAdapter<MediaItem, PlaylistSongAdapter.ViewHolder>(MediaItemDiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_song_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = getItem(position)
            val artUri = item.mediaMetadata.artworkUri
            if (artUri != null) {
                holder.cover.load(artUri) {
                    crossfade(true)
                    size(120.dp.px.toInt(), 120.dp.px.toInt())
                }
            } else {
                holder.cover.setImageResource(R.drawable.default_cover)
            }
            holder.title.text = item.mediaMetadata.title?.toString().orEmpty()
            holder.subtitle.text = item.mediaMetadata.artist?.toString().orEmpty()
            holder.divider.visibility = if (position == itemCount - 1) View.GONE else View.VISIBLE

            holder.itemView.setOnClickListener {
                val mediaController = activity.getPlayer() ?: return@setOnClickListener
                if (currentList.isEmpty() || position !in currentList.indices) return@setOnClickListener
                mediaController.setMediaItems(currentList, position, C.TIME_UNSET)
                mediaController.prepare()
                mediaController.play()
            }
            // Inside a playlist the row can also be taken back out of it, which is the one place
            // that entry means anything.
            holder.menu?.setOnClickListener { anchor ->
                TrackRowMenu.show(anchor, item) { removeFromPlaylist(item) }
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
            val divider: View = view.findViewById(R.id.divider)
            val menu: View? = view.findViewById(R.id.menu_btn)
        }
    }

    companion object {
        private const val ARG_ID = "playlist_id"
        private const val ARG_TITLE = "playlist_title"
        private const val ARG_COUNT = "playlist_count"
        private const val ARG_REMOTE_ID = "remote_playlist_id"
        private const val ARG_ARTWORK_URI = "playlist_artwork_uri"
        private const val NO_ID = -1L
        private const val SUGGESTED_COUNT = 5

        fun newInstance(
            playlistId: Long?,
            title: String,
            songCount: Int,
            remotePlaylistId: String? = null,
            artworkUri: Uri? = null,
        ): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, playlistId ?: NO_ID)
                    putString(ARG_TITLE, title)
                    putInt(ARG_COUNT, songCount)
                    putString(ARG_REMOTE_ID, remotePlaylistId)
                    putString(ARG_ARTWORK_URI, artworkUri?.toString())
                }
            }
        }
    }
}

private object MediaItemDiffCallback : DiffUtil.ItemCallback<MediaItem>() {
    override fun areItemsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
        return oldItem.mediaId == newItem.mediaId
    }

    override fun areContentsTheSame(oldItem: MediaItem, newItem: MediaItem): Boolean {
        return oldItem == newItem
    }
}
