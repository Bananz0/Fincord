package org.akanework.gramophone.logic.data.jellyfin

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem

/** Builds reversible karaoke MediaItems without changing their Jellyfin identity or metadata. */
object KaraokeMediaItems {

    fun isActive(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(EXTRA_ACTIVE, false) == true

    fun activate(item: MediaItem, streamUrl: String): MediaItem {
        val original = originalUri(item) ?: error("The current track has no playable URI")
        return item.withUriAndExtras(
            uri = Uri.parse(streamUrl),
            extras = Bundle(item.mediaMetadata.extras).apply {
                putBoolean(EXTRA_ACTIVE, true)
                putString(EXTRA_ORIGINAL_URI, original.toString())
            },
        )
    }

    fun deactivate(item: MediaItem): MediaItem {
        val original = originalUri(item) ?: return item
        return item.withUriAndExtras(
            uri = original,
            extras = Bundle(item.mediaMetadata.extras).apply {
                remove(EXTRA_ACTIVE)
                remove(EXTRA_ORIGINAL_URI)
            },
        )
    }

    fun originalUri(item: MediaItem): Uri? =
        originalUriString(
            stored = item.mediaMetadata.extras?.getString(EXTRA_ORIGINAL_URI),
            current = item.localConfiguration?.uri?.toString(),
        )?.let(Uri::parse)

    internal fun originalUriString(stored: String?, current: String?): String? =
        stored?.takeIf(String::isNotBlank) ?: current?.takeIf(String::isNotBlank)

    private fun MediaItem.withUriAndExtras(uri: Uri, extras: Bundle): MediaItem =
        buildUpon()
            .setUri(uri)
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()

    internal const val EXTRA_ACTIVE = "AccordKaraokeActive"
    internal const val EXTRA_ORIGINAL_URI = "AccordKaraokeOriginalUri"
}
