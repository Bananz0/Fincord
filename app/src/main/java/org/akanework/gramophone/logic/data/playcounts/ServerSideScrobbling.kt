package org.akanework.gramophone.logic.data.playcounts

import android.util.Log
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.jellyfin.sdk.api.client.extensions.pluginApi

/**
 * Whether the server is already importing a service's play counts by itself.
 *
 * Jellyfin's Last.fm plugin can be configured to sync play counts from Last.fm into the very
 * UserData this importer writes. Where that is on, importing from Accord as well is not merely
 * redundant - it breaks the ledger. The whole scheme rests on being able to say that any count
 * Jellyfin holds which no source accounts for is organic listening; a plugin raising counts from
 * Last.fm between runs violates that, its increases are absorbed into the baseline, and this app's
 * Last.fm contribution is then added on top of them. The error compounds on every import.
 *
 * So the question is asked rather than assumed. A server without the plugin has no such problem,
 * and blanket-disabling the source there would remove a feature for no reason.
 */
object ServerSideScrobbling {

    private const val TAG = "ServerSideScrobbling"

    /**
     * Plugin names that import play counts into Jellyfin.
     *
     * Matched on name because the plugin's GUID has changed across the forks of it in circulation,
     * while the display name has not.
     */
    private val PLAY_COUNT_PLUGINS = mapOf(
        PlayCountSource.LAST_FM to listOf("last.fm", "lastfm"),
    )

    /**
     * Whether a plugin on the server already owns [source]'s play counts.
     *
     * Null when the answer is unknown - listing plugins is an administrator's call and a normal
     * account is refused. Unknown is not "no": the caller warns rather than blocks, because the
     * alternative is silently letting a non-admin user corrupt their own counts.
     */
    suspend fun handledByServer(source: PlayCountSource): Boolean? {
        val names = PLAY_COUNT_PLUGINS[source] ?: return false
        val api = JellyfinClientHolder.api() ?: return null
        return try {
            val plugins = api.pluginApi.getPlugins().content
            plugins.any { plugin ->
                val name = plugin.name.lowercase()
                names.any { name.contains(it) }
            }
        } catch (e: Exception) {
            // A non-administrator gets 403 here, which is not an error worth surfacing - it just
            // means this app cannot see the answer.
            Log.d(TAG, "Could not list server plugins: ${e.message}")
            null
        }
    }
}
