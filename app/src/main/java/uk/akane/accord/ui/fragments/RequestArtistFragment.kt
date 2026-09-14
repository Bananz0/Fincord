package uk.akane.accord.ui.fragments

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.AcquirableArtist
import org.akanework.gramophone.logic.data.acquisition.AcquirableRelease
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProvider
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProviders
import org.akanework.gramophone.logic.data.acquisition.ReleaseAvailability
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.RequestableReleasesAdapter
import uk.akane.accord.ui.components.LibraryReleaseIndex
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment

/**
 * One artist's complete discography, whether or not any of it has been fetched yet.
 *
 * The library's own artist page can only show what the media server holds, which is precisely the
 * wrong thing when the question is "what else did they make". This is the other half: everything
 * they released, each record labelled with where it stands - in the library, arriving, requested,
 * or not asked for. Records already owned open the real library page, so playing and queueing
 * happen where they always did rather than being rebuilt here.
 *
 * The discography comes from the downloader for artists it tracks and from MusicBrainz for everyone
 * else, which is what makes this work for an artist nobody has requested yet.
 */
class RequestArtistFragment : Fragment() {

    private lateinit var headerArt: ImageView
    private lateinit var headerContainer: View
    private lateinit var scrollView: NestedScrollView
    private lateinit var navigationBar: NavigationBar
    private lateinit var artistNameView: TextView
    private lateinit var latestCard: View
    private lateinit var latestCover: ImageView
    private lateinit var latestDateView: TextView
    private lateinit var latestTitleView: TextView
    private lateinit var latestCountView: TextView
    private lateinit var latestAddButton: MaterialButton
    private lateinit var albumsHeader: TextView
    private lateinit var albumsRecyclerView: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var releases: RecyclerView
    private lateinit var adapter: RequestableReleasesAdapter
    private lateinit var albumAdapter: DiscographyCardAdapter
    private var latestRelease: AcquirableRelease? = null
    private var latestButtonAnimator: ObjectAnimator? = null
    private val requestStates = mutableMapOf<String, RequestableReleasesAdapter.RequestState>()

    /** Worked out once per list; see [LibraryReleaseIndex] for why not per row. */
    private val libraryReleases = LibraryReleaseIndex()

    private val provider: AcquisitionProvider
        get() = AcquisitionProviders.active(requireContext())

    private val artist: AcquirableArtist by lazy {
        val args = requireArguments()
        AcquirableArtist(
            providerId = args.getString(ARG_PROVIDER).orEmpty(),
            id = args.getString(ARG_ID).orEmpty(),
            name = args.getString(ARG_NAME).orEmpty(),
            disambiguation = args.getString(ARG_DISAMBIGUATION),
            artworkUrl = args.getString(ARG_ARTWORK),
            internalId = args.getInt(ARG_INTERNAL_ID),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_request_artist, container, false)

        headerArt = rootView.findViewById(R.id.ivHeaderArt)
        headerContainer = rootView.findViewById(R.id.headerContainer)
        scrollView = rootView.findViewById(R.id.scrollContainer)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        artistNameView = rootView.findViewById(R.id.tvArtistName)
        artistNameView.isSelected = true
        latestCard = rootView.findViewById(R.id.latestCard)
        latestCover = rootView.findViewById(R.id.ivLatestCover)
        latestDateView = rootView.findViewById(R.id.tvLatestDate)
        latestTitleView = rootView.findViewById(R.id.tvLatestTitle)
        latestCountView = rootView.findViewById(R.id.tvLatestCount)
        latestAddButton = rootView.findViewById(R.id.btnLatestAdd)
        albumsHeader = rootView.findViewById(R.id.tvAlbumsHeader)
        albumsRecyclerView = rootView.findViewById(R.id.rvAlbums)
        status = rootView.findViewById(R.id.artist_status)
        progress = rootView.findViewById(R.id.artist_progress)
        releases = rootView.findViewById(R.id.artist_releases)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            (activity as? MainActivity)?.fragmentSwitcherView?.popBackTopFragmentIfExists()
        }
        navigationBar.attach(scrollView, applyTopPadding = false)

        // The same proportion the library's artist page uses, so the two are the same shape.
        val headerHeight = (resources.displayMetrics.heightPixels * HEADER_HEIGHT_RATIO).toInt()
        headerContainer.layoutParams = headerContainer.layoutParams.apply {
            height = headerHeight
        }
        navigationBar.doOnLayout {
            navigationBar.setCollapseStartOffsetPx(
                (headerHeight - navigationBar.height).coerceAtLeast(0)
            )
        }

