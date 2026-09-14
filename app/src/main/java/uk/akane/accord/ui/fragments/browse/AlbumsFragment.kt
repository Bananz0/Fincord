package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R
import uk.akane.accord.logic.utils.BrowseGrid
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.browse.AlbumAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged

class AlbumsFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity

    private lateinit var recyclerView: RecyclerView
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var navigationBar: NavigationBar

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_albums, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        recyclerView = rootView.findViewById(R.id.rv)

        val columns = BrowseGrid.columnCount(requireContext())
        layoutManager = GridLayoutManager(requireContext(), columns)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val viewType = albumAdapter.getItemViewType(position)
                return if (viewType == AlbumAdapter.VIEW_TYPE_CONTROL) columns else 1
            }
        }

        albumAdapter = AlbumAdapter(recyclerView, this) { notifyContentLoaded() }

        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = albumAdapter

        // Outer edges match the master control row's own start/end margins; the gap between cards
        // is the 16dp those buttons leave between themselves.
        recyclerView.addItemDecoration(
            BrowseGrid.spacing(requireContext(), columns) { position ->
                // The master control row already carries that padding as margins.
                albumAdapter.getItemViewType(position) == AlbumAdapter.VIEW_TYPE_CONTROL
            }
        )

        // The layout has always carried a search box that nothing read.
        rootView.findViewById<EditText?>(R.id.search_input)?.doAfterTextChanged {
            albumAdapter.setFilter(it?.toString().orEmpty())
        }
        navigationBar.attach(recyclerView)
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }
}
