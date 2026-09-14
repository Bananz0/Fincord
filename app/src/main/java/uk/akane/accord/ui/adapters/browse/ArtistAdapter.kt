package uk.akane.accord.ui.adapters.browse

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import uk.akane.libphonograph.items.Artist

class ArtistAdapter(
    private val recyclerView: RecyclerView,
    private val fragment: Fragment,
    private val onContentLoaded: (() -> Unit)
) : RecyclerView.Adapter<ArtistAdapter.ViewHolder>() {

    private val list = mutableListOf<ArtistListItem>()

    private val mainActivity
        get() = fragment.activity as MainActivity

    /** Already grouped by the library layer; opening this screen never scans every song. */
    private var latestPrimaryArtists: List<Artist> = emptyList()
    private var latestFeaturedArtists: List<Artist> = emptyList()
    private var displayMode = ArtistKind.PRIMARY

    init {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(
                androidx.lifecycle.Lifecycle.State.STARTED
            ) {
                combine(
                    mainActivity.reader.primaryArtistListFlow,
                    mainActivity.reader.featuredArtistListFlow,
                ) { primary, featured -> primary to featured }
                    .collectLatest { (primary, featured) ->
                    latestPrimaryArtists = primary
                    latestFeaturedArtists = featured
                    submitArtists()
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(
                    if (viewType == VIEW_TYPE_HEADER) R.layout.adapter_category_header
                    else R.layout.layout_artist_item,
                    parent,
                    false,
                )
        )
    }

    override fun getItemViewType(position: Int): Int =
        if (list[position] is ArtistListItem.Header) VIEW_TYPE_HEADER else VIEW_TYPE_ARTIST

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val item = list[position]) {
            is ArtistListItem.Header -> {
                holder.artistName.text = item.title
                return
            }
            is ArtistListItem.Artist -> bindArtist(holder, item)
        }
    }

    private fun bindArtist(holder: ViewHolder, item: ArtistListItem.Artist) {
        holder.artistImage?.load(item.artworkUri) {
            crossfade(true)
            size(160.dp.px.toInt(), 160.dp.px.toInt())
        }

        holder.artistName.text = item.name

        holder.itemView.setOnClickListener {
            mainActivity.fragmentSwitcherView.addFragmentToCurrentStack(
                ArtistDetailFragment.newInstance(
                    artist = item.name,
                    artistId = item.id,
                    featuredOnly = item.kind == ArtistKind.FEATURED,
                )
            )
        }
    }

    override fun getItemCount(): Int = list.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val artistImage: ImageView? = view.findViewById(R.id.artistImage)
        val artistName: TextView =
            view.findViewById<TextView?>(R.id.artistName) ?: view.findViewById(R.id.title)
    }

    /** See [SongAdapter.submitMutex] - same race, same reason. */
    private val submitMutex = Mutex()
    private var submitJob: Job? = null


    /**
     * Narrows the list to what the screen's search box says.
     *
     * The box is in the layout on every browse screen and was only ever read on two of them, so
     * typing on the others did nothing at all.
     */
    fun setFilter(query: String) {
        val next = query.trim()
        if (next == filter) return
        filter = next
        submitArtists()
    }

    fun setDisplayMode(mode: ArtistKind) {
        if (displayMode == mode) return
        displayMode = mode
        submitArtists()
    }

    private fun String?.matchesFilter(): Boolean {
        if (filter.isEmpty()) return true
        return this?.normaliseForFilter()?.contains(filter.normaliseForFilter()) == true
    }

    private fun String.normaliseForFilter(): String = trim().lowercase()

    private var filter = ""

    private fun submitArtists() {
        submitJob?.cancel()
        submitJob = fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            submitMutex.withLock {
            val primaryItems = latestPrimaryArtists
                .filter { artist -> artist.title.matchesFilter() }
                .map { artist ->
                    ArtistListItem.Artist(
                        id = artist.id,
                        name = artist.title.orEmpty(),
                        artworkUri = artist.songList.firstOrNull()?.mediaMetadata?.artworkUri,
                        kind = ArtistKind.PRIMARY,
                    )
                }
                .sortedBy { it.name.lowercase() }
            val featuredItems = latestFeaturedArtists
                .filter { artist -> artist.title.matchesFilter() }
                .map { artist ->
                    ArtistListItem.Artist(
                        id = artist.id,
                        name = artist.title.orEmpty(),
                        artworkUri = artist.songList.firstOrNull()?.mediaMetadata?.artworkUri,
                        kind = ArtistKind.FEATURED,
                    )
                }
                .sortedBy { it.name.lowercase() }

            val newItems = buildList<ArtistListItem> {
                if (displayMode == ArtistKind.PRIMARY && primaryItems.isNotEmpty()) {
                    add(ArtistListItem.Header("Main artists"))
                    addAll(primaryItems)
                }
                if (displayMode == ArtistKind.FEATURED && featuredItems.isNotEmpty()) {
                    add(ArtistListItem.Header("Featured artists"))
                    addAll(featuredItems)
                }
            }

            val oldSnapshot = withContext(Dispatchers.Main) { list.toList() }
            val diff = DiffUtil.calculateDiff(
                ArtistDiffCallback(oldSnapshot, newItems)
            )

            withContext(Dispatchers.Main) {
                list.clear()
                list.addAll(newItems)
                diff.dispatchUpdatesTo(this@ArtistAdapter)
                recyclerView.post { onContentLoaded.invoke() }
            }
            }
        }
    }

    class ArtistDiffCallback(
        private val oldList: List<ArtistListItem>,
        private val newList: List<ArtistListItem>
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
            when {
                oldList[oldPos] is ArtistListItem.Header && newList[newPos] is ArtistListItem.Header ->
                    (oldList[oldPos] as ArtistListItem.Header).title ==
                        (newList[newPos] as ArtistListItem.Header).title
                oldList[oldPos] is ArtistListItem.Artist && newList[newPos] is ArtistListItem.Artist -> {
                    val old = oldList[oldPos] as ArtistListItem.Artist
                    val new = newList[newPos] as ArtistListItem.Artist
                    (old.id != null && old.id == new.id || old.name == new.name) &&
                        old.kind == new.kind
                }
                else -> false
            }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
            oldList[oldPos] == newList[newPos]
    }

    enum class ArtistKind { PRIMARY, FEATURED }

    sealed class ArtistListItem {
        data class Header(val title: String) : ArtistListItem()
        data class Artist(
            val id: Long?,
            val name: String,
            val artworkUri: android.net.Uri?,
            val kind: ArtistKind,
        ) : ArtistListItem()
    }

    private companion object {
        const val VIEW_TYPE_ARTIST = 0
        const val VIEW_TYPE_HEADER = 1
    }
}
