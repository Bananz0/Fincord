package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client for Accord's authenticated, asynchronous instrumental-stem service. */
object JellyfinKaraoke {

    enum class Status { IDLE, QUEUED, PROCESSING, READY, FAILED }

    data class State(
        val status: Status,
        val phase: String,
        val progress: Int,
        val message: String?,
        val streamUrl: String?,
    )

    suspend fun status(context: Context, mediaId: String): Result<State> =
        request(context, mediaId, prepare = false)

    suspend fun prepare(context: Context, mediaId: String): Result<State> =
        request(context, mediaId, prepare = true)

    private suspend fun request(
        context: Context,
        mediaId: String,
        prepare: Boolean,
    ): Result<State> {
        // A configuration change destroys the Activity while this synchronous HTTP call may still
        // be occupying an IO worker. Capturing the View's Activity context in that worker retained
        // the entire old player hierarchy until the socket timed out (LeakCanary signature
        // c47f1ef322c9e43deb595b6972b0479fb4971920). The resolver only needs app storage, so keep the
        // process context across the dispatcher boundary and let the Activity go immediately.
        val appContext = context.applicationContext
        return try {
            val request = withContext(Dispatchers.IO) {
                val credentials = JellyfinClientHolder.credentials
                val serverUrl = credentials.serverUrl ?: error("Not signed in to Jellyfin")
                val token = credentials.accessToken ?: error("Not signed in to Jellyfin")
                val remoteId = JellyfinItemResolver.remoteIdForMediaId(appContext, mediaId)
                    ?: error("This track is not from Jellyfin")
                val endpoint = endpointCandidates(serverUrl).firstOrNull()
                    ?: error("The karaoke service address is invalid")
                RequestDetails(endpoint, token, remoteId)
            }
            Result.success(execute(request.endpoint, request.token, request.remoteId, prepare))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "Karaoke request failed", failure)
            Result.failure(failure)
        }
    }

    private data class RequestDetails(
        val endpoint: HttpUrl,
        val token: String,
        val remoteId: String,
    )

    @WorkerThread
    private suspend fun execute(
        base: HttpUrl,
        token: String,
        remoteId: String,
        prepare: Boolean,
    ): State {
        val url = base.newBuilder()
            .addPathSegments("v1/tracks")
            .addPathSegment(remoteId)
            .apply { if (prepare) addPathSegment("prepare") }
            .build()
        val builder = Request.Builder()
            .url(url)
            .header("X-Emby-Token", token)
            .header("Accept", "application/json")
        if (prepare) {
            builder.post(ByteArray(0).toRequestBody("application/json".toMediaType()))
        }
        val call = JellyfinClientHolder.apiHttpClient().newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { parseResponse(base, it) } }
                    if (!continuation.isActive) return
                    result.fold(
                        onSuccess = continuation::resume,
                        onFailure = continuation::resumeWithException,
                    )
                }
            })
        }
    }

    @WorkerThread
    private fun parseResponse(base: HttpUrl, response: Response): State {
            // OkHttp 4 types `body` as nullable because the cached and network sub-responses on a
            // Response carry none; the one handed back by a call always does. An empty body is a
            // real answer here anyway - the next line already treats blank as an empty object.
            val body = response.body?.string().orEmpty()
            val payload = body.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            if (!response.isSuccessful) {
                error(payload.optString("error").takeIf(String::isNotBlank)
                    ?: "Karaoke service returned HTTP ${response.code}")
            }
            val streamPath = payload.optString("streamPath").takeIf(String::isNotBlank)
            return State(
                status = runCatching {
                    Status.valueOf(payload.optString("status", "idle").uppercase())
                }.getOrDefault(Status.IDLE),
                phase = payload.optString("phase", "idle"),
                progress = payload.optInt("progress", 0).coerceIn(0, 100),
                message = payload.opt("message")
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.toString()
                    ?.takeIf(String::isNotBlank),
                streamUrl = streamPath?.removePrefix("/")?.let(base::resolve)?.toString(),
            )
    }

    /**
     * Public Jellyfin goes through the VPS and exposes the service below the same origin. Direct
     * LAN logins use the adjacent host port, so the Jellyfin token is never redirected elsewhere.
     */
    internal fun endpointCandidates(serverUrl: String): List<HttpUrl> {
        val server = serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val sameOrigin = server.newBuilder()
            .encodedPath(server.encodedPath.trimEnd('/') + "/accord-karaoke/")
            .query(null)
            .fragment(null)
            .build()
        if (server.host.endsWith("glenmuthoka.com", ignoreCase = true)) {
            return listOf(sameOrigin)
        }
        // A directly entered LAN Jellyfin address has no reverse proxy path. The service listens
        // beside Jellyfin on 8097 and still validates the same Jellyfin access token.
        val direct = server.newBuilder()
            .port(8097)
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
        return listOf(direct, sameOrigin).distinct()
    }

    private const val TAG = "JellyfinKaraoke"
}
