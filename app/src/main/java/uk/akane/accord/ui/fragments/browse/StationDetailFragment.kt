package uk.akane.accord.ui.fragments.browse

import android.net.Uri
import android.os.Bundle
import org.akanework.gramophone.logic.data.jellyfin.QueuePrefetcher
import coil3.imageLoader
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.CollectionPopupMenu
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.TrackRowMenu
import uk.akane.accord.ui.components.TrackSwipeActions
import uk.akane.accord.ui.components.StationArtView
import uk.akane.accord.ui.components.performPressHaptic
import uk.akane.cupertino.navigation.FragmentSwitcherTransitionProvider
import uk.akane.cupertino.navigation.FragmentSwitcherTransitionStyle
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import kotlin.random.Random

/**
 * A home-screen station opened as its own screen - Daily shuffle, Most played, Favourites and the
 * rest - so a tap gives somewhere to look at the tracks and choose where to start, rather than
 * immediately taking over what is playing.
 *
 * The station is carried as a list of media ids and resolved against the library, so it survives
 * the fragment being recreated without a list of MediaItems being pushed through a Bundle.
 */
class StationDetailFragment : SwitcherPostponeFragment(), FragmentSwitcherTransitionProvider {

    // Generated collections already have all of their identity and artwork in the arguments.
    // Their detail page can use the tighter push instead of the deliberately leisurely 500 ms
    // Cupertino transition used by heavier library pages.
    override val fragmentSwitcherTransitionStyle = FragmentSwitcherTransitionStyle.DEFAULT

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var headerArt: ImageView
    private lateinit var generatedArt: StationArtView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var metaView: TextView
    private lateinit var playButton: MaterialButton
    private lateinit var shuffleButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var scrollView: NestedScrollView
    private lateinit var headerContainer: View
    private lateinit var navigationBar: NavigationBar

