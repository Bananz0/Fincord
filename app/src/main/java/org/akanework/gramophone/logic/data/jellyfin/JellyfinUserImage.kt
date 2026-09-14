package org.akanework.gramophone.logic.data.jellyfin

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.UUID

/**
 * The signed-in user's Jellyfin profile picture.
 *
 * The navigation bar drew a tinted glyph whoever was signed in. Jellyfin already knows what this
 * user looks like - it is shown on the sign-in screen's user picker - so the same picture belongs in
 * the app, and changing it here changes it everywhere rather than only in Accord.
 *
 * Reads use the SDK. Writes cannot: see [upload] for what the SDK does to a user's existing picture
 * on this server. The route is `/UserImage` either way - the older `/Users/{id}/Images/Primary` still
 * answers reads but is the deprecated spelling.
 */
object JellyfinUserImage {

    /**
     * The current picture's URL, or null when the user has none.
     *
     * A flow rather than a getter because the navigation bar on every screen shows it, and a change
     * made in settings has to reach all of them without each one polling.
     */
    val urlFlow: StateFlow<String?> get() = _urlFlow.asStateFlow()
    private val _urlFlow = MutableStateFlow<String?>(null)

    /**
     * Re-reads the picture from the server.
     *
     * The tag is what makes the URL change when the picture does; without it every client would
     * keep showing the cached copy of the old one indefinitely.
     */
    suspend fun refresh() {
        val api = JellyfinClientHolder.api()
        val userId = JellyfinClientHolder.credentials.userId
        if (api == null || userId == null) {
            _urlFlow.value = null
            return
        }
        val tag = runCatching {
            val user by api.userApi.getCurrentUser()
            user.primaryImageTag
        }.getOrElse {
            Log.w(TAG, "Could not read the current user", it)
            return
        }
        _urlFlow.value = tag?.let {
            api.imageApi.getUserImageUrl(
                userId = UUID.fromString(userId.toDashedUuid()),
                tag = it,
            )
        }
    }

    /**
     * Replaces the picture. [bytes] should already be scaled down - see [MAX_EDGE_PX] for why.
     *
     * Deliberately not through the SDK. Its `postUserImage` sends the file as raw bytes, which this
     * server answers with HTTP 500 - and, worse, clears the picture the user already had on the way
     * out, so a failed upload silently destroys their avatar. The route wants the image base64
     * encoded in the body, which is what is sent here; that was checked against the live server, and
     * a picture uploaded this way reads back byte for byte.
     *
     * @return whether the server accepted it.
     */
    suspend fun upload(bytes: ByteArray, mediaType: String): Boolean {
        val session = session() ?: return false
        val request = Request.Builder()
            .url("${session.server}/UserImage?userId=${session.userId}")
            .header("Authorization", session.authHeader)
            .post(
                Base64.encodeToString(bytes, Base64.NO_WRAP)
                    .toByteArray()
                    .toRequestBody(mediaType.toMediaType())
            )
            .build()
        return send(request, "Uploading the profile picture")
    }

    /** Removes the picture, leaving the app's default glyph in its place. */
    suspend fun remove(): Boolean {
        val api = JellyfinClientHolder.api() ?: return false
        val userId = JellyfinClientHolder.credentials.userId ?: return false
        val accepted = withContext(Dispatchers.IO) {
            runCatching {
                api.imageApi.deleteUserImage(UUID.fromString(userId.toDashedUuid()))
                true
            }.getOrElse {
                Log.w(TAG, "Removing the profile picture failed", it)
                false
            }
        }
        refresh()
        return accepted
    }

    private suspend fun send(request: Request, what: String): Boolean {
        val accepted = withContext(Dispatchers.IO) {
            runCatching {
                JellyfinClientHolder.apiHttpClient().newCall(request).execute().use { response ->
                    response.isSuccessful.also {
                        if (!it) Log.w(TAG, "$what rejected with HTTP ${response.code}")
                    }
                }
            }.getOrElse {
                Log.w(TAG, "$what failed", it)
                false
            }
        }
        // Even a rejected write can have changed what the server holds, so the picture is re-read
        // either way rather than assumed unchanged.
        refresh()
        return accepted
    }

    private class Session(val server: String, val token: String, val userId: String) {
        val authHeader get() = "MediaBrowser Token=\"$token\""
    }

    private fun session(): Session? {
        val credentials = JellyfinClientHolder.credentials
        val server = credentials.serverUrl?.trimEnd('/') ?: return null
        val token = credentials.accessToken ?: return null
        val userId = credentials.userId ?: return null
        return Session(server, token, userId)
    }

    /** Forget the picture, for when the session changes. */
    fun clear() {
        _urlFlow.value = null
    }

    private const val TAG = "JellyfinUserImage"

    /**
     * The longest edge an uploaded picture is scaled to.
     *
     * Jellyfin stores whatever it is given and hands it back unchanged - it ignores maxWidth on this
     * endpoint, which was checked against the server: a 7 MB PNG avatar comes back at 7 MB however
     * small the request asks for. Every client that shows the picture then pays for that, so the
     * scaling has to happen before the upload rather than being left to the server.
     */
    const val MAX_EDGE_PX = 512

    /** Uploaded as JPEG: an avatar has no transparency to preserve and PNG is far larger. */
    const val UPLOAD_MEDIA_TYPE = "image/jpeg"
    const val UPLOAD_QUALITY = 90
}
