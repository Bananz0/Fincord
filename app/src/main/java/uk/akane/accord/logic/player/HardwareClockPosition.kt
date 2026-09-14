package uk.akane.accord.logic.player

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * A playback position that advances on the audio hardware's own clock instead of media3's.
 *
 * Measured on device, comparing every clock in the stack against `elapsedRealtime` over 37 s of
 * continuous playback:
 *
 * ```
 * wall=+30030ms | player=+29650ms (-380ms) | head=+30080ms (+50ms) | stamp=+30012ms (-18ms)
 * wall=+37037ms | player=+36802ms (-235ms) | head=+37120ms (+83ms) | stamp=+37019ms (-18ms)
 * ```
 *
 * `AudioTrack.getTimestamp()` - the `stamp` column - held a constant -18 ms with no drift at all,
 * and with `ps_hardware_acc` disabled it held +-1 ms. The playback head was similarly steady. Only
 * `ExoPlayer.getCurrentPosition()` wandered: several hundred milliseconds out and closing at a
 * couple of percent a second, which is the sawtooth that has been drawn onto the lyrics all along.
 *
 * The hardware clock is therefore the accurate one, and this turns it into a content position.
 * A timestamp counts frames since the track was written, not song time, so it can only supply the
 * *rate*; the *anchor* still has to come from the player. Both are used for what each is good at:
 *
 * - The player says where we are, once, whenever something discontinuous happens - a seek, a track
 *   change, a flush that resets the frame counter, or the first sample after playback starts.
 * - The hardware says how far we have moved since, which it does perfectly.
 *
 * Deliberately conservative. Anything unexpected - no track, no timestamp, a frame counter that
 * went backwards, or a corrected value that has wandered more than [MAX_DISAGREEMENT_MS] from what
 * media3 believes - drops back to the player's own position and re-anchors. A clock that is
 * occasionally no better than before is fine; one that is confidently wrong is not, and this value
 * reaches the notification, the seek bar, scrobbling and the Jellyfin progress report as well as
 * the lyrics.
 */
class HardwareClockPosition {

    private companion object {
        const val TAG = "HardwareClock"

        /**
         * How far the corrected position may drift from media3's before it is abandoned.
         *
         * Generous, because disagreeing with media3 by a few hundred milliseconds is the entire
         * point - that disagreement is media3 being wrong. It is a bound on *nonsense*, not on
         * correction: past a second and a half something has gone wrong that this class did not
         * anticipate, and the player's own answer is the safer one.
         */
        const val MAX_DISAGREEMENT_MS = 1_500L
    }

    private val timestamp = AudioTimestamp()

    private var anchored = false
    private var anchorContentMs = 0L
    private var anchorHardwareMs = 0L
    private var lastFramePosition = 0L

    /** Drops the anchor, so the next sample takes the player's position as truth. */
    fun reset() {
        anchored = false
    }

    /**
     * @param playerPositionMs what media3 reports right now, used to anchor and as the fallback.
     * @param audioTrack the platform track, or null if it could not be reached.
     * @param isPlaying only a playing track has a meaningful hardware clock.
     * @return the position to report, which is [playerPositionMs] whenever the hardware clock
     *   cannot be trusted.
     */
    fun correct(playerPositionMs: Long, audioTrack: AudioTrack?, isPlaying: Boolean): Long {
        if (!isPlaying || audioTrack == null) {
            anchored = false
            return playerPositionMs
        }

        val hardwareMs = hardwarePositionMs(audioTrack)
        if (hardwareMs == null) {
            anchored = false
            return playerPositionMs
        }

        if (!anchored) {
            anchorContentMs = playerPositionMs
            anchorHardwareMs = hardwareMs
            anchored = true
            return playerPositionMs
        }

        val corrected = anchorContentMs + (hardwareMs - anchorHardwareMs)
        if (kotlin.math.abs(corrected - playerPositionMs) > MAX_DISAGREEMENT_MS) {
            // Not a correction any more; something happened that this does not model. Believe
            // media3 and start again from there.
            Log.d(
                TAG,
                "re-anchoring: hardware said ${corrected}ms, media3 said ${playerPositionMs}ms"
            )
            anchorContentMs = playerPositionMs
            anchorHardwareMs = hardwareMs
            return playerPositionMs
        }
        return corrected
    }

    /**
     * Milliseconds of audio the hardware has actually presented, or null if it will not say.
     *
     * The frame counter going backwards means the track was flushed - a seek, or a new item - so
     * the anchor is void and the caller has to take the player's position again.
     */
    private fun hardwarePositionMs(audioTrack: AudioTrack): Long? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) return@runCatching null
        if (!audioTrack.getTimestamp(timestamp)) return@runCatching null
        val sampleRate = audioTrack.sampleRate
        if (sampleRate <= 0) return@runCatching null

        val frames = timestamp.framePosition
        if (frames < lastFramePosition) {
            lastFramePosition = frames
            anchored = false
            return@runCatching null
        }
        lastFramePosition = frames

        // Extrapolate from when the hardware said it to now. The timestamp is sampled rarely by
        // the platform, so without this the position would advance in visible steps.
        val ageMs = (System.nanoTime() - timestamp.nanoTime) / 1_000_000L
        frames * 1000L / sampleRate + ageMs.coerceIn(0L, 1_000L)
    }.getOrNull()

    /** Only for tests and diagnostics; the real caller is the forwarding player. */
    @Suppress("unused")
    fun isAnchored(): Boolean = anchored

    @Suppress("unused")
    fun elapsedRealtimeForTest(): Long = SystemClock.elapsedRealtime()
}
