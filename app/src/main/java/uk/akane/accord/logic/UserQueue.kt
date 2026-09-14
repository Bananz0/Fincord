package uk.akane.accord.logic

import androidx.media3.common.MediaItem

/**
 * Marks the tracks the user put in the queue by hand.
 *
 * Jellyfin's player holds one flat list, so a track queued with Play Next is indistinguishable from
 * the album it was dropped into - which is fine for playback and useless for explaining the queue.
 * Apple Music separates the two: what you asked for plays first, and then it says where the rest is
 * coming from. That needs the distinction to survive from the swipe all the way to the queue view.
 *
 * Carried in the item's own metadata rather than in a registry beside the player. The controller
 * talks to a playback service across a process boundary and hands back items it has round-tripped;
 * anything kept alongside would have to be re-matched by id on the way out, and would be wrong the
 * moment the service restored a queue this app did not build.
 */
object UserQueue {

    private const val EXTRA_USER_QUEUED = "AccordUserQueued"

    /**
     * Returns [item] tagged as user-queued.
     *
     * Existing extras are copied rather than replaced: the library loader puts play counts,
     * favourite state and the source container in here, and a track queued by hand would otherwise
     * lose all of it and render differently from the same track anywhere else.
     */
    fun mark(item: MediaItem): MediaItem {
        val metadata = item.mediaMetadata
        val extras = android.os.Bundle(metadata.extras ?: android.os.Bundle()).apply {
            putBoolean(EXTRA_USER_QUEUED, true)
        }
        return item.buildUpon()
            .setMediaMetadata(metadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun isUserQueued(item: MediaItem): Boolean =
        item.mediaMetadata.extras?.getBoolean(EXTRA_USER_QUEUED, false) == true
}
