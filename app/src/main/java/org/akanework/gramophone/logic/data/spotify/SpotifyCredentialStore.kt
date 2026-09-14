package org.akanework.gramophone.logic.data.spotify


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import uk.akane.accord.BuildConfig

/**
 * Stores the Spotify app credentials and the user's OAuth tokens.
 *
 * Accord uses the Authorization Code flow with PKCE, which exists so a mobile app can authenticate
 * without shipping a client secret - there is nowhere on a phone to keep one that the phone's owner
 * cannot read. Only the client id is needed, and it is not a secret.
 *
 * The client id is application configuration, not user configuration. It is supplied by the build
 * through `package.properties`; the user only links or unlinks their account.
 */
class SpotifyCredentialStore(context: Context) {

    private val prefs: SharedPreferences = openPreferences(context)

    val clientId: String
        get() = BuildConfig.SPOTIFY_CLIENT_ID

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Unix millis at which [accessToken] stops working. */
    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_EXPIRES_AT, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    /**
     * The PKCE verifier for an authorisation in flight.
     *
     * Has to outlive the process: the user leaves for a browser to approve access, and Android is
     * free to kill the app while they are gone. Losing the verifier would make the returned code
     * unredeemable.
     */
    var pendingCodeVerifier: String?
        get() = prefs.getString(KEY_CODE_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_CODE_VERIFIER, value).commit().let { }

    fun isLinked(): Boolean = !refreshToken.isNullOrBlank() && clientId.isNotBlank()

    fun hasClientId(): Boolean = clientId.isNotBlank()

    fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        nowMillis: Long,
    ) {
        @Suppress("ApplySharedPref")
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            // A refresh response may omit the refresh token, which means keep using the old one.
            if (!refreshToken.isNullOrBlank()) putString(KEY_REFRESH_TOKEN, refreshToken)
            // Expire a minute early so a request is never sent with a token about to lapse.
            putLong(KEY_EXPIRES_AT, nowMillis + (expiresInSeconds - 60) * 1000)
            remove(KEY_CODE_VERIFIER)
        }.commit()
        publishLinkFlag(context)
    }

    fun clear(context: Context) {
        @Suppress("ApplySharedPref")
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_CODE_VERIFIER)
            .commit()
        publishLinkFlag(context)
    }

    fun publishLinkFlag(context: Context) {
        @Suppress("ApplySharedPref")
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            .edit()
            .putBoolean(KEY_IS_LINKED, isLinked())
            .commit()
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
        private const val TAG = "SpotifyCredentialStore"
        private const val ENCRYPTED_PREFS_NAME = "spotify_credentials"
        private const val FALLBACK_PREFS_NAME = "spotify_credentials_plain"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_CODE_VERIFIER = "code_verifier"

        private const val KEY_IS_LINKED = "spotify_is_linked"

        /**
         * Must match the redirect URI registered on the Spotify app, and the intent filter on
         * SpotifyAuthActivity.
         */
        const val REDIRECT_URI = "fincord://spotify-callback"

        fun isLinked(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                .getBoolean(KEY_IS_LINKED, false)
    }
}
