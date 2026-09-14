package uk.akane.accord.ui.components

import android.view.View
import kotlin.math.abs

/**
 * Tactile state for a single swipe gesture.
 *
 * A swipe feels anchored when the content follows at reduced speed and stops firmly at its action
 * threshold. There is deliberately one restrained pulse at that stop: intermediate detents made a
 * single gesture feel noisy, while a second completion pulse merely repeated the same information.
 */
internal class ResistiveSwipeHaptics {
    private var started = false
    private var thresholdActive = false
    private var thresholdAvailable = true
    private var endpointPulseDelivered = false

    fun update(
        view: View,
        distance: Float,
        thresholdDistance: Float,
        actionAvailable: Boolean = true,
    ) {
        if (thresholdDistance <= 0F) return
        val progress = abs(distance) / thresholdDistance
        started = true

        val nowAtThreshold = progress >= 1F
        if (nowAtThreshold != thresholdActive ||
            (nowAtThreshold && actionAvailable != thresholdAvailable)
        ) {
            when {
                // Arming is a promise, not a completion: the finger is still down, and this is
                // the app saying what will happen when it lifts.
                nowAtThreshold && actionAvailable -> Haptics.arm(view)

                nowAtThreshold -> Haptics.reject(view)

                else -> Unit
            }
            thresholdActive = nowAtThreshold
            thresholdAvailable = actionAvailable
            if (nowAtThreshold) endpointPulseDelivered = true
            return
        }
    }

    fun commit(view: View) {
        // A very fast fling can be accepted before a rendered frame reaches the clamp. Preserve
        // the endpoint cue in that case, but never add a second pulse to an ordinary swipe.
        if (started && !endpointPulseDelivered) {
            Haptics.arm(view)
        }
        reset()
    }

    fun release(view: View) {
        reset()
    }

    fun reset() {
        started = false
        thresholdActive = false
        thresholdAvailable = true
        endpointPulseDelivered = false
    }

}

/**
 * Reduced-speed response with a true hard stop. The threshold and [maxTravel] are chosen together
 * by each caller, so reaching the visible stop and arming the action are the same event.
 */
internal fun resistedSwipeDistance(
    rawDistance: Float,
    maxTravel: Float,
    initialFollow: Float,
): Float {
    if (rawDistance == 0F || maxTravel <= 0F) return 0F
    return (rawDistance * initialFollow).coerceIn(-maxTravel, maxTravel)
}

/**
 * The quiet, single detent used for ordinary button presses throughout the player chrome.
 * Keep this distinct from swipe completion feedback: a press should feel alive, not forceful.
 */
internal fun View.performPressHaptic() = Haptics.press(this)
