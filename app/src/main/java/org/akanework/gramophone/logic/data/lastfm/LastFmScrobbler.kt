package org.akanework.gramophone.logic.data.lastfm

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.OwnPlay
import org.akanework.gramophone.logic.data.db.entity.PendingScrobble
import org.akanework.gramophone.logic.data.playcounts.TrackKey

/**
 * Turns playback into Last.fm scrobbles.
 *
 * Follows Last.fm's submission rules rather than scrobbling on track change: a track counts once it
 * has been listened to for half its length or four minutes, whichever comes first, and tracks
 * shorter than 30 seconds never count. Ignoring those rules is how a scrobbler ends up submitting
 * every track someone skipped through.
 *
 * Qualifying plays are written to the local queue *first* and only removed once Last.fm confirms
 * them, so losing signal delays a scrobble instead of losing it.
 */
class LastFmScrobbler(context: Context) {

    private val context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises queue flushes, so a heartbeat and a track change cannot submit the same rows twice. */
    private val flushLock = Mutex()

    private var current: NowPlaying? = null

    private data class NowPlaying(
        val mediaId: String?,
        val track: LastFmClient.Track,
        val startedAtSeconds: Long,
        val durationMs: Long,
        var scrobbled: Boolean = false,
    )

    /**
     * Begins tracking [mediaItem] unless it is already being tracked.
     *
     * Playback does not always begin with a track change: pressing play on a queue restored after
     * the app was killed resumes an item that is already loaded, and [onTrackStarted] never fires
     * for it. Without this the track playing when the service came up could never scrobble.
     */
    fun ensureTracking(mediaItem: MediaItem?, startedAtSeconds: Long) {
        if (mediaItem == null) return
        if (current?.mediaId == mediaItem.mediaId) return
        onTrackStarted(mediaItem, startedAtSeconds)
    }

    /**
     * Call when a new track begins. Announces it as now playing and arms the scrobble.
     *
     * [startedAtSeconds] is passed in rather than read from the clock here so the caller decides the
     * timestamp, which keeps this testable and lets a resumed track keep its original start time.
     */
    fun onTrackStarted(mediaItem: MediaItem?, startedAtSeconds: Long) {
        val track = mediaItem?.toLastFmTrack()
        if (track == null) {
            current = null
            return
        }
        val durationMs = mediaItem.mediaMetadata.extras?.getLong("Duration") ?: 0L
        current = NowPlaying(mediaItem.mediaId, track, startedAtSeconds, durationMs)
        if (!LastFmCredentialStore.isScrobblingEnabled(context)) return
        scope.launch(NonCancellable) {
            try {
                val (client, sessionKey) = clientAndSession() ?: return@launch
                client.updateNowPlaying(sessionKey, track)
            } catch (e: Exception) {
                // Cosmetic only - the badge on the profile. Never worth surfacing or queueing.
                Log.d(TAG, "updateNowPlaying failed", e)
            }
        }
    }

    /**
     * Call periodically while a track plays, and whenever playback stops.
     *
     * [positionMs] is the listened position. Once it crosses the threshold the play is queued; the
     * flag makes this idempotent, so calling it on every heartbeat is fine.
     */
    fun onProgress(positionMs: Long) {
        val playing = current ?: return
        if (playing.scrobbled) return
        if (!LastFmCredentialStore.isScrobblingEnabled(context)) return
        if (!qualifies(playing.durationMs, positionMs)) return
        playing.scrobbled = true
        enqueue(playing)
    }

    /**
     * Call when a track is left behind.
     *
     * [playedToEnd] short-circuits the position check: when the player advances by itself the track
     * necessarily reached its end, and the last heartbeat may have been up to ten seconds earlier -
     * which for a three-minute track is the difference between scrobbling it and not.
     */
    fun onTrackFinished(positionMs: Long, playedToEnd: Boolean) {
        val playing = current ?: return
        current = null
        if (playing.scrobbled) return
        if (!LastFmCredentialStore.isScrobblingEnabled(context)) return
        val effectivePosition = if (playedToEnd) playing.durationMs else positionMs
        if (!qualifies(playing.durationMs, effectivePosition)) return
        playing.scrobbled = true
        enqueue(playing)
    }

    /**
     * Last.fm's rule: longer than 30 seconds, and listened to for at least half its length or four
     * minutes, whichever is reached first.
     */
    private fun qualifies(durationMs: Long, positionMs: Long): Boolean {
        if (durationMs < MIN_SCROBBLE_DURATION_MS) return false
        return positionMs >= minOf(durationMs / 2, MAX_REQUIRED_PLAY_MS)
    }

    private fun enqueue(playing: NowPlaying) {
        scope.launch(NonCancellable) {
            recordOwnPlay(playing)
            try {
                val dao = AppDatabase.getInstance(context).pendingScrobbleDao()
                dao.insert(
                    PendingScrobble(
                        artist = playing.track.artist,
                        title = playing.track.title,
                        album = playing.track.album,
                        albumArtist = playing.track.albumArtist,
                        durationSeconds = playing.track.durationSeconds,
                        trackNumber = playing.track.trackNumber,
                        timestampSeconds = playing.startedAtSeconds,
                    )
                )
                dao.trimTo(MAX_QUEUED_SCROBBLES)
                Log.d(TAG, "Queued scrobble: ${playing.track.artist} - ${playing.track.title}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not queue scrobble", e)
                return@launch
            }
            flush()
        }
    }

