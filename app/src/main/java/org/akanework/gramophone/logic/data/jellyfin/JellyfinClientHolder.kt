package org.akanework.gramophone.logic.data.jellyfin


import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import uk.akane.accord.BuildConfig
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.discovery.DiscoveryService
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Owns the Jellyfin SDK objects for the whole process.
 *
 * Everything is built on a single [OkHttpFactory] so the SDK's metadata calls, artwork loading and
 * ExoPlayer's media requests share one connection pool - reconnecting per stream is a noticeable
 * cost on mobile networks.
 */
object JellyfinClientHolder {

    private const val CLIENT_NAME = "Fincord"

    private lateinit var appContext: Context
    private val endpointScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var networkCallbackRegistered = false

    /** Shared connection pool; [mediaHttpClient] hands the same one to media3. */
    private val okHttpFactory = OkHttpFactory()

    @Volatile
    private var cachedApi: ApiClient? = null

    /**
     * Registers the application context. Kept deliberately cheap: opening the credential store
     * touches the keystore and disk, and debug builds run StrictMode with penaltyDeath, so that
     * work is deferred to [credentials] and must first be triggered off the main thread.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (!networkCallbackRegistered) {
            val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
            runCatching {
                connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        endpointScope.launch { JellyfinEndpoints.selectReachableStoredEndpoint() }
                    }
                })
                networkCallbackRegistered = connectivity != null
            }.onFailure { Log.d(CLIENT_NAME, "Could not watch network changes", it) }
        }
    }

    internal fun context(): Context = appContext

    /** Opens the credential store on first use. Must be touched off the main thread first. */
    val credentials: JellyfinCredentialStore by lazy { JellyfinCredentialStore(appContext) }

    private val jellyfin: Jellyfin by lazy {
        createJellyfin {
            this.context = appContext
            clientInfo = ClientInfo(CLIENT_NAME, BuildConfig.MY_VERSION_NAME)
            deviceInfo = DeviceInfo(
                id = credentials.deviceId,
                name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            )
            apiClientFactory = okHttpFactory
        }
    }

    /**
     * An API client bound to the stored session, or null when signed out. Cached because the
     * library loader, the playback service and the reporter all need one.
     */
    fun api(): ApiClient? {
        cachedApi?.let { return it }
        if (!credentials.isLoggedIn()) return null
        return synchronized(this) {
            cachedApi ?: jellyfin.createApi(
                baseUrl = credentials.serverUrl,
                accessToken = credentials.accessToken,
                // The SDK defaults to a 6 second connect timeout, which a phone whose WiFi radio
                // has just woken from doze regularly overshoots on a LAN server. A full library
                // sync is also many sequential pages, so the request timeout has to allow for a
                // slow server rather than the default.
                httpClientOptions = HttpClientOptions(
                    connectTimeout = 20.seconds,
                    socketTimeout = 30.seconds,
                    requestTimeout = 120.seconds,
                )
            ).also { cachedApi = it }
        }
    }

    /**
     * A client for a server we are not signed in to yet, used by the login screen.
     */
    fun createUnauthenticatedApi(serverUrl: String): ApiClient =
        jellyfin.createApi(baseUrl = serverUrl)

    /** A short-lived unauthenticated client used only to prove that a candidate base URL answers. */
    fun createEndpointProbeApi(serverUrl: String): ApiClient = jellyfin.createApi(
        baseUrl = serverUrl,
        httpClientOptions = HttpClientOptions(
            connectTimeout = 4.seconds,
            socketTimeout = 4.seconds,
            requestTimeout = 5.seconds,
        ),
    )

    /**
     * Server discovery and address resolution.
     *
     * The SDK knows how to turn what someone actually types - "192.168.1.192", "jellyfin.example.com
     * /jf", a bare hostname - into the candidate URLs worth probing, and how to find servers
     * broadcasting on the local network. Guessing at the scheme and port in the app instead is the
     * single biggest reason a correct address gets rejected as wrong.
     */
    fun discovery(): DiscoveryService = jellyfin.discovery

    /** Call after login or logout so [api] stops handing out a stale session. */
    fun invalidate() {
        synchronized(this) { cachedApi = null }
    }

    /**
     * The OkHttp client media3 should use. Request timeouts are disabled: the default call timeout
     * applies to the whole response body, which for a long track is a download that legitimately
     * outlives it.
     */
    fun mediaHttpClient(): OkHttpClient =
        okHttpFactory.createClient(HttpClientOptions(requestTimeout = kotlin.time.Duration.ZERO))

    @Volatile
    private var cachedApiHttpClient: OkHttpClient? = null

    /**
     * The client for small API calls - Last.fm, Spotify, anything that is a request rather than a
     * stream.
     *
     * Shares [mediaHttpClient]'s connection pool but puts the timeouts back. Media deliberately has
     * none, because a call timeout applied to a long track is a download that legitimately outlives
     * it; borrowing that client for an API call means a half-open socket - a server restarted, a
     * network moved under us - blocks the caller forever instead of failing.
     */
    fun apiHttpClient(): OkHttpClient {
        cachedApiHttpClient?.let { return it }
        return synchronized(this) {
            cachedApiHttpClient ?: mediaHttpClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
                .also { cachedApiHttpClient = it }
        }
    }

}
