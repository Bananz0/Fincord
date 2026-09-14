package org.akanework.gramophone.logic.data.jellyfin

import android.util.Log
import org.jellyfin.sdk.api.client.extensions.pluginApi
import org.jellyfin.sdk.model.api.PluginStatus

/**
 * What the Jellyfin server has installed, so the app can avoid duplicating it.
 *
 * The only question asked so far is whether the server scrobbles to Last.fm itself. If it does, the
 * app reporting playback to the server is already enough to get a scrobble, and signing in to
 * Last.fm here as well means every track is scrobbled twice - which is not something the user finds
 * out until their profile is full of duplicates.
 *
 * No server-side plugin of our own is involved: the SDK's [pluginApi] performs the authenticated
 * `/Plugins` read that the server already answers.
 */
object JellyfinPlugins {

    /**
     * Whether the server's Last.fm plugin is installed and running.
     *
     * Three-valued on purpose. Null means "could not tell" - signed out, offline, or a server that
     * refuses the plugin list to non-administrators - and a warning must not be shown on a guess.
     */
    suspend fun isLastFmScrobblingActive(): Boolean? {
        cached?.let { return it }
        val api = JellyfinClientHolder.api() ?: return null
        val plugins = runCatching {
            api.pluginApi.getPlugins().content
        }.getOrElse {
            // A non-administrator may not be allowed to inspect installed plugins. That is an
            // unknown result, not evidence that the server is not scrobbling.
            Log.d(TAG, "Plugin list unavailable: $it")
            return null
        }

        return plugins.any { plugin ->
                val name = plugin.name.lowercase()
                if (LASTFM_NAMES.none { name.contains(it) }) return@any false
                // A plugin can be present but switched off, or superseded by a newer copy of
                // itself that has not been loaded yet; neither of those scrobbles anything.
                plugin.status == PluginStatus.ACTIVE
            }
            .also { cached = it }
    }

    /** Forget the answer, for when the user signs in to a different server. */
    fun invalidate() {
        cached = null
    }

    /**
     * Held for the process's lifetime. Plugins are not installed while someone is looking at a
     * settings screen, and the alternative is a network call every time the screen is opened.
     */
    @Volatile
    private var cached: Boolean? = null

    /** The plugin has been spelled both ways across its releases. */
    private val LASTFM_NAMES = listOf("last.fm", "lastfm")

    private const val TAG = "JellyfinPlugins"
}
