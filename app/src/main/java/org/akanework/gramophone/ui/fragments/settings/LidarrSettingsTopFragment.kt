package org.akanework.gramophone.ui.fragments.settings


import android.os.Bundle
import android.view.View
import android.widget.TextView
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.GramophoneApplication
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment
import uk.akane.accord.ui.components.LidarrSetupPrompt
import uk.akane.accord.ui.components.enablePasteInto

class LidarrSettingsFragment : BaseSettingFragment(
    R.string.settings_category_lidarr,
    { LidarrSettingsTopFragment() }
)

/**
 * Points Accord at the user's Lidarr instance.
 *
 * The three defaults below the address are not decoration: Lidarr refuses an add that does not name
 * a root folder, a quality profile and a metadata profile, and the valid values differ per instance.
 * They are fetched from the server and chosen once so that requesting an album later is a single tap
 * rather than a form.
 */
class LidarrSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_lidarr, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
        // Someone opening this screen to set Lidarr up is exactly who should not have to: the
        // Jellyfin server usually knows the address and key already. Filling them in silently
        // costs one request and only ever writes into empty fields.
        viewLifecycleOwner.lifecycleScope.launch {
            (requireActivity().application as GramophoneApplication)
                .syncLidarrFromPlugin()
                .join()
            if (isAdded) refreshSummaries()
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "lidarr_server" -> showServerDialog()
            "lidarr_root_folder" -> chooseRootFolder()
            "lidarr_quality_profile" -> chooseProfile(quality = true)
            "lidarr_metadata_profile" -> chooseProfile(quality = false)
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSummaries() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = withContext(Dispatchers.IO) { LidarrCredentialStore(requireContext()) }
            val state = withContext(Dispatchers.IO) {
                listOf(store.serverUrl, store.rootFolderPath, store.qualityProfileName,
                    store.metadataProfileName)
            }
            val syncedThroughPlugin = withContext(Dispatchers.IO) {
                store.isSyncedThroughPlugin
            }
            if (!isAdded) return@launch
            findPreference<Preference>("lidarr_server")?.summary =
                state[0]?.takeIf { it.isNotBlank() }?.let {
                    if (syncedThroughPlugin) {
                        getString(R.string.lidarr_synced_through_plugin_summary, it)
                    } else {
                        it
                    }
                } ?: getString(R.string.lidarr_server_summary)
            findPreference<Preference>("lidarr_root_folder")?.summary =
                state[1]?.takeIf { it.isNotBlank() } ?: getString(R.string.lidarr_not_set)
            findPreference<Preference>("lidarr_quality_profile")?.summary =
                state[2]?.takeIf { it.isNotBlank() } ?: getString(R.string.lidarr_not_set)
            findPreference<Preference>("lidarr_metadata_profile")?.summary =
                state[3]?.takeIf { it.isNotBlank() } ?: getString(R.string.lidarr_not_set)
        }
    }

    private fun showServerDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lidarr_server, null)
        val urlField = view.findViewById<TextInputEditText>(R.id.server_url)
        val keyField = view.findViewById<TextInputEditText>(R.id.api_key)
        val urlLayout = view.findViewById<TextInputLayout>(R.id.server_url_layout)
        val keyLayout = view.findViewById<TextInputLayout>(R.id.api_key_layout)
        val status = view.findViewById<TextView>(R.id.status)
        urlLayout.enablePasteInto(urlField)
        keyLayout.enablePasteInto(keyField)

        viewLifecycleOwner.lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                val store = LidarrCredentialStore(requireContext())
                store.serverUrl to store.apiKey
            }
            if (!isAdded) return@launch
            urlField.setText(existing.first.orEmpty())
            keyField.setText(existing.second.orEmpty())

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lidarr_server)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                // Bound below rather than here, so a failed connection test leaves the dialog open
                // with the typed address still in it.
                .setPositiveButton(R.string.lidarr_save_and_test, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener {
                        val url = urlField.text?.toString()?.trim().orEmpty()
                        val key = keyField.text?.toString()?.trim().orEmpty()
                        if (url.isEmpty() || key.isEmpty()) {
                            status.visibility = View.VISIBLE
                            status.setText(R.string.lidarr_error_empty)
                            return@setOnClickListener
                        }
                        status.visibility = View.VISIBLE
                        status.setText(R.string.lidarr_testing)
                        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                            .isEnabled = false
                        viewLifecycleOwner.lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val store = LidarrCredentialStore(requireContext())
                                store.updateServer(url, key)
                                try {
                                    val version = LidarrClient(store).testConnection()
                                    LidarrSetupPrompt.autoConfigure(requireContext()).getOrThrow()
                                    Result.success(version)
                                } catch (e: Exception) {
                                    Result.failure(e)
                                }
                            }
                            if (!isAdded) return@launch
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                                .isEnabled = true
                            result.fold(
                                onSuccess = {
                                    dialog.dismiss()
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.lidarr_connected, it),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    refreshSummaries()
                                },
                                onFailure = {
                                    status.text = it.message
                                        ?: getString(R.string.lidarr_error_generic)
                                }
                            )
                        }
                    }
            }
            dialog.show()
        }
    }

    private fun chooseRootFolder() {
        viewLifecycleOwner.lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) {
                runCatching {
                    LidarrClient(LidarrCredentialStore(requireContext())).rootFolders()
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (folders.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.lidarr_error_generic, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            val labels = folders.map { it.path }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.lidarr_root_folder)
                .setItems(labels) { _, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            LidarrCredentialStore(requireContext()).apply {
                                rootFolderPath = folders[which].path
                                publishConfiguredFlag(requireContext())
                            }
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }

    private fun chooseProfile(quality: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                val client = LidarrClient(LidarrCredentialStore(requireContext()))
                runCatching {
                    if (quality) client.qualityProfiles() else client.metadataProfiles()
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (profiles.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.lidarr_error_generic, Toast.LENGTH_LONG)
                    .show()
                return@launch
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(
                    if (quality) R.string.lidarr_quality_profile
                    else R.string.lidarr_metadata_profile
                )
                .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            LidarrCredentialStore(requireContext()).apply {
                                if (quality) {
                                    qualityProfileId = profiles[which].id
                                    qualityProfileName = profiles[which].name
                                } else {
                                    metadataProfileId = profiles[which].id
                                    metadataProfileName = profiles[which].name
                                }
                                publishConfiguredFlag(requireContext())
                            }
                        }
                        if (isAdded) refreshSummaries()
                    }
                }
                .show()
        }
    }
}
