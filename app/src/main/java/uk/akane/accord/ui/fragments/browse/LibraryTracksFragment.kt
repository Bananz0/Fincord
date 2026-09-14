package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import uk.akane.accord.ui.components.TrackSwipeActions
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.browse.SongAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment

/** Recently-added and offline-only library views, backed by the normal playable song rows. */
class LibraryTracksFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity
    private lateinit var navigationBar: NavigationBar

    init { postponeSwitcherAnimation() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.fragment_browse_song, container, false)
        val mode = Mode.valueOf(requireArguments().getString(ARG_MODE) ?: Mode.RECENT.name)
        navigationBar = root.findViewById(R.id.navigation_bar)
        navigationBar.setTitle(when (mode) {
            Mode.RECENT -> getString(R.string.recently_added)
            Mode.DOWNLOADED -> getString(R.string.downloads_size)
            Mode.AVAILABLE_OFFLINE -> getString(R.string.available_offline)
        })
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }

        val recycler = root.findViewById<RecyclerView>(R.id.rv)
        val adapter = SongAdapter(
            recyclerView = recycler,
            fragment = this,
            sourceTransform = { songs ->
                when (mode) {
                    Mode.RECENT -> songs.sortedByDescending { addDate(it) }
                    Mode.DOWNLOADED, Mode.AVAILABLE_OFFLINE -> {
                        val ids = withContext(Dispatchers.IO) {
                            if (mode == Mode.DOWNLOADED) {
                                JellyfinDownloadManager.completedIds(requireContext())
                            } else {
                                JellyfinDownloadManager.availableOfflineIds(requireContext(), songs)
                            }
                        }
                        songs.filter { it.mediaId in ids }
                            .sortedBy { it.mediaMetadata.title?.toString() }
                    }
                }
            },
            onContentLoaded = { notifyContentLoaded() },
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        // Recently added, Downloads and Available offline are ordinary song lists and were the
        // only ones without the gestures every other song list has.
        TrackSwipeActions.attach(
            recyclerView = recycler,
            activity = activity,
            trackAt = { index -> adapter.itemAt(index) },
        )
        root.findViewById<EditText?>(R.id.search_input)?.doAfterTextChanged {
            adapter.setFilter(it?.toString().orEmpty())
        }
        navigationBar.attach(recycler)
        return root
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }

    private fun addDate(item: MediaItem): Long =
        item.mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L

    enum class Mode { RECENT, DOWNLOADED, AVAILABLE_OFFLINE }

    companion object {
        private const val ARG_MODE = "library_tracks_mode"

        fun recent() = newInstance(Mode.RECENT)
        fun downloaded() = newInstance(Mode.DOWNLOADED)
        fun availableOffline() = newInstance(Mode.AVAILABLE_OFFLINE)

        private fun newInstance(mode: Mode) = LibraryTracksFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
