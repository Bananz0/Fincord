package uk.akane.accord.ui.fragments

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.Accord
import uk.akane.accord.logic.utils.BrowseGrid
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.SearchResultsAdapter
import uk.akane.accord.ui.adapters.RequestableReleasesAdapter
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import org.akanework.gramophone.logic.data.db.AppDatabase
import uk.akane.accord.logic.RecentSearches
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import uk.akane.accord.ui.adapters.SearchAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.accord.ui.components.TrackSwipeActions
import androidx.core.view.updatePadding
import org.akanework.gramophone.logic.data.acquisition.AcquirableArtist
import org.akanework.gramophone.logic.data.acquisition.AcquirableRelease
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProvider
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProviders
import org.akanework.gramophone.logic.data.acquisition.ReleaseAvailability
import org.akanework.gramophone.logic.data.acquisition.MusicRequestService
import org.akanework.gramophone.logic.data.catalog.CatalogResolution
import org.akanework.gramophone.logic.data.catalog.MusicCatalogProviders
import org.akanework.gramophone.logic.data.matching.MusicText
import org.akanework.gramophone.logic.data.matching.ReleaseMatcher
import org.akanework.gramophone.logic.data.matching.ScoredRelease
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import org.akanework.gramophone.ui.fragments.settings.LidarrSettingsFragment
import uk.akane.accord.ui.components.LibraryReleaseIndex
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.performPressHaptic

class SearchFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var indicatorTitleTextView: TextView
    private lateinit var indicatorSubtitleTextView: TextView
    private lateinit var detailedSearchContainer: View
    private lateinit var searchBarNav: View
    private lateinit var searchBarDetail: View
    private lateinit var tabContainer: View
    private lateinit var tabIndicator: View
    private lateinit var tabAppleTextView: TextView
    private lateinit var tabLibraryTextView: TextView
    private lateinit var searchInputNav: EditText
    private lateinit var searchInputDetail: EditText
    private var searchRoot: View? = null

    private lateinit var searchResults: RecyclerView
    private lateinit var searchStatus: View
    private lateinit var searchEmpty: TextView
    private lateinit var searchStatusAction: TextView
    private lateinit var searchProgress: View
    private lateinit var recentsHeader: View
    private lateinit var recentsDivider: View
    private lateinit var resultsAdapter: SearchResultsAdapter
    private lateinit var requestResultsAdapter: RequestableReleasesAdapter

    /** Immutable, pre-normalised snapshots keep a keystroke from rebuilding 30,000 strings. */
    @Volatile
    private var songSearchIndex: List<SongSearchEntry> = emptyList()
    @Volatile
    private var libraryById: Map<String, MediaItem> = emptyMap()
    @Volatile
    private var albumSearchIndex: List<AlbumSearchEntry> = emptyList()
    @Volatile
    private var artistSearchIndex: List<ArtistSearchEntry> = emptyList()
    private var pendingQuery: Job? = null

    private var indicatorTitleVisible = true
    private var indicatorSubtitleVisible = true
    private var isSearchExpanded = false
    private var isAppleTabSelected = true
    private var enterAnimator: AnimatorSet? = null
    private var exitAnimator: AnimatorSet? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_search, container, false)
        searchRoot = rootView
        setRootOpaque(true)
        navigationBar = rootView.findViewById(R.id.navigation_bar)
        recyclerView = rootView.findViewById(R.id.rv)
        indicatorTitleTextView = rootView.findViewById(R.id.no_track_title)
        indicatorSubtitleTextView = rootView.findViewById(R.id.no_track_subtitle)
        detailedSearchContainer = rootView.findViewById(R.id.detailed_search_container)
        searchBarNav = rootView.findViewById(R.id.search_bar_nav)
        searchBarDetail = rootView.findViewById(R.id.search_bar_detail)
        tabContainer = rootView.findViewById(R.id.tab_container)
        tabIndicator = rootView.findViewById(R.id.tab_apple_music_container)
        tabAppleTextView = rootView.findViewById(R.id.tab_apple_music)
        tabLibraryTextView = rootView.findViewById(R.id.tab_library)
        searchInputNav = searchBarNav.findViewById(R.id.search_input)
        searchInputDetail = searchBarDetail.findViewById(R.id.search_input)
        val statusBarBackdrop = rootView.findViewById<View>(R.id.status_bar_backdrop)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarBackdrop.layoutParams = statusBarBackdrop.layoutParams.apply {
                height = systemBars.top
            }
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            detailedSearchContainer.setPadding(
                detailedSearchContainer.paddingLeft,
                systemBars.top,
                detailedSearchContainer.paddingRight,
                detailedSearchContainer.paddingBottom
            )
            recyclerView.setPadding(
                recyclerView.paddingLeft,
                recyclerView.paddingTop,
                recyclerView.paddingRight,
                systemBars.bottom + resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
            )
            insets
        }

        recyclerView.layoutManager =
            GridLayoutManager(context, BrowseGrid.columnCount(requireContext()))
        recyclerView.adapter = SearchAdapter(requireContext(), this) {
            // Genres are already visible, so this cannot truthfully be an empty library. Do not
            // rely on a one-shot fade whose end state can be restored with alpha > 0 later.
            setLandingEmpty(false)
        }
        navigationBar.attach(recyclerView)

        tabAppleTextView.setOnClickListener { selectTab(true) }
        tabLibraryTextView.setOnClickListener { selectTab(false) }
        tabContainer.doOnLayout { updateTabIndicator(false) }
        updateTabSelection()

        detailedSearchContainer.visibility = View.GONE
        resetTabRevealState()

        searchResults = detailedSearchContainer.findViewById(R.id.search_results)
        searchStatus = detailedSearchContainer.findViewById(R.id.search_status)
        searchEmpty = detailedSearchContainer.findViewById(R.id.search_empty)
        searchStatusAction = detailedSearchContainer.findViewById(R.id.search_status_action)
        searchProgress = detailedSearchContainer.findViewById(R.id.search_progress)
        recentsHeader = detailedSearchContainer.findViewById(R.id.recently_searched_header)
        recentsDivider = detailedSearchContainer.findViewById(R.id.recently_searched_divider)
        detailedSearchContainer.findViewById<TextView>(R.id.recently_searched_clear)
            .setOnClickListener {
                it.performPressHaptic()
                RecentSearches.clear(requireContext())
                showRecents()
            }
        resultsAdapter = SearchResultsAdapter(
            player = { (activity as? MainActivity)?.getPlayer() },
            onAlbum = { album ->
                finishSearchSelection()
                rememberQuery()
                push(
                    AlbumDetailFragment.newInstance(
                        album.title.orEmpty(),
                        album.albumArtist.orEmpty(),
                    )
                )
            },
            onArtist = { artist ->
                finishSearchSelection()
                rememberQuery()
                push(ArtistDetailFragment.newInstance(artist.title.orEmpty(), artist.id))
            },
            onRecent = { query ->
                // Fills the field rather than only running the search, so the query can be edited
                // from where it left off - which is usually why it is being repeated.
                searchInputDetail.setText(query)
                searchInputDetail.setSelection(query.length)
            },
            onTrackSelected = {
                finishSearchSelection()
            },
        )
        requestResultsAdapter = RequestableReleasesAdapter(
            onReleaseClick = ::requestRelease,
            isInLibrary = libraryReleases::isInLibrary,
            onArtistClick = { artist ->
                finishSearchSelection()
                rememberQuery()
                push(RequestArtistFragment.newInstance(artist))
            },
        )
        searchResults.layoutManager = LinearLayoutManager(requireContext())
        searchResults.adapter = resultsAdapter
        // This list is not the one the navigation bar is attached to, so nothing was leaving
        // room for the mini player: the last result sat behind it and sprang back when
        // dragged into view.
        searchResults.clipToPadding = false
        // Applied straight away rather than on layout: this list lives inside a container that
        // starts hidden, so a layout-time hook either never ran or ran before the mini player had
        // a height - which is why the last result stayed underneath it.
        searchResults.updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
        ViewCompat.setOnApplyWindowInsetsListener(searchResults) { v, insets ->
            // Recomputed when the insets land, since the mini player sits above the gesture bar.
            v.updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
            insets
        }
        // Right queues, on the library tab. Left only exists on the Lidarr tab, where it means
        // "go and get this" - the one list in the app where the trailing edge has something to do.
        TrackSwipeActions.attach(
            recyclerView = searchResults,
            activity = requireActivity() as MainActivity,
            trackAt = { index ->
                if (isAppleTabSelected) resultsAdapter.itemAt(index) else null
            },
            trailing = TrackSwipeActions.Trailing(
                iconRes = R.drawable.ic_download,
                colorRes = R.color.accentColor,
                enabledAt = { index ->
                    !isAppleTabSelected && requestResultsAdapter.itemAt(index) != null
                },
                onAction = { index ->
                    requestResultsAdapter.itemAt(index)?.let { requestRelease(it) }
                },
            ),
        )

        observeLibrary()
        searchInputDetail.doAfterTextChanged { runQuery(it?.toString().orEmpty()) }
        searchInputDetail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(searchInputDetail)
                searchInputDetail.clearFocus()
                // Enter is an explicit submission, so do not make it wait for the typing debounce.
                runQuery(searchInputDetail.text?.toString().orEmpty(), debounce = false)
                true
            } else {
                false
            }
        }

        searchBarNav.setOnClickListener { enterSearchMode() }
        searchInputNav.setOnClickListener { enterSearchMode() }
        searchInputNav.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) enterSearchMode()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isSearchExpanded) {
                        exitSearchMode()
                        return
                    }
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        )

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        setRootOpaque(!hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }

    override fun onResume() {
        super.onResume()
        setRootOpaque(true)
    }

    override fun onPause() {
        // FragmentSwitcher stages the incoming detail in the container below this root. Leaving a
        // solid background here covers that page after Search's children animate away.
        setRootOpaque(false)
        super.onPause()
    }

    override fun onDestroyView() {
        searchRoot = null
        super.onDestroyView()
    }

    private fun setRootOpaque(opaque: Boolean) {
        searchRoot?.setBackgroundColor(
            if (opaque) requireContext().getColor(R.color.surfaceColor)
            else android.graphics.Color.TRANSPARENT
        )
    }

    /** A second tap on the already-selected Search destination goes straight to typing. */
    fun focusSearch() {
        if (!isAdded || view == null) return
        if (isSearchExpanded) focusDetailedSearch() else enterSearchMode()
    }

    /** Opens the normal search UI with an Assistant/deep-link query already applied. */
    fun showQuery(query: String) {
        if (!isAdded || view == null) return
        if (!isSearchExpanded) enterSearchMode()
        searchInputNav.setText(query)
        searchInputDetail.setText(query)
        searchInputDetail.setSelection(query.length)
        focusDetailedSearch()
    }

    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.songListFlow.collectLatest { songs ->
                    setLandingEmpty(songs.isEmpty())
                    val indexed = withContext(Dispatchers.Default) {
                        songs.map { item ->
                            val metadata = item.mediaMetadata
                            SongSearchEntry(
                                item = item,
                                title = metadata.title?.toString().orEmpty().normaliseForSearch(),
                                artist = metadata.artist?.toString().orEmpty().normaliseForSearch(),
                                album = metadata.albumTitle?.toString().orEmpty().normaliseForSearch(),
                            )
                        }
                    }
                    songSearchIndex = indexed
                    libraryById = indexed.associate { it.item.mediaId to it.item }
                    // Progressive sync can emit a new library page several times inside the
                    // typing debounce. Restarting that delay on every page starved the user's
                    // query until the whole sync ended. Let an active query finish against the
                    // latest immutable list reference; refresh an idle result immediately.
                    val currentQuery = searchInputDetail.text?.toString().orEmpty()
                    if (
                        isAppleTabSelected &&
                        currentQuery.isNotBlank() &&
                        pendingQuery?.isActive != true
                    ) {
                        runQuery(currentQuery, debounce = false)
                    }
                }
            }
        }
        // Separate collectors: these three flows do not emit together, and combining them would
        // hold the results back until the slowest had produced something.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.albumListFlow.collectLatest { albums ->
                    // Results already on screen can become "in your library" the moment a sync
                    // finishes, so the index follows the library rather than only the query.
                    if (libraryReleases.onLibraryChanged(albums) && !isAppleTabSelected) {
                        requestResultsAdapter.notifyItemRangeChanged(
                            0,
                            requestResultsAdapter.itemCount,
                        )
                    }
                    albumSearchIndex = withContext(Dispatchers.Default) {
                        albums.map { album ->
                            AlbumSearchEntry(
                                album = album,
                                title = album.title.orEmpty().normaliseForSearch(),
                                artist = album.albumArtist.orEmpty().normaliseForSearch(),
                            )
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.artistListFlow.collectLatest { artists ->
                    artistSearchIndex = withContext(Dispatchers.Default) {
                        artists.map { artist ->
                            ArtistSearchEntry(
                                artist = artist,
                                title = artist.title.orEmpty().normaliseForSearch(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setLandingEmpty(empty: Boolean) {
        val visibility = if (empty) View.VISIBLE else View.GONE
        indicatorTitleTextView.animate().cancel()
        indicatorSubtitleTextView.animate().cancel()
        indicatorTitleTextView.alpha = 1f
        indicatorSubtitleTextView.alpha = 1f
        indicatorTitleTextView.visibility = visibility
        indicatorSubtitleTextView.visibility = visibility
        indicatorTitleVisible = empty
        indicatorSubtitleVisible = empty
    }

    private fun push(fragment: Fragment) {
        (activity as? MainActivity)?.fragmentSwitcherView?.addFragmentToCurrentStack(fragment)
    }

    /**
     * Matches title, artist and album, all normalised, so "sabrina" finds "Sabrina Carpenter" and
     * punctuation in a tag does not hide a track.
     *
     * Debounced and run off the main thread: this scans the whole library on every keystroke, and
     * that library is thousands of items.
     */
    private fun runQuery(rawQuery: String, debounce: Boolean = true) {
        pendingQuery?.cancel()
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) {
            requestResultsAdapter.submit(emptyList())
            // An empty box is exactly when a broken Lidarr is worth saying out loud - waiting for a
            // query to report it means the user types something first and blames the search.
            if (isAppleTabSelected) hideStatus() else showLidarrSetupStatusIfNeeded()
            showRecents()
            return
        }
        setRecentsVisible(false)
        showResultsFor(recents = false)
        pendingQuery = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) {
                // Local search is cheap. Lidarr search is several real network calls, and sending
                // one after every 100 ms pause flooded its metadata proxy with abandoned queries,
                // making the final query and its artwork arrive later rather than sooner.
                delay(if (isAppleTabSelected) SEARCH_DEBOUNCE_MS else LIDARR_SEARCH_DEBOUNCE_MS)
            }
            if (isAppleTabSelected) {
                val rows = withContext(Dispatchers.Default) { buildResults(query) }
                resultsAdapter.submit(rows)
                if (rows.isEmpty()) showStatus(getString(R.string.search_no_results))
                else hideStatus()
            } else {
                searchForRequests(query)
            }
        }
    }

    /**
     * Offers what was searched before, on the screen that would otherwise be blank.
     *
     * The heading and its Clear button have been in this layout from the start with nothing behind
     * either - no storage, no list, no listener - which is why it never worked.
     */
    private fun showRecents() {
        val recent = RecentSearches.recent(requireContext())
        setRecentsVisible(recent.isNotEmpty())
        showResultsFor(recents = true)
        resultsAdapter.submit(recent.map { SearchResultsAdapter.Row.Recent(it) })
    }

    /**
     * Puts the right list in front of the user for what is about to be shown.
     *
     * There is one history behind both tabs - what you looked for is what you looked for, and
     * having to remember which tab you typed it on would be a strange thing to ask. Only the Apple
     * adapter can draw a bare query, though, so it takes the screen for recents whichever tab is
     * selected, and the request adapter comes back the moment there is something to search for.
     */
    private fun showResultsFor(recents: Boolean) {
        val target: RecyclerView.Adapter<*> =
            if (recents || isAppleTabSelected) resultsAdapter else requestResultsAdapter
        if (searchResults.adapter !== target) searchResults.adapter = target
    }

    private fun setRecentsVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        recentsHeader.visibility = visibility
        recentsDivider.visibility = visibility
    }

    /** Records the query behind a result the user actually opened. */
    private fun rememberQuery() {
        RecentSearches.remember(
            requireContext(),
            searchInputDetail.text?.toString().orEmpty(),
        )
    }

    /**
     * Artists, then albums, then songs - each ranked, each capped, each under its own heading.
     *
     * Ordered smallest section first on purpose. An artist or album match is nearly always what
     * someone typing a name is after, and burying it under forty tracks by that artist is what the
     * songs-only search did wrong.
     */
    private fun buildResults(query: String): List<SearchResultsAdapter.Row> {
        val needle = query.normaliseForSearch()
        if (needle.isBlank()) return emptyList()

        val artists = artistSearchIndex
            .rankedBy(needle, ARTIST_LIMIT) { it.title }
            .map { SearchResultsAdapter.Row.ArtistRow(it.artist) }

        val albumsByTitle = albumSearchIndex.rankedBy(needle, ALBUM_LIMIT) { it.title }
        // An artist's records are what someone typing their name is usually after, and matching
        // only album titles meant searching "kendrick" listed the artist and then went straight to
        // loose tracks, with the albums nowhere. Ranked under the title matches, which are still
        // the more literal answer to what was typed.
        val albumsByArtist = if (albumsByTitle.size >= ALBUM_LIMIT) emptyList() else {
            val alreadyShown = albumsByTitle.mapTo(HashSet()) { it.album.id }
            albumSearchIndex
                .filter { it.album.id !in alreadyShown }
                .rankedBy(needle, ALBUM_LIMIT - albumsByTitle.size) { it.artist }
        }
        val albums = (albumsByTitle + albumsByArtist)
            .map { SearchResultsAdapter.Row.AlbumRow(it.album) }

        val songEntries = songSearchIndex
            .rankedBy(needle, SEARCH_RESULT_LIMIT) { it.title }
        val songs = songEntries.map { SearchResultsAdapter.Row.SongRow(it.item) }

        // Songs still match on their artist and album, so a track can be found by the record it is
        // on - but only after the ones whose own title matched, which are the better answer.
        val extraSongs = if (songs.size >= SEARCH_RESULT_LIMIT) emptyList() else {
            val alreadyShown = songEntries.mapTo(HashSet()) { it.item.mediaId }
            songSearchIndex.asSequence()
                .filter { it.item.mediaId !in alreadyShown }
                .filter { it.artist.contains(needle) || it.album.contains(needle) }
                .take(SEARCH_RESULT_LIMIT - songs.size)
                .map { SearchResultsAdapter.Row.SongRow(it.item) }
                .toList()
        }

        return buildList {
            if (artists.isNotEmpty()) {
                add(SearchResultsAdapter.Row.Header(getString(R.string.category_artists)))
                addAll(artists)
            }
            if (albums.isNotEmpty()) {
                add(SearchResultsAdapter.Row.Header(getString(R.string.category_albums)))
                addAll(albums)
            }
            val allSongs = songs + extraSongs
            if (allSongs.isNotEmpty()) {
                add(SearchResultsAdapter.Row.Header(getString(R.string.category_songs)))
                addAll(allSongs)
            }
            // Last, and only for a query long enough to mean something. A two-letter fragment
            // matches half the library's lyrics and would bury everything above it.
            if (query.length >= MIN_LYRIC_QUERY) {
                val shown = (songs + extraSongs).mapTo(HashSet()) { it.item.mediaId }
                val lyrics = lyricMatches(query, shown)
                if (lyrics.isNotEmpty()) {
                    add(SearchResultsAdapter.Row.Header(getString(R.string.category_lyrics)))
                    addAll(lyrics)
                }
            }
        }
    }

    /**
     * Songs whose words match, with the phrase in context.
     *
     * Searched as a phrase rather than as loose terms: someone typing a line of a song means those
     * words in that order, and treating them as independent terms returns every track containing
     * "the" and "night" anywhere.
     */
    private fun lyricMatches(
        query: String,
        exclude: Set<String>,
    ): List<SearchResultsAdapter.Row.LyricRow> {
        val phrase = query.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        if (phrase.isBlank()) return emptyList()
        val byId = libraryById
        return runCatching {
            AppDatabase.getInstance(requireContext().applicationContext)
                .lyricsDao()
                .searchWithText("\"" + phrase + "\"", LYRIC_LIMIT)
        }.getOrDefault(emptyList())
            .mapNotNull { match ->
                val mediaId = match.localId.toString()
                if (mediaId in exclude) return@mapNotNull null
                val item = byId[mediaId] ?: return@mapNotNull null
                val context = match.text
                    .replace(FTS_MATCH_START, "")
                    .replace(FTS_MATCH_END, "")
                SearchResultsAdapter.Row.LyricRow(item, snippet(context, phrase))
            }
    }

    /**
     * The words around the hit.
     *
     * Whole lyrics in a one-line subtitle would show the first few words of the song, which is
     * rarely the part that matched and tells the user nothing about why the result is there.
     */
    private fun snippet(text: String, phrase: String): CharSequence {
        val at = text.indexOf(phrase, ignoreCase = true)
        if (at < 0) return text.take(SNIPPET_WINDOW * 2)
        val start = (at - SNIPPET_WINDOW).coerceAtLeast(0)
        val end = (at + phrase.length + SNIPPET_WINDOW).coerceAtMost(text.length)
        return buildString {
            if (start > 0) append("…")
            append(text, start, end)
            if (end < text.length) append("…")
        }
    }

    /**
     * Keeps everything that matches anywhere, but puts the obvious answers first.
     *
     * Filtering to word starts would be tidier and would lose real results: a half-remembered
     * fragment from the middle of a title is a perfectly good way to look for something. So the
     * match stays broad and the order carries the meaning - whole match, then prefix, then a word
     * start, then anywhere.
     */
    private inline fun <T> Iterable<T>.rankedBy(
        needle: String,
        limit: Int,
        crossinline normalisedName: (T) -> String,
    ): List<T> {
        return asSequence()
            .mapNotNull { candidate ->
                val text = normalisedName(candidate)
                val rank = when {
                    text == needle -> 0
                    text.startsWith(needle) -> 1
                    text.contains(" $needle") -> 2
                    text.contains(needle) -> 3
                    else -> return@mapNotNull null
                }
                // Shorter titles win ties: "Damn" before "Damn Right I've Got the Blues" when both
                // merely start with the query.
                Triple(rank, text.length, candidate)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }
            .toList()
    }

    private data class SongSearchEntry(
        val item: MediaItem,
        val title: String,
        val artist: String,
        val album: String,
    )

    private data class AlbumSearchEntry(
        val album: Album,
        val title: String,
        val artist: String,
    )

    private data class ArtistSearchEntry(
        val artist: Artist,
        val title: String,
    )

    /**
     * Shows the background message, optionally with a tappable line under it.
     *
     * Everything that can leave this screen with nothing to show goes through here so the list and
     * the message can never both be visible, and so a dead end always offers the way out of it.
     */
    /**
     * Reports a search in progress: the spinner and a line saying what is being waited on.
     *
     * Kept apart from [showStatus] because the two mean opposite things. A status is a conclusion;
     * this is a promise that one is coming.
     */
    private fun showSearching(message: CharSequence) {
        showStatus(message)
        searchProgress.visibility = View.VISIBLE
    }

    private fun showStatus(
        message: CharSequence,
        actionText: CharSequence? = null,
        action: (() -> Unit)? = null,
    ) {
        // Any conclusion ends the wait, so nothing can leave the spinner running by forgetting.
        searchProgress.visibility = View.GONE
        searchEmpty.text = message
        if (actionText != null && action != null) {
            searchStatusAction.text = actionText
            searchStatusAction.visibility = View.VISIBLE
            searchStatusAction.setOnClickListener {
                it.performPressHaptic()
                action()
            }
        } else {
            searchStatusAction.visibility = View.GONE
            searchStatusAction.setOnClickListener(null)
        }
        searchStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        searchProgress.visibility = View.GONE
        searchStatus.visibility = View.GONE
        searchStatusAction.setOnClickListener(null)
    }

    /**
     * Reports an unusable Lidarr on the empty Lidarr tab, and says which kind of unusable it is.
     *
     * Missing credentials and missing profiles need different actions - the first cannot be guessed
     * and the second is discovered from the server - so they are not collapsed into one message.
     *
     * @return true if a message was shown.
     */
    private fun showLidarrSetupStatusIfNeeded(): Boolean {
        val provider = AcquisitionProviders.active(requireContext())
        return when (provider.readiness(requireContext())) {
            AcquisitionProvider.Readiness.UNCONFIGURED -> {
                showStatus(
                    getString(R.string.search_lidarr_not_configured),
                    getString(R.string.search_lidarr_set_up),
                ) { openLidarrSettings() }
                true
            }

            AcquisitionProvider.Readiness.INCOMPLETE -> {
                // Searching works without a root folder and profiles, so this is a notice rather
                // than a wall: the query below still runs, and the missing settings are asked for
                // at the moment something is actually requested.
                showStatus(
                    getString(R.string.search_lidarr_incomplete),
                    getString(R.string.search_lidarr_open_settings),
                ) { openLidarrSettings() }
                false
            }

            AcquisitionProvider.Readiness.READY -> {
                hideStatus()
                false
            }
        }
    }

    private fun openLidarrSettings() {
        (activity as? MainActivity)?.fragmentSwitcherView
            ?.addFragmentToCurrentStack(LidarrSettingsFragment())
    }

    /**
     * The request tab: a phrase searches the downloader, a link is followed to whatever it names.
     *
     * Pasting an album link is the shortest path from "I heard this somewhere" to owning it, and it
     * used to be refused unless the link happened to be a playlist. Now the link is resolved, the
     * record it names is matched on identity, and the row that appears is the right one rather than
     * the best of five guesses.
     */
    private suspend fun searchForRequests(query: String) {
        requestResultsAdapter.submit(emptyList())
        if (showLidarrSetupStatusIfNeeded()) return
        showSearching(getString(R.string.requests_searching))

        val provider = AcquisitionProviders.active(requireContext())
        if (MusicCatalogProviders.looksLikeLink(query)) {
            showReleasesBehind(query, provider)
            return
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                // Artists and releases answer the same question and are asked for together, so the
                // slower of the two sets the wait rather than the sum of both.
                coroutineScope {
                    val artists = async { matchingArtists(provider, query) }
                    val releases = async {
                        MusicRequestService.find(
                            context = requireContext(),
                            query = ReleaseQuery.freeText(query),
                            provider = provider,
                            // Not on every keystroke: the metadata service behind the fallback is
                            // rate limited to one call a second, and half-typed words would spend
                            // them all.
                            useMetadataFallback = false,
                        )
                    }
                    artists.await() to releases.await()
                }
            }
        }
        result.onSuccess { (artists, ranked) ->
            if (MusicRequestService.needsSecondOpinion(ranked)) {
                // Near-misses are held back rather than flashed up and replaced half a second
                // later. The second pass returns them anyway, below whatever it finds, so nothing
                // is lost by waiting for the answer that is actually worth showing.
                askMetadataServiceFor(query, provider, artists)
            } else {
                showRows(artists, ranked.map(ScoredRelease<AcquirableRelease>::release))
            }
        }.onFailure { error ->
            requestResultsAdapter.submit(emptyList())
            showStatus(
                getString(
                    R.string.search_lidarr_unreachable,
                    error.message ?: getString(R.string.requests_failed),
                ),
                getString(R.string.search_lidarr_retry),
            ) { runQuery(searchInputDetail.text?.toString().orEmpty()) }
        }
    }

    /**
     * The second pass, once typing has stopped and the downloader has admitted it has nothing.
     *
     * Worth the wait: Lidarr's text search returns nothing at all for records its metadata service
     * holds perfectly well, so "no results" is frequently a failure of the search box rather than
     * an absence in the catalogue. The pause before asking is what keeps a rate-limited service
     * from being spent on half-typed words - and this coroutine is cancelled by the next keystroke,
     * so an abandoned query never gets there.
     */
    private suspend fun askMetadataServiceFor(
        query: String,
        provider: AcquisitionProvider,
        artists: List<AcquirableArtist>,
    ) {
        showSearching(getString(R.string.requests_searching_deeper))
        delay(METADATA_FALLBACK_DELAY_MS)
        val found = withContext(Dispatchers.IO) {
            runCatching {
                MusicRequestService.find(requireContext(), ReleaseQuery.freeText(query), provider)
                    .map(ScoredRelease<AcquirableRelease>::release)
            }.getOrDefault(emptyList())
        }
        showRows(artists, found)
    }

    /**
     * Artists worth putting at the top of the results.
     *
     * The downloader returns ten for any phrase and most are noise - "Chxrry" brings back four
     * Cherrys - so they go through the same matcher as everything else, and only a couple of the
     * closest survive. An artist row is a doorway, not an answer, and a list of doorways to the
     * wrong people is worse than none.
     */
    private suspend fun matchingArtists(
        provider: AcquisitionProvider,
        query: String,
    ): List<AcquirableArtist> = runCatching {
        provider.searchArtists(requireContext(), query)
            .map { it to MusicText.similarity(query, it.name) }
            .filter { it.second >= ARTIST_MATCH_FLOOR }
            .sortedByDescending { it.second }
            .take(MAX_ARTIST_ROWS)
            .map { it.first }
    }.getOrDefault(emptyList())

    private suspend fun showRows(
        artists: List<AcquirableArtist>,
        releases: List<AcquirableRelease>,
    ) {
        // Indexed before the rows are submitted, so every row is bound once with its final label.
        libraryReleases.index(releases)
        requestResultsAdapter.submitRows(
            artists.map(RequestableReleasesAdapter.Row::Artist) +
                releases.map(RequestableReleasesAdapter.Row::Release)
        )
        if (artists.isEmpty() && releases.isEmpty()) {
            showStatus(getString(R.string.requests_no_results))
        } else {
            hideStatus()
        }
    }

    /** The link branch of [searchForRequests], kept separate because it cannot be re-ranked. */
    private suspend fun showReleasesBehind(url: String, provider: AcquisitionProvider) {
        showSearching(getString(R.string.requests_reading_link))
        val result = withContext(Dispatchers.IO) {
            runCatching { releasesBehindLink(provider, url) }
        }
        result.onSuccess { releases ->
            requestResultsAdapter.submit(releases)
            if (releases.isEmpty()) showStatus(getString(R.string.requests_no_results))
            else hideStatus()
        }.onFailure { error ->
            requestResultsAdapter.submit(emptyList())
            showStatus(
                getString(
                    R.string.search_lidarr_unreachable,
                    error.message ?: getString(R.string.requests_failed),
                ),
                getString(R.string.search_lidarr_retry),
            ) { runQuery(searchInputDetail.text?.toString().orEmpty()) }
        }
    }

    /**
     * What a pasted link resolves to, as releases this downloader could fetch.
     *
     * Only convincing matches are shown. A link is a precise statement about one record, so
     * answering it with approximations would throw away the precision that made pasting it useful.
     */
    private suspend fun releasesBehindLink(
        provider: AcquisitionProvider,
        url: String,
    ): List<AcquirableRelease> {
        val queries = when (val resolution = MusicCatalogProviders.resolve(requireContext(), url)) {
            is CatalogResolution.Resolved ->
                resolution.releases.map { it.toReleaseQuery() }
                    .ifEmpty { resolution.tracks.map { it.toReleaseQuery() } }

            is CatalogResolution.Failed -> throw IllegalStateException(resolution.reason)
            is CatalogResolution.Unsupported ->
                throw IllegalStateException(resolution.provider + ": " + resolution.reason)

            CatalogResolution.Unrecognised ->
                throw IllegalStateException(getString(R.string.requests_unknown_link))
        }
        return MusicRequestService.collapse(queries)
            .take(MAX_LINK_RELEASES)
            .mapNotNull { query ->
                ReleaseMatcher.best(query, provider.search(requireContext(), query))?.release
            }
            .distinctBy(AcquirableRelease::id)
    }

    /**
     * Which results the user already owns, worked out once per list rather than per row.
     *
     * See [LibraryReleaseIndex]: doing this from the adapter meant matching against the whole
     * library on every bind, which is what made the list stutter while scrolling.
     */
    private val libraryReleases = LibraryReleaseIndex()

    private fun requestRelease(release: AcquirableRelease) {
        // Choosing a result ends the typing. Leaving the keyboard up covered the row that was just
        // acted on, so its own status - the only feedback there is - was hidden behind it.
        finishSearchSelection()

        when (requestResultsAdapter.requestState(release.id)) {
            RequestableReleasesAdapter.RequestState.SENDING -> return
            RequestableReleasesAdapter.RequestState.REQUESTED -> {
                showStatus(getString(R.string.requests_row_requested))
                return
            }
            else -> Unit
        }

        // Owning it already makes the row a way into the library, not a request. This is the whole
        // point of telling the two states apart.
        libraryReleases.match(release)?.let { album ->
            // This row leaves the search screen like any other result, so it owes the keyboard the
            // same courtesy. It was the one destination that did not, because it is reached from
            // the request handler rather than from a result callback.
            finishSearchSelection()
            rememberQuery()
            push(
                AlbumDetailFragment.newInstance(
                    album.title.orEmpty(),
                    album.albumArtist.orEmpty(),
                )
            )
            return
        }
        if (release.alreadyPresent) {
            showStatus(
                getString(
                    when (release.availability) {
                        ReleaseAvailability.DOWNLOADING -> R.string.requests_row_downloading
                        ReleaseAvailability.DOWNLOADED -> R.string.requests_row_downloaded
                        else -> R.string.requests_already_added
                    }
                )
            )
            return
        }
        val provider = AcquisitionProviders.active(requireContext())
        if (!provider.readiness(requireContext()).canRequest) {
            LidarrSetupPrompt.ensureConfigured(requireContext(), viewLifecycleOwner) {
                requestRelease(release)
            }
            return
        }
        // The row says what is happening to it. This app suppresses system toasts, so before this
        // a tap produced no visible response at all until the list reloaded seconds later.
        requestResultsAdapter.setState(release.id, RequestableReleasesAdapter.RequestState.SENDING)
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { provider.request(requireContext(), release) }
            }
            val succeeded = added.getOrDefault(false)
            requestResultsAdapter.setState(
                release.id,
                if (succeeded) RequestableReleasesAdapter.RequestState.REQUESTED
                else RequestableReleasesAdapter.RequestState.FAILED,
            )
            // Only a failure needs more than the row: it carries a reason worth reading.
            added.exceptionOrNull()?.let {
                showStatus(it.message ?: getString(R.string.requests_failed))
            }
        }
    }

    private fun String.normaliseForSearch(): String =
        lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }

    private fun enterSearchMode() {
        if (isSearchExpanded) return
        isSearchExpanded = true
        cancelSearchAnimations()

        indicatorTitleVisible = indicatorTitleTextView.visibility == View.VISIBLE && indicatorTitleTextView.alpha > 0f
        indicatorSubtitleVisible = indicatorSubtitleTextView.visibility == View.VISIBLE && indicatorSubtitleTextView.alpha > 0f

        detailedSearchContainer.visibility = View.VISIBLE
        detailedSearchContainer.alpha = 0f
        detailedSearchContainer.translationY = 24.dp.px
        searchBarDetail.alpha = 1f
        resetTabRevealState()

        if (!detailedSearchContainer.isLaidOut) {
            detailedSearchContainer.doOnLayout { startEnterAnimation() }
        } else {
            startEnterAnimation()
        }
    }

    private fun startEnterAnimation() {
        val duration = (AnimationUtils.MID_DURATION * 1.15f).toLong()
        val fadeDuration = duration
        val pageOffsetY = -24.dp.px
        val detailOffsetY = 24.dp.px
        val mainViews = getMainPageViews()
        searchInputDetail.setText(searchInputNav.text)
        searchInputDetail.setSelection(searchInputDetail.text.length)

        mainViews.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 1f
            view.translationY = 0f
        }
        detailedSearchContainer.alpha = 0f
        detailedSearchContainer.translationY = detailOffsetY

        val moveOut = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, pageOffsetY).apply {
                this.duration = duration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val fadeOut = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f).apply {
                this.duration = fadeDuration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val moveInDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.TRANSLATION_Y, 0f).apply {
            this.duration = duration
            interpolator = AnimationUtils.easingStandardInterpolator
        }
        val fadeInDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.ALPHA, 1f).apply {
            this.duration = fadeDuration
            interpolator = AnimationUtils.easingStandardInterpolator
        }

        val enterSet = AnimatorSet().apply {
            playTogether(*(moveOut + fadeOut + listOf(moveInDetail, fadeInDetail)).toTypedArray())
            doOnEnd {
                mainViews.forEach { it.visibility = View.INVISIBLE }
                focusDetailedSearch()
            }
        }

        enterAnimator = enterSet
        enterSet.start()
    }

    private fun focusDetailedSearch() {
        searchInputNav.clearFocus()
        searchInputDetail.requestFocus()
        showKeyboard(searchInputDetail)
    }

    private fun exitSearchMode() {
        if (!isSearchExpanded) return
        isSearchExpanded = false
        cancelSearchAnimations()

        hideKeyboard(searchInputDetail)
        searchInputDetail.clearFocus()
        searchInputNav.setText(searchInputDetail.text)
        searchInputNav.setSelection(searchInputNav.text.length)

        if (!detailedSearchContainer.isLaidOut) {
            detailedSearchContainer.doOnLayout { startExitAnimation() }
        } else {
            startExitAnimation()
        }
    }

    private fun startExitAnimation() {
        val duration = (AnimationUtils.MID_DURATION * 1.15f).toLong()
        val fadeDuration = duration
        val pageOffsetY = -24.dp.px
        val detailOffsetY = 24.dp.px
        val mainViews = getMainPageViews()

        mainViews.forEach { view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = pageOffsetY
        }
        detailedSearchContainer.alpha = 1f
        detailedSearchContainer.translationY = 0f

        val moveIn = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f).apply {
                this.duration = duration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val fadeIn = mainViews.map { view ->
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f).apply {
                this.duration = fadeDuration
                interpolator = AnimationUtils.easingStandardInterpolator
            }
        }
        val moveOutDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.TRANSLATION_Y, detailOffsetY).apply {
            this.duration = duration
            interpolator = AnimationUtils.easingStandardInterpolator
        }
        val fadeOutDetail = ObjectAnimator.ofFloat(detailedSearchContainer, View.ALPHA, 0f).apply {
            this.duration = fadeDuration
            interpolator = AnimationUtils.easingStandardInterpolator
        }

        val exitSet = AnimatorSet().apply {
            playTogether(*(moveIn + fadeIn + listOf(moveOutDetail, fadeOutDetail)).toTypedArray())
            doOnEnd {
                detailedSearchContainer.visibility = View.GONE
                resetTabRevealState()
            }
        }

        exitAnimator = exitSet
        exitSet.start()
    }

    private fun cancelSearchAnimations() {
        enterAnimator?.cancel()
        exitAnimator?.cancel()
        enterAnimator = null
        exitAnimator = null
    }

    private fun resetTabRevealState() {
        tabContainer.alpha = 1f
        tabContainer.translationY = 0f
        tabContainer.scaleX = 1f
        tabContainer.scaleY = 1f
    }

    private fun getMainPageViews(): List<View> {
        val views = mutableListOf<View>(navigationBar, recyclerView)
        if (indicatorTitleVisible) {
            indicatorTitleTextView.visibility = View.VISIBLE
            views.add(indicatorTitleTextView)
        } else {
            indicatorTitleTextView.visibility = View.GONE
        }
        if (indicatorSubtitleVisible) {
            indicatorSubtitleTextView.visibility = View.VISIBLE
            views.add(indicatorSubtitleTextView)
        } else {
            indicatorSubtitleTextView.visibility = View.GONE
        }
        return views
    }

    private fun selectTab(isAppleTab: Boolean) {
        if (isAppleTabSelected == isAppleTab) return
        isAppleTabSelected = isAppleTab
        tabContainer.performPressHaptic()
        updateTabSelection()
        updateTabIndicator(true)
        searchResults.scrollToPosition(0)
        // Leaves the adapter to runQuery, which knows whether what is about to be drawn is a set
        // of results or the shared history.
        runQuery(searchInputDetail.text?.toString().orEmpty())
    }

    private fun updateTabSelection() {
        val selectedColor = requireContext().getColor(R.color.onSurfaceColor)
        val inactiveColor = requireContext().getColor(R.color.onSurfaceColorInactive)
        tabAppleTextView.setTextColor(if (isAppleTabSelected) selectedColor else inactiveColor)
        tabLibraryTextView.setTextColor(if (isAppleTabSelected) inactiveColor else selectedColor)
    }

    private fun updateTabIndicator(animate: Boolean) {
        val container = tabIndicator.parent as View
        val containerWidth = container.width
        if (containerWidth == 0) return
        val marginParams = tabIndicator.layoutParams as ViewGroup.MarginLayoutParams
        val horizontalMargin = marginParams.leftMargin + marginParams.rightMargin
        val segmentWidth = (containerWidth - horizontalMargin) / 2f
        val targetWidth = segmentWidth.toInt()
        if (marginParams.width != targetWidth) {
            marginParams.width = targetWidth
            tabIndicator.layoutParams = marginParams
        }
        val targetTranslation = if (isAppleTabSelected) {
            0f
        } else {
            (containerWidth - targetWidth - horizontalMargin).toFloat()
        }
        if (animate) {
            tabIndicator.animate()
                .translationX(targetTranslation)
                .setDuration(AnimationUtils.MID_DURATION)
                .setInterpolator(AnimationUtils.easingStandardInterpolator)
                .start()
        } else {
            tabIndicator.translationX = targetTranslation
        }
    }

    private fun showKeyboard(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard(target: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    /** Every result destination leaves the query intact but gives the screen back to its content. */
    private fun finishSearchSelection() {
        hideKeyboard(searchInputDetail)
        searchInputDetail.clearFocus()
    }

    private companion object {
        /** Long enough that a fast typist scans the library once, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 100L

        /** Lets a word settle before spending three downloader/metadata round trips on it. */
        const val LIDARR_SEARCH_DEBOUNCE_MS = 350L

        /** The list is scrolled, not read whole; past this it is cheaper to refine the query. */
        const val SEARCH_RESULT_LIMIT = 200

        /**
         * A pasted artist link can name a hundred records, and each one costs a lookup. Show the
         * first handful rather than making the user wait on a discography they did not ask for.
         */
        const val MAX_LINK_RELEASES = 12

        /** Long enough to mean "they stopped typing", short enough not to feel like a hang. */
        const val METADATA_FALLBACK_DELAY_MS = 450L

        /** Below this an artist row is a doorway to the wrong person. */
        const val ARTIST_MATCH_FLOOR = 0.7

        /** Two is a disambiguation; ten is the downloader's unfiltered guess list. */
        const val MAX_ARTIST_ROWS = 2

        /**
         * Deliberately small. These sections sit above the songs, and a hundred artists between the
         * query and the track someone wanted is worse than no artist section at all.
         */
        const val ARTIST_LIMIT = 6
        const val ALBUM_LIMIT = 8

        /** Long enough to be a phrase somebody means, rather than a fragment matching everything. */
        const val MIN_LYRIC_QUERY = 4
        const val LYRIC_LIMIT = 12

        /** Characters of context either side of a lyric hit. */
        const val SNIPPET_WINDOW = 42

        /** Private-use markers let FTS choose the matching fragment without leaking markup to UI. */
        const val FTS_MATCH_START = ""
        const val FTS_MATCH_END = ""
    }
}
