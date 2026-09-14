package org.akanework.gramophone.ui.fragments.settings


import android.content.Intent
import android.os.Bundle
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlugins
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import org.akanework.gramophone.logic.data.lastfm.LastFmScrobbler
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class ScrobblingSettingsFragment : BaseSettingFragment(
    R.string.settings_category_scrobbling,
    { ScrobblingSettingsTopFragment() }
)

/**
 * Connects the app to Last.fm and shows the state of the scrobble queue.
 *
 * Every credential-store read touches keystore-backed preferences, which is disk I/O, so all of it
 * happens off the main thread and the summaries are filled in when it returns. That is also why the
 * screen refreshes its own summaries rather than binding them declaratively.
 */
class ScrobblingSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_scrobbling, rootKey)
    }

    override fun onResume() {
        super.onResume()
        // Also the moment we get back from the browser, which is where completePendingAuth picks
        // up an approved token.
        completePendingAuth()
        refreshSummaries()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "lastfm_account" -> onAccountClicked()
            "lastfm_api_keys" -> showApiKeyDialog()
            "lastfm_broker" -> showBrokerDialog()
            "lastfm_pending" -> submitPendingNow()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val store = LastFmCredentialStore(requireContext())
                AccountState(
                    username = store.username,
                    isLinked = store.isLinked(),
                    hasKeys = store.hasApplicationCredentials(),
                    pending = LastFmScrobbler(requireContext()).pendingCount(),
                    // Only true when the server was actually asked and said yes; null - offline, or
                    // a server that will not list its plugins - leaves the notice hidden.
                    serverScrobbles = JellyfinPlugins.isLastFmScrobblingActive() == true,
                )
            }
            if (!isAdded) return@launch
            findPreference<Preference>("lastfm_server_scrobbles")?.isVisible = state.serverScrobbles
            findPreference<Preference>("lastfm_account")?.summary = when {
                !state.hasKeys -> getString(R.string.lastfm_account_needs_keys)
                state.isLinked -> getString(R.string.lastfm_account_linked, state.username ?: "")
                else -> getString(R.string.lastfm_account_not_linked)
            }
            findPreference<Preference>("lastfm_pending")?.summary =
                resources.getQuantityString(
                    R.plurals.lastfm_pending_count, state.pending, state.pending
                )
        }
    }

    private fun onAccountClicked() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { LastFmCredentialStore(requireContext()) }
            val linked = withContext(Dispatchers.IO) { store.isLinked() }
            val hasKeys = withContext(Dispatchers.IO) { store.hasApplicationCredentials() }
            // Asking the server is a network call, so it cannot happen in the branch below.
            val serverScrobbles = withContext(Dispatchers.IO) {
                JellyfinPlugins.isLastFmScrobblingActive() == true
            }
            if (!isAdded) return@launch
            when {
                // Signing in is impossible without credentials, so send the user straight to where
                // they can add them instead of failing at the approval step.
                !hasKeys -> showApiKeyDialog()
                linked -> showDisconnectDialog()
                // The notice at the top of the screen says this too, but the moment someone taps
                // connect is the moment it matters, and duplicate scrobbles are tedious to undo.
                serverScrobbles -> showDoubleScrobbleWarning(store)
                else -> startWebAuth(store)
            }
        }
    }

    /**
     * Warns that the server is already scrobbling, and lets the user go ahead anyway.
     *
     * Not a refusal: someone may deliberately want the app's own scrobbles - richer metadata, or
     * because they are about to turn the server plugin off - and this cannot tell the difference.
     */
    private fun showDoubleScrobbleWarning(store: LastFmCredentialStore) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lastfm_server_scrobbles)
            .setMessage(R.string.lastfm_server_scrobbles_summary)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.lastfm_connect_anyway) { _, _ -> startWebAuth(store) }
            .show()
    }

    /**
     * Sends the user to Last.fm to approve access.
     *
     * The alternative - asking for their Last.fm password here - is a flow nobody should agree to in
     * a third-party app, and Last.fm only kept it for legacy desktop clients. This way the password
     * is only ever typed on last.fm itself.
     */
    private fun startWebAuth(store: LastFmCredentialStore) {
        viewLifecycleOwner.lifecycleScope.launch {
            val url = withContext(Dispatchers.IO) {
                try {
                    val client = LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl)
                    val request = client.getToken()
                    store.pendingAuthToken = request.token
                    client.authorizationUrl(request)
                } catch (e: Exception) {
                    null
                }
            }
            if (!isAdded) return@launch
            if (url == null) {
                Toast.makeText(
                    requireContext(), R.string.lastfm_error_generic, Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                Toast.makeText(
                    requireContext(), R.string.lastfm_approve_in_browser, Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(), R.string.spotify_error_no_browser, Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Turns an approved request token into a session key.
     *
     * Runs on every resume rather than waiting for a redirect. Last.fm's callback URL is optional
     * and only fires for API accounts that have one configured, so polling on return covers both -
     * and the failure case is simply that the token is not approved yet.
     */
    private fun completePendingAuth() {
        viewLifecycleOwner.lifecycleScope.launch {
            val linked = withContext(Dispatchers.IO) {
                val store = LastFmCredentialStore(requireContext())
                val token = store.pendingAuthToken?.takeIf { it.isNotBlank() }
                    ?: return@withContext false
                try {
                    val session = LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl)
                        .getSession(token)
                    store.saveSession(requireContext(), session.name, session.key)
                    store.pendingAuthToken = null
                    LastFmScrobbler(requireContext()).flushAsync()
                    true
                } catch (e: Exception) {
                    // Not approved yet, or approval was abandoned. Either way, leave the token in
                    // place so returning to this screen tries again.
                    false
                }
            }
            if (!isAdded || !linked) return@launch
            Toast.makeText(requireContext(), R.string.lastfm_connected, Toast.LENGTH_SHORT).show()
            refreshSummaries()
        }
    }

    private fun showDisconnectDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val username = withContext(Dispatchers.IO) {
                LastFmCredentialStore(requireContext()).username
            }
            if (!isAdded) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lastfm_account)
                .setMessage(getString(R.string.lastfm_disconnect_message, username ?: ""))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lastfm_disconnect) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            LastFmCredentialStore(requireContext()).clearSession(requireContext())
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun showApiKeyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lastfm_api_keys, null)
        val keyField = view.findViewById<TextInputEditText>(R.id.api_key)
        val secretField = view.findViewById<TextInputEditText>(R.id.api_secret)

        viewLifecycleOwner.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                val store = LastFmCredentialStore(requireContext())
                store.apiKey to store.apiSecret
            }
            if (!isAdded) return@launch
            keyField.setText(existing.first)
            secretField.setText(existing.second)

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lastfm_api_keys)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val key = keyField.text?.toString()?.trim().orEmpty()
                    val secret = secretField.text?.toString()?.trim().orEmpty()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val store = LastFmCredentialStore(requireContext())
                            store.apiKey = key
                            store.apiSecret = secret
                            // The "linked" flag depends on the keys being usable, so it has to be
                            // recomputed whenever they change.
                            store.publishLinkFlag(requireContext())
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun showBrokerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lastfm_broker, null)
        val field = view.findViewById<TextInputEditText>(R.id.broker_url)

        viewLifecycleOwner.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                LastFmCredentialStore(requireContext()).brokerUrl
            }
            if (!isAdded) return@launch
            field.setText(existing.orEmpty())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lastfm_broker)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = field.text?.toString()?.trim().orEmpty()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            LastFmCredentialStore(requireContext()).apply {
                                brokerUrl = value.ifBlank { null }
                                // Whether the app can make signed calls at all depends on this, so
                                // the linked flag has to be recomputed.
                                publishLinkFlag(requireContext())
                            }
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun submitPendingNow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val submitted = withContext(Dispatchers.IO) {
                LastFmScrobbler(requireContext()).flush()
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                resources.getQuantityString(
                    R.plurals.lastfm_submitted_count, submitted, submitted
                ),
                Toast.LENGTH_SHORT
            ).show()
            refreshSummaries()
        }
    }

    private data class AccountState(
        val username: String?,
        val isLinked: Boolean,
        val hasKeys: Boolean,
        val pending: Int,
        /** Whether the Jellyfin server is known to be scrobbling on its own. */
        val serverScrobbles: Boolean,
    )
}
