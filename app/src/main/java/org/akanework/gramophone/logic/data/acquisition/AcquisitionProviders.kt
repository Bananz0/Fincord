package org.akanework.gramophone.logic.data.acquisition

import android.content.Context

/**
 * Every downloader this app can drive, and the one place that picks between them.
 *
 * There is one today. The registry exists anyway, because it is what keeps "Lidarr" out of the
 * screens: adding a second is a new [AcquisitionProvider] and a line in [all].
 */
object AcquisitionProviders {

    val all: List<AcquisitionProvider> by lazy {
        listOf(LidarrAcquisitionProvider())
    }

    fun byId(id: String): AcquisitionProvider? = all.firstOrNull { it.id == id }

    /**
     * The provider to use, preferring one that is completely set up.
     *
     * Never null: with nothing configured the first provider is still returned, so callers can ask
     * it for its [AcquisitionProvider.readiness] and tell the user what is missing rather than
     * silently doing nothing.
     */
    fun active(context: Context): AcquisitionProvider =
        all.firstOrNull { it.readiness(context).canRequest }
            ?: all.firstOrNull { it.readiness(context).canSearch }
            ?: all.first()

    /** Providers that at least have an address, in preference order. */
    fun usable(context: Context): List<AcquisitionProvider> =
        all.filter { it.readiness(context).canSearch }
}
