package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.akanework.gramophone.logic.data.matching.MusicBrainzResolver
import org.json.JSONObject

/**
 * Asks the Jellyfin server for an artist's discography instead of asking MusicBrainz.
 *
 * MusicBrainz is donated infrastructure with a published limit of one request per second per
 * client, and every phone in the house was its own client: the same artist fetched separately on
 * each, paced separately, and thrown away separately when the app was killed. The server is one
 * client, it is already awake, and it keeps what it fetched - so it asks once and everyone reads
 * that copy.
 *
 * What comes back is MusicBrainz's own `release-groups` array, unfiltered, parsed here by exactly
 * the code that reads a direct answer. That is deliberate: it keeps which records belong on an
 * artist page a decision this app makes, and it means a server without the plugin is a fallback
 * rather than a different feature.
 */
object JellyfinDiscography {

    private const val TAG = "JellyfinDiscography"

    /**
     * The artist's release groups as the server has them, or null when it cannot answer.
     *
     * Null means "ask MusicBrainz yourself" in every case - no plugin, an older plugin, a server
     * that is unreachable, or a plugin that could not reach MusicBrainz either.
     */
    suspend fun releaseGroupsOfArtist(
        context: Context,
        artistId: String,
    ): List<MusicBrainzResolver.ReleaseGroup>? = withContext(Dispatchers.IO) {
        if (artistId.isBlank() || available == false) return@withContext null
        val credentials = JellyfinCredentialStore(context)
        val baseUrl = credentials.serverUrl?.trimEnd('/') ?: return@withContext null
        val token = credentials.accessToken ?: return@withContext null

        val body = runCatching {
            val request = Request.Builder()
                .url("$baseUrl/Fincord/Discography/$artistId")
                .header("Authorization", "MediaBrowser Token=\"$token\"")
                .build()
            JellyfinClientHolder.apiHttpClient().newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        available = true
                        response.body?.string()
                    }
                    // 404 is a server without the plugin, and that will not change while the app
                    // is running. Remember it, or every artist page pays a pointless round trip
                    // before falling back to the source it was going to use anyway.
                    response.code == 404 -> {
                        available = false
                        Log.d(TAG, "Server has no discography endpoint")
                        null
                    }
                    // 503 is the plugin saying it has nothing cached and could not fetch. Anything
                    // else is a permissions or auth problem. Both are this request's problem only.
                    else -> {
                        Log.d(TAG, "Server would not serve a discography: ${response.code}")
                        null
                    }
                }
            }
        }.getOrElse {
            Log.d(TAG, "Could not ask the server for a discography: $it")
            null
        } ?: return@withContext null

        runCatching {
            MusicBrainzResolver.artistReleaseGroups(
                JSONObject(body).optJSONArray("release-groups")
            )
        }.getOrElse {
            Log.d(TAG, "Server discography was not readable: $it")
            null
        }?.takeIf { it.isNotEmpty() }
    }

    /** Forget whether the plugin is there, for when the user signs in to a different server. */
    fun invalidate() {
        available = null
    }

    /**
     * Whether this server has the plugin. Null until the first answer settles it; false is held
     * for the process's lifetime, since a plugin is not installed while an app is running.
     */
    @Volatile
    private var available: Boolean? = null
}
