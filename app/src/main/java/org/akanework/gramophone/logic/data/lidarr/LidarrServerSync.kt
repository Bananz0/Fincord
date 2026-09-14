package org.akanework.gramophone.logic.data.lidarr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.json.JSONObject

/**
 * Takes Lidarr's address and key from the Jellyfin server rather than from the user.
 *
 * The server has known both all along. Asking every person to find an API key in Lidarr's settings
 * and type it into a phone is a setup step that exists only because nothing carried the answer
 * across - and it is the step most likely to be got wrong, since a mistyped key fails in exactly
 * the same way as a server that is switched off.
 *
 * Answered by the Fincord server plugin. A server without it, or a user the server will not tell,
 * simply leaves the manual fields as they were: this fills a gap, it never overrules a person who
 * has already entered something.
 */
object LidarrServerSync {

    /**
     * Fetches and stores Lidarr's details if the server offers them.
     *
     * @param force overwrite settings already present. Off by default - someone who has pointed
     *   this phone at a different Lidarr than the server uses meant to.
     * @return true when the store now holds a usable address and key because of this call.
     */
    suspend fun sync(context: Context, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val store = LidarrCredentialStore(context)
            val credentials = JellyfinCredentialStore(context)
            val baseUrl = credentials.serverUrl?.trimEnd('/') ?: return@withContext false
            val token = credentials.accessToken ?: return@withContext false

            val body = runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/Fincord/ClientConfig")
                    .header("Authorization", "MediaBrowser Token=\"$token\"")
                    .build()
                JellyfinClientHolder.apiHttpClient().newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body?.string()
                        // 403 is the server saying this user may not have them, and 404 that the
                        // plugin is not installed. Neither is a fault worth surfacing: the manual
                        // fields are still there and still work.
                        else -> {
                            Log.d(TAG, "Server did not share Lidarr: ${response.code}")
                            null
                        }
                    }
                }
            }.getOrElse {
                Log.d(TAG, "Could not ask the server for Lidarr: $it")
                null
            } ?: return@withContext false

            val lidarr = runCatching {
                JSONObject(body).child("lidarr")
            }.getOrNull() ?: return@withContext false

            val serverUrl = lidarr.string("serverUrl")
            val apiKey = lidarr.string("apiKey")
            if (serverUrl == null || apiKey == null) return@withContext false

            val normalizedUrl = serverUrl.trim().trimEnd('/')
            val normalizedKey = apiKey.trim()
            val hasExisting = !store.serverUrl.isNullOrBlank() && !store.apiKey.isNullOrBlank()
            val matchesExisting = store.serverUrl == normalizedUrl && store.apiKey == normalizedKey
            val mayAdopt = force || !hasExisting || store.isSyncedThroughPlugin || matchesExisting
            if (!mayAdopt) {
                Log.i(TAG, "Kept manually configured Lidarr instead of replacing it from plugin")
                return@withContext false
            }

            // A matching pair is marked as plugin-sourced too. This migrates installs that adopted
            // plugin credentials before the source flag existed without overwriting a manual setup.
            store.updateServerFromPlugin(normalizedUrl, normalizedKey)
            Log.i(TAG, "Adopted Lidarr settings from the Jellyfin server")
            true
        }

    /**
     * Finds a field however the server spelled it.
     *
     * Jellyfin serializes a plugin controller's own types in PascalCase while its first-party API
     * is camelCase, and which one a given endpoint produces is the server's business rather than
     * something worth pinning a client to. Matching either way costs nothing and means a change of
     * serializer settings on some future server does not quietly stop this working.
     */
    private fun JSONObject.key(name: String): String? =
        keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }

    private fun JSONObject.child(name: String): JSONObject? = key(name)?.let(::optJSONObject)

    private fun JSONObject.string(name: String): String? =
        key(name)?.let(::optString)?.takeIf { it.isNotBlank() }

    private const val TAG = "LidarrServerSync"
}