    private val trackAdapter = StationTrackAdapter()
    private var currentTracks: List<MediaItem> = emptyList()
    private var saveInProgress = false
    private var savedPlaylistId: String? = null
    private var collectionDownloaded = false

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_album, container, false)

        headerArt = rootView.findViewById(R.id.ivHeaderArt)
        generatedArt = rootView.findViewById(R.id.stationGeneratedArt)
        titleView = rootView.findViewById(R.id.tvAlbumTitle)
        artistView = rootView.findViewById(R.id.tvAlbumArtist)
        metaView = rootView.findViewById(R.id.tvMeta)
        playButton = rootView.findViewById(R.id.btnPlay)
        shuffleButton = rootView.findViewById(R.id.btnShuffle)
        recyclerView = rootView.findViewById(R.id.rvTracks)
        scrollView = rootView.findViewById(R.id.scrollContainer)
        headerContainer = rootView.findViewById(R.id.headerContainer)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        rootView.findViewById<TextView>(R.id.tvQuote).visibility = View.GONE

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        val kind = CollectionKind.fromArgument(requireArguments().getString(ARG_KIND))
        navigationBar.setReturnButtonText(getString(kind.labelRes))
        navigationBar.shouldDrawAddButton = true
        navigationBar.setOnAddClickListener(::saveAsPlaylist)
        navigationBar.setMenuEntries(
            entries = {
                CollectionPopupMenu.build(
                    resources = resources,
                    withArtist = true,
                    withAlbum = true,
                    withDownload = !collectionDownloaded,
                    withRemoveDownload = collectionDownloaded,
                    withServerDelete = savedPlaylistId != null,
                    // The top-right plus/check is the generated collection's save control. Keeping
                    // the same action in this menu showed two conflicting save states at once.
                    withSaveAsPlaylist = false,
                )
            },
            onClick = { entry ->
                when (CollectionPopupMenu.actionOf(entry)) {
                    CollectionPopupMenu.Action.DELETE_FROM_SERVER -> promptDeleteFromServer()
                    CollectionPopupMenu.Action.SAVE_AS_PLAYLIST -> saveAsPlaylist()
                    else -> CollectionPopupMenu.handle(
                        activity, entry, currentTracks, titleView.text?.toString().orEmpty()
                    )
                }
            },
        )

        val title = requireArguments().getString(ARG_TITLE).orEmpty()
        val subtitle = requireArguments().getString(ARG_SUBTITLE).orEmpty()
        titleView.text = title
        generatedArt.visibility = View.VISIBLE
        // Held still until the screen has settled. This field fills most of the display, and
        // animating it while the fragment is still sliding in put a full-screen shader redraw on
        // every frame of the transition - the reason opening a station stuttered where an album,
        // which shows a plain bitmap, did not.
        generatedArt.animated = false
        generatedArt.bind(title)
        generatedArt.postDelayed({ generatedArt.animated = true }, HEADER_ANIMATION_DELAY_MS)
        headerArt.visibility = View.GONE
        artistView.text = subtitle
        // This layout is shared with albums, where the red subtitle is an artist link. A mix can
        // contain several artists, so retaining the album click semantics creates an inert button.
        artistView.isClickable = false
        artistView.isFocusable = false
        artistView.setTextColor(Color.argb(179, 255, 255, 255))
        navigationBar.setTitle(title)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = trackAdapter
        TrackSwipeActions.attach(
            recyclerView = recyclerView,
            activity = activity,
            trackAt = trackAdapter::itemAt
        )
        navigationBar.attach(scrollView, applyTopPadding = false)

        val headerHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        headerContainer.layoutParams = headerContainer.layoutParams.also { it.height = headerHeight }
        navigationBar.doOnLayout {
            navigationBar.setCollapseStartOffsetPx(
                (headerHeight - navigationBar.height).coerceAtLeast(0)
            )
        }

        playButton.setOnClickListener {
            it.performPressHaptic()
            play(currentTracks, 0)
        }
        shuffleButton.setOnClickListener {
            it.performPressHaptic()
            play(currentTracks.shuffled(Random(System.currentTimeMillis())), 0)
        }

        // The chrome and cached artwork can be shown immediately while tracks finish resolving.
        metaView.text = getString(R.string.sync_in_progress)
        notifyContentLoaded()

        val wantedIds = requireArguments().getStringArray(ARG_MEDIA_IDS)?.toList().orEmpty()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            val songs = resolveLibrarySnapshot(wantedIds)
            val byId = songs.associateBy { it.mediaId }
            val resolved = wantedIds.mapNotNull { byId[it] }
            val played = if (wantedIds.isEmpty()) {
                songs.filter { (it.mediaMetadata.extras?.getLong(JellyfinLibraryLoader.EXTRA_LAST_PLAYED, 0L) ?: 0L) > 0 }
                    .sortedByDescending { it.mediaMetadata.extras?.getLong(JellyfinLibraryLoader.EXTRA_LAST_PLAYED, 0L) ?: 0L }
            } else emptyList()
            val tracks = if (wantedIds.isNotEmpty()) resolved else played.ifEmpty { songs }

            withContext(Dispatchers.Main) {
                currentTracks = tracks
                trackAdapter.submitList(tracks)
                QueuePrefetcher.warmArtwork(requireContext(), tracks, requireContext().imageLoader)
                refreshDownloadState(tracks)
                refreshSavedPlaylistState(title, tracks)
                metaView.text = resources.getQuantityString(
                    R.plurals.songs, tracks.size, tracks.size
                )
            }
        }

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
        if (!hidden) refreshDownloadState()
    }

    private fun refreshDownloadState(tracks: List<MediaItem> = currentTracks) {
        if (!isAdded || tracks.isEmpty()) {
            collectionDownloaded = false
            return
        }
        val expectedIds = tracks.map(MediaItem::mediaId)
        viewLifecycleOwner.lifecycleScope.launch {
            val downloaded = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.areAllDownloaded(requireContext(), tracks)
            }
            if (currentTracks.map(MediaItem::mediaId) == expectedIds) {
                collectionDownloaded = downloaded
            }
        }
    }

    /** Restores the red checkmark when this generated station was saved on an earlier visit. */
    private fun refreshSavedPlaylistState(stationTitle: String, tracks: List<MediaItem>) {
        val signature = stationSignature(tracks)
        val prefs = requireContext().getSharedPreferences(SAVED_STATIONS_PREFS, 0)
        val stored = prefs.getString(savedStationKey(stationTitle), null)
            ?.split('\n', limit = 2)
            ?.takeIf { it.size == 2 }
        val storedId = stored?.get(0)
        val storedMatches = stored?.get(1) == signature

        fun matching(playlists: List<JellyfinPlaylists.RemotePlaylist>) = when {
            stored != null && !storedMatches -> null
            storedId != null -> playlists.firstOrNull { it.id == storedId }
            else -> playlists.firstOrNull {
                it.name.equals(stationTitle, ignoreCase = true) &&
                    (tracks.isEmpty() || it.songCount == tracks.size)
            }
        }

        if (stored != null && !storedMatches) {
            savedPlaylistId = null
            navigationBar.isAddButtonChecked = false
        }

        // The last successful server list gives an immediate answer without waiting on network.
        matching(JellyfinPlaylists.cachedList(requireContext()))?.let { cached ->
            savedPlaylistId = cached.id
            navigationBar.isAddButtonChecked = true
            if (stored == null) rememberSavedStation(stationTitle, tracks, cached.id)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val remote = withContext(Dispatchers.IO) {
                JellyfinPlaylists.list(requireContext())
            }
            if (!isAdded || titleView.text?.toString() != stationTitle || remote.isEmpty()) {
                return@launch
            }
            val saved = matching(remote)
            savedPlaylistId = saved?.id
            navigationBar.isAddButtonChecked = saved != null
            navigationBar.shouldDrawAddButton = true
            if (saved != null && stored == null) {
                rememberSavedStation(stationTitle, tracks, saved.id)
            }
        }
    }

    private fun rememberSavedStation(title: String, tracks: List<MediaItem>, playlistId: String) {
        requireContext().getSharedPreferences(SAVED_STATIONS_PREFS, 0)
            .edit()
            .putString(savedStationKey(title), "$playlistId\n${stationSignature(tracks)}")
            .apply()
    }

    private fun forgetSavedStation(title: String) {
        requireContext().getSharedPreferences(SAVED_STATIONS_PREFS, 0)
            .edit()
            .remove(savedStationKey(title))
            .apply()
    }

    private fun savedStationKey(title: String) = "station:${title.trim().lowercase()}"

    private fun stationSignature(tracks: List<MediaItem>) =
        tracks.joinToString(",", transform = MediaItem::mediaId)

    /**
     * Cached station ids can arrive before the library's incremental cold-start sync. Prefer a
     * snapshot containing every requested track, while retaining a bounded fallback for tracks
     * which have genuinely been removed from the server.
     */
    private suspend fun resolveLibrarySnapshot(wantedIds: List<String>): List<MediaItem> {
        val flow = activity.reader.songListFlow
        if (wantedIds.isEmpty()) return emptyList()
        return withTimeoutOrNull(4_500L) {
            flow.first { snapshot ->
                if (snapshot.isEmpty()) return@first false
                val available = snapshot.asSequence().map { it.mediaId }.toHashSet()
                wantedIds.all(available::contains)
            }
        } ?: flow.first { it.isNotEmpty() }
    }

    private fun saveAsPlaylist() {
        if (saveInProgress) return
        // The control is a toggle, so the second press has to be the way back out. It used to
        // report that the playlist already existed, which left the tick with nothing to undo it.
        if (savedPlaylistId != null) {
            promptDeleteFromServer()
            return
        }
        val tracks = currentTracks
        if (tracks.isEmpty()) {
            Toast.makeText(
                requireContext(),
                R.string.add_to_playlist_create_failed,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        saveInProgress = true
        // The button stays on screen for the whole round trip. Hiding it here is what made the
        // mark vanish, reappear, and only then morph: three separate events for one action, when
        // the morph alone is the feedback.
        val playlistTitle = titleView.text?.toString()?.trim().orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    createJellyfinPlaylist(requireContext(), playlistTitle, tracks)
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { saved ->
                saveInProgress = false
                savedPlaylistId = saved.id
                rememberSavedStation(saved.title, tracks, saved.id)
                navigationBar.setAddButtonChecked(true, animate = true, haptic = true)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.add_to_playlist_created, saved.title),
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure {
                saveInProgress = false
                navigationBar.setAddButtonChecked(false, animate = true, haptic = false)
                Toast.makeText(
                    requireContext(),
                    R.string.add_to_playlist_create_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun createJellyfinPlaylist(
        context: android.content.Context,
        requestedTitle: String,
        tracks: List<MediaItem>
    ): SavedPlaylist {
        val title = requestedTitle.ifBlank { context.getString(R.string.station) }
        val createdId = JellyfinPlaylists.create(context, title, tracks.map { it.mediaId })
        check(!createdId.isNullOrBlank()) { "Jellyfin did not create the playlist" }
        // Refresh the lightweight playlist cache only. A playlist write does not change the song
        // library and must not trigger an 8k-item Jellyfin rescan or reshuffle the home feed.
        JellyfinPlaylists.list(context)
        return SavedPlaylist(createdId, title)
    }

    private fun promptDeleteFromServer() {
        if (savedPlaylistId == null) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.collection_delete_server_title, titleView.text))
            .setMessage(R.string.collection_delete_server_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.playlist_delete_confirm) { _, _ -> deleteFromServer() }
            .show()
    }

    private fun deleteFromServer() {
        val playlistId = savedPlaylistId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                JellyfinPlaylists.delete(playlistId).also { success ->
                    if (success) JellyfinPlaylists.list(requireContext())
                }
            }
            if (!isAdded) return@launch
            if (deleted) {
                savedPlaylistId = null
                forgetSavedStation(titleView.text?.toString().orEmpty())
                // Animated, so the tick runs back into the plus and the removal is legible as the
                // undo of the save rather than the button silently changing shape.
                navigationBar.setAddButtonChecked(false, animate = true, haptic = true)
                navigationBar.shouldDrawAddButton = true
                Toast.makeText(
                    requireContext(), R.string.collection_deleted_from_server, Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(), R.string.collection_delete_server_failed, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private data class SavedPlaylist(val id: String, val title: String)

    private fun play(tracks: List<MediaItem>, startIndex: Int) {
        if (tracks.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
            return
        }
        activity.getPlayer()?.apply {
            setMediaItems(tracks, startIndex, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    private inner class StationTrackAdapter :
        RecyclerView.Adapter<StationTrackAdapter.ViewHolder>() {

        private val items = mutableListOf<MediaItem>()

        fun submitList(tracks: List<MediaItem>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): MediaItem? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_album_track_item, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.trackNumber?.text = (position + 1).toString()
            holder.title?.text = item.mediaMetadata.title?.toString()?.trim().orEmpty()
            holder.itemView.setOnClickListener { play(items.toList(), position) }
            holder.menu?.setOnClickListener { anchor -> TrackRowMenu.show(anchor, item) }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val trackNumber: TextView? = view.findViewById(R.id.track_number)
            val title: TextView? = view.findViewById(R.id.title)
            val menu: View? = view.findViewById(R.id.menu_btn)
        }
    }

    enum class CollectionKind(val labelRes: Int) {
        MIX(R.string.mix),
        STATION(R.string.station),
        PLAYLIST(R.string.playlist);

        companion object {
            fun fromArgument(value: String?): CollectionKind =
                entries.firstOrNull { it.name == value } ?: MIX
        }
    }

    companion object {
        private const val ARG_TITLE = "station_title"
        private const val ARG_SUBTITLE = "station_subtitle"
        private const val ARG_MEDIA_IDS = "station_media_ids"
        private const val ARG_COVER = "station_cover"
        private const val ARG_KIND = "station_kind"
        private const val SAVED_STATIONS_PREFS = "saved_generated_stations"

        /** Comfortably past the switcher's enter transition, so the drift starts unnoticed. */
        private const val HEADER_ANIMATION_DELAY_MS = 450L

        fun newInstance(
            title: String,
            subtitle: String?,
            mediaIds: List<String>,
            cover: Uri? = null,
            kind: CollectionKind = CollectionKind.MIX
        ) =
            StationDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_SUBTITLE, subtitle.orEmpty())
                    putStringArray(ARG_MEDIA_IDS, mediaIds.toTypedArray())
                    putString(ARG_COVER, cover?.toString())
                    putString(ARG_KIND, kind.name)
                }
            }
    }
}
