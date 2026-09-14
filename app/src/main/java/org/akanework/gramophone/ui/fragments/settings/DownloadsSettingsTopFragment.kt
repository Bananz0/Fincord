package org.akanework.gramophone.ui.fragments.settings


import android.os.Bundle
import android.text.format.Formatter
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import org.akanework.gramophone.logic.data.jellyfin.StreamQuality
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment

class DownloadsSettingsFragment : BaseSettingFragment(
    R.string.settings_category_downloads,
    { DownloadsSettingsTopFragment() }
)

/**
 * Shows how much space offline music takes and offers to reclaim it.
 *
 * Two figures rather than one, because they answer different questions: downloads are what the user
 * deliberately stored, while the cache total also includes whatever streaming happened to leave
 * behind. Clearing downloads only removes the former.
 */
class DownloadsSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_downloads, rootKey)
        // Applying the new ceiling immediately is the only way the choice is legible - the figures
        // above it are what tells the user the setting did anything.
        findPreference<Preference>("cache_size_limit")?.setOnPreferenceChangeListener { _, _ ->
            view?.post { trimCache() }
            true
        }

        // Each quality is a separate cache entry, so changing this orphans the previous one rather
        // than replacing it. Dropped without asking: the cache is disposable and leaving the old
        // copies behind would quietly hold on to the space the change was meant to free.
        listOf(StreamQuality.KEY_STREAMING, StreamQuality.KEY_METERED_STREAMING).forEach { key ->
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, value ->
                val chosen = StreamQuality.fromPreference(value as? String)
                view?.post { dropStaleVariants(chosen) }
                true
            }
        }

        // Downloads are not touched behind the user's back - see promptRequalifyDownloads.
        findPreference<Preference>(StreamQuality.KEY_DOWNLOAD)?.setOnPreferenceChangeListener { _, value ->
            val chosen = StreamQuality.fromPreference(value as? String)
            view?.post { promptRequalifyDownloads(chosen) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSizes()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference.key == "downloads_clear") {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.downloads_clear)
                .setMessage(R.string.downloads_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.downloads_clear) { _, _ -> clearDownloads() }
                .show()
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun refreshSizes() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Both figures read the media3 index and the cache directory, which is disk I/O.
            val sizes = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.downloadedBytes(requireContext()) to
                        JellyfinMediaCache.currentSizeBytes(requireContext())
            }
            val byQuality = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.downloadsByQuality(requireContext())
            }
            if (!isAdded) return@launch
            // Broken down by quality, because downloads keep whatever they were fetched at and
            // changing the setting leaves them alone. Without this the only way to discover you
            // are holding 256 where you asked for Original is to listen for it.
            val breakdown = byQuality.entries
                .sortedByDescending { it.value.first }
                .joinToString("  ·  ") { (quality, stats) ->
                    val (count, bytes) = stats
                    val label = getString(quality.labelRes)
                    "$count $label (${Formatter.formatFileSize(requireContext(), bytes)})"
                }
            findPreference<Preference>("downloads_size")?.summary = if (breakdown.isEmpty()) {
                getString(
                    R.string.downloads_size_summary,
                    Formatter.formatFileSize(requireContext(), sizes.first)
                )
            } else {
                breakdown
            }
            findPreference<Preference>("downloads_cache_size")?.summary = getString(
                R.string.downloads_cache_size_summary,
                Formatter.formatFileSize(requireContext(), sizes.second)
            )
        }
    }

    private fun dropStaleVariants(keep: StreamQuality) {
        viewLifecycleOwner.lifecycleScope.launch {
            val dropped = withContext(Dispatchers.IO) {
                JellyfinMediaCache.dropOtherVariants(requireContext(), keep)
            }
            if (!isAdded) return@launch
            if (dropped > 0) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.quality_cache_cleared, dropped),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            refreshSizes()
        }
    }

    /**
     * Offers to re-fetch downloads that are stored at a different quality.
     *
     * Asked rather than done. A download is the copy somebody chose to keep, and re-fetching it
     * costs their data and their time; going the other way costs their storage. The direction
     * decides which of those to warn about, so the message names it rather than saying "re-download
     * 400 tracks" and leaving them to work out what that means.
     */
    private fun promptRequalifyDownloads(target: StreamQuality) {
        viewLifecycleOwner.lifecycleScope.launch {
            val stale = withContext(Dispatchers.IO) {
                JellyfinDownloadManager.downloadsNotAt(requireContext(), target)
            }
            if (!isAdded || stale.isEmpty()) return@launch

            val message = if (target.isOriginal) {
                getString(R.string.quality_requalify_upgrade, stale.size)
            } else {
                getString(R.string.quality_requalify_downgrade, stale.size, target.preferenceValue)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.quality_requalify_title)
                .setMessage(message)
                .setNegativeButton(R.string.quality_requalify_keep, null)
                .setPositiveButton(R.string.quality_requalify_go) { _, _ ->
                    requalifyDownloads(stale)
                }
                .show()
        }
    }

    private fun requalifyDownloads(mediaIds: List<String>) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                JellyfinDownloadManager.redownload(requireContext(), mediaIds)
            }
            if (!isAdded) return@launch
            Toast.makeText(
                requireContext(),
                getString(R.string.quality_requalify_started, mediaIds.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun trimCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                JellyfinMediaCache.trimToLimit(requireContext())
            }
            if (!isAdded) return@launch
            if (freed > 0L) {
                Toast.makeText(
                    requireContext(),
                    getString(
                        R.string.downloads_cache_trimmed,
                        Formatter.formatFileSize(requireContext(), freed)
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            refreshSizes()
        }
    }

    private fun clearDownloads() {
        JellyfinDownloadManager.removeAll(requireContext())
        Toast.makeText(requireContext(), R.string.downloads_cleared, Toast.LENGTH_SHORT).show()
        // Removal runs in the download service, so the figures only settle a moment later. Refresh
        // on the next resume rather than reporting a stale number now.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            if (isAdded) refreshSizes()
        }
    }
}
