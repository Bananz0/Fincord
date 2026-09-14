package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import java.net.URI

/** Resolution, identity checking and failover for a server's LAN and remote addresses. */
object JellyfinEndpoints {

    data class TestedEndpoint(
        val url: String,
        val serverId: String?,
        val serverName: String?,
    )

    /** Expands a hostname/bare IP exactly as Jellyfin's SDK does, then proves the API responds. */
    suspend fun testInput(input: String): TestedEndpoint? {
        val resolved = try {
            JellyfinClientHolder.discovery()
                .getRecommendedServers(input, RecommendedServerInfoScore.OK)
                .minWithOrNull(
                    compareBy<RecommendedServerInfo> { it.score.ordinal }
                        .thenBy { it.responseTime }
                )
                ?.address
        } catch (failure: Exception) {
            Log.w(TAG, "Could not resolve Jellyfin endpoint", failure)
            null
        } ?: return null
        return testResolved(resolved)
    }

    /**
     * The address exactly as typed, in the shape this app stores addresses in.
     *
     * Used for an address the user chose to keep without it having answered - the LAN address
     * entered from outside the house, or a proxy that is down right now. It is not proven, so it
     * gets no resolution pass; [resolveStored] does that the first time it does answer.
     */
    fun normalizeInput(input: String): String =
        input.trim().withDefaultScheme().trimEnd('/')

    /** Public system info is deliberately unauthenticated and identifies the Jellyfin instance. */
    suspend fun testResolved(url: String): TestedEndpoint? = runCatching {
        val endpoint = url.trimEnd('/')
        val info = JellyfinClientHolder.createEndpointProbeApi(endpoint)
            .systemApi.getPublicSystemInfo().content
        TestedEndpoint(
            url = endpoint,
            serverId = info.id?.takeIf(String::isNotBlank),
            serverName = info.serverName?.takeIf(String::isNotBlank),
        )
    }.onFailure { Log.d(TAG, "Jellyfin endpoint did not answer", it) }.getOrNull()

    /**
     * Probes a stored address, falling back to full resolution when the stored form does not answer.
     *
     * Onboarding can save an address that never answered - the LAN one typed in from outside the
     * house, or a proxy that was down at sign-in - so a stored address is not necessarily in the
     * resolved form [testInput] would have produced. Resolution costs an extra round trip and only
     * happens after the cheap probe has already failed.
     */
    private suspend fun resolveStored(url: String): TestedEndpoint? =
        testResolved(url) ?: testInput(url)

    /**
     * Tests both stored addresses in parallel, prefers LAN when it answers, and swaps the active
     * SDK base URL when necessary. Where onboarding proved both addresses it also proved they
     * expose the same server id, so the same access token remains valid across the swap.
     */
    suspend fun selectReachableStoredEndpoint(): String? = coroutineScope {
        val credentials = JellyfinClientHolder.credentials
        val localStored = credentials.localServerUrl
        val remoteStored = credentials.remoteServerUrl
        val candidates = listOfNotNull(localStored, remoteStored).distinct()
        if (candidates.isEmpty()) return@coroutineScope credentials.serverUrl
        val results = candidates.map { candidate ->
            async { candidate to resolveStored(candidate) }
        }.awaitAll().toMap()
        val currentNetwork = currentWifiName(JellyfinClientHolder.context())
        val savedNetwork = credentials.localNetworkName
        val localMatchesNetwork = savedNetwork == null || currentNetwork == null ||
            savedNetwork == currentNetwork
        val chosenStored = localStored?.takeIf {
            results[it] != null && (localMatchesNetwork || remoteStored == null)
        }
            ?: remoteStored?.takeIf { results[it] != null }
            ?: return@coroutineScope credentials.serverUrl
        val chosen = results[chosenStored]?.url ?: chosenStored
        // An unproven address is stored as typed. The first time it answers, keep the resolved
        // form that worked so later switches are one probe rather than two.
        if (chosen != chosenStored) {
            if (chosenStored == localStored) credentials.localServerUrl = chosen
            else credentials.remoteServerUrl = chosen
        }
        if (chosen != credentials.serverUrl) {
            credentials.serverUrl = chosen
            JellyfinClientHolder.invalidate()
        }
        chosen
    }

    /** Used to migrate installs that only have the historical single URL. */
    internal fun isLocalAddress(url: String): Boolean {
        val host = runCatching { URI(url.withDefaultScheme()).host?.lowercase() }
            .getOrNull() ?: return false
        if (host == "localhost" || host.endsWith(".local") || !host.contains('.')) return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size == 4) {
            val (a, b) = parts
            return a == 10 || a == 127 || (a == 192 && b == 168) ||
                (a == 172 && b in 16..31)
        }
        return host.contains(':') && (host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") ||
            host.startsWith("feb"))
    }

    /** Android may redact the SSID without location access; null keeps reachability fallback active. */
    fun currentWifiName(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val value = (capabilities.transportInfo as? WifiInfo)?.ssid
            ?.trim()
            ?.removeSurrounding("\"")
        return value?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
    }

    /** Re-homes a cached Jellyfin API/media URL after LAN ↔ remote endpoint selection. */
    fun rewriteServerBase(original: Uri, activeServerUrl: String?): Uri {
        if (activeServerUrl.isNullOrBlank()) return original
        val apiIndex = original.pathSegments.indexOfFirst {
            it == "Audio" || it == "Items" || it == "Users"
        }
        if (apiIndex < 0) return original
        val base = Uri.parse(activeServerUrl)
        if (base.host.isNullOrBlank()) return original
        val builder = base.buildUpon().path(null).clearQuery().fragment(null)
        base.pathSegments.filter(String::isNotBlank).forEach(builder::appendPath)
        original.pathSegments.drop(apiIndex).forEach(builder::appendPath)
        original.queryParameterNames.forEach { name ->
            original.getQueryParameters(name).forEach { value ->
                builder.appendQueryParameter(name, value)
            }
        }
        return builder.build()
    }

    private fun String.withDefaultScheme(): String =
        if (contains("://")) this else "http://$this"

    private const val TAG = "JellyfinEndpoints"
}
