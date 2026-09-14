package uk.akane.accord.logic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Publishes recently played music as a semantic, exact, on-device shortcut. */
object PlaybackShortcuts {
    private const val TAG = "PlaybackShortcuts"
    private const val GET_THING = "actions.intent.GET_THING"
    private const val THING_NAME = "thing.name"

    /**
     * The size the artwork is fetched at.
     *
     * An adaptive icon is 108dp, of which the launcher may mask away the outer ring, so this is
     * about three times the densest common launcher grid and leaves the mask something to cut.
     */
    private const val ICON_PX = 320

    suspend fun push(context: Context, mediaItem: MediaItem) {
        val mediaId = mediaItem.mediaId.takeIf { it.isNotBlank() } ?: return
        val metadata = mediaItem.mediaMetadata
        val title = metadata.title?.toString()?.trim().orEmpty()
            .ifBlank { context.getString(R.string.unknown_title) }
        val artist = metadata.artist?.toString()?.trim().orEmpty()
        val stableId = UUID.nameUUIDFromBytes(mediaId.toByteArray(StandardCharsets.UTF_8)).toString()
        val deepLink = Uri.Builder()
            .scheme(MainActivity.APP_LINK_SCHEME)
            .authority(MainActivity.PLAY_LINK_HOST)
            .appendPath(MainActivity.PLAY_LINK_PATH)
            .appendPath(mediaId)
            .build()
        val intent = Intent(Intent.ACTION_VIEW, deepLink, context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val aliases = listOf(title, listOf(title, artist).filter { it.isNotBlank() }.joinToString(" by "))
            .distinct()

        val shortcut = ShortcutInfoCompat.Builder(context, "play-$stableId")
            .setShortLabel(title.take(40))
            .setLongLabel(
                if (artist.isBlank()) title.take(80) else "$title — $artist".take(80),
            )
            .setIcon(
                artwork(context, mediaItem)?.let(IconCompat::createWithAdaptiveBitmap)
                    ?: IconCompat.createWithResource(context, R.drawable.ic_music_note_24dp),
            )
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setIntent(intent)
            .setLocusId(LocusIdCompat("media-$stableId"))
            .addCapabilityBinding(GET_THING, THING_NAME, aliases)
            .build()

        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
            .onFailure { Log.w(TAG, "Unable to publish shortcut for $mediaId", it) }
    }

    /**
     * The track's cover, as a bitmap the system can keep.
     *
     * A shortcut is what the launcher, the Assistant and the on-device suggestion engines actually
     * show, and a row of identical music notes tells a person nothing about which song each one is.
     * The cover is the only thing that distinguishes them at a glance.
     *
     * Hardware bitmaps are refused deliberately: an [IconCompat] is written into a parcel and
     * handed to another process, and a hardware bitmap has no pixels on this side to write - the
     * icon silently arrives blank. Everything here returns null on any failure, and the caller
     * falls back to the generic note rather than publishing no shortcut at all.
     */
    private suspend fun artwork(context: Context, mediaItem: MediaItem): Bitmap? {
        val uri = mediaItem.mediaMetadata.artworkUri ?: return null
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(ICON_PX, ICON_PX)
                .allowHardware(false)
                .build()
            (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap()
        }.onFailure { Log.w(TAG, "no shortcut artwork for ${mediaItem.mediaId}", it) }
            .getOrNull()
    }
}
