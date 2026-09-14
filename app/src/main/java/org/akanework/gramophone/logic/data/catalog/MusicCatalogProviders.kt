package org.akanework.gramophone.logic.data.catalog

import android.content.Context

/**
 * Every catalogue this app can read from, and the one place that picks between them.
 *
 * Adding a service is adding a line to [all]. Nothing that consumes a link - the request screen,
 * the share handler, the playlist importer - names a provider, so none of them change.
 */
object MusicCatalogProviders {

    val all: List<MusicCatalogProvider> by lazy {
        listOf(
            SpotifyCatalogProvider(),
            DeezerCatalogProvider(),
            AppleMusicCatalogProvider(),
        )
    }

    fun byId(id: String): MusicCatalogProvider? = all.firstOrNull { it.id == id }

    fun providerFor(url: String): MusicCatalogProvider? =
        url.takeIf { looksLikeLink(it) }?.let { link -> all.firstOrNull { it.recognises(link) } }

    /** True for anything worth handing to [resolve] rather than treating as a search term. */
    fun looksLikeLink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.contains("://") ||
            // Desktop clients copy URIs, not URLs: `spotify:album:…`.
            URI_SCHEME.containsMatchIn(trimmed)
    }

    /** Resolves [url] through whichever provider owns it. */
    suspend fun resolve(context: Context, url: String): CatalogResolution {
        val provider = providerFor(url) ?: return CatalogResolution.Unrecognised
        return provider.resolve(context, url)
    }

    /** For messages that have to list what the user may paste. */
    fun displayNames(): List<String> = all.map(MusicCatalogProvider::displayName)

    private val URI_SCHEME = Regex("""(?i)^[a-z][a-z0-9+.-]*:[a-z]+:""")
}
