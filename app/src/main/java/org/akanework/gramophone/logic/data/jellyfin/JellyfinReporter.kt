package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Tells the Jellyfin server what is being played, and syncs favourites.
 *
 * Without this the server never learns a track was played, so play counts, "recently played" and
 * resume position stay frozen at whatever the last web-client session left behind - and the same
 * library looks untouched from every other client.
 *
 * Every call is fire-and-forget on a background scope: reporting is best-effort telemetry and must
 * never delay or break playback if the server is unreachable.
 */
class JellyfinReporter(context: Context) {

    /**
     * Reporting is intentionally allowed to finish after its caller is destroyed (most notably
     * the final playback-stopped report from the service's onDestroy). Keep only the process
     * context so an in-flight OkHttp continuation cannot retain that service or a UI context.
     */
    private val appContext = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Ticks are Jellyfin's unit throughout its API: 100 nanoseconds. Reporting milliseconds
     * directly would tell the server every track resumes 10,000x too early.
     */
    private fun Long.msToTicks(): Long = this * TICKS_PER_MILLISECOND

    fun reportStart(mediaId: String?, positionMs: Long, isPaused: Boolean) = report(mediaId) { api, id ->
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                isPaused = isPaused,
                canSeek = true,
                isMuted = false,
                // Reported, not assumed. This field is taken at face value by the server's
                // dashboard and by the playback-reporting plugin - nothing on the server checks it
                // - so hardcoding DirectPlay while asking for a transcode makes both of them lie,
                // and makes the one place you would look to confirm a quality setting works say
                // that it does not.
                playMethod = playMethod(appContext),
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
        Log.d(TAG, "Reported playback start for $id at ${positionMs}ms")
    }

    fun reportProgress(mediaId: String?, positionMs: Long, isPaused: Boolean) = report(mediaId) { api, id ->
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                isPaused = isPaused,
                canSeek = true,
                isMuted = false,
                // Reported, not assumed. This field is taken at face value by the server's
                // dashboard and by the playback-reporting plugin - nothing on the server checks it
                // - so hardcoding DirectPlay while asking for a transcode makes both of them lie,
                // and makes the one place you would look to confirm a quality setting works say
                // that it does not.
                playMethod = playMethod(appContext),
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
        )
    }

    fun reportStopped(mediaId: String?, positionMs: Long) = report(mediaId) { api, id ->
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = id,
                positionTicks = positionMs.msToTicks(),
                failed = false,
            )
        )
        Log.d(TAG, "Reported playback stopped for $id at ${positionMs}ms")
    }

    /**
     * Mirrors a favourite toggle to the server so other clients see it.
     *
     * Deliberately not through the SDK. Its favourite call targets the newer "current user" route,
     * which this server answers 200 to - with a body claiming the item is now a favourite - and then
     * does not persist, for any user. Nothing here can detect that: the call succeeds, the response
     * says yes, and the star silently means nothing. The older per-user route does persist, so it is
     * addressed directly.
     */
    fun setFavourite(mediaId: String?, favourite: Boolean) = report(mediaId) { _, id ->
        val credentials = JellyfinClientHolder.credentials
        val server = credentials.serverUrl?.trimEnd('/')
        val token = credentials.accessToken
        val userId = credentials.userId
        if (server == null || token == null || userId == null) {
            Log.w(TAG, "Not signed in; cannot sync favourite for $id")
            return@report
        }
        // Both GUIDs go in undashed. The server accepts the dashed form in this path, answers 200,
        // and returns a body saying the item is now a favourite - while writing nothing at all.
        // There is no way to detect that from the response, so the only defence is to send the form
        // that works.
        val request = Request.Builder()
            .url(
                "$server/Users/${userId.undashed()}" +
                        "/FavoriteItems/${id.toString().undashed()}"
            )
            .header("Authorization", "MediaBrowser Token=\"$token\"")
            .apply { if (favourite) post(EMPTY_BODY) else delete() }
            .build()
        PLAIN_CLIENT.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.d(TAG, "Favourite ${request.method} for $id accepted")
            } else {
                Log.w(TAG, "Favourite for $id rejected with HTTP ${response.code}")
            }
        }
    }

    private inline fun report(
        mediaId: String?,
        crossinline block: suspend (org.jellyfin.sdk.api.client.ApiClient, UUID) -> Unit
    ) {
        if (mediaId == null) return
        scope.launch {
            try {
                val api = JellyfinClientHolder.api() ?: return@launch
                val remote = JellyfinItemResolver.remoteIdForMediaId(appContext, mediaId)
                    ?: return@launch
                block(api, UUID.fromString(remote.toDashedUuid()))
            } catch (e: Exception) {
                // Telemetry only - a failure here must not surface to the user or stop playback.
                Log.w(TAG, "Reporting call failed", e)
            }
        }
    }

    /**
     * What this client is actually asking the server for.
     *
     * Derived from the quality setting rather than stated once and forgotten: with a cap in place
     * the request carries a codec and a container the source does not have, and the server encodes
     * on the fly.
     */
    private fun playMethod(context: Context): PlayMethod =
        if (StreamQuality.streamingQuality(context).isOriginal) PlayMethod.DIRECT_PLAY
        else PlayMethod.TRANSCODE

    companion object {
        private const val TAG = "JellyfinReporter"
        private const val TICKS_PER_MILLISECOND = 10_000L

        /**
         * A client of our own, deliberately not the SDK's.
         *
         * The SDK's factory client carries its own auth handling; a hand-built request sent through
         * it reaches the server without the credentials set here, which Jellyfin answers 200 to and
         * then ignores.
         */
        private val PLAIN_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Jellyfin expects a POST with no payload for a favourite. */
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** Jellyfin's paths want GUIDs with no dashes; see [setFavourite] for why it matters. */
        fun String.undashed(): String = replace("-", "")

        /**
         * Jellyfin returns GUIDs without dashes ("a1b2..."), but UUID.fromString() demands the
         * dashed form, so it has to be reinserted before parsing.
         */
        fun String.toDashedUuid(): String {
            if (length != 32) return this
            return buildString(36) {
                append(this@toDashedUuid, 0, 8).append('-')
                append(this@toDashedUuid, 8, 12).append('-')
                append(this@toDashedUuid, 12, 16).append('-')
                append(this@toDashedUuid, 16, 20).append('-')
                append(this@toDashedUuid, 20, 32)
            }
        }
    }
}
