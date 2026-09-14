package uk.akane.accord.logic.player

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

/**
 * Compares every clock the playback stack has, so the one that is lying can be identified.
 *
 * ```
 * adb shell setprop log.tag.PlaybackClock DEBUG
 * adb logcat -s PlaybackClock
 * ```
 *
 * The position this app reports is measurably wrong - about 4% fast for twenty-odd seconds, then a
 * correction of most of a second, and on one device a 3.3 s discontinuity and 4 s of accumulated
 * error in half a minute. Everything measured so far has been *downstream* of the whole stack: the
 * lyric sheet, the `MediaController`, the session's published `PlaybackState`. All three see the
 * same number, so none of them can say which layer introduced the error.
 *
 * This sits at the bottom and prints all of them side by side, once a second:
 *
 * - `player` - what `ExoPlayer.getCurrentPosition()` says, which is what the whole app consumes.
 * - `head` - `AudioTrack.getPlaybackHeadPosition()` converted to time by the track's own sample
 *   rate. This is frames the track has consumed, and it is the estimate media3 falls back to.
 * - `stamp` - `AudioTrack.getTimestamp()`, extrapolated to now. This is the presentation clock the
 *   hardware reports, and it is the one media3 trusts when it can get it. Also printed raw, since
 *   a timestamp that stops advancing is itself the answer.
 * - `wall` - `elapsedRealtime`, the reference everything is compared against.
 *
 * What each pattern would mean:
 *
 * - `head` tracks `wall` but `player` runs fast: the error is in media3's own bookkeeping above the
 *   track, and `ps_hardware_acc` is the first thing to suspect.
 * - `head` itself runs fast: frames are being counted at one rate and divided by another, which is
 *   a sample-rate disagreement between the track and what media3 thinks the track is.
 * - `stamp` disagrees with `head` by a growing amount: the HAL is the one drifting, and no amount
 *   of app-side arithmetic fixes it - the correct response is to trust `stamp` and publish that.
 */
object PlaybackClockProbe {

    private const val TAG = "PlaybackClock"
    private const val REPORT_INTERVAL_MS = 1_000L

    val isEnabled: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    private var lastReportRealtimeMs = 0L
    private var baseRealtimeMs = 0L
    private var basePlayerMs = 0L
    private var baseHeadMs = 0L
    private var baseStampMs = 0L
    private var started = false

    private val timestamp = AudioTimestamp()

    /** Forgets the baseline, so a seek or a track change does not land in the accumulated error. */
    fun reset() {
        started = false
        lastReportRealtimeMs = 0L
    }

    /**
     * Called about once a second from the playback thread while playing.
     *
     * @param playerPositionMs what the player reports, in content time.
     * @param audioTrack the platform track, if it could be reached. Without it only the player's
     *   own number is printed, which is still worth having but cannot apportion blame.
     */
    fun sample(playerPositionMs: Long, audioTrack: AudioTrack?) {
        if (!isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportRealtimeMs < REPORT_INTERVAL_MS) return
        lastReportRealtimeMs = now

        val sampleRate = audioTrack?.let { runCatching { it.sampleRate }.getOrNull() } ?: 0
        val headMs = audioTrack?.let {
            runCatching {
                // Unsigned: the platform returns this as a 32-bit frame counter that wraps.
                val frames = it.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                if (sampleRate > 0) frames * 1000L / sampleRate else null
            }.getOrNull()
        }
        val stampMs = audioTrack?.let {
            runCatching {
                if (!it.getTimestamp(timestamp) || sampleRate <= 0) return@runCatching null
                // Extrapolate the timestamp to now, which is what media3 does with it.
                val ageMs = (System.nanoTime() - timestamp.nanoTime) / 1_000_000L
                timestamp.framePosition * 1000L / sampleRate + ageMs
            }.getOrNull()
        }

        if (!started) {
            baseRealtimeMs = now
            basePlayerMs = playerPositionMs
            baseHeadMs = headMs ?: 0L
            baseStampMs = stampMs ?: 0L
            started = true
            Log.d(TAG, "baseline: player=${playerPositionMs}ms head=${headMs}ms stamp=${stampMs}ms rate=${sampleRate}Hz")
            return
        }

        val wallDelta = now - baseRealtimeMs
        val playerDelta = playerPositionMs - basePlayerMs
        val headDelta = headMs?.minus(baseHeadMs)
        val stampDelta = stampMs?.minus(baseStampMs)

        Log.d(
            TAG,
            "wall=+%dms | player=%+dms (%+dms) | head=%s | stamp=%s | rate=%dHz".format(
                wallDelta,
                playerDelta, playerDelta - wallDelta,
                headDelta?.let { "%+dms (%+dms)".format(it, it - wallDelta) } ?: "n/a",
                stampDelta?.let { "%+dms (%+dms)".format(it, it - wallDelta) } ?: "n/a",
                sampleRate,
            )
        )
    }
}
