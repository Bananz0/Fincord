package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uk.akane.accord.R
import uk.akane.accord.logic.utils.BrowseGrid
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import uk.akane.libphonograph.items.Genre

/**
 * The genres in the library, and a way into each one.
 *
 * Browse offers "Browse by Genre" but had nothing behind it - upstream wires only Songs there, and
 * every other entry threw. Tapping a genre opens its tracks.
 */
class GenresFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity

    private lateinit var recyclerView: RecyclerView
    private lateinit var navigationBar: NavigationBar
    private val adapter = GenreAdapter()

    private var all: List<Genre> = emptyList()
    private var filter = ""

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Reuses the songs screen's layout: a navigation bar, a search bar and a list, which is
        // exactly the shape this screen needs.
        val rootView = inflater.inflate(R.layout.fragment_browse_song, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setTitle(getString(R.string.category_genres))
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        recyclerView = rootView.findViewById(R.id.rv)
        val columns = BrowseGrid.columnCount(requireContext())
        recyclerView.layoutManager = GridLayoutManager(requireContext(), columns)
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            BrowseGrid.spacing(requireContext(), columns, topDp = 8, bottomDp = 8)
        )
        navigationBar.attach(recyclerView)

        rootView.findViewById<EditText?>(R.id.search_input)?.doAfterTextChanged {
            filter = it?.toString()?.trim().orEmpty()
            adapter.submit(applyFilter(all))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity.reader.genreListFlow.collectLatest { genres ->
                    // Genres with nothing in them are noise; a server can carry tags no track uses.
                    all = genres.filter { it.songList.isNotEmpty() }
                        .sortedBy { it.title?.lowercase().orEmpty() }
                    adapter.submit(applyFilter(all))
                    notifyContentLoaded()
                }
            }
        }

        return rootView
    }

    private fun applyFilter(source: List<Genre>): List<Genre> {
        if (filter.isEmpty()) return source
        val needle = filter.lowercase()
        return source.filter { it.title?.lowercase()?.contains(needle) == true }
    }

    private inner class GenreAdapter : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {
        private val items = mutableListOf<Genre>()

        fun submit(genres: List<Genre>) {
            items.clear()
            items.addAll(genres)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_album_item, parent, false)
        )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val genre = items[position]
            holder.title?.text = genre.title ?: getString(R.string.unknown_genre)
            holder.subtitle?.text = resources.getQuantityString(
                R.plurals.songs, genre.songList.size, genre.songList.size
            )
            holder.cover?.load(genre.songList.firstNotNullOfOrNull {
                it.mediaMetadata.artworkUri
            }) {
                crossfade(true)
            }
            holder.itemView.setOnClickListener {
                activity.fragmentSwitcherView.addFragmentToCurrentStack(
                    StationDetailFragment.newInstance(
                        title = genre.title ?: getString(R.string.unknown_genre),
                        subtitle = null,
                        mediaIds = genre.songList.map { song -> song.mediaId },
                        kind = StationDetailFragment.CollectionKind.STATION
                    )
                )
            }
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: android.widget.ImageView? = view.findViewById(R.id.cover)
            val title: TextView? = view.findViewById(R.id.title)
            val subtitle: TextView? = view.findViewById(R.id.subtitle)
        }
    }
}
