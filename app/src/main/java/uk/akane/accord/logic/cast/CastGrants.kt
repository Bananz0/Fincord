package uk.akane.accord.logic.cast

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader.Companion.EXTRA_JELLYFIN_ITEM_ID
import org.json.JSONArray
import org.json.JSONObject

internal fun castGrantRenewalMarginMs(lifetimeMs: Long): Long =
    (lifetimeMs / 10L).coerceAtMost(2 * 60 * 60 * 1000L)

/**
 * Stream URLs a Cast receiver can be given without handing it the account.
 *
 * A Cast session broadcasts every queue item's `contentId` and custom data to every sender joined
 * to it, and Cast has no sender authentication: any device on the network running a sender with
 * this receiver's app id can join and read them. The Jellyfin stream URL carries `api_key` - the
 * user's access token, good for the whole account, indefinitely, and from anywhere, because the
 * server has a public hostname. The Fincord plugin instead signs a URL for one item, read-only, for
 * a few hours (`POST /Fincord/Cast/Grants`), and that is what the receiver gets.
 *
 * A server without the plugin answers 404, and for it the credentialed URL is still used: casting
 * keeps working, and the leak is the one that server always had. A server *with* the plugin whose
 * grant request fails does not fall back - the item waits instead, because a transient failure is
 * no reason to publish a credential that outlives it.
 *
 * Grants are fetched ahead of use and read synchronously, so the Cast queue code keeps its
 * synchronous shape: it asks [needsGrants] before building a batch and, when grants are missing,
 * [fetch]es them and runs again.
 */
object CastGrants {

    private const val TAG = "CastGrants"

    /** Renew before expiry, without making the configurable one-hour lifetime unusable. */
    /** How long an item the server declined to grant is treated as uncastable. */
    private const val REFUSED_FOR_MS = 10 * 60 * 1000L

    /** After a failed request, how long before another is attempted. */
    private const val RETRY_AFTER_MS = 15_000L

    /** The plugin considers at most this many items per request. */
    private const val MAX_PER_REQUEST = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private data class Session(val server: String, val token: String, val key: String)

    private data class Grant(
        val url: String,
        val expiresAtElapsedMs: Long,
        val renewalMarginMs: Long,
    )

    /** Keyed by undashed lower-case Jellyfin id. Cleared when the authenticated session changes. */
    private val grants = ConcurrentHashMap<String, Grant>()

    /**
     * Ids the server was asked for and declined - an item this user cannot see, or one deleted since
     * the library synced - with when to stop believing it. Without this such an item would wait for
     * a grant forever, and the queue would ask for it again on every progress tick.
     */
    private val refused = ConcurrentHashMap<String, Long>()

    private val requestLock = Any()
    private val pendingIds = linkedSetOf<String>()
    private val pendingCallbacks = mutableListOf<() -> Unit>()

    @Volatile private var grantSession: String? = null
    @Volatile private var unsupportedSession: String? = null
    @Volatile private var lastFailureElapsedMs = 0L
    @Volatile private var requestInFlight = false

    /** Where an item stands for the receiver. */
    sealed interface ReceiverUrl {
        /** Give the receiver this. */
        data class Ready(val url: String) : ReceiverUrl

        /** The server issues grants and this item does not have a current one yet. */
        data object AwaitingGrant : ReceiverUrl

        /** Nothing a receiver could fetch. */
        data object Uncastable : ReceiverUrl
    }

    /** What to put in front of the receiver for [item], right now, without blocking. */
    fun receiverUrl(item: MediaItem): ReceiverUrl {
        val local = item.localConfiguration?.uri?.toString()
        if (local.isNullOrBlank()) return ReceiverUrl.Uncastable
        val id = item.jellyfinKey() ?: return ReceiverUrl.Ready(local)
        val session = currentSession() ?: return ReceiverUrl.Uncastable
        ensureSession(session.key)
        if (session.key == unsupportedSession) return ReceiverUrl.Ready(local)
        freshGrant(session.key, id)?.let { return ReceiverUrl.Ready(it.url) }
        val refusedUntil = refused[id]
        if (grantSession == session.key && refusedUntil != null && refusedUntil > SystemClock.elapsedRealtime()) {
            return ReceiverUrl.Uncastable
        }
        return ReceiverUrl.AwaitingGrant
    }

