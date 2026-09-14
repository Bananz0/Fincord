package org.akanework.gramophone.ui.fragments.settings


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import org.akanework.gramophone.logic.dpToPx
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.enableEdgeToEdgePaddingListener
import org.akanework.gramophone.ui.adapters.BlacklistAdapter
import org.akanework.gramophone.ui.fragments.BaseFragment
import uk.akane.accord.Accord
import uk.akane.accord.R

/**
 * Hides artists and songs from the library.
 *
 * The page used to list local folders, which is meaningless against a Jellyfin server - it showed a
 * short list of device directories nobody recognised and looked broken. Artists and songs are what
 * there is actually a reason to hide: the comedy album that keeps surfacing in a shuffle, or the
 * one artist on a shared server nobody in the house wants to hear.
 */
class BlacklistSettingsFragment : BaseFragment() {

    private lateinit var adapter: BlacklistAdapter

    /** The whole list, rebuilt when the library changes and re-filtered as the user types. */
    private var allRows: List<BlacklistAdapter.Row> = emptyList()
    private var filter: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_blacklist_settings, container, false)
        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        navigationBar.setHasReturnButton(true)
        navigationBar.setReturnButtonText(getString(R.string.settings))
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                v.paddingLeft,
                maxOf(systemBars.top, cutout.top),
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        navigationBar.setOnReturnClickListener {
            (activity as? MainActivity)?.fragmentSwitcherView?.popBackTopFragmentIfExists()
                ?: requireActivity().supportFragmentManager.popBackStack()
        }

        adapter = BlacklistAdapter((requireActivity().application as Accord).blacklist)
        rootView.findViewById<RecyclerView>(R.id.recyclerview).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@BlacklistSettingsFragment.adapter
            addItemDecoration(GroupBackgroundDecoration())
            navigationBar.attach(this)
            navigationBar.post { navigationBar.resetToExpandedState() }
        }

        rootView.findViewById<EditText>(R.id.blacklist_filter).doAfterTextChanged {
            filter = it?.toString().orEmpty().trim()
            publish()
        }

        observeLibrary()
        return rootView
    }

    /**
     * Paints the rounded card behind each run of rows.
     *
     * Drawn rather than given to the rows themselves, because only the first and last of a run are
     * rounded and a row does not know where it sits - the same reason the preference screens do it
     * this way, and the reason both end up looking like one card instead of a stack of tiles.
     */
    private inner class GroupBackgroundDecoration : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), R.color.settings_card_background)
        }
        private val radius = 14.dpToPx(requireContext()).toFloat()

        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            var top: Float? = null
            var bottom = 0F
            var left = 0F
            var right = 0F

            fun flush() {
                val start = top ?: return
                canvas.drawRoundRect(
                    RectF(left, start, right, bottom), radius, radius, paint
                )
                top = null
            }

            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) continue
                if (adapter.isHeader(position)) {
                    flush()
                    continue
                }
                if (top == null) {
                    top = child.top.toFloat()
                    left = child.left.toFloat()
                    right = child.right.toFloat()
                }
                bottom = child.bottom.toFloat()
            }
            flush()
        }
    }

    /**
     * Reads the unfiltered library.
     *
     * Deliberately not the app's `reader`: that one already has the blacklist applied, so anything
     * blocked would vanish from this page and could never be unblocked again.
     */
    private fun observeLibrary() {
        val source = (requireActivity().application as Accord).unfilteredReader
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(source.songListFlow, source.artistListFlow) { songs, artists ->
                    buildRows(
                        artists = artists.mapNotNull { it.title }.distinct()
                            .sortedBy { it.lowercase() },
                        songs = songs,
                    )
                }.collect { rows ->
                    allRows = rows
                    publish()
                }
            }
        }
    }

    private fun buildRows(
        artists: List<String>,
        songs: List<MediaItem>,
    ): List<BlacklistAdapter.Row> = buildList {
        add(BlacklistAdapter.Row.Header(R.string.blacklist_section_artists, artists.size))
        artists.forEach { add(BlacklistAdapter.Row.Artist(it)) }

        val songRows = songs
            .filter { it.mediaId.isNotBlank() }
            .distinctBy { it.mediaId }
            .map {
                BlacklistAdapter.Row.Song(
                    mediaId = it.mediaId,
                    title = it.mediaMetadata.title?.toString().orEmpty(),
                    artist = it.mediaMetadata.artist?.toString(),
                )
            }
            .sortedBy { it.title.lowercase() }
        add(BlacklistAdapter.Row.Header(R.string.blacklist_section_songs, songRows.size))
        addAll(songRows)
    }

    /**
     * Applies the current filter, dropping headings whose section came back empty so a search does
     * not leave labels with nothing under them.
     */
    private fun publish() {
        if (filter.isEmpty()) {
            adapter.submit(allRows)
            return
        }
        val needle = filter.lowercase()
        val kept = allRows.filter { row ->
            when (row) {
                is BlacklistAdapter.Row.Header -> true
                is BlacklistAdapter.Row.Artist -> row.name.lowercase().contains(needle)
                is BlacklistAdapter.Row.Song ->
                    row.title.lowercase().contains(needle) ||
                        row.artist?.lowercase()?.contains(needle) == true
            }
        }
        adapter.submit(
            kept.filterIndexed { index, row ->
                row !is BlacklistAdapter.Row.Header ||
                    kept.getOrNull(index + 1).let { it != null && it !is BlacklistAdapter.Row.Header }
            }
        )
    }
}
