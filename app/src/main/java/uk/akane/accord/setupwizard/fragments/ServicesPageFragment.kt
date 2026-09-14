package uk.akane.accord.setupwizard.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.GramophoneApplication
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import uk.akane.accord.R
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.enablePasteInto

/**
 * The wizard's third page: the services beyond the library itself.
 *
 * Everything here is optional and the page says so - the app works with only a Jellyfin server. What
 * it avoids is the thing that used to happen: finding out months later, at the moment of trying to
 * request an album, that three fields were never filled in.
 *
 * Only what cannot be discovered is asked for. Lidarr needs an address and a key; everything after
 * that - root folder, quality profile, metadata profile - is read from the server and chosen here,
 * without asking when there is only one answer.
 */
class ServicesPageFragment : Fragment() {

    private lateinit var lidarrButton: MaterialButton
    private lateinit var lidarrSubtitle: TextView
    private lateinit var lastfmButton: MaterialButton
    private lateinit var lastfmSubtitle: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView =
            inflater.inflate(R.layout.fragment_setup_wizard_services_page, container, false)

        lidarrButton = rootView.findViewById(R.id.lidarr_btn)
        lidarrSubtitle = rootView.findViewById(R.id.lidarr_subtitle)
        lastfmButton = rootView.findViewById(R.id.lastfm_btn)
        lastfmSubtitle = rootView.findViewById(R.id.lastfm_subtitle)

        lidarrButton.setOnClickListener { askLidarr() }
        lastfmButton.setOnClickListener { askLastFm() }

        refreshState()
        return rootView
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        syncLidarrFromPlugin()
    }

    /** Joins the application-owned plugin sync; no Lidarr row tap is required. */
    private fun syncLidarrFromPlugin() {
        val context = context ?: return
        val store = LidarrCredentialStore(context)
        if (store.isConfigured() && store.isSyncedThroughPlugin) return

        lidarrButton.isEnabled = false
        lidarrSubtitle.setText(R.string.setup_services_lidarr_syncing_plugin)
        viewLifecycleOwner.lifecycleScope.launch {
            (requireActivity().application as GramophoneApplication)
                .syncLidarrFromPlugin()
                .join()
            if (!isAdded) return@launch
            lidarrButton.isEnabled = true
            refreshState()
        }
    }

    private fun refreshState() {
        val context = context ?: return
        val lidarr = LidarrCredentialStore(context)
        val lidarrReady = lidarr.isConfigured()
        lidarrButton.isChecked = lidarrReady
        lidarrButton.setText(
            if (lidarrReady) R.string.setup_services_connected else R.string.setup_services_connect
        )
        lidarrSubtitle.setText(
            when {
                lidarrReady && lidarr.isSyncedThroughPlugin ->
                    R.string.lidarr_synced_through_plugin
                lidarrReady -> R.string.setup_services_lidarr_ready
                else -> R.string.setup_services_lidarr_desc
            }
        )

        val lastfmReady = LastFmCredentialStore(context).hasApplicationCredentials()
        lastfmButton.isChecked = lastfmReady
        lastfmButton.setText(
            if (lastfmReady) R.string.setup_services_connected else R.string.setup_services_connect
        )
        lastfmSubtitle.setText(
            if (lastfmReady) R.string.setup_services_lastfm_ready
            else R.string.setup_services_lastfm_desc
        )
    }

    /**
     * Asks for the two things about Lidarr that cannot be discovered, then discovers the rest.
     */
    private fun askLidarr() {
        val context = requireContext()
        val store = LidarrCredentialStore(context)
        val content = layoutInflater.inflate(R.layout.dialog_lidarr_server, null)
        val addressLayout = content.findViewById<TextInputLayout>(R.id.server_url_layout)
        val keyLayout = content.findViewById<TextInputLayout>(R.id.api_key_layout)
        val address = content.findViewById<TextInputEditText>(R.id.server_url)
        val key = content.findViewById<TextInputEditText>(R.id.api_key)
        val status = content.findViewById<TextView>(R.id.status)
        address.setText(store.serverUrl.orEmpty())
        key.setText(store.apiKey.orEmpty())
        addressLayout.enablePasteInto(address)
        keyLayout.enablePasteInto(key)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_category_lidarr)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.setup_services_connect, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = address.text?.toString()?.trim().orEmpty()
                val apiKey = key.text?.toString()?.trim().orEmpty()
                addressLayout.error = if (url.isEmpty()) getString(R.string.lidarr_error_empty) else null
                keyLayout.error = if (apiKey.isEmpty()) getString(R.string.lidarr_error_empty) else null
                if (url.isEmpty() || apiKey.isEmpty()) return@setOnClickListener

                store.updateServer(url, apiKey)
                status.visibility = View.VISIBLE
                status.setText(R.string.lidarr_testing)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                verifyLidarr(store) { result ->
                    if (!isAdded) return@verifyLidarr
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    result.onSuccess {
                        dialog.dismiss()
                        refreshState()
                        Toast.makeText(
                            requireContext(), R.string.setup_services_lidarr_ready, Toast.LENGTH_SHORT
                        ).show()
                    }.onFailure {
                        status.text = it.message ?: getString(R.string.lidarr_error_generic)
                    }
                }
            }
        }
        dialog.show()
    }

    /**
     * Confirms the address and key work before going after the defaults, so a typo is reported as a
     * typo rather than as "could not read the options".
     */
    private fun verifyLidarr(
        store: LidarrCredentialStore,
        onResult: (Result<String>) -> Unit,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LidarrClient(store).testConnection() }
            }
            if (result.isFailure) {
                onResult(result)
                return@launch
            }
            val configured = LidarrSetupPrompt.autoConfigure(requireContext())
            onResult(configured.map { result.getOrThrow() })
        }
    }

    /**
     * Last.fm needs an application key and secret; the account itself is linked later, in settings,
     * because that is a round trip through a browser and does not belong in a setup flow.
     */
    private fun askLastFm() {
        val context = requireContext()
        val store = LastFmCredentialStore(context)
        val apiKey = EditText(context).apply {
            hint = getString(R.string.setup_services_lastfm_key_hint)
            setText(store.apiKey.orEmpty())
            setSingleLine()
        }
        val secret = EditText(context).apply {
            hint = getString(R.string.setup_services_lastfm_secret_hint)
            setText(store.apiSecret.orEmpty())
            setSingleLine()
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(apiKey)
            addView(secret)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_category_scrobbling)
            .setMessage(R.string.setup_services_lastfm_help)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.setup_services_connect) { _, _ ->
                store.apiKey = apiKey.text?.toString()?.trim().orEmpty()
                store.apiSecret = secret.text?.toString()?.trim().orEmpty()
                refreshState()
            }
            .show()
    }
}
