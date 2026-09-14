package uk.akane.accord.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.ui.home.HomeCard
import org.akanework.gramophone.ui.home.HomeCardTarget
import org.akanework.gramophone.ui.home.HomeFeed
import org.akanework.gramophone.ui.home.HomeFeedCache
import org.akanework.gramophone.ui.home.HomeSection
import org.akanework.gramophone.ui.home.HomeSectionStyle
import org.akanework.gramophone.ui.home.HomeSectionAdapter
import uk.akane.accord.Accord
import uk.akane.accord.R
import uk.akane.accord.logic.ArtistCredits
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.BannerCarouselAdapter
import uk.akane.accord.ui.adapters.BannerItem
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.CollageArtView
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.fragments.browse.StationDetailFragment

class HomeFragment: Fragment() {
    private lateinit var navigationBar: NavigationBar
    private lateinit var sectionAdapter: HomeSectionAdapter
    private lateinit var headerAdapter: HeaderAdapter

    /** Bound when the header row is created, which is after the fragment's view. */
    private var subtitle: TextView? = null
    private var subtitleText: CharSequence? = null

    /** Fetched once per view, and folded back into the feed on every later rebuild. */
    private var similarSection: HomeSection? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_home, container, false)
        navigationBar = rootView.findViewById(R.id.navigation_bar)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        // The profile control opens settings
        navigationBar.setOnAvatarClickListener {
            (activity as? MainActivity)?.fragmentSwitcherView
                ?.addFragmentToCurrentStack(SettingsFragment())
        }

        sectionAdapter = HomeSectionAdapter(
            player = { (activity as? MainActivity)?.getPlayer() },
            onCardClick = { section, card -> openStation(section, card) }
        )
        headerAdapter = HeaderAdapter { subtitle = it }

        // Restore complete identities as well as labels, so cached stations remain usable on frame 1.
        headerAdapter.setBannerItems(HomeFeedCache.loadBanners(requireContext()).orEmpty())
        val cachedSections = HomeFeedCache.load(requireContext())
        sectionAdapter.submit(cachedSections ?: buildInitialSkeletonSections(requireContext()))
        cachedSections?.let { sections ->
            CollageArtView.prefetch(
                requireContext(),
                sections.asSequence()
                    .flatMap { it.cards.asSequence() }
                    .flatMap { it.collageCovers.asSequence() }
                    .asIterable(),
            )
        }

        rootView.findViewById<RecyclerView>(R.id.home_sections).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(headerAdapter, sectionAdapter)
            // The layout reserved only the nav bar's height, so the mini player sat over the last
            // row's labels and they sprang back out of reach when dragged up. bottomHeight is the
            // nav bar and the player together, and is recomputed when the insets land because the
            // player sits above the gesture bar.
            clipToPadding = false
            updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.updatePadding(bottom = (requireActivity() as MainActivity).bottomHeight)
                insets
            }
        }

        observeLibrarySync()
        observeLibrary()
        return rootView
    }

    /**
     * Clean skeleton placeholder cards for fresh installs before disk cache exists.
     * Uses empty titles so there are NO misleading text swaps when real library items land.
     */
    private fun buildInitialSkeletonSections(context: Context): List<HomeSection> {
        val jumpBackInCards = List(6) {
            HomeCard(
                title = "",
                subtitle = null,
                cover = null,
                songs = emptyList()
            )
        }
        val skeletonCards = List(6) {
            HomeCard(
                title = "",
                subtitle = null,
                cover = null,
                songs = emptyList()
            )
        }
        return listOf(
            HomeSection(
                id = "jump_back_in",
                title = context.getString(R.string.home_jump_back_in),
                cards = jumpBackInCards
            ),
            HomeSection(
                id = "top_mixes",
                title = context.getString(R.string.home_top_mixes),
                subtitle = context.getString(R.string.home_top_mixes_subtitle),
                cards = skeletonCards
            )
        )
    }

    private fun observeLibrarySync() {
        val jellyfin = (requireActivity().application as Accord).jellyfinReader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                jellyfin.syncProgress.collect { progress ->
                    subtitleText = when {
                        progress == null -> getString(R.string.recommendations)
                        progress.second > 0 ->
                            getString(R.string.sync_progress, progress.first, progress.second)
                        else -> getString(R.string.sync_in_progress)
                    }
                    subtitle?.text = subtitleText
                }
            }
        }
    }

    private fun observeLibrary() {
        val reader = (requireActivity().application as Accord).reader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reader.songListFlow.collect { songs ->
                    val artists = withContext(Dispatchers.Default) {
                        songs.groupBy(ArtistCredits::primaryArtist)
                            .map { (name, tracks) -> HomeFeed.ArtistInput(name, tracks) }
                    }
                    val sections = withContext(Dispatchers.Default) {
                        HomeFeed.build(requireContext(), songs, artists)
                    }
                    val bannerItems = withContext(Dispatchers.Default) {
                        HomeFeed.banners(requireContext(), songs, artists)
                    }
                    if (!isAdded) return@collect
                    if (bannerItems.isNotEmpty()) {
                        val displayedBanners = mergeBanners(
                            headerAdapter.currentItems(),
                            bannerItems
                        )
                        headerAdapter.setBannerItems(displayedBanners)
                        withContext(Dispatchers.IO) {
                            HomeFeedCache.saveBanners(requireContext(), displayedBanners)
                        }
                    }
                    if (sections.isNotEmpty()) {
                        val displayedSections = mergeSections(
                            sectionAdapter.currentSections(),
                            withSimilar(sections)
                        )
                        CollageArtView.prefetch(
                            requireContext(),
                            displayedSections.asSequence()
                                .flatMap { it.cards.asSequence() }
                                .flatMap { it.collageCovers.asSequence() }
                                .asIterable(),
                        )
                        sectionAdapter.submit(displayedSections)
                        withContext(Dispatchers.IO) {
                            HomeFeedCache.save(requireContext(), displayedSections)
                        }
                    }
                    fetchSimilarArtists(artists)
                }
            }
        }
    }

    private fun fetchSimilarArtists(artists: List<HomeFeed.ArtistInput>) {
        if (similarSection != null || artists.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val section = withContext(Dispatchers.IO) {
                HomeFeed.similarArtistSection(requireContext(), artists)
            } ?: return@launch
            if (!isAdded) return@launch
            similarSection = section
            sectionAdapter.submit(withSimilar(sectionAdapter.currentSections()))
        }
    }

    private fun openStation(section: HomeSection, card: HomeCard) {
        val activity = activity as? MainActivity ?: return
        if (card.target == HomeCardTarget.ALBUM) {
            val albumTitle = card.title
            val albumArtist = card.subtitle ?: ""
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                AlbumDetailFragment.newInstance(albumTitle, albumArtist)
            )
        } else {
            if (card.mediaIds.isEmpty()) return
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                StationDetailFragment.newInstance(
                    title = card.title.ifEmpty { section.title },
                    subtitle = section.title,
                    mediaIds = card.mediaIds,
                    cover = card.cover ?: card.collageCovers.firstOrNull(),
                    kind = if (section.style == HomeSectionStyle.STATION) {
                        StationDetailFragment.CollectionKind.STATION
                    } else {
                        StationDetailFragment.CollectionKind.MIX
                    },
                )
            )
        }
    }

    private fun openBannerStation(item: BannerItem) {
        if (item.mediaIds.isEmpty()) return
        val activity = activity as? MainActivity ?: return
        activity.fragmentSwitcherView.addFragmentToCurrentStack(
            StationDetailFragment.newInstance(
                title = item.title,
                subtitle = item.subtitle ?: item.artistsSummary ?: getString(R.string.recommendations),
                mediaIds = item.mediaIds,
                cover = item.cover,
                kind = StationDetailFragment.CollectionKind.STATION,
            )
        )
    }

    private inner class HeaderAdapter(
        private val onSubtitleBound: (TextView) -> Unit
    ) : RecyclerView.Adapter<HeaderAdapter.ViewHolder>() {

        private var carouselItems: List<BannerItem> = emptyList()
        private var carouselAdapter: BannerCarouselAdapter? = null

        fun setBannerItems(items: List<BannerItem>) {
            carouselItems = items
            if (carouselAdapter == null) {
                notifyItemChanged(0)
            } else {
                carouselAdapter?.submitList(items)
            }
        }

        fun currentItems(): List<BannerItem> = carouselItems

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_home_header, parent, false)
            val holder = ViewHolder(view)
            holder.carouselRecyclerView.layoutManager =
                LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            PagerSnapHelper().attachToRecyclerView(holder.carouselRecyclerView)
            return holder
        }

        override fun getItemCount(): Int = 1

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            onSubtitleBound(holder.subtitle)
            subtitleText?.let { holder.subtitle.text = it }

            if (carouselAdapter == null) {
                carouselAdapter = BannerCarouselAdapter(carouselItems) { item ->
                    openBannerStation(item)
                }
                holder.carouselRecyclerView.adapter = carouselAdapter
            } else {
                carouselAdapter?.submitList(carouselItems)
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val subtitle: TextView = view.findViewById(R.id.subtitle)
            val carouselRecyclerView: RecyclerView = view.findViewById(R.id.banner_carousel)
        }
    }

    private fun withSimilar(sections: List<HomeSection>): List<HomeSection> {
        val similar = similarSection ?: return sections
        if (sections.any { it.id == similar.id }) return sections
        return sections.toMutableList().apply { add(minOf(1, size), similar) }
    }

    /**
     * Refresh data in-place. Library sync emits several successively larger snapshots; retaining
     * existing row/card positions prevents every emission from visibly rearranging the home page.
     */
    private fun mergeSections(
        current: List<HomeSection>,
        fresh: List<HomeSection>
    ): List<HomeSection> {
        if (current.isEmpty() || current.all(::isSkeletonSection)) return fresh
        val cachedSimilar = current.firstOrNull { it.id == "for_fans_of_v2" }
        val completeFresh = if (
            cachedSimilar != null && fresh.none { it.id == cachedSimilar.id }
        ) {
            fresh.toMutableList().apply { add(minOf(3, size), cachedSimilar) }
        } else fresh

        // Library sync emits successively larger snapshots. Keep the cached complete feed while a
        // small intermediate snapshot is arriving, then replace it wholesale. Appending unmatched
        // cards here used to grow rows from 12 items to 70-130 items over several launches.
        val currentFootprint = current.flatMap { section ->
            section.cards.flatMap(HomeCard::mediaIds)
        }.toSet().size
        val freshFootprint = completeFresh.flatMap { section ->
            section.cards.flatMap(HomeCard::mediaIds)
        }.toSet().size
        return if (currentFootprint > 0 && freshFootprint < currentFootprint * 0.7) {
            current
        } else {
            completeFresh
        }
    }

    private fun mergeBanners(
        current: List<BannerItem>,
        fresh: List<BannerItem>
    ): List<BannerItem> {
        if (current.isEmpty()) return fresh
        val currentFootprint = current.flatMap(BannerItem::mediaIds).toSet().size
        val freshFootprint = fresh.flatMap(BannerItem::mediaIds).toSet().size
        return if (currentFootprint > 0 && freshFootprint < currentFootprint * 0.7) current else fresh
    }

    private fun isSkeletonSection(section: HomeSection): Boolean =
        section.cards.isNotEmpty() && section.cards.all {
            it.title.isBlank() && it.mediaIds.isEmpty()
        }

}
