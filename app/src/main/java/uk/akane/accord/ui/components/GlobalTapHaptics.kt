package uk.akane.accord.ui.components

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import kotlin.math.abs

/**
 * Gives every tappable thing in the app a press cue, without asking each one to remember.
 *
 * There are north of a hundred click listeners across these screens and most were silent, so
 * feedback depended on which control you happened to touch - the player felt alive and the browse
 * lists felt dead. Wiring each one is a hundred chances to miss one, and a hundred more the next
 * time somebody adds a button.
 *
 * So it is caught once, at the window. A press fires when a touch goes down on a clickable view and
 * comes up on the same one without wandering - which is the same test the framework uses to decide
 * a click happened, and the reason a scroll that begins on a list row stays silent.
 *
 * Controls that ask for their own feedback still do; [Haptics] swallows the second of two cues that
 * land together, so the explicit call wins and this never doubles it.
 */
object GlobalTapHaptics {

    /**
     * Counts touches on the window, so work started by one tap can tell it has been overtaken.
     *
     * Anything that answers a tap after a round trip has a question to ask when the answer comes
     * back: is this still what the user is waiting for? Attachment and visibility cannot answer it
     * - the screen behind a late reply is usually the same screen - but a touch that landed in the
     * meantime says plainly that the user has moved on. Read it when the tap is handled, compare
     * when the work finishes.
     */
    @Volatile
    var touchGeneration: Long = 0L
        private set

    fun install(window: Window) {
        val decor = window.decorView
        val slop = ViewConfiguration.get(decor.context).scaledTouchSlop
        val state = State(slop)
        // A touch listener on the decor view sees every event before the hierarchy does and
        // consumes none of them, which is exactly the observation post wanted here.
        decor.setOnTouchListener { _, event ->
            state.onTouch(decor, event)
            false
        }
    }

    private class State(private val slop: Int) {
        private var downView: View? = null
        private var downX = 0F
        private var downY = 0F
        private val location = IntArray(2)

        fun onTouch(decor: View, event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchGeneration++
                    downX = event.rawX
                    downY = event.rawY
                    downView = clickableUnder(decor, downX, downY)
                }

                MotionEvent.ACTION_UP -> {
                    val target = downView
                    downView = null
                    if (target == null) return
                    // Moved too far to be a tap: this was a scroll, a drag or a swipe, and each of
                    // those has feedback of its own or deserves none.
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) return
                    if (clickableUnder(decor, event.rawX, event.rawY) !== target) return
                    if (!target.isEnabled) return
                    Haptics.press(target)
                }

                MotionEvent.ACTION_CANCEL -> downView = null
            }
        }

        /**
         * The deepest clickable view under the point.
         *
         * Deepest, not first: containers are frequently clickable so their children can be reached,
         * and answering with the container would make two different controls indistinguishable.
         * Walked back-to-front because later children draw on top.
         */
        private fun clickableUnder(view: View, x: Float, y: Float): View? {
            if (view.visibility != View.VISIBLE) return null
            view.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            if (x < left || x > left + view.width || y < top || y > top + view.height) return null
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    clickableUnder(view.getChildAt(index), x, y)?.let { return it }
                }
            }
            return view.takeIf { it.isClickable }
        }
    }
}
