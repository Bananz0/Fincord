package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource

/**
 * Rewrites a request to the quality the user asked for, at the moment it is opened.
 *
 * Deliberately not done when the library is built. A [androidx.media3.common.MediaItem] made at
 * sync time would carry whichever quality was configured then, so changing the setting would mean
 * rebuilding nine thousand items, and walking out of the house mid-album would keep streaming
 * lossless over mobile data because the URI was decided indoors. Resolving per request means the
 * answer is always current and nothing upstream has to know about it.
 *
 * Each variant gets its own explicit cache key. Without one, media3 derives the key from the URI
 * and every quality would occupy a separate, unpredictable entry - so eviction after a server-side
 * edit could clear one and leave the others serving pre-edit tags. Keying on the item id plus the
 * quality makes the whole set enumerable from a track alone.
 */
@OptIn(UnstableApi::class)
object StreamQualityResolver {

    private const val PARAM_STATIC = "static"
    private const val PARAM_CONTAINER = "container"
    private const val PARAM_AUDIO_CODEC = "AudioCodec"
    private const val PARAM_AUDIO_BITRATE = "AudioBitrate"

    /**
     * The transcoding container, carried as a file extension on the path.
     *
     * This is the part that decides. Asking `/Audio/{id}/stream` for a codec and a bitrate leaves
     * the container unchanged and the server hands over the file; naming the container in the path
     * is what selects an encoder. Confirmed by asking Jellyfin directly: `PlaybackInfo` for a FLAC
     * against an mp3/aac profile answers `SupportsDirectPlay: false` and hands back a
     * `TranscodingUrl` of exactly this shape.
     */
    private const val TRANSCODE_EXTENSION = "stream.ts"

    /**
     * AAC. Chosen over Opus or Vorbis because every Android decoder handles it, and over MP3
     * because it is materially better at these bitrates.
     */
    private const val TRANSCODE_CODEC = "aac"

    /**
     * @param quality supplied per call rather than read here, so playback and downloading can ask
     *   for different things - they are different settings and a download is about disk, not data.
     */
    /**
     * @param cachedVariant the variant of a track this device already holds, if any. Consulted
     *   first, so bytes already on disk are always preferred to bytes over the network - including
     *   a download still in flight, because the downloader and the player share one cache and a
     *   half-written entry is readable from the first byte.
     */
    fun factory(
        context: Context,
        upstream: androidx.media3.datasource.DataSource.Factory,
        quality: () -> StreamQuality,
        cachedVariant: (String) -> StreamQuality? = { null },
    ): ResolvingDataSource.Factory =
        ResolvingDataSource.Factory(upstream) { dataSpec ->
            val local = dataSpec.uri.itemId()?.let(cachedVariant)
            resolve(dataSpec, local ?: quality(), JellyfinClientHolder.credentials.serverUrl)
        }

    fun resolve(
        dataSpec: DataSpec,
        quality: StreamQuality,
        activeServerUrl: String? = null,
    ): DataSpec {
        // Cached MediaItems may have been built on home Wi-Fi. Rewrite their origin at open time
        // after endpoint selection switches to the remote URL (or back), while retaining the item
        // path, API key and media-source query parameters.
        val activeSpec = activeServerUrl?.let { active ->
            dataSpec.buildUpon()
                .setUri(JellyfinEndpoints.rewriteServerBase(dataSpec.uri, active))
                .build()
        } ?: dataSpec
        val itemId = activeSpec.uri.itemId() ?: return activeSpec
        val key = cacheKey(itemId, quality)
        if (quality.isOriginal) {
            // Still keyed explicitly, so the untouched file is one enumerable variant among the
            // rest rather than whatever media3 would have derived from the URI.
            return activeSpec.buildUpon().setKey(key).build()
        }
        return activeSpec.buildUpon()
            .setUri(activeSpec.uri.withTranscoding(quality))
            .setKey(key)
            .build()
    }

    /** Every cache key a track could occupy, for eviction that has to clear all of them. */
    fun allCacheKeys(streamUri: String): List<String> {
        val itemId = Uri.parse(streamUri).itemId() ?: return listOf(streamUri)
        return StreamQuality.entries.map { cacheKey(itemId, it) }
    }

    fun cacheKey(itemId: String, quality: StreamQuality) = "$itemId${quality.cacheSuffix}"

    /**
     * The item's GUID, taken from `/Audio/{id}/stream` or `/Audio/{id}/universal`.
     *
     * Read from the path rather than a query parameter because the id is what identifies the track
     * across every quality, and the query is exactly the part that differs between them.
     */
    fun Uri.itemId(): String? {
        val segments = pathSegments ?: return null
        val audioIndex = segments.indexOf("Audio")
        if (audioIndex < 0 || audioIndex + 1 >= segments.size) return null
        return segments[audioIndex + 1]
    }

    private fun Uri.withTranscoding(quality: StreamQuality): Uri {
        val segments = pathSegments ?: return this
        val audioIndex = segments.indexOf("Audio")
        if (audioIndex < 0 || audioIndex + 1 >= segments.size) return this

        // The final segment becomes stream.ts. Rebuilt rather than edited because the extension is
        // the part that selects an encoder, and it lives in the path rather than the query.
        val builder = buildUpon().path(null)
        segments.forEachIndexed { index, segment ->
            builder.appendPath(if (index == audioIndex + 2) TRANSCODE_EXTENSION else segment)
        }
        if (segments.size <= audioIndex + 2) builder.appendPath(TRANSCODE_EXTENSION)

        // Everything the original carried except the flag that forces direct play, which would
        // make the cap meaningless, and the source container, which no longer describes what
        // arrives.
        queryParameterNames.forEach { name ->
            if (name == PARAM_STATIC || name == PARAM_CONTAINER) return@forEach
            getQueryParameter(name)?.let { builder.appendQueryParameter(name, it) }
        }

        return builder
            .appendQueryParameter(PARAM_AUDIO_CODEC, TRANSCODE_CODEC)
            .appendQueryParameter(PARAM_AUDIO_BITRATE, quality.bitrateBps.toString())
            .build()
    }

}
