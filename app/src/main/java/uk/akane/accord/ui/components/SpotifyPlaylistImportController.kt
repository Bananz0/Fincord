package uk.akane.accord.ui.components

import uk.akane.accord.ui.components.NoToast as Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.MusicRequestService
import org.akanework.gramophone.logic.data.catalog.CatalogPlaylistImporter
import org.akanework.gramophone.logic.data.catalog.ExternalTrack
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlaylists
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore
import org.akanework.gramophone.logic.data.library.songListSnapshot
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Owns the Spotify-to-Jellyfin import flow shown from the Playlists screen. */
class SpotifyPlaylistImportController(
    private val fragment: Fragment,
    private val onImported: () -> Unit,
) {
    private val context get() = fragment.requireContext()
    private val activity get() = fragment.requireActivity() as MainActivity

    fun showPicker() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { SpotifyCredentialStore(context) }
            if (!store.isLinked()) {
                Toast.makeText(context, R.string.spotify_link_in_settings, Toast.LENGTH_LONG).show()
                return@launch
            }
            val playlists = withContext(Dispatchers.IO) {
                runCatching {
                    SpotifyClient(store).playlists(context, System.currentTimeMillis())
                }.getOrNull()
            }
            if (!fragment.isAdded) return@launch
            if (playlists.isNullOrEmpty()) {
                Toast.makeText(context, R.string.spotify_no_playlists, Toast.LENGTH_LONG).show()
                return@launch
            }
            val labels = playlists.map {
                context.getString(R.string.spotify_playlist_label, it.name, it.trackCount)
            }.toTypedArray()
            val checked = BooleanArray(playlists.size)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.spotify_import)
                .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.spotify_import_action) { _, _ ->
                    val selected = playlists.filterIndexed { index, _ -> checked[index] }
                    if (selected.isNotEmpty()) confirmAndImport(selected)
                }
                .show()
        }
    }

    private fun confirmAndImport(selected: List<SpotifyClient.Playlist>) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val existingNames = withContext(Dispatchers.IO) {
                JellyfinPlaylists.list(context).mapTo(HashSet()) { it.name.lowercase() }
            }
            if (!fragment.isAdded) return@launch
            val conflicts = selected.count { it.name.lowercase() in existingNames }
            if (conflicts == 0) {
                import(selected, replaceExisting = false)
                return@launch
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.spotify_import_existing_title)
                .setMessage(
                    context.resources.getQuantityString(
                        R.plurals.spotify_import_existing_message,
                        conflicts,
                        conflicts,
                    )
                )
                .setNegativeButton(R.string.spotify_import_keep) { _, _ ->
                    import(selected, replaceExisting = false)
                }
                .setPositiveButton(R.string.spotify_import_replace) { _, _ ->
                    import(selected, replaceExisting = true)
                }
                .show()
        }
    }

    private fun import(selected: List<SpotifyClient.Playlist>, replaceExisting: Boolean) {
        Toast.makeText(context, R.string.spotify_importing, Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val library = activity.reader.songListSnapshot()
            val results = withContext(Dispatchers.IO) {
                val store = SpotifyCredentialStore(context)
                val client = SpotifyClient(store)
                selected.mapNotNull { playlist ->
                    runCatching {
                        val tracks = client.playlistTracks(
                            context, playlist.id, System.currentTimeMillis()
                        )
                        CatalogPlaylistImporter.import(
                            context,
                            playlist.name,
                            tracks,
                            library,
                            replaceExisting,
                        )
                    }.getOrNull()
                }
            }
            if (!fragment.isAdded) return@launch
            onImported()
            val matched = results.sumOf { it.matched }
            val missing = results.sumOf { it.missing }
            Toast.makeText(
                context,
                context.getString(R.string.spotify_import_result, matched, missing),
                Toast.LENGTH_LONG,
            ).show()
            offerRequest(results.flatMap { it.missingTracks })
        }
    }

    private fun offerRequest(missing: List<ExternalTrack>) {
        if (missing.isEmpty()) return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lidarr_request_missing)
            .setMessage(
                context.resources.getQuantityString(
                    R.plurals.spotify_missing_prompt, missing.size, missing.size
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lidarr_request_missing) { _, _ ->
                LidarrSetupPrompt.ensureConfigured(context, fragment.viewLifecycleOwner) {
                    request(missing)
                }
            }
            .show()
    }

    private fun request(missing: List<ExternalTrack>) {
        Toast.makeText(context, R.string.lidarr_requesting, Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                MusicRequestService.requestTracks(context, missing)
            }
            if (!fragment.isAdded) return@launch
            Toast.makeText(
                context,
                // Tracks the downloader already tracks are not a failure, and saying "nothing
                // matched" when it in fact matched everything is how this looked broken.
                if (!outcome.didSomething) context.getString(R.string.lidarr_no_matches)
                else context.resources.getQuantityString(
                    R.plurals.lidarr_requested, outcome.requested, outcome.requested
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
