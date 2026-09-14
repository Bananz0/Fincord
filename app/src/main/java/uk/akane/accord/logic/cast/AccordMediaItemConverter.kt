package uk.akane.accord.logic.cast

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.cast.MediaItemConverter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaMetadata as CastMetadata
import com.google.android.gms.common.images.WebImage
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader.Companion.EXTRA_JELLYFIN_ITEM_ID
import org.json.JSONObject

/**
 * Translates between Media3's [MediaItem] and Cast's [MediaQueueItem], in both directions.
 *
 * Both directions matter, and for different reasons. Outbound is what the receiver needs in order
 * to fetch a Jellyfin stream itself. Inbound is what lets this phone *adopt* a queue it did not
 * create - one loaded by another sender, or by this app before its process was killed - instead of
 * overwriting it. [RemoteCastPlayer][androidx.media3.cast.RemoteCastPlayer] builds its timeline by
 * calling [toMediaItem] over the receiver's queue, so anything not recoverable here is simply
 * absent from the player's timeline.
 *
 * Cast's own metadata fields are lossy for that purpose - they carry no media id and no stream URL
 * that survives a round trip - so the original item is also serialised into the queue item's custom
 * data, and [toMediaItem] prefers it. Custom data reaches every joined sender, so a second phone
 * reconstructs full items too. The Cast metadata path remains as a fallback for queue items written
 * by a sender that is not Fincord.
 */
class AccordMediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem =
        MediaQueueItem.Builder(mediaItem.toMediaInfo())
            // Autoplay is a property of the queue, not of how playback happens to start: the load
            // request owns the initial paused/playing state. Leaving it true is what makes NEXT -
            // from this phone, another joined sender, or Google Home - actually start the item.
            .setAutoplay(true)
            .setPreloadTime(PRELOAD_LEAD_SECONDS)
            .build()

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media
        if (info == null) {
            // Cast can describe a queue entry by id alone, with the media filled in later. There is
            // nothing to rebuild from, and the empty item this becomes is what the queue renders as
            // "unknown track / unknown artist".
            Log.w(TAG, "queue item ${mediaQueueItem.itemId} came back with no MediaInfo")
            return MediaItem.EMPTY
        }
        val custom = info.customData?.optJSONObject(KEY_CUSTOM_DATA)
        if (custom != null) return custom.toMediaItem(info)
        // Reached whenever the receiver did not echo back the data this converter attached on the
        // way out - for a queue item written by some other sender, that is expected.
        Log.w(
            TAG,
            "queue item ${mediaQueueItem.itemId} has no Fincord custom data; " +
                "title=${info.metadata?.getString(CastMetadata.KEY_TITLE)} " +
                "contentId=${info.contentId?.takeLast(48)}",
        )
        return info.toMediaItemFromCastMetadata()
    }

    private fun MediaItem.toMediaInfo(): MediaInfo {
        val url = receiverUrl()
        val metadata = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            mediaMetadata.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
            mediaMetadata.artist?.let { putString(CastMetadata.KEY_ARTIST, it.toString()) }
            mediaMetadata.albumTitle?.let {
                putString(CastMetadata.KEY_ALBUM_TITLE, it.toString())
            }
            mediaMetadata.albumArtist?.let {
                putString(CastMetadata.KEY_ALBUM_ARTIST, it.toString())
            }
            // The receiver fetches artwork over the same LAN URL the phone uses.
            mediaMetadata.artworkUri?.let { addImage(WebImage(it)) }
        }
        return MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            // Jellyfin reports a container rather than a MIME type, and the receiver - unlike
            // ExoPlayer - will not sniff: an absent or wrong content type is refused outright.
            .setContentType(localConfiguration?.uri.audioMimeType())
            .setMetadata(metadata)
            .setCustomData(JSONObject().put(KEY_CUSTOM_DATA, toCustomData()))
            .apply { identity().takeIf(String::isNotBlank)?.let(::setEntity) }
            .apply { knownDurationMs()?.let(::setStreamDuration) }
            .build()
    }

    private fun MediaItem.toCustomData(): JSONObject = JSONObject().apply {
        put(KEY_MEDIA_ID, mediaId)
        put(KEY_URI, receiverUrl())
        put(KEY_TITLE, mediaMetadata.title?.toString())
        put(KEY_ARTIST, mediaMetadata.artist?.toString())
        put(KEY_ALBUM, mediaMetadata.albumTitle?.toString())
        put(KEY_ALBUM_ARTIST, mediaMetadata.albumArtist?.toString())
        put(KEY_ARTWORK_URI, mediaMetadata.artworkUri?.toString())
        put(KEY_DURATION_MS, knownDurationMs())
        put(KEY_JELLYFIN_ID, mediaMetadata.extras?.getString(EXTRA_JELLYFIN_ITEM_ID))
    }

    private fun JSONObject.toMediaItem(info: MediaInfo): MediaItem {
        val jellyfinId = optStringOrNull(KEY_JELLYFIN_ID)
        val metadata = MediaMetadata.Builder()
            .setTitle(optStringOrNull(KEY_TITLE))
            .setArtist(optStringOrNull(KEY_ARTIST))
            .setAlbumTitle(optStringOrNull(KEY_ALBUM))
            .setAlbumArtist(optStringOrNull(KEY_ALBUM_ARTIST))
            .setArtworkUri(optStringOrNull(KEY_ARTWORK_URI)?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                optLongOrNull(KEY_DURATION_MS)?.let(::setDurationMs)
                // The library layer identifies a track by this extra, not by the media id, so
                // lyrics and "what is playing" lookups fail silently without it.
                jellyfinId?.let {
                    setExtras(Bundle().apply { putString(EXTRA_JELLYFIN_ITEM_ID, it) })
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(optStringOrNull(KEY_MEDIA_ID) ?: info.contentId)
            .setUri(optStringOrNull(KEY_URI) ?: info.contentId)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Last resort for a queue item some other sender wrote: everything Cast itself exposes. */
    private fun MediaInfo.toMediaItemFromCastMetadata(): MediaItem {
        val castMetadata = metadata
        val metadata = MediaMetadata.Builder()
            .setTitle(castMetadata?.getString(CastMetadata.KEY_TITLE))
            .setArtist(castMetadata?.getString(CastMetadata.KEY_ARTIST))
            .setAlbumTitle(castMetadata?.getString(CastMetadata.KEY_ALBUM_TITLE))
            .setAlbumArtist(castMetadata?.getString(CastMetadata.KEY_ALBUM_ARTIST))
            .setArtworkUri(castMetadata?.images?.firstOrNull()?.url)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply { streamDuration.takeIf { it > 0 }?.let(::setDurationMs) }
            .build()
        return MediaItem.Builder()
            .setMediaId(entity ?: contentId)
            .setUri(contentId)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        takeIf { it.has(key) && !it.isNull(key) }?.getString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        takeIf { it.has(key) && !it.isNull(key) }?.getLong(key)

    private fun String.toUri(): Uri = Uri.parse(this)

    /**
     * The URL the receiver fetches: a [CastGrants] grant when the server issues them.
     *
     * The queue code only converts items whose grant is ready, so the stripped fallback is reached
     * only by a caller that skipped that check - and for it an unplayable URL is the right failure,
     * where the credentialed one would publish the user's token to every joined sender.
     */
    private fun MediaItem.receiverUrl(): String =
        when (val receiver = CastGrants.receiverUrl(this)) {
            is CastGrants.ReceiverUrl.Ready -> receiver.url
            else -> CastGrants.withoutCredentials(localConfiguration?.uri?.toString().orEmpty())
        }

    /** Mirrors the receiver's `entity`: the stable id every other Fincord surface keys on. */
    private fun MediaItem.identity(): String =
        mediaMetadata.extras?.getString(EXTRA_JELLYFIN_ITEM_ID) ?: mediaId

    private fun MediaItem.knownDurationMs(): Long? =
        mediaMetadata.durationMs?.takeIf { it > 0L }
            ?: mediaMetadata.extras?.getLong("Duration")?.takeIf { it > 0L }

    private fun Uri?.audioMimeType(): String {
        val container = this?.getQueryParameter("container")?.lowercase()
        return when (container) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "aac", "mp4" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            // Transcodes go out as an MPEG-TS segment stream; everything else is a guess that the
            // receiver would reject, so fall back to the one type it always accepts.
            "ts" -> "video/mp2t"
            else -> "audio/*"
        }
    }

    private companion object {
        /**
         * How long before an item ends the receiver should start fetching the next one.
         *
         * Must stay well under a track's length. This was briefly set to an hour, on the theory
         * that a bigger number meant "preload sooner" and would make Next respond faster; it does
         * not. The value is measured backwards from the end of the *current* item, so an hour is
         * before every song has even started, which leaves the receiver preloading continuously
         * from the first frame. Thirty seconds is the ordinary case it is designed for: the next
         * stream is open and buffering before the current one runs out. Making a manual Next fast
         * is a different problem, and not one this setting can solve.
         */
        const val PRELOAD_LEAD_SECONDS = 30.0

        const val TAG = "FincordCastConverter"
        const val KEY_CUSTOM_DATA = "fincordMediaItem"
        const val KEY_MEDIA_ID = "mediaId"
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_ALBUM_ARTIST = "albumArtist"
        const val KEY_ARTWORK_URI = "artworkUri"
        const val KEY_DURATION_MS = "durationMs"
        const val KEY_JELLYFIN_ID = "jellyfinItemId"
    }
}
