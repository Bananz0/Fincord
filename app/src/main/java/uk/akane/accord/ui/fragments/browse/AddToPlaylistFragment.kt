package uk.akane.accord.ui.fragments.browse

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment

/**
 * Picks a playlist to put one or more songs into, or makes a new one for them.
 *
 * The playlists are the Jellyfin server's rather than a local list, because every song this app
 * plays already comes from there - a local-only playlist would be invisible from the web client and
 * from every other device, which is not what "add to a playlist" is expected to mean.
 */
class AddToPlaylistFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity

    private lateinit var navigationBar: NavigationBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView

    private val adapter = PlaylistAdapter()
    private val mediaIds: List<String>
        get() = requireArguments().getStringArray(ARG_MEDIA_IDS)?.toList().orEmpty()

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_add_to_playlist, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        emptyView = rootView.findViewById(R.id.playlist_empty)
        recyclerView = rootView.findViewById(R.id.playlist_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        // Constrained below the bar in the layout already; see SettingsFragment.
        navigationBar.attach(recyclerView, applyTopPadding = false)

        refresh()

        return rootView
    }

    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) { JellyfinPlaylists.list() }
            adapter.submit(playlists)
            // "New Playlist" is always offered, so an empty list is only worth explaining when the
            // reason is that there is no server to talk to.
            emptyView.isVisible = playlists.isEmpty() &&
                    !org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
                        .credentials.isLoggedIn()
            notifyContentLoaded()
        }
    }

    private fun addTo(playlist: JellyfinPlaylists.RemotePlaylist) {
        val context = requireContext().applicationContext
        val songs = mediaIds
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                JellyfinPlaylists.addTo(context, playlist.id, songs)
            }
            Toast.makeText(
                context,
                getString(
                    if (ok) R.string.add_to_playlist_added else R.string.add_to_playlist_failed,
                    playlist.name
                ),
                Toast.LENGTH_SHORT
            ).show()
            if (ok) activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
    }

    private fun promptForNewPlaylist() {
        val input = EditText(requireContext()).apply {
            setHint(R.string.playlist_name_hint)
            setSingleLine()
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_playlist)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) create(name)
            }
            .show()
    }

    private fun create(name: String) {
        val context = requireContext().applicationContext
        val songs = mediaIds
        viewLifecycleOwner.lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                JellyfinPlaylists.create(context, name, songs)
            }
            Toast.makeText(
                context,
                if (id != null) getString(R.string.add_to_playlist_created, name)
                else getString(R.string.add_to_playlist_create_failed),
                Toast.LENGTH_SHORT
            ).show()
            if (id != null) activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
    }

    /**
     * The list, with "New Playlist" pinned to the top as row zero rather than as a separate view
     * above the list - so it scrolls with the playlists and cannot end up stranded on screen.
     */
    private inner class PlaylistAdapter : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

        private val items = mutableListOf<JellyfinPlaylists.RemotePlaylist>()

        fun submit(playlists: List<JellyfinPlaylists.RemotePlaylist>) {
            items.clear()
            items.addAll(playlists)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_add_to_playlist_row, parent, false)
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (position == 0) {
                holder.title.setText(R.string.add_to_playlist_new)
                holder.title.setTextColor(resources.getColor(R.color.accentColor, null))
                holder.subtitle.setText(R.string.add_to_playlist_new_subtitle)
                holder.cover.isVisible = false
                holder.icon.isVisible = true
                holder.itemView.setOnClickListener { promptForNewPlaylist() }
            } else {
                val playlist = items[position - 1]
                holder.title.text = playlist.name
                holder.title.setTextColor(resources.getColor(R.color.onSurfaceColor, null))
                holder.subtitle.text = resources.getQuantityString(
                    R.plurals.songs, playlist.songCount, playlist.songCount
                )
                holder.icon.isVisible = false
                holder.cover.isVisible = true
                holder.cover.load(playlist.imageUrl) { crossfade(true) }
                holder.itemView.setOnClickListener { addTo(playlist) }
            }
            holder.divider.isVisible = position != itemCount - 1
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.cover)
            val icon: ImageView = view.findViewById(R.id.icon)
            val title: TextView = view.findViewById(R.id.title)
            val subtitle: TextView = view.findViewById(R.id.subtitle)
            val divider: View = view.findViewById(R.id.divider)
        }
    }

    companion object {
        private const val ARG_MEDIA_IDS = "add_to_playlist_media_ids"

        fun newInstance(mediaIds: List<String>) = AddToPlaylistFragment().apply {
            arguments = Bundle().apply {
                putStringArray(ARG_MEDIA_IDS, mediaIds.toTypedArray())
            }
        }
    }
}
