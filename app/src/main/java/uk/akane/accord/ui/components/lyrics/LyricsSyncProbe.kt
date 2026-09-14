package uk.akane.accord.ui.components.lyrics

import android.os.SystemClock
import android.util.Log

/**
 * Frame-accurate instrumentation for the lyric sheet, off unless it is asked for.
 *
 * ```
 * adb shell setprop log.tag.LyricsProbe DEBUG   # on  (survives until reboot)
 * adb shell setprop log.tag.LyricsProbe INFO    # off
 * adb logcat -s LyricsProbe
 * ```
 *
 * It exists because the two complaints about this screen - "it stutters" and "it drifts behind" -
 * look identical from the outside and have nothing to do with each other underneath, and because
 * the obvious ways to tell them apart do not work here. A screen recording samples at the rate the
 * encoder feels like and cannot see a dropped frame at 120 Hz at all. `dumpsys gfxinfo` measures
 * the whole window, which on the full player also contains an animated gradient backdrop and a
 * blur, so a bad number there says nothing about whose fault it is.
 *
 * One line a second, and each field answers one question:
 *
 * - `fps` against the panel rate: whether frames are being *dropped*. That is the stutter.
 * - `draw` percentiles: how long `LyricsLineView.onDraw` itself takes. If `fps` is low and this is
 *   small, the lyrics are a victim of something else on the UI thread rather than the cause.
 * - `gaps`: how many frame intervals ran long, and the worst. A handful of 30 ms gaps a second is
 *   invisible in an average and is exactly what reads as a hitch.
 * - `follow`: how far the smoothed clock sits from the position the player reports. A follower
 *   whose trim rate has saturated shows up here and nowhere else - as a large, steady, one-signed
 *   number - and that is exactly the bug that shipped in the first version of this follower.
 * - `drift`: song time advanced, minus wall time advanced, accumulated since the probe started.
 *   This is the one that answers "it lags behind after some time" - if the clock is honest it
 *   stays near zero for as long as you leave it running, whatever the frame rate is doing.
 */
internal object LyricsSyncProbe {

    private const val TAG = "LyricsProbe"

    /** Reported once a second: long enough to be a stable sample, short enough to watch live. */
    private const val REPORT_INTERVAL_MS = 1_000L

    /** A frame interval past this has visibly hitched even on a 60 Hz panel. */
    private const val LONG_GAP_MS = 20L

    /**
     * A single frame may legitimately carry about 8 ms of song at 120 Hz. Anything past this is
     * the position *stepping* rather than flowing, which is the difference between a clock that
     * runs at the wrong rate and one that is being yanked into line - and they need opposite
     * fixes, so the probe has to be able to tell them apart.
     */
    private const val STEP_MS = 25L

    /**
     * Re-read on every report rather than cached, so the probe can be turned on and off against a
     * running app without restarting it - which matters when the fault takes minutes to appear.
     */
    val isEnabled: Boolean
        get() = Log.isLoggable(TAG, Log.DEBUG)

    private var windowStartRealtimeMs = 0L
    private var windowStartPositionMs = 0L
    private var lastFrameRealtimeMs = 0L
    private var frames = 0
    private var loopTicks = 0
    private var longGaps = 0
    private var worstGapMs = 0L
    private var cumulativeDriftMs = 0L
    private var reports = 0

    private var lastPositionMs = Long.MIN_VALUE
    private var steps = 0
    private var biggestStepMs = 0L
    private var backwardSteps = 0
    private var biggestBackwardMs = 0L

    // How far the smoothed clock sits from what the player reports. A follower that has saturated
    // its trim rate shows up here as a large, steady, one-signed number and nowhere else.
    private var followErrorSumMs = 0L
    private var worstFollowErrorMs = Long.MIN_VALUE
    private var bestFollowErrorMs = Long.MAX_VALUE

    /** Draw durations in microseconds for this window; a second at 120 Hz needs 120 slots. */
    private val drawMicros = LongArray(512)
    private var drawSamples = 0

    /** Called once per turn of the position loop, whether or not it produced a frame. */
    fun onLoopTick() {
        if (isEnabled) loopTicks++
    }

