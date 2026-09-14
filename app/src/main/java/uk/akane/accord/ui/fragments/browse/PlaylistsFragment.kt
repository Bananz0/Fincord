package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.browse.PlaylistAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.accord.ui.components.SpotifyPlaylistImportController
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import uk.akane.cupertino.popup.PopupHelper

class PlaylistsFragment : SwitcherPostponeFragment() {

    private val activity
        get() = requireActivity() as MainActivity

    private lateinit var recyclerView: RecyclerView
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var navigationBar: NavigationBar
    private lateinit var importController: SpotifyPlaylistImportController

    private enum class MenuAction { IMPORT_SPOTIFY, REFRESH_LIBRARY, SETTINGS }

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_playlists, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        recyclerView = rootView.findViewById(R.id.rv)
        playlistAdapter = PlaylistAdapter(recyclerView, this) { notifyContentLoaded() }
        importController = SpotifyPlaylistImportController(this) {
            playlistAdapter.refreshRemotePlaylists()
        }
        layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = playlistAdapter
        recyclerView.layoutManager = layoutManager

        navigationBar.attach(recyclerView)
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        navigationBar.setOnAddClickListener {
            activity.showContainer(AddPlaylistFragment())
        }
        navigationBar.setMenuEntries(
            entries = {
                PopupHelper.PopupMenuBuilder()
                    .addMenuEntry(
                        resources, R.drawable.ic_playlist, R.string.spotify_import,
                        MenuAction.IMPORT_SPOTIFY,
                    )
                    .addSpacer()
                    .addMenuEntry(
                        resources, R.drawable.ic_refresh, R.string.home_menu_refresh,
                        MenuAction.REFRESH_LIBRARY,
                    )
                    .addMenuEntry(
                        resources, R.drawable.ic_settings, R.string.home_menu_settings,
                        MenuAction.SETTINGS,
                    )
                    .build()
            },
            onClick = { entry ->
                when ((entry as? PopupHelper.MenuEntry)?.payload as? MenuAction) {
                    MenuAction.IMPORT_SPOTIFY -> importController.showPicker()
                    MenuAction.REFRESH_LIBRARY -> activity.updateLibrary()
                    MenuAction.SETTINGS -> activity.openSettings()
                    null -> Unit
                }
            },
        )

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }
}
