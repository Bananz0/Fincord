package org.akanework.gramophone.ui.fragments.settings


import android.content.Intent
import android.os.Bundle
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class SpotifySettingsFragment : BaseSettingFragment(
    R.string.settings_category_spotify,
    { SpotifySettingsTopFragment() }
)

/**
 * Connects a Spotify account. Playlist actions live with the playlists they affect rather than in
 * Settings; the application-owned client id is supplied by the build.
 */
class SpotifySettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_spotify, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Also covers coming back from the browser after authorising.
        refreshSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "spotify_account" -> onAccountClicked()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val store = SpotifyCredentialStore(requireContext())
                Triple(store.isLinked(), store.hasClientId(), store.displayName)
            }
            if (!isAdded) return@launch
            val (linked, hasClientId, name) = state
            findPreference<Preference>("spotify_account")?.summary = when {
                !hasClientId -> getString(R.string.spotify_account_needs_client_id)
                linked -> getString(R.string.spotify_account_linked, name ?: "")
                else -> getString(R.string.spotify_account_not_linked)
            }
        }
    }

    private fun onAccountClicked() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { SpotifyCredentialStore(requireContext()) }
            val linked = withContext(Dispatchers.IO) { store.isLinked() }
            val hasClientId = withContext(Dispatchers.IO) { store.hasClientId() }
            if (!isAdded) return@launch
            when {
                !hasClientId -> Toast.makeText(
                    requireContext(), R.string.spotify_account_needs_client_id, Toast.LENGTH_LONG
                ).show()
                linked -> showDisconnectDialog()
                else -> startAuthorisation(store)
            }
        }
    }

    /**
     * Hands the user to Spotify in a browser.
     *
     * Deliberately not an in-app WebView: the user is typing their Spotify password, and they should
     * be able to see the real address bar and use their password manager.
     */
    private fun startAuthorisation(store: SpotifyCredentialStore) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                SpotifyClient(store).buildAuthorizationUrl()
            }
            if (!isAdded) return@launch
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(), R.string.spotify_error_no_browser, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDisconnectDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.spotify_account)
            .setMessage(R.string.spotify_disconnect_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lastfm_disconnect) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        SpotifyCredentialStore(requireContext()).clear(requireContext())
                    }
                    if (isAdded) refreshSummaries()
                }
            }
            .show()
    }

}
