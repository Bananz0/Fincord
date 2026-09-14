package uk.akane.accord.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.AcquirableRelease
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProvider
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProviders
import org.akanework.gramophone.logic.data.acquisition.MusicRequestService
import org.akanework.gramophone.logic.data.acquisition.ReleaseAvailability
import org.akanework.gramophone.logic.data.catalog.CatalogResolution
import org.akanework.gramophone.logic.data.catalog.MusicCatalogProviders
import org.akanework.gramophone.logic.data.matching.ReleaseQuery
import org.akanework.gramophone.logic.data.matching.ScoredRelease
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.adapters.RequestableReleasesAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.LibraryReleaseIndex
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.TrackSwipeActions

/**
 * Asking for music that is not in the library yet.
 *
 * Two ways in, both through the same field. Type a name and it searches whichever downloader is set
 * up, which looks the album up and can fetch it. Paste a link - an album, a track, an artist or a
 * playlist, from any service this app can read - and what it names becomes a request. That second
 * case is the one worth having: someone shares a record and the library catches up with it without
 * anybody retyping a title.
 *
 * Downloaders work in releases, so a single track is requested by asking for the release carrying
 * it. There is no way to fetch one song on its own, and pretending otherwise would fail quietly.
 */
class RequestsFragment : Fragment() {

    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var results: RecyclerView
    private lateinit var adapter: RequestableReleasesAdapter

