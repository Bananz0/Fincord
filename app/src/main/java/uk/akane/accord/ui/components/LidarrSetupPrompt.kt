package uk.akane.accord.ui.components

import android.content.Context
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.lidarr.LidarrClient
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.logic.data.lidarr.LidarrServerSync
import uk.akane.accord.R

/**
 * Discovers the Lidarr settings a request needs, at the moment it needs them.
 *
 * Lidarr will not accept an album without a root folder, a quality profile and a metadata profile.
 * Being told to go to the settings screen and find three fields is a worse answer than being asked
 * for them, particularly when Lidarr can say what the choices are.
 *
 * Everything here is read from the server. The root with the most available space is selected and
 * Lidarr's first quality and metadata profiles are used. Settings can still offer advanced
 * overrides, but first-run setup never asks users to interpret server-internal profile ids.
 */
object LidarrSetupPrompt {

    /**
     * Makes sure the defaults are set, discovering them if they are not.
     *
     * @param onReady run once everything needed is stored - the request that triggered this can then
     *   go ahead. Not called if the user backs out.
     */
    fun ensureConfigured(
        context: Context,
        owner: LifecycleOwner,
        onReady: () -> Unit,
    ) {
        val store = LidarrCredentialStore(context)
        if (store.isConfigured()) {
            onReady()
            return
        }

        owner.lifecycleScope.launch {
            if (store.serverUrl.isNullOrBlank() || store.apiKey.isNullOrBlank()) {
                // The Jellyfin server usually knows both already. Asking it first turns the worst
                // setup step in the app - find an API key in Lidarr, type it into a phone, get it
                // subtly wrong - into nothing at all. Only if it will not say do we ask the user.
                if (!LidarrServerSync.sync(context)) {
                    Toast.makeText(context, R.string.requests_no_lidarr, Toast.LENGTH_LONG).show()
                    return@launch
                }
            }

            val result = autoConfigure(context)
            if (result.isSuccess) onReady()
            else Toast.makeText(
                context,
                result.exceptionOrNull()?.message ?: context.getString(R.string.lidarr_setup_unreachable),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private data class Options(
        val rootFolders: List<LidarrClient.RootFolder>,
        val quality: List<LidarrClient.Profile>,
        val metadata: List<LidarrClient.Profile>,
    )

    /** Fetches and saves deterministic defaults. Safe to call again after Lidarr changes. */
    suspend fun autoConfigure(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val store = LidarrCredentialStore(context)
            val client = LidarrClient(store)
            val options = Options(
                rootFolders = client.rootFolders().filter { it.path.isNotBlank() },
                quality = client.qualityProfiles().filter { it.id > 0 },
                metadata = client.metadataProfiles().filter { it.id > 0 },
            )
            val root = options.rootFolders.maxByOrNull { it.freeSpaceBytes ?: Long.MIN_VALUE }
                ?: error(context.getString(R.string.lidarr_setup_no_root))
            val quality = options.quality.firstOrNull()
                ?: error(context.getString(R.string.lidarr_setup_no_profiles))
            val metadata = options.metadata.firstOrNull()
                ?: error(context.getString(R.string.lidarr_setup_no_profiles))

            val currentRoot = options.rootFolders.firstOrNull { it.path == store.rootFolderPath }
            val currentQuality = options.quality.firstOrNull { it.id == store.qualityProfileId }
            val currentMetadata = options.metadata.firstOrNull { it.id == store.metadataProfileId }
            store.rootFolderPath = (currentRoot ?: root).path
            store.qualityProfileId = (currentQuality ?: quality).id
            store.qualityProfileName = (currentQuality ?: quality).name
            store.metadataProfileId = (currentMetadata ?: metadata).id
            store.metadataProfileName = (currentMetadata ?: metadata).name
            store.publishConfiguredFlag(context)
            check(store.isConfigured()) { context.getString(R.string.lidarr_setup_incomplete) }
        }
    }
}
