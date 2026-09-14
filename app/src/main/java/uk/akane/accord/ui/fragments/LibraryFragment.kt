package uk.akane.accord.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.ui.home.HomeCard
import org.akanework.gramophone.ui.home.HomeCardTarget
import org.akanework.gramophone.ui.home.HomeSection
import org.akanework.gramophone.ui.home.HomeSectionAdapter
import uk.akane.accord.R
import uk.akane.accord.logic.ArtistCredits
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.LibraryHeadAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment

class LibraryFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var libraryRecyclerView: RecyclerView
    private lateinit var collectionAdapter: HomeSectionAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_library, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        libraryRecyclerView = rootView.findViewById(R.id.library_rv)

        collectionAdapter = HomeSectionAdapter(
            player = { (activity as? MainActivity)?.getPlayer() },
            onCardClick = { _, card ->
                (activity as? MainActivity)?.fragmentSwitcherView?.addFragmentToCurrentStack(
                    AlbumDetailFragment.newInstance(card.title, card.subtitle.orEmpty())
                )
            },
        )
        libraryRecyclerView.layoutManager = LinearLayoutManager(context)
        libraryRecyclerView.adapter = ConcatAdapter(
            LibraryHeadAdapter(requireContext()),
            collectionAdapter,
        )

        navigationBar.attach(libraryRecyclerView)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navigationBar.setPadding(
                navigationBar.paddingLeft,
                systemBars.top,
                navigationBar.paddingRight,
                navigationBar.paddingBottom
            )
            insets
        }

        observeCollections()
        return rootView
    }

    private fun observeCollections() {
        val mainActivity = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainActivity.reader.songListFlow.collectLatest { songs ->
                    val sections = withContext(Dispatchers.Default) {
                        val recent = albumCards(songs.sortedByDescending(::addDate))
                        val explicitDownloadIds = withContext(Dispatchers.IO) {
                            JellyfinDownloadManager.completedIds(requireContext())
                        }
                        val availableOfflineIds = withContext(Dispatchers.IO) {
                            JellyfinDownloadManager.availableOfflineIds(requireContext(), songs)
                        }
                        val downloaded = albumCards(songs.filter { it.mediaId in explicitDownloadIds })
                        val availableOffline = albumCards(
                            songs.filter { it.mediaId in availableOfflineIds }
                        )
                        buildList {
                            if (recent.isNotEmpty()) add(
                                HomeSection(
                                    id = "library_recently_added",
                                    title = getString(R.string.recently_added),
                                    subtitle = "The newest albums in your library",
                                    cards = recent,
                                )
                            )
                            if (downloaded.isNotEmpty()) add(
                                HomeSection(
                                    id = "library_downloaded",
                                    title = getString(R.string.downloads_size),
                                    subtitle = "Available offline on this device",
                                    cards = downloaded,
                                )
                            )
                            if (availableOffline.isNotEmpty()) add(
                                HomeSection(
                                    id = "library_available_offline",
                                    title = getString(R.string.available_offline),
                                    subtitle = "Downloads and fully cached music",
                                    cards = availableOffline,
                                )
                            )
                        }
                    }
                    collectionAdapter.submit(sections)
                }
            }
        }
    }

    private fun albumCards(source: List<MediaItem>): List<HomeCard> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        source.forEach { song ->
            val title = song.mediaMetadata.albumTitle?.toString()?.trim()
                ?.takeIf(String::isNotBlank) ?: return@forEach
            val artist = ArtistCredits.primaryArtist(song)
            val key = "${title.lowercase()}\u0000${artist.lowercase()}"
            groups.getOrPut(key) { mutableListOf() }.add(song)
        }
        return groups.values.take(12).map { tracks ->
            HomeCard(
                title = tracks.first().mediaMetadata.albumTitle?.toString().orEmpty(),
                subtitle = ArtistCredits.primaryArtist(tracks.first()),
                cover = tracks.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                songs = tracks,
                target = HomeCardTarget.ALBUM,
            )
        }
    }

    private fun addDate(item: MediaItem): Long =
        item.mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }
}