    /** Whether any of [items] is waiting on a grant. */
    fun needsGrants(items: List<MediaItem>): Boolean =
        items.any { receiverUrl(it) == ReceiverUrl.AwaitingGrant }

    /** False for a short while after a failed request, so a failing server is not asked every tick. */
    fun mayRequest(): Boolean =
        SystemClock.elapsedRealtime() - lastFailureElapsedMs >= RETRY_AFTER_MS

    /**
     * The URL with any credential query parameter removed.
     *
     * For places a URL is only descriptive - custom data used to recognise an item - and must not be
     * the thing that leaks a token.
     */
    fun withoutCredentials(url: String): String {
        val uri = Uri.parse(url)
        val names = uri.queryParameterNames
        if (names.none { it.equals("api_key", true) || it.equals("ApiKey", true) }) return url
        val builder = uri.buildUpon().clearQuery()
        names.filterNot { it.equals("api_key", true) || it.equals("ApiKey", true) }
            .forEach { name -> uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) } }
        return builder.build().toString()
    }

    /**
     * Requests grants for whichever of [items] lack one, then calls [onDone] on the main thread -
     * whether or not the request succeeded. Callers re-check [needsGrants] rather than being told
     * the outcome, because the answer they need is about the items, not about the request.
     */
    fun fetch(items: List<MediaItem>, onDone: () -> Unit) {
        val session = currentSession()
        val wanted = items.filter { receiverUrl(it) == ReceiverUrl.AwaitingGrant }
            .mapNotNull { it.jellyfinKey() }
            .distinct()
        if (session == null || wanted.isEmpty()) {
            main.post(onDone)
            return
        }
        ensureSession(session.key)
        var launch = false
        synchronized(requestLock) {
            pendingIds.addAll(wanted)
            pendingCallbacks += onDone
            if (!requestInFlight) {
                requestInFlight = true
                launch = true
            }
        }
        if (launch) scope.launch { drain(session) }
    }

    /** Coalesces overlapping queue/load requests into one bounded sequence. */
    private fun drain(session: Session) {
        while (true) {
            var callbacks: List<() -> Unit>? = null
            val ids = synchronized(requestLock) {
                if (grantSession != session.key) return
                if (pendingIds.isEmpty()) {
                    requestInFlight = false
                    callbacks = pendingCallbacks.toList()
                    pendingCallbacks.clear()
                    emptyList()
                } else {
                    pendingIds.take(MAX_PER_REQUEST).also { pendingIds.removeAll(it.toSet()) }
                }
            }
            if (ids.isEmpty()) {
                callbacks.orEmpty().forEach { main.post(it) }
                return
            }
            if (!request(session, ids)) {
                synchronized(requestLock) { pendingIds.clear() }
            }
        }
    }

    /** Warm grants for items likely to be queued soon. Fire and forget. */
    fun prefetch(items: List<MediaItem>) {
        if (!mayRequest() || !needsGrants(items)) return
        fetch(items) {}
    }

    private fun request(session: Session, ids: List<String>): Boolean {
        val body = JSONObject().put("ItemIds", JSONArray(ids)).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${session.server}/Fincord/Cast/Grants")
            .header("Authorization", "MediaBrowser Token=\"${session.token}\"")
            .post(body)
            .build()
        return runCatching {
            JellyfinClientHolder.apiHttpClient().newCall(request).execute().use { response ->
                if (currentSession()?.key != session.key) return@use false
                when {
                    response.isSuccessful -> {
                        val sentAt = response.sentRequestAtMillis
                        val serverNowMs = response.headers.getDate("Date")?.time ?: sentAt
                        val roundTripMs = (response.receivedResponseAtMillis - sentAt).coerceAtLeast(0L)
                        val granted = store(
                            session,
                            JSONObject(response.body?.string().orEmpty()),
                            serverNowMs,
                            roundTripMs,
                            ids.toSet(),
                        ) ?: error("Malformed Cast grant response")
                        val declinedUntil = SystemClock.elapsedRealtime() + REFUSED_FOR_MS
                        (ids - granted).forEach { refused[it] = declinedUntil }
                        if (granted.size < ids.size) {
                            Log.w(TAG, "server declined ${ids.size - granted.size} of ${ids.size} grants")
                        }
                        true
                    }
                    response.code == 404 -> {
                        // A server without the plugin, or with one older than grants. That will not
                        // change while the app runs, so stop asking.
                        unsupportedSession = session.key
                        Log.w(
                            TAG,
                            "${session.server} issues no Cast grants; receivers will be given credentialed " +
                                "stream URLs. Update the Fincord plugin to stop that.",
                        )
                        true
                    }
                    else -> {
                        lastFailureElapsedMs = SystemClock.elapsedRealtime()
                        Log.w(TAG, "grant request refused: ${response.code}")
                        false
                    }
                }
            }
        }.onFailure {
            lastFailureElapsedMs = SystemClock.elapsedRealtime()
            Log.w(TAG, "grant request failed: $it")
        }.getOrDefault(false)
    }

    /** Records the grants in [json] and returns the ids it granted. */
    private fun store(
        session: Session,
        json: JSONObject,
        serverNowMs: Long,
        roundTripMs: Long,
        expectedIds: Set<String>,
    ): Set<String>? {
        val expiresSeconds = json.optLongAnyCase("Expires") ?: return null
        // Measured against the server's own clock, then carried onto this device's monotonic one,
        // so a phone whose wall clock is wrong neither discards good grants nor keeps dead ones.
        val lifetimeMs = expiresSeconds * 1000L - serverNowMs
        if (lifetimeMs <= 0L) return null
        val remainingMs = (lifetimeMs - roundTripMs).coerceAtLeast(0L)
        val expiresAt = SystemClock.elapsedRealtime() + remainingMs
        val renewalMarginMs = castGrantRenewalMarginMs(lifetimeMs)
        val list = json.optJSONArrayAnyCase("Grants") ?: return null
        if (grantSession != session.key) return null
        val parsed = linkedMapOf<String, String>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: return null
            val id = entry.optStringAnyCase("ItemId")?.normaliseId() ?: return null
            val path = entry.optStringAnyCase("Stream") ?: return null
            if (id !in expectedIds || !path.startsWith("/")) return null
            parsed[id] = session.server + path
        }
        parsed.forEach { (id, url) ->
            grants[id] = Grant(url, expiresAt, renewalMarginMs)
            refused.remove(id)
        }
        Log.d(TAG, "stored ${parsed.size} grants, ${remainingMs / 60_000} min each")
        return parsed.keys
    }

    private fun freshGrant(session: String, id: String): Grant? {
        if (grantSession != session) return null
        val grant = grants[id] ?: return null
        return grant.takeIf {
            it.expiresAtElapsedMs - SystemClock.elapsedRealtime() >= it.renewalMarginMs
        }
    }

    private fun currentSession(): Session? {
        val api = JellyfinClientHolder.api() ?: return null
        val server = api.baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val token = api.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Session(server, token, "$server|$digest")
    }

    private fun ensureSession(key: String) {
        if (grantSession == key) return
        synchronized(requestLock) {
            if (grantSession == key) return
            grants.clear()
            refused.clear()
            pendingIds.clear()
            pendingCallbacks.clear()
            requestInFlight = false
            grantSession = key
            unsupportedSession = null
            lastFailureElapsedMs = 0L
        }
    }

    private fun MediaItem.jellyfinKey(): String? =
        mediaMetadata.extras?.getString(EXTRA_JELLYFIN_ITEM_ID)?.normaliseId()

    private fun String.normaliseId(): String = replace("-", "").lowercase()

    private fun JSONObject.keyAnyCase(name: String): String? =
        keys().asSequence().firstOrNull { it.equals(name, ignoreCase = true) }

    private fun JSONObject.optLongAnyCase(name: String): Long? =
        keyAnyCase(name)?.let { optLong(it) }?.takeIf { it > 0 }

    private fun JSONObject.optStringAnyCase(name: String): String? =
        keyAnyCase(name)?.takeUnless { isNull(it) }?.let { optString(it) }?.takeIf { it.isNotBlank() }

    private fun JSONObject.optJSONArrayAnyCase(name: String): JSONArray? =
        keyAnyCase(name)?.let { optJSONArray(it) }
}