        artistNameView.text = artist.name
        artist.artworkUrl?.takeIf(String::isNotBlank)?.let {
            headerArt.load(it) { crossfade(true) }
        }
        latestCard.visibility = View.GONE
        albumsHeader.visibility = View.GONE
        albumsRecyclerView.visibility = View.GONE

        adapter = RequestableReleasesAdapter(
            onReleaseClick = ::requestRelease,
            isInLibrary = libraryReleases::isInLibrary,
        )
        releases.layoutManager = LinearLayoutManager(requireContext())
        releases.adapter = adapter

        albumAdapter = DiscographyCardAdapter(::requestRelease)
        albumsRecyclerView.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        albumsRecyclerView.adapter = albumAdapter

        observeLibrary()
        loadDiscography()
        return rootView
    }

    private fun loadDiscography() {
        showBusy(getString(R.string.requests_artist_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    provider.releasesOf(requireContext(), artist) { partial ->
                        withContext(Dispatchers.Main.immediate) {
                            showDiscography(partial, stillLoading = true)
                        }
                    }
                }
            }
            val list = found.getOrElse {
                showStatus(it.message ?: getString(R.string.requests_failed))
                return@launch
            }
            showDiscography(list, stillLoading = false)
        }
    }

    /** Replaces the visible snapshot; partial Lidarr results arrive before the full catalogue. */
    private suspend fun showDiscography(list: List<AcquirableRelease>, stillLoading: Boolean) {
        libraryReleases.index(list)
        adapter.submit(list)
        showAlbums(list)
        showLatest(list)
        showArtistImage(list)
        val message = if (list.isEmpty()) getString(R.string.requests_artist_empty)
        else resources.getQuantityString(R.plurals.requests_artist_releases, list.size, list.size)
        showStatus(message)
        progress.visibility = if (stillLoading) View.VISIBLE else View.GONE
    }

    /** The carousel the library page puts here, holding this artist's albums and EPs. */
    private fun showAlbums(all: List<AcquirableRelease>) {
        // Singles would fill the row with artwork nobody is looking for; the list below has them.
        val albums = all.filter(::isAlbumOrEp).ifEmpty { all.take(CAROUSEL_LIMIT) }
        if (albums.isEmpty()) return
        albumsHeader.visibility = View.VISIBLE
        albumsRecyclerView.visibility = View.VISIBLE
        albumAdapter.submit(albums.take(CAROUSEL_LIMIT))
    }

    /**
     * Whether a release belongs in the album carousel rather than the list below it.
     *
     * The question the row is really asking is "is this a body of work or one song", and no single
     * signal answers it. The provider's own word for it comes first, because a discography
     * assembled from metadata has no track counts at all - which is what made every record read as
     * an album and filled the row with whichever singles happened to sort first. But that word is
     * not the whole answer either:
     *
     *  - **Album and EP** are collections by definition and always belong here.
     *  - **Single** usually does not, but a maxi-single or a three-track release filed as one is a
     *    collection in everything but its label, so the track count is allowed to overrule it.
     *  - **Broadcast, Other and anything unrecognised** are types this app has no opinion about;
     *    they fall through to the track count, and an unknown count still reads as a collection
     *    rather than being hidden.
     */
    private fun isAlbumOrEp(release: AcquirableRelease): Boolean {
        val enoughTracks = (release.totalTracks ?: MANY_TRACKS) >= ALBUM_TRACK_FLOOR
        val type = release.releaseType ?: return enoughTracks
        return when {
            type.equals("Album", ignoreCase = true) -> true
            type.equals("EP", ignoreCase = true) -> true
            // Named a single by whoever filed it, but long enough to be listened to as a record.
            type.equals("Single", ignoreCase = true) -> release.totalTracks != null && enoughTracks
            else -> enoughTracks
        }
    }

    /** The newest thing they put out, which is usually why someone opened this page. */
    private fun showLatest(all: List<AcquirableRelease>) {
        val latest = all.maxByOrNull { it.year ?: 0 } ?: return
        latestRelease = latest
        latestCard.visibility = View.VISIBLE
        latestDateView.text = getString(R.string.requests_artist_latest)
        latestTitleView.text = latest.title
        latestCountView.text = listOfNotNull(
            latest.year?.toString(),
            statusLabelFor(latest),
        ).joinToString(" · ")
        latestCover.load(latest.artworkUrl) {
            crossfade(true)
            size(72.dp.px.toInt(), 72.dp.px.toInt())
        }
        bindLatestAction(latest, animate = false)
        latestAddButton.setOnClickListener { requestRelease(latest) }
        latestCard.setOnClickListener { requestRelease(latest) }
    }

    private fun bindLatestAction(release: AcquirableRelease, animate: Boolean) {
        latestButtonAnimator?.cancel()
        latestButtonAnimator = null
        latestAddButton.visibility = View.VISIBLE
        latestAddButton.rotation = 0f
        latestAddButton.alpha = 1f
        val state = requestStates[release.id] ?: RequestableReleasesAdapter.RequestState.IDLE
        val handled = libraryReleases.isInLibrary(release) || release.alreadyPresent
        latestCountView.text = listOfNotNull(
            release.year?.toString(),
            statusLabelFor(release),
        ).joinToString(" · ")
        latestAddButton.isClickable = state != RequestableReleasesAdapter.RequestState.SENDING &&
            state != RequestableReleasesAdapter.RequestState.REQUESTED && !handled
        when {
            state == RequestableReleasesAdapter.RequestState.SENDING -> {
                latestAddButton.setIconResource(R.drawable.ic_progress_ring)
                latestAddButton.contentDescription = getString(R.string.requests_row_sending)
                latestButtonAnimator = ObjectAnimator.ofFloat(
                    latestAddButton,
                    View.ROTATION,
                    0f,
                    360f,
                ).apply {
                    duration = 800L
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            state == RequestableReleasesAdapter.RequestState.REQUESTED || handled -> {
                latestAddButton.setIconResource(R.drawable.ic_checkmark)
                latestAddButton.contentDescription = getString(R.string.requests_row_requested)
            }
            state == RequestableReleasesAdapter.RequestState.FAILED -> {
                latestAddButton.setIconResource(R.drawable.ic_error)
                latestAddButton.contentDescription = getString(R.string.requests_row_failed)
                latestAddButton.isClickable = true
            }
            else -> {
                latestAddButton.setIconResource(R.drawable.ic_plus)
                latestAddButton.contentDescription = getString(R.string.lidarr_request_missing)
            }
        }
        if (animate) {
            latestAddButton.scaleX = 0.72f
            latestAddButton.scaleY = 0.72f
            latestAddButton.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
    }

    private fun statusLabelFor(release: AcquirableRelease): String? = when {
        requestStates[release.id] == RequestableReleasesAdapter.RequestState.SENDING ->
            getString(R.string.requests_row_sending)

        requestStates[release.id] == RequestableReleasesAdapter.RequestState.REQUESTED ->
            getString(R.string.requests_row_requested)

        requestStates[release.id] == RequestableReleasesAdapter.RequestState.FAILED ->
            getString(R.string.requests_row_failed)

        libraryReleases.isInLibrary(release) -> getString(R.string.requests_row_in_library)
        release.availability == ReleaseAvailability.DOWNLOADING ->
            getString(R.string.requests_row_downloading)

        release.availability == ReleaseAvailability.DOWNLOADED ->
            getString(R.string.requests_row_downloaded)

        release.availability == ReleaseAvailability.REQUESTED ->
            getString(R.string.requests_row_requested)

        else -> null
    }

    /**
     * Gives the header a picture even when nobody has filed a portrait of this artist.
     *
     * The downloader only holds artist images for acts its metadata source has photographs of,
     * which for anyone newer or smaller is nobody - and an artist page whose header is the grey
     * placeholder looks broken rather than sparse. Their own cover art is the obvious stand-in, and
     * the header is a darkened, cropped backdrop, which is exactly what a record sleeve survives.
     */
    private fun showArtistImage(releases: List<AcquirableRelease>) {
        val cover = releases.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
        val portrait = artist.artworkUrl?.takeIf(String::isNotBlank)
        val primary = portrait ?: cover ?: return
        headerArt.load(primary) {
            crossfade(true)
            // The portrait can simply not exist - the cache behind it 404s often enough that an
            // empty header would be the common case rather than the exception.
            if (cover != null && cover != primary) {
                listener(onError = { _, _ -> headerArt.load(cover) { crossfade(true) } })
            }
        }
    }

    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.albumListFlow.collectLatest { albums ->
                    if (libraryReleases.onLibraryChanged(albums)) {
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                        albumAdapter.notifyItemRangeChanged(0, albumAdapter.itemCount)
                    }
                }
            }
        }
    }

    private fun requestRelease(release: AcquirableRelease) {
        when (requestStates[release.id]) {
            RequestableReleasesAdapter.RequestState.SENDING -> return
            RequestableReleasesAdapter.RequestState.REQUESTED -> {
                showStatus(getString(R.string.requests_row_requested))
                return
            }
            else -> Unit
        }
        // Owning it already makes the row a way into the library, where playing and queueing live.
        libraryReleases.match(release)?.let { album ->
            (activity as? MainActivity)?.fragmentSwitcherView?.addFragmentToCurrentStack(
                AlbumDetailFragment.newInstance(
                    album.title.orEmpty(),
                    album.albumArtist.orEmpty(),
                )
            )
            return
        }
        if (release.alreadyPresent) {
            showStatus(statusLabelFor(release) ?: getString(R.string.requests_already_added))
            return
        }
        if (!provider.readiness(requireContext()).canRequest) {
            LidarrSetupPrompt.ensureConfigured(requireContext(), viewLifecycleOwner) {
                requestRelease(release)
            }
            return
        }

        setRequestState(release, RequestableReleasesAdapter.RequestState.SENDING)
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { provider.request(requireContext(), release) }
            }
            val succeeded = added.getOrDefault(false)
            setRequestState(
                release,
                if (succeeded) RequestableReleasesAdapter.RequestState.REQUESTED
                else RequestableReleasesAdapter.RequestState.FAILED,
            )
            added.exceptionOrNull()?.let {
                showStatus(it.message ?: getString(R.string.requests_failed))
            }
        }
    }

    private fun setRequestState(
        release: AcquirableRelease,
        state: RequestableReleasesAdapter.RequestState,
    ) {
        requestStates[release.id] = state
        adapter.setState(release.id, state)
        albumAdapter.setState(release.id)
        if (latestRelease?.id == release.id) bindLatestAction(release, animate = true)
    }

    /** A wait, with the spinner. Every conclusion goes through [showStatus], which clears it. */
    private fun showBusy(text: String) {
        showStatus(text)
        progress.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        progress.visibility = View.GONE
        status.text = text
        status.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        latestButtonAnimator?.cancel()
        latestButtonAnimator = null
        super.onDestroyView()
    }

    /** The album carousel, drawn with the same card the rest of the app uses for a release. */
    private inner class DiscographyCardAdapter(
        private val onClick: (AcquirableRelease) -> Unit,
    ) : RecyclerView.Adapter<DiscographyCardAdapter.ViewHolder>() {

        private val items = mutableListOf<AcquirableRelease>()

        fun submit(releases: List<AcquirableRelease>) {
            items.clear()
            items.addAll(releases)
            notifyDataSetChanged()
        }

        fun setState(releaseId: String) {
            val index = items.indexOfFirst { it.id == releaseId }
            if (index >= 0) notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.homepage_recommend_card, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val release = items[position]
            holder.title.text = release.title
            holder.subtitle.text = listOfNotNull(
                release.year?.toString(),
                statusLabelFor(release),
            ).joinToString(" · ")
            holder.cover.load(release.artworkUrl) {
                crossfade(true)
                size(168.dp.px.toInt(), 168.dp.px.toInt())
            }
            holder.itemView.setOnClickListener { onClick(release) }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
        }
    }

    companion object {
        private const val ARG_PROVIDER = "provider"
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_DISAMBIGUATION = "disambiguation"
        private const val ARG_ARTWORK = "artwork"
        private const val ARG_INTERNAL_ID = "internalId"

        /** Matches the library's artist page exactly; the two are meant to look the same. */
        private const val HEADER_HEIGHT_RATIO = 0.6f

        private const val CAROUSEL_LIMIT = 12

        /** Below this a release is a single, and singles belong in the list, not the carousel. */
        private const val ALBUM_TRACK_FLOOR = 4

        /** Used when the track count is unknown, so unknowns are treated as albums. */
        private const val MANY_TRACKS = 99

        fun newInstance(artist: AcquirableArtist) = RequestArtistFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PROVIDER, artist.providerId)
                putString(ARG_ID, artist.id)
                putString(ARG_NAME, artist.name)
                putString(ARG_DISAMBIGUATION, artist.disambiguation)
                putString(ARG_ARTWORK, artist.artworkUrl)
                putInt(ARG_INTERNAL_ID, artist.internalId)
            }
        }
    }
}
