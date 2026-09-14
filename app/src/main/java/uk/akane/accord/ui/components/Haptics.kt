package uk.akane.accord.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One vocabulary of touch feedback for the whole app.
 *
 * Everything used to go through `performHapticFeedback(CLOCK_TICK)`, the lightest cue the framework
 * has - a button press, a swipe arming and a swipe firing all felt identical, and all of them felt
 * like nothing. Feedback that cannot be told apart carries no information, so this names the
 * moments instead of the effects and gives each one a distinct weight.
 *
 * Built on the framework constants rather than on hand-rolled vibrations, which is what Android's
 * haptics guidance asks for: the constants are what the rest of the system uses, so a confirmation
 * here feels like a confirmation everywhere else on the device, and each OEM has already tuned them
 * for its own motor. An earlier version composed its own primitives and got this backwards - it was
 * louder, but it was also this app inventing a private dialect of something the user already
 * speaks. Only the swipe's middle detent, which has no equivalent below API 34, is composed.
 *
 * Weight is matched to how often a cue fires, per the same guidance. A press happens constantly and
 * is deliberately restrained; a commit is rare and is allowed to land.
 */
object Haptics {

    /**
     * A light detent. Scrubbing past a mark, a value stepping, a list settling.
     *
     * The most frequent cue there is, so the most subtle - a strong effect repeated at this rate
     * stops being information and becomes a buzz.
     */
    fun tick(view: View) = play(view, Cue.TICK)

    /** An ordinary press: a button, a row, a toggle. Alive, not forceful. */
    fun press(view: View) = play(view, Cue.PRESS)

    /**
     * A gesture has reached the point where releasing would do something.
     *
     * Firmer than a press because it is a promise: the finger is still down and this is the app
     * saying what will happen if it lifts.
     */
    fun arm(view: View) = play(view, Cue.ARM)

    /** The action fired. The heaviest cue, and the only one that should feel like a thunk. */
    fun commit(view: View) = play(view, Cue.COMMIT)

    /** The gesture reached its limit but there is nothing to do here. */
    fun reject(view: View) = play(view, Cue.REJECT)

    /** A continuous gesture has taken hold - a slider grabbed, a drag begun. */
    fun gestureStart(view: View) = play(view, Cue.GESTURE_START)

    /** ...and let go. Paired with [gestureStart]; one without the other feels unfinished. */
    fun gestureEnd(view: View) = play(view, Cue.GESTURE_END)

    private enum class Cue { TICK, PRESS, ARM, COMMIT, REJECT, GESTURE_START, GESTURE_END }

    /**
     * Swallows a cue that lands on top of another.
     *
     * Two things fire haptics: the individual controls that ask, and the global tap hook that
     * catches every clickable view nobody wired up. On a control that does both, that is two pulses
     * a few milliseconds apart, which does not read as emphasis - it reads as a rattle. The first
     * one through wins and the rest of the window is silent.
     *
     * It doubles as a rate limit, which the guidance is explicit about: a drag crossing several
     * detents inside one frame would otherwise queue a pulse for each while the motor is still
     * finishing the first.
     */
    private fun suppressed(): Boolean {
        val now = System.nanoTime()
        if (now - lastCueNanos < COALESCE_NANOS) return true
        lastCueNanos = now
        return false
    }

    @Volatile
    private var lastCueNanos = 0L

    /**
     * Plays [cue] through the framework.
     *
     * Deliberately never reaches for the vibrator directly. performHapticFeedback already honours
     * the system's touch-feedback setting, and driving the motor around it would buzz at somebody
     * who has explicitly turned haptics off. A cue the device declines is simply not played.
     */
    private fun play(view: View, cue: Cue) {
        if (suppressed()) return
        val constant = constantFor(cue)
        if (view.performHapticFeedback(constant)) return
        // The richer constants arrived in API 34 and a device may still refuse one; fall back to a
        // plainer effect rather than to silence.
        val fallback = fallbackFor(cue)
        if (fallback != constant) view.performHapticFeedback(fallback)
    }

    private fun constantFor(cue: Cue): Int = when (cue) {
        // SEGMENT_FREQUENT_TICK exists precisely for cues that repeat: it is tuned to stay legible
        // without accumulating into a hum.
        Cue.TICK -> if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }

        Cue.PRESS -> HapticFeedbackConstants.CONTEXT_CLICK

        // A detent between "nothing yet" and "done", which only API 34 has a name for.
        Cue.ARM -> if (Build.VERSION.SDK_INT >= 34) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }

        // The system's own "that worked" and "that did not", so they mean here what they mean
        // everywhere else on the phone.
        Cue.COMMIT -> HapticFeedbackConstants.CONFIRM
        Cue.REJECT -> HapticFeedbackConstants.REJECT

        Cue.GESTURE_START -> HapticFeedbackConstants.GESTURE_START
        Cue.GESTURE_END -> HapticFeedbackConstants.GESTURE_END
    }

    private fun fallbackFor(cue: Cue): Int = when (cue) {
        Cue.TICK -> HapticFeedbackConstants.CLOCK_TICK
        Cue.PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
        Cue.ARM -> HapticFeedbackConstants.CONTEXT_CLICK
        Cue.COMMIT -> HapticFeedbackConstants.LONG_PRESS
        Cue.REJECT -> HapticFeedbackConstants.LONG_PRESS
        Cue.GESTURE_START -> HapticFeedbackConstants.CONTEXT_CLICK
        Cue.GESTURE_END -> HapticFeedbackConstants.CLOCK_TICK
    }

    /** Long enough to swallow a duplicate, short enough that deliberate repeats still land. */
    private const val COALESCE_NANOS = 45_000_000L
}
