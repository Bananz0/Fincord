package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stores the Jellyfin server address and access token.
 *
 * The token is a bearer credential for the user's whole media server, so it lives in
 * [EncryptedSharedPreferences]. Keystore-backed preferences are known to fail to open on some
 * devices with a damaged keystore, and a music player refusing to start is a worse outcome than a
 * plaintext token, so that case falls back to ordinary preferences.
 */
class JellyfinCredentialStore(context: Context) {

    private val prefs: SharedPreferences = openPreferences(context)

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /** Tested address used on the same Wi-Fi/LAN as the Jellyfin server. */
    var localServerUrl: String?
        get() = prefs.getString(KEY_LOCAL_SERVER_URL, null)
            ?: serverUrl?.takeIf(JellyfinEndpoints::isLocalAddress)
        set(value) = prefs.edit().putString(KEY_LOCAL_SERVER_URL, value).apply()

    /** Tested public, VPN or reverse-proxy address used away from the server's LAN. */
    var remoteServerUrl: String?
        get() = prefs.getString(KEY_REMOTE_SERVER_URL, null)
            ?: serverUrl?.takeUnless(JellyfinEndpoints::isLocalAddress)
        set(value) = prefs.edit().putString(KEY_REMOTE_SERVER_URL, value).apply()

    var localNetworkName: String?
        get() = prefs.getString(KEY_LOCAL_NETWORK_NAME, null)
        set(value) = prefs.edit().putString(KEY_LOCAL_NETWORK_NAME, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var serverName: String?
        get() = prefs.getString(KEY_SERVER_NAME, null)
        set(value) = prefs.edit().putString(KEY_SERVER_NAME, value).apply()

    /**
     * Stable per-install identifier. Jellyfin ties playback sessions and "remember this device" to
     * it, so it is generated once and then never changes until the user logs out.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    fun isLoggedIn(): Boolean =
        !serverUrl.isNullOrBlank() && !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

    /**
     * Stores a freshly obtained session and mirrors the "signed in" flag into the default
     * preferences for [hasStoredSession].
     *
     * Both writes use commit() rather than apply(). apply() flushes on a background thread, and
     * anything that kills the process shortly after login - including this app's own crash handler
     * calling exitProcess() - would discard the session and drop the user back on this screen with
     * no explanation.
     */
    fun saveSession(
        context: Context,
        serverUrl: String,
        accessToken: String,
        userId: String,
        serverName: String?,
        localServerUrl: String? = null,
        remoteServerUrl: String? = null,
        localNetworkName: String? = null,
    ) {
        @Suppress("ApplySharedPref")
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_SERVER_NAME, serverName)
            .putString(KEY_LOCAL_SERVER_URL, localServerUrl)
            .putString(KEY_REMOTE_SERVER_URL, remoteServerUrl)
            .putString(KEY_LOCAL_NETWORK_NAME, localNetworkName)
            .commit()
        publishSessionFlag(context)
    }

    /**
     * Records the session in ordinary preferences as well, so startup can branch on it without
     * opening the keystore. Call after storing or clearing credentials.
     */
    fun publishSessionFlag(context: Context) {
        @Suppress("ApplySharedPref")
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putBoolean(KEY_HAS_SESSION, isLoggedIn())
            .commit()
    }

    /** Drops the session but keeps [deviceId], so the server still recognises this install. */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_SERVER_NAME)
            .remove(KEY_LOCAL_SERVER_URL)
            .remove(KEY_REMOTE_SERVER_URL)
            .remove(KEY_LOCAL_NETWORK_NAME)
            .apply()
    }

    private fun openPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                ENCRYPTED_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted preferences unavailable, falling back to plaintext", e)
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "JellyfinCredentialStore"
        private const val ENCRYPTED_PREFS_NAME = "jellyfin_credentials"
        private const val FALLBACK_PREFS_NAME = "jellyfin_credentials_plain"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_LOCAL_SERVER_URL = "local_server_url"
        private const val KEY_REMOTE_SERVER_URL = "remote_server_url"
        private const val KEY_LOCAL_NETWORK_NAME = "local_network_name"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_DEVICE_ID = "device_id"

        /** Lives in the default (unencrypted) preferences - it is only a boolean. */
        private const val KEY_HAS_SESSION = "jellyfin_has_session"

        /**
         * Whether a session exists, cheap enough to call on the main thread.
         *
         * Startup has to decide between the library and the login screen *synchronously*: deciding
         * after a coroutine hop makes the resulting startActivity a background activity launch,
         * which StrictMode's penaltyDeath kills the process for.
         */
        fun hasStoredSession(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY_HAS_SESSION, false)
    }
}