    private val provider: AcquisitionProvider
        get() = AcquisitionProviders.active(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_requests, container, false)

        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            (activity as? MainActivity)?.fragmentSwitcherView?.popBackTopFragmentIfExists()
        }

        query = rootView.findViewById(R.id.request_query)
        status = rootView.findViewById(R.id.request_status)
        progress = rootView.findViewById(R.id.request_progress)
        results = rootView.findViewById(R.id.request_results)
        adapter = RequestableReleasesAdapter(
            onReleaseClick = ::requestRelease,
            isInLibrary = libraryReleases::isInLibrary,
        )
        observeLibrary()
        results.layoutManager = LinearLayoutManager(requireContext())
        results.adapter = adapter
        // Same gesture as everywhere else in the app; here both directions mean request it.
        TrackSwipeActions.attachRequest(
            recyclerView = results,
            canSwipe = { position -> adapter.itemAt(position)?.alreadyPresent == false },
            onRequest = { position -> adapter.itemAt(position)?.let { requestRelease(it) } },
        )

        query.setOnEditorActionListener { _, _, _ ->
            submit(query.text?.toString().orEmpty())
            true
        }

        // Arrived from another app's share sheet. Show what was shared in the field - so it is
        // obvious what is being requested and it can be edited - and get on with it.
        arguments?.getString(ARG_LINK)?.takeIf { it.isNotBlank() }?.let { link ->
            query.setText(link)
            query.post { submit(link) }
        }
        return rootView
    }

    /** A link is followed; anything else is treated as a search term. */
    private fun submit(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        // Searching here is an explicit act, so the keyboard has done its job and the results
        // deserve the screen.
        hideKeyboard()
        // Searching only needs somewhere to ask. A root folder and profiles are needed to actually
        // fetch something, and gating search on them meant a perfectly reachable server still
        // answered "set it up first".
        if (!provider.readiness(requireContext()).canSearch) {
            showStatus(getString(R.string.requests_no_lidarr))
            return
        }
        if (MusicCatalogProviders.looksLikeLink(text)) importLink(text) else search(text)
    }

    private fun search(term: String) {
        showBusy(getString(R.string.requests_searching))
        viewLifecycleOwner.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    // This screen searches on Enter, not on every keystroke, so the metadata
                    // fallback is always worth taking here.
                    MusicRequestService
                        .find(requireContext(), ReleaseQuery.freeText(term), provider)
                        .map(ScoredRelease<AcquirableRelease>::release)
                }
            }
            found.onSuccess { releases ->
                libraryReleases.index(releases)
                adapter.submit(releases)
                showStatus(
                    if (releases.isEmpty()) getString(R.string.requests_no_results)
                    else resources.getQuantityString(
                        R.plurals.requests_results, releases.size, releases.size
                    )
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
        }
    }

    /**
     * Follows a link and requests what it named.
     *
     * A link that names one release is requested outright - that is the whole reason someone pasted
     * it. A playlist becomes the set of releases behind its tracks, which is the same request a
     * person would have made by hand after looking each one up.
     */
    private fun importLink(url: String) {
        showBusy(getString(R.string.requests_reading_link))
        viewLifecycleOwner.lifecycleScope.launch {
            val resolution = withContext(Dispatchers.IO) {
                MusicCatalogProviders.resolve(requireContext(), url)
            }
            when (resolution) {
                is CatalogResolution.Resolved -> requestAll(resolution)
                is CatalogResolution.Failed -> showStatus(resolution.reason)
                is CatalogResolution.Unsupported ->
                    showStatus(resolution.provider + ": " + resolution.reason)

                CatalogResolution.Unrecognised ->
                    showStatus(getString(R.string.requests_unknown_link))
            }
        }
    }

    private suspend fun requestAll(resolved: CatalogResolution.Resolved) {
        val queries = resolved.releases.map { it.toReleaseQuery() }
            .ifEmpty { resolved.tracks.map { it.toReleaseQuery() } }
        if (queries.isEmpty()) {
            showStatus(getString(R.string.requests_no_results))
            return
        }
        if (!ensureConfigured { viewLifecycleOwner.lifecycleScope.launch { requestAll(resolved) } }) {
            return
        }
        showBusy(getString(R.string.requests_sending, queries.size))
        val outcome = withContext(Dispatchers.IO) {
            runCatching { MusicRequestService.request(requireContext(), queries) }
        }
        outcome.onSuccess {
            showStatus(
                when {
                    it.failureReason != null -> it.failureReason
                    // Saying "requested 0" when everything asked for is already tracked reads as a
                    // failure, and this is the case a shared album most often lands in.
                    it.requested == 0 && it.alreadyPresent > 0 ->
                        resources.getQuantityString(
                            R.plurals.requests_already_have, it.alreadyPresent, it.alreadyPresent
                        )

                    else -> getString(R.string.requests_outcome, it.requested, it.notFound)
                }
            )
        }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
    }

    /**
     * Which results the user already owns, worked out once per list rather than per row.
     *
     * See [LibraryReleaseIndex]: doing this from the adapter meant matching against the whole
     * library on every bind, which is what made the list stutter while scrolling.
     */
    private val libraryReleases = LibraryReleaseIndex()

    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.albumListFlow.collectLatest { albums ->
                    if (libraryReleases.onLibraryChanged(albums)) {
                        adapter.notifyItemRangeChanged(0, adapter.itemCount)
                    }
                }
            }
        }
    }

    private fun requestRelease(release: AcquirableRelease) {
        // Choosing a result ends the typing; the keyboard would otherwise cover the row's status.
        hideKeyboard()

        when (adapter.requestState(release.id)) {
            RequestableReleasesAdapter.RequestState.SENDING -> return
            RequestableReleasesAdapter.RequestState.REQUESTED -> {
                showStatus(getString(R.string.requests_row_requested))
                return
            }
            else -> Unit
        }

        // Something already in the library is a way in, not a request.
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
        // The downloader will not accept a release without a root folder and both profiles. Rather
        // than sending the user off to find three fields, ask for them here - the server knows what
        // the choices are - and carry on with the request that prompted it.
        if (!ensureConfigured { requestRelease(release) }) return

        adapter.setState(release.id, RequestableReleasesAdapter.RequestState.SENDING)
        viewLifecycleOwner.lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching { provider.request(requireContext(), release) }
            }
            val succeeded = added.getOrDefault(false)
            // The row carries the outcome; the list is left exactly as it was.
            adapter.setState(
                release.id,
                if (succeeded) RequestableReleasesAdapter.RequestState.REQUESTED
                else RequestableReleasesAdapter.RequestState.FAILED,
            )
            added.onSuccess {
                showStatus(
                    if (it) getString(R.string.requests_added, release.title)
                    else getString(R.string.requests_failed)
                )
            }.onFailure { showStatus(it.message ?: getString(R.string.requests_failed)) }
        }
    }

    /** True when the request may proceed now; false when the user is being asked for settings. */
    private fun ensureConfigured(retry: () -> Unit): Boolean {
        if (provider.readiness(requireContext()).canRequest) return true
        LidarrSetupPrompt.ensureConfigured(requireContext(), viewLifecycleOwner) { retry() }
        return false
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
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

    companion object {
        private const val ARG_LINK = "link"

        /** Opens this screen already pointed at [url] - the share-sheet entry point. */
        fun forLink(url: String) = RequestsFragment().apply {
            arguments = Bundle().apply { putString(ARG_LINK, url) }
        }
    }
}
