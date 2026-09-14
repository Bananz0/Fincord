package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.jellyfin.sdk.api.client.extensions.lyricApi
import org.jellyfin.sdk.model.api.LyricLine

/**
 * Lyrics served by the Jellyfin server.
 *
 * Covers the case embedded tags cannot: a `.lrc` or `.elrc` sitting next to the track on the server, which the
 * player never sees because it only ever receives the audio stream. Jellyfin indexes those sidecars
 * and hands them back as timed lines.
 *
 * The lines are rendered back into LRC text rather than mapped straight to [MediaStoreUtils.Lyric],
 * so they go through the same [LrcUtils.parseLrcString] every other source does and inherit its
 * handling of translations and speaker labels. Jellyfin's cue objects are rendered as Enhanced
 * LRC markers first, preserving server-side word timing through that same parser.
 */
object JellyfinLyricsSource {

    private const val TAG = "JellyfinLyricsSource"

    /** Jellyfin timestamps are in ticks of 100 nanoseconds. */
    private const val TICKS_PER_MILLISECOND = 10_000L

    /**
     * Fetches lyrics for [mediaId], or null when the server has none.
     *
     * Suspends on a network call, so it belongs on a background dispatcher - it is already called
     * from the playback service's lyrics worker.
     */
    suspend fun load(
        context: Context,
        mediaId: String?,
        trim: Boolean
    ): MutableList<MediaStoreUtils.Lyric>? {
        if (mediaId == null) return null
        return try {
            val api = JellyfinClientHolder.api() ?: return null
            val remoteId = JellyfinItemResolver.remoteIdForMediaId(context, mediaId) ?: return null
            val uuid = java.util.UUID.fromString(
                with(JellyfinReporter) { remoteId.toDashedUuid() }
            )
            val lines = api.lyricApi.getLyrics(uuid).content.lyrics
            if (lines.isNullOrEmpty()) return null
            val lrc = buildString {
                lines.forEach { line ->
                    val text = line.text.takeIf { it.isNotBlank() } ?: return@forEach
                    // Unsynced lyrics come back with no start time. Emitting them without a tag is
                    // correct - the parser treats untagged lines as plain, unsynced lyrics.
                    val cues = line.cues.orEmpty()
                    val lineStart = line.start ?: cues.firstOrNull()?.start
                    lineStart?.let { append(formatTimestamp(it / TICKS_PER_MILLISECOND)) }
                    append(renderCues(text, line)).append('\n')
                }
            }
            if (lrc.isBlank()) return null
            LrcUtils.parseLrcString(lrc, trim).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // A server without the lyrics endpoint answers 404, and a track without lyrics is the
            // common case. Neither is worth surfacing - playback carries on without them.
            Log.d(TAG, "No server lyrics for $mediaId: ${e.message}")
            null
        }
    }

    /** Renders milliseconds as an LRC tag: `[mm:ss.cc]`. */
    private fun formatTimestamp(totalMs: Long): String {
        val safeMs = totalMs.coerceAtLeast(0)
        val minutes = safeMs / 60_000
        val seconds = (safeMs % 60_000) / 1000
        val hundredths = (safeMs % 1000) / 10
        return "[%02d:%02d.%02d]".format(minutes, seconds, hundredths)
    }

    private fun formatWordTimestamp(ticks: Long): String =
        formatTimestamp(ticks / TICKS_PER_MILLISECOND)
            .replace('[', '<')
            .replace(']', '>')

    /** Restores the ELRC markers represented by Jellyfin's position/time cue objects. */
    @VisibleForTesting
    internal fun renderCues(text: String, line: LyricLine): String {
        val cues = line.cues.orEmpty()
            .filter { cue -> cue.position in 0..text.length }
            .sortedWith(compareBy({ it.position }, { it.start }))
        if (cues.isEmpty()) return text

        val markers = linkedMapOf<Int, Long>()
        cues.forEach { cue -> markers[cue.position] = cue.start }
        cues.last().let { cue ->
            cue.end?.let { end ->
                if (cue.endPosition in 0..text.length) markers.putIfAbsent(cue.endPosition, end)
            }
        }

        return buildString(text.length + markers.size * 12) {
            for (offset in 0..text.length) {
                markers[offset]?.let { append(formatWordTimestamp(it)) }
                if (offset < text.length) append(text[offset])
            }
        }
    }
}
