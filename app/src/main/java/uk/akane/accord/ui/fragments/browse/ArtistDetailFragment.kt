package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProviders
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.CollectionPopupMenu
import uk.akane.accord.ui.components.TrackRowMenu
import uk.akane.accord.ui.components.TrackSwipeActions
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import uk.akane.libphonograph.items.addDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArtistDetailFragment : SwitcherPostponeFragment() {

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var headerArt: ImageView
    private lateinit var artistNameView: TextView
    private lateinit var playButton: FloatingActionButton
    private lateinit var latestCard: View
    private lateinit var latestCover: ImageView
    private lateinit var latestDateView: TextView
    private lateinit var latestTitleView: TextView
    private lateinit var latestCountView: TextView
    private lateinit var latestAddButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var albumsHeader: View
    private lateinit var scrollView: NestedScrollView
    private lateinit var headerContainer: View
    private lateinit var navigationBar: NavigationBar

    private val songAdapter = ArtistSongAdapter()
    private val albumAdapter = ArtistAlbumAdapter()
    private var currentTracks: List<MediaItem> = emptyList()
    private var latestAlbumTracks: List<MediaItem> = emptyList()
    private var didLoadOnce = false
    private var collectionDownloaded = false

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_artist, container, false)

        headerArt = rootView.findViewById(R.id.ivHeaderArt)
        artistNameView = rootView.findViewById(R.id.tvArtistName)
        artistNameView.isSelected = true
        playButton = rootView.findViewById(R.id.btnPlay)
        latestCard = rootView.findViewById(R.id.latestCard)
        latestCover = rootView.findViewById(R.id.ivLatestCover)
        latestDateView = rootView.findViewById(R.id.tvLatestDate)
        latestTitleView = rootView.findViewById(R.id.tvLatestTitle)
        latestCountView = rootView.findViewById(R.id.tvLatestCount)
        latestAddButton = rootView.findViewById(R.id.btnLatestAdd)
        recyclerView = rootView.findViewById(R.id.rvSongs)
        albumsRecyclerView = rootView.findViewById(R.id.rvAlbums)
        albumsHeader = rootView.findViewById(R.id.tvAlbumsHeader)
        scrollView = rootView.findViewById(R.id.scrollContainer)
        headerContainer = rootView.findViewById(R.id.headerContainer)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        val artistName = requireArguments().getString(ARG_ARTIST).orEmpty()
        val artistId = requireArguments().getLong(ARG_ARTIST_ID, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
        val featuredOnly = requireArguments().getBoolean(ARG_FEATURED_ONLY, false)
        artistNameView.text = artistName
        navigationBar.setTitle(artistName)
        rootView.findViewById<TextView>(R.id.tvTopSongs).text = if (featuredOnly) {
            "Featured appearances"
        } else {
            getString(R.string.artist_top_songs)
        }
        navigationBar.setMenuEntries(
            entries = {
                CollectionPopupMenu.build(
                    resources,
                    withArtist = false,
                    withAlbum = true,
                    withDownload = !collectionDownloaded,
                    withRemoveDownload = collectionDownloaded,
                    // Read when the menu opens, so setting the downloader up makes the entry
                    // appear without leaving this page.
                    withFindMoreReleases = AcquisitionProviders.active(requireContext())
                        .readiness(requireContext()).canSearch,
                )
            },
            onClick = { entry ->
                CollectionPopupMenu.handle(activity, entry, currentTracks, artistName)
            },
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = songAdapter
        albumsRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        albumsRecyclerView.adapter = albumAdapter
        TrackSwipeActions.attach(
            recyclerView = recyclerView,
            activity = activity,
            trackAt = songAdapter::itemAt
        )
        navigationBar.attach(scrollView, applyTopPadding = false)

        val headerHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
        val headerParams = headerContainer.layoutParams
        if (headerParams.height != headerHeight) {
            headerParams.height = headerHeight
            headerContainer.layoutParams = headerParams
        }
        navigationBar.doOnLayout {
            navigationBar.setCollapseStartOffsetPx((headerHeight - navigationBar.height).coerceAtLeast(0))
        }

        playButton.setOnClickListener {
            val mediaController = activity.getPlayer() ?: return@setOnClickListener
            if (currentTracks.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mediaController.setMediaItems(currentTracks, 0, C.TIME_UNSET)
            mediaController.prepare()
            mediaController.play()
        }

        latestAddButton.setOnClickListener {
            val mediaController = activity.getPlayer() ?: return@setOnClickListener
            if (latestAlbumTracks.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (mediaController.mediaItemCount == 0) {
                mediaController.setMediaItems(latestAlbumTracks, 0, C.TIME_UNSET)
                mediaController.prepare()
                mediaController.play()
            } else {
                mediaController.addMediaItems(latestAlbumTracks)
            }
            Toast.makeText(requireContext(), R.string.queued, Toast.LENGTH_SHORT).show()
        }
        latestCard.setOnClickListener {
            val title = latestTitleView.text?.toString()?.takeIf { it.isNotBlank() }
                ?: return@setOnClickListener
            openAlbum(title, artistName)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                val artists = if (featuredOnly) {
                    activity.reader.featuredArtistListFlow
                } else {
                    activity.reader.primaryArtistListFlow
                }
                artists.collectLatest { index ->
                    val tracks = index.firstOrNull { artist ->
                        if (artistId != null && artist.id != null) artist.id == artistId
                        else artist.title.equals(artistName, ignoreCase = true)
                    }?.songList.orEmpty()
                    val sorted = tracks.sortedWith(
                        compareByDescending<MediaItem> { it.mediaMetadata.addDate ?: 0L }
                            .thenBy { it.mediaMetadata.title?.toString().orEmpty() }
                    )
                    updateArtistDetails(sorted, featuredOnly)
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

    private fun updateArtistDetails(tracks: List<MediaItem>, featuredOnly: Boolean) {
        currentTracks = tracks
        songAdapter.submitList(tracks)
        refreshDownloadState(tracks)

        val albums = if (featuredOnly) emptyList() else tracks
            .groupBy { safeAlbum(it.mediaMetadata.albumTitle?.toString()) }
            .map { (title, albumTracks) -> ArtistAlbum(title, albumTracks) }
            .sortedWith(
                compareByDescending<ArtistAlbum> { album ->
                    album.tracks.maxOfOrNull { it.mediaMetadata.releaseYear ?: 0 } ?: 0
                }.thenByDescending { album ->
                    album.tracks.maxOfOrNull { it.mediaMetadata.addDate ?: 0L } ?: 0L
                }.thenBy { it.title.lowercase() }
            )
        albumAdapter.submitList(albums)
        albumsHeader.visibility = if (albums.isEmpty()) View.GONE else View.VISIBLE
        albumsRecyclerView.visibility = if (albums.isEmpty()) View.GONE else View.VISIBLE

        if (featuredOnly) {
            latestCard.visibility = View.GONE
            headerArt.load(tracks.firstOrNull()?.mediaMetadata?.artworkUri ?: R.drawable.default_cover) {
                crossfade(true)
            }
            if (!didLoadOnce) {
                didLoadOnce = true
                notifyContentLoaded()
            }
            return
        }

        val latest = tracks.firstOrNull()
        if (latest == null) {
            latestCard.visibility = View.GONE
            headerArt.setImageResource(R.drawable.default_cover)
            if (!didLoadOnce) {
                didLoadOnce = true
                notifyContentLoaded()
            }
            return
        }

        latestCard.visibility = View.VISIBLE
        val latestArt = latest.mediaMetadata.artworkUri
        if (latestArt != null) {
            headerArt.load(latestArt) { crossfade(true) }
            latestCover.load(latestArt) {
                crossfade(true)
                size(140.dp.px.toInt(), 140.dp.px.toInt())
            }
        } else {
            headerArt.setImageResource(R.drawable.default_cover)
            latestCover.setImageResource(R.drawable.default_cover)
        }

        val albumTitle = safeAlbum(latest.mediaMetadata.albumTitle?.toString())
        latestTitleView.text = albumTitle
        latestAlbumTracks = tracks.filter { track ->
            safeAlbum(track.mediaMetadata.albumTitle?.toString()) == albumTitle
        }
        latestCountView.text = resources.getString(
            R.string.artist_song_count,
            latestAlbumTracks.size
        )

        val formattedDate = formatAddDate(latest.mediaMetadata.addDate)
        if (formattedDate.isNullOrBlank()) {
            latestDateView.visibility = View.GONE
        } else {
            latestDateView.visibility = View.VISIBLE
            latestDateView.text = formattedDate
        }

        if (!didLoadOnce) {
            didLoadOnce = true
            notifyContentLoaded()
        }
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

    private fun formatAddDate(addDate: Long?): String? {
        if (addDate == null || addDate <= 0L) return null
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(addDate * 1000))
    }

    private fun safeAlbum(value: String?): String =
        value?.trim().orEmpty().ifEmpty { "(Unknown Album)" }

    private fun safeArtist(value: String?): String =
        value?.trim().orEmpty().ifEmpty { "(Unknown Artist)" }

    private fun openAlbum(albumTitle: String, artistName: String) {
        activity.fragmentSwitcherView.addFragmentToCurrentStack(
            AlbumDetailFragment.newInstance(albumTitle, artistName)
        )
    }

    private data class ArtistAlbum(
        val title: String,
        val tracks: List<MediaItem>
    )

    private inner class ArtistAlbumAdapter :
        RecyclerView.Adapter<ArtistAlbumAdapter.ViewHolder>() {
        private val items = mutableListOf<ArtistAlbum>()

        fun submitList(albums: List<ArtistAlbum>) {
            items.clear()
            items.addAll(albums)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.homepage_recommend_card, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val album = items[position]
            // Some Jellyfin album entries omit art on disc/bonus tracks even though another track
            // in the same album has the cover. Do not let whichever track sorted first blank the
            // entire album card.
            val artwork = album.tracks.firstNotNullOfOrNull {
                it.mediaMetadata.artworkUri
            }
            if (artwork == null) {
                holder.cover.setImageResource(R.drawable.default_cover)
            } else {
                holder.cover.load(artwork) {
                    crossfade(true)
                    size(168.dp.px.toInt(), 168.dp.px.toInt())
                }
            }
            holder.title.text = album.title
            val year = album.tracks.mapNotNull { it.mediaMetadata.releaseYear }.maxOrNull()
            holder.subtitle.text = year?.toString().orEmpty()
            holder.subtitle.visibility = if (year == null) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener {
                openAlbum(album.title, requireArguments().getString(ARG_ARTIST).orEmpty())
            }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }
    }

    private inner class ArtistSongAdapter :
        RecyclerView.Adapter<ArtistSongAdapter.ViewHolder>() {
        private val items = mutableListOf<MediaItem>()

        fun submitList(tracks: List<MediaItem>) {
            items.clear()
            items.addAll(tracks)
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): MediaItem? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_artist_song_item, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.cover?.load(item.mediaMetadata.artworkUri) {
                crossfade(true)
                size(62.dp.px.toInt(), 62.dp.px.toInt())
            }
            holder.title?.text = item.mediaMetadata.title?.toString().orEmpty()
            val album = safeAlbum(item.mediaMetadata.albumTitle?.toString())
            holder.subtitle?.text = album

            holder.itemView.setOnClickListener {
                val mediaController = activity.getPlayer() ?: return@setOnClickListener
                if (items.isEmpty()) return@setOnClickListener
                mediaController.setMediaItems(items, position, C.TIME_UNSET)
                mediaController.prepare()
                mediaController.play()
            }
            holder.menu?.setOnClickListener { anchor -> TrackRowMenu.show(anchor, item) }
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView? = view.findViewById(R.id.cover)
            val title: TextView? = view.findViewById(R.id.title)
            val subtitle: TextView? = view.findViewById(R.id.subtitle)
            val menu: View? = view.findViewById(R.id.menu_btn)
        }
    }

    companion object {
        private const val ARG_ARTIST = "artist_name"
        private const val ARG_ARTIST_ID = "artist_id"
        private const val ARG_FEATURED_ONLY = "featured_only"

        fun newInstance(
            artist: String,
            artistId: Long? = null,
            featuredOnly: Boolean = false,
        ): ArtistDetailFragment {
            return ArtistDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_ARTIST, artist)
                    artistId?.let { putLong(ARG_ARTIST_ID, it) }
                    putBoolean(ARG_FEATURED_ONLY, featuredOnly)
                }
            }
        }
    }
}