    /**
     * Notes that this app counted a play, so importing it back later cannot count it twice.
     *
     * Recorded here rather than alongside the Jellyfin report because this is the moment that
     * produces a Last.fm scrobble, and it is that scrobble which will come back around. Sharing
     * [NowPlaying.startedAtSeconds] means the returning row carries the identical timestamp, so
     * the importer recognises it exactly instead of guessing from a window.
     *
     * Written whether or not Last.fm is linked. Linking it later must not leave a gap of plays the
     * ledger cannot account for, and an unused row costs a few bytes until it is pruned.
     */
    private fun recordOwnPlay(playing: NowPlaying) {
        val mediaId = playing.mediaId ?: return
        try {
            AppDatabase.getInstance(context).ownPlayDao().insert(
                OwnPlay(
                    jellyfinId = mediaId,
                    trackKey = TrackKey.exact(playing.track.artist, playing.track.title),
                    playedAtSeconds = playing.startedAtSeconds,
                )
            )
        } catch (e: Exception) {
            // Only costs accuracy on a future import, and never one that inflates: an unrecorded
            // play means a scrobble that could have been deduped is instead counted as the
            // source's. Not worth disturbing playback over.
            Log.w(TAG, "Could not record own play", e)
        }
    }

    /** Submits whatever is queued. Safe to call often; does nothing when the queue is empty. */
    fun flushAsync() {
        scope.launch(NonCancellable) { flush() }
    }

    /**
     * Drains the queue in batches, deleting only what Last.fm accepted.
     *
     * Permanent failures (bad session, malformed metadata) drop the batch instead of retrying: a row
     * that can never succeed would otherwise block every scrobble behind it forever.
     */
    suspend fun flush(): Int = flushLock.withLock { flushLocked() }

    private suspend fun flushLocked(): Int {
        if (!LastFmCredentialStore.isScrobblingEnabled(context)) return 0
        val dao = AppDatabase.getInstance(context).pendingScrobbleDao()
        val (client, sessionKey) = clientAndSession() ?: return 0
        var submitted = 0
        while (true) {
            val batch = try {
                dao.oldest(LastFmClient.MAX_BATCH)
            } catch (e: Exception) {
                Log.w(TAG, "Could not read scrobble queue", e)
                return submitted
            }
            if (batch.isEmpty()) return submitted
            try {
                client.scrobble(sessionKey, batch.map { it.toTimedTrack() })
                dao.delete(batch)
                submitted += batch.size
            } catch (e: LastFmClient.LastFmException) {
                if (e.isTransient) {
                    Log.i(TAG, "Scrobble submission deferred: ${e.message}")
                    return submitted
                }
                Log.w(TAG, "Dropping ${batch.size} unsubmittable scrobbles: ${e.message}")
                dao.delete(batch)
            } catch (e: Exception) {
                Log.w(TAG, "Scrobble submission failed", e)
                return submitted
            }
        }
    }

    /** Number of plays waiting to be submitted, for the settings screen. */
    fun pendingCount(): Int = try {
        AppDatabase.getInstance(context).pendingScrobbleDao().count()
    } catch (e: Exception) {
        0
    }

    private fun clientAndSession(): Pair<LastFmClient, String>? {
        val store = LastFmCredentialStore(context)
        val sessionKey = store.sessionKey?.takeIf { it.isNotBlank() } ?: return null
        if (!store.hasApplicationCredentials()) return null
        return LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl) to sessionKey
    }

    private fun PendingScrobble.toTimedTrack() = LastFmClient.TimedTrack(
        track = LastFmClient.Track(
            artist = artist,
            title = title,
            album = album,
            albumArtist = albumArtist,
            durationSeconds = durationSeconds,
            trackNumber = trackNumber,
        ),
        timestampSeconds = timestampSeconds,
    )

    companion object {
        private const val TAG = "LastFmScrobbler"

        /** Last.fm rejects anything shorter; no point queueing it. */
        private const val MIN_SCROBBLE_DURATION_MS = 30_000L

        /** Four minutes, the point at which a long track counts regardless of its length. */
        private const val MAX_REQUIRED_PLAY_MS = 4 * 60 * 1000L

        /** Roughly a fortnight of heavy listening, which is as far back as Last.fm will accept. */
        private const val MAX_QUEUED_SCROBBLES = 1000

        /**
         * Builds a scrobble from a library item.
         *
         * Returns null when artist or title is missing: Last.fm matches on those two fields alone,
         * so a scrobble without them cannot be attributed to anything and would just be rejected.
         */
        fun MediaItem.toLastFmTrack(): LastFmClient.Track? {
            val metadata = mediaMetadata
            val artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: return null
            val durationMs = metadata.extras?.getLong("Duration") ?: 0L
            return LastFmClient.Track(
                artist = artist,
                title = title,
                album = metadata.albumTitle?.toString(),
                albumArtist = metadata.albumArtist?.toString(),
                durationSeconds = (durationMs / 1000).toInt().takeIf { it > 0 },
                trackNumber = metadata.trackNumber,
            )
        }
    }
}