    /**
     * Called from the end of the active line's draw pass.
     *
     * @param drawDurationNanos how long this view's own `onDraw` took.
     * @param positionMs the song position that frame was drawn against.
     */
    fun onFrameDrawn(drawDurationNanos: Long, positionMs: Long, followErrorMs: Long = 0L) {
        if (!isEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (windowStartRealtimeMs == 0L) {
            startWindow(now, positionMs)
            lastFrameRealtimeMs = now
            return
        }

        frames++
        followErrorSumMs += followErrorMs
        if (followErrorMs > worstFollowErrorMs) worstFollowErrorMs = followErrorMs
        if (followErrorMs < bestFollowErrorMs) bestFollowErrorMs = followErrorMs
        if (lastPositionMs != Long.MIN_VALUE) {
            val step = positionMs - lastPositionMs
            if (step > STEP_MS) {
                steps++
                if (step > biggestStepMs) biggestStepMs = step
                Log.d(TAG, "STEP +${step}ms in one frame, to ${positionMs}ms")
            } else if (step < 0L) {
                backwardSteps++
                if (-step > biggestBackwardMs) biggestBackwardMs = -step
                Log.d(TAG, "BACK ${step}ms in one frame, to ${positionMs}ms")
            }
        }
        lastPositionMs = positionMs

        val gap = now - lastFrameRealtimeMs
        if (gap > LONG_GAP_MS) longGaps++
        if (gap > worstGapMs) worstGapMs = gap
        lastFrameRealtimeMs = now

        if (drawSamples < drawMicros.size) {
            drawMicros[drawSamples++] = drawDurationNanos / 1_000L
        }

        val elapsed = now - windowStartRealtimeMs
        if (elapsed >= REPORT_INTERVAL_MS) {
            report(elapsed, positionMs)
            startWindow(now, positionMs)
        }
    }

    /** Drops the accumulated window, so a track change or a seek does not land in the drift. */
    fun reset() {
        windowStartRealtimeMs = 0L
        lastFrameRealtimeMs = 0L
        frames = 0
        loopTicks = 0
        longGaps = 0
        worstGapMs = 0L
        drawSamples = 0
        cumulativeDriftMs = 0L
        reports = 0
        lastPositionMs = Long.MIN_VALUE
        steps = 0
        biggestStepMs = 0L
        backwardSteps = 0
        biggestBackwardMs = 0L
        followErrorSumMs = 0L
        worstFollowErrorMs = Long.MIN_VALUE
        bestFollowErrorMs = Long.MAX_VALUE
    }

    private fun startWindow(now: Long, positionMs: Long) {
        windowStartRealtimeMs = now
        windowStartPositionMs = positionMs
        frames = 0
        loopTicks = 0
        longGaps = 0
        worstGapMs = 0L
        drawSamples = 0
        steps = 0
        biggestStepMs = 0L
        backwardSteps = 0
        biggestBackwardMs = 0L
        followErrorSumMs = 0L
        worstFollowErrorMs = Long.MIN_VALUE
        bestFollowErrorMs = Long.MAX_VALUE
    }

    private fun report(elapsedMs: Long, positionMs: Long) {
        val songAdvancedMs = positionMs - windowStartPositionMs
        // Song time should advance exactly as fast as wall time at 1x. Anything else accumulates,
        // and it is the accumulation rather than any single second that is felt.
        cumulativeDriftMs += songAdvancedMs - elapsedMs
        reports++

        val fps = frames * 1000f / elapsedMs
        val sorted = drawMicros.copyOf(drawSamples).apply { sort() }
        val p50 = sorted.percentileMs(0.50f)
        val p95 = sorted.percentileMs(0.95f)
        val worst = (sorted.lastOrNull() ?: 0L) / 1000f

        Log.d(
            TAG,
            "fps=%.1f frames=%d | draw p50=%.2fms p95=%.2fms max=%.2fms | gaps>%dms=%d worst=%dms | steps>%dms=%d max=+%dms back=%d max=-%dms | follow avg=%+dms range=%+d..%+dms | song=%+dms wall=%dms drift=%+dms over %ds"
                .format(
                    fps, frames,
                    p50, p95, worst,
                    LONG_GAP_MS, longGaps, worstGapMs,
                    STEP_MS, steps, biggestStepMs, backwardSteps, biggestBackwardMs,
                    if (frames > 0) followErrorSumMs / frames else 0L,
                    if (bestFollowErrorMs == Long.MAX_VALUE) 0L else bestFollowErrorMs,
                    if (worstFollowErrorMs == Long.MIN_VALUE) 0L else worstFollowErrorMs,
                    songAdvancedMs, elapsedMs, cumulativeDriftMs, reports,
                )
        )
    }

    private fun LongArray.percentileMs(fraction: Float): Float {
        if (isEmpty()) return 0f
        val index = ((size - 1) * fraction).toInt().coerceIn(0, size - 1)
        return this[index] / 1000f
    }
}
