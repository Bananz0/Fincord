package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.preference.PreferenceManager

/**
 * How much of a track to actually fetch.
 *
 * A lossless library is the reason to run Jellyfin and also the reason a phone fills up: FLAC is
 * roughly three times the bytes of a good AAC, both over the air and on disk. Asking the server to
 * transcode trades fidelity for both at once, and which of those matters is the user's call rather
 * than something to infer - somebody may want everything kept locally at 256 purely for space, on
 * wifi, with no data cap in sight.
 *
 * Three separate answers, because they are three separate decisions:
 *  - [streamingQuality] for playing over an unmetered connection,
 *  - [meteredStreamingQuality] for playing over mobile data,
 *  - [downloadQuality] for what gets kept, which is about storage and not the network at all.
 *
 * [ORIGINAL] asks for the file untouched, which is what this app did everywhere before these
 * settings existed and remains the default for all three.
 */
enum class StreamQuality(
    val bitrateBps: Int,
    val preferenceValue: String,
    @param:androidx.annotation.StringRes val labelRes: Int,
) {
    /** No transcoding: the server hands over the file as stored. */
    ORIGINAL(0, "original", uk.akane.accord.R.string.quality_original),
    HIGH(320_000, "320", uk.akane.accord.R.string.quality_320),
    MEDIUM(256_000, "256", uk.akane.accord.R.string.quality_256),
    LOW(192_000, "192", uk.akane.accord.R.string.quality_192);

    val isOriginal: Boolean get() = this == ORIGINAL

    /**
     * Distinguishes one variant of a track from another in the media cache.
     *
     * Without this every quality would collide on the same cache entry, so a track first heard at
     * 256 on the train would keep playing at 256 at home no matter what the setting said - and the
     * eviction that follows a server-side retag would clear one variant and leave the rest stale.
     */
    val cacheSuffix: String get() = if (isOriginal) "" else "|$preferenceValue"

    companion object {
        const val KEY_STREAMING = "streaming_quality"
        const val KEY_METERED_STREAMING = "metered_streaming_quality"
        const val KEY_DOWNLOAD = "download_quality"

        fun fromPreference(value: String?): StreamQuality =
            entries.firstOrNull { it.preferenceValue == value } ?: ORIGINAL

        private fun read(context: Context, key: String): StreamQuality =
            fromPreference(
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                    .getString(key, ORIGINAL.preferenceValue)
            )

        fun downloadQuality(context: Context): StreamQuality = read(context, KEY_DOWNLOAD)

        /**
         * The quality to play at right now, which depends on how the phone is connected.
         *
         * Reads the metered flag rather than the transport type, so a metered wifi hotspot counts
         * as mobile data - which is what the user meant by the setting even though it is not what
         * it is called.
         */
        fun streamingQuality(context: Context): StreamQuality =
            if (isMetered(context)) read(context, KEY_METERED_STREAMING)
            else read(context, KEY_STREAMING)

        private fun isMetered(context: Context): Boolean =
            context.applicationContext.getSystemService<ConnectivityManager>()
                ?.isActiveNetworkMetered == true
    }
}
