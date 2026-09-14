package uk.akane.accord.ui.components

import android.view.Choreographer

/**
 * The single clock behind every procedurally drawn surface: station cards, carousel banners and
 * the large header on a station or mix.
 *
 * They already shared [ProceduralStationMotion] - the same palettes, styles and closed-loop
 * expressions - but each view drove it with its own infinite `ValueAnimator`. A screenful of cards
 * therefore meant a screenful of animators competing to invalidate, which is why the cards had to
 * be throttled to stay smooth while the one-per-screen banner never did, and why the two looked
 * like different engines when they were not.
 *
 * One frame callback drives all of them, so a card is no more expensive per frame than the banner
 * and everything runs at the display's rate.
 *
 * Phase comes from elapsed wall time rather than an accumulator, so a host that misses frames
 * stays in step with the rest instead of drifting slowly out of sync with them.
 */
internal object ProceduralMotionTicker {

    /**
     * Deliberately does not hand over the [ProceduralStationMotion] itself: the ticker only needs
     * the cycle length and somewhere to put the phase, and keeping the motion out of the interface
     * keeps it internal to this package rather than leaking through two public views.
     */
    interface Host {
        /** One full loop of this host's motion, in milliseconds. */
        val motionDurationMs: Long

        /** Where the host is in its loop, 0..1. Read once on registration, written every frame. */
        var motionPhase: Float

        /** False once the view is off-window; the ticker drops it rather than holding a leak. */
        fun isMotionAttached(): Boolean

        /**
         * False while the host is attached but cannot be seen - a hidden fragment, a `GONE`
         * ancestor, a card recycled off screen.
         *
         * Distinct from [isMotionAttached] on purpose: an unattached host is *dropped*, and only
         * `onAttachedToWindow` puts it back, so folding visibility into that would silently kill a
         * card's motion the first time it was hidden and never restart it. An invisible host stays
         * registered and merely stops being drawn.
         */
        fun isMotionVisible(): Boolean

        fun invalidateMotion()
    }

    /** Host to the frame time its current cycle began at; zero means "not started yet". */
    private val startedAt = LinkedHashMap<Host, Long>()
    private var ticking = false

    /**
     * Set while something opaque covers every host - the expanded player, which fills the window.
     *
     * Occlusion by a sibling is invisible to [Host.isMotionVisible]: a card under the open player
     * is still `isShown`, so it kept invalidating at the display's full rate behind a surface that
     * hid it completely. Each of those invalidations dragged the blurred backdrop through another
     * GPU pass, which is what left no headroom for a track change.
     */
    private var paused = false

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        // Hosts keep their phase across the pause; restarting the clock below resumes from it
        // rather than snapping back to the start of the loop.
        if (!value) {
            startedAt.keys.forEach { startedAt[it] = 0L }
            ensureTicking()
        }
    }

    fun register(host: Host) {
        if (startedAt.containsKey(host)) return
        startedAt[host] = 0L
        ensureTicking()
    }

    fun unregister(host: Host) {
        startedAt.remove(host)
    }

    /**
     * Restarts a host's clock, for a recycled view that has just bound a different station - its
     * seed sets a new phase, and the old start time would immediately overwrite it.
     */
    fun resetPhase(host: Host) {
        if (startedAt.containsKey(host)) startedAt[host] = 0L
    }

    private fun ensureTicking() {
        if (ticking) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (paused) {
                ticking = false
                return
            }
            // Copied: a host detaching as it is stepped would otherwise mutate the map underneath.
            startedAt.keys.toList().forEach { host ->
                if (!host.isMotionAttached()) {
                    startedAt.remove(host)
                    return@forEach
                }
                if (!host.isMotionVisible()) {
                    // Stays registered, but its clock restarts from the phase it holds now, so it
                    // resumes where it stopped instead of jumping forward by the time it spent
                    // hidden.
                    startedAt[host] = 0L
                    return@forEach
                }
                val periodNanos = host.motionDurationMs * NANOS_PER_MILLI
                if (periodNanos <= 0.0) return@forEach

                var start = startedAt[host] ?: 0L
                if (start == 0L) {
                    // Begin where bind() left the phase, so a station looks the same each time it
                    // appears instead of restarting from zero whenever it scrolls back on screen.
                    start = frameTimeNanos - (host.motionPhase * periodNanos).toLong()
                    startedAt[host] = start
                }

                val elapsed = (frameTimeNanos - start) / periodNanos
                host.motionPhase = (((elapsed % 1.0) + 1.0) % 1.0).toFloat()
                host.invalidateMotion()
            }

            if (startedAt.isEmpty()) {
                ticking = false
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private const val NANOS_PER_MILLI = 1_000_000.0
}
