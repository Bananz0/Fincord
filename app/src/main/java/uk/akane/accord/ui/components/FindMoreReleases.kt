package uk.akane.accord.ui.components

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.acquisition.AcquisitionProviders
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NoToast as Toast
import uk.akane.accord.ui.fragments.RequestArtistFragment

/**
 * Opens the downloader's page for an artist the library already holds.
 *
 * The library's artist page can only show what the media server has, so the natural question it
 * raises - "what else did they make" - had no answer anywhere near it: the user had to go to
 * Search, change tab, and type the name they were already looking at. The downloader knows an
 * artist by its own id rather than by name, which is why this is a lookup and not a link.
 */
object FindMoreReleases {

    fun open(activity: MainActivity, artistName: String) {
        val name = artistName.trim()
        if (name.isEmpty()) {
            Toast.makeText(activity, R.string.go_to_artist_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val provider = AcquisitionProviders.active(activity)
        if (!provider.readiness(activity).canSearch) {
            Toast.makeText(activity, R.string.requests_no_lidarr, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, R.string.requests_searching, Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { provider.searchArtists(activity, name) }
            }
            val matches = found.getOrNull().orEmpty()
            // An exact name is the answer whenever the catalogue has one. Falling back to the first
            // result matters for the acts whose library spelling carries punctuation the catalogue
            // drops, and is still the provider's own idea of the best match.
            val artist = matches.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: matches.firstOrNull { it.name.forMatching() == name.forMatching() }
                ?: matches.firstOrNull()
            if (artist == null) {
                Toast.makeText(
                    activity,
                    found.exceptionOrNull()?.message ?: activity.getString(R.string.requests_no_results),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            activity.collapseNowPlaying()
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                RequestArtistFragment.newInstance(artist)
            )
        }
    }

    private fun String.forMatching(): String = lowercase().filter { it.isLetterOrDigit() }
}
