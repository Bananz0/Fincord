package uk.akane.accord.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * A horizontal row that lets the page behind it scroll under the same finger.
 *
 * Android hands a gesture to one scroller. The first of the two to pass touch slop calls
 * `requestDisallowInterceptTouchEvent` on the other and keeps the stream to itself, so a drag that
 * began downwards could not then move a row sideways, and a drag that began sideways pinned the
 * page in place until the finger came up. On a wall of artwork that is the wrong rule: the covers
 * and the page are one surface to the hand, and a diagonal drag means both.
 *
 * So this row takes the whole gesture at the down event and splits it by axis: the horizontal
 * component is [RecyclerView]'s own, unchanged - drag, fling, snapping, everything - and the
 * vertical component is handed to the nearest scrollable container above it, delta by delta, with
 * the release velocity passed on as a fling so letting go still coasts.
 *
 * Screen coordinates throughout. Scrolling the page moves this view under the finger, so a delta
 * measured in local coordinates counts the page's own movement a second time and the row runs away
 * from the thumb.
 *
 * A list that scrolls vertically itself keeps every axis it has and behaves as a plain
 * [RecyclerView], so this is safe to use as the tag for any row whose direction may change.
 */
class TwoAxisRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maximumFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var velocityTracker: VelocityTracker? = null
    private var downRawY = 0F
    private var lastRawY = 0F

    /** Fractions of a pixel the page could not be scrolled by yet; dropped, they become drift. */
    private var pendingScrollY = 0F
    private var forwardingVertical = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        trackVelocity(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(e)
                // Claim the gesture before the page above can. It is not being denied its scroll -
                // it is being driven from here instead, for as long as this finger is down.
                if (verticalParent() != null) parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseVertical(e)
        }
        if (super.onInterceptTouchEvent(e)) return true
        // Take the stream on vertical movement too, so the children see the cancel that stops a
        // press on a cover from becoming a tap once the drag has started.
        return e.actionMasked == MotionEvent.ACTION_MOVE &&
            abs(e.rawY - downRawY) > touchSlop &&
            verticalParent() != null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        trackVelocity(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(e)
            MotionEvent.ACTION_MOVE -> forwardVertical(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseVertical(e)
        }
        // The horizontal axis is untouched: RecyclerView reads the same event and moves the row.
        val handled = super.onTouchEvent(e)
        return handled || forwardingVertical
    }

    private fun beginGesture(e: MotionEvent) {
        downRawY = e.rawY
        lastRawY = e.rawY
        pendingScrollY = 0F
        forwardingVertical = false
    }

    private fun forwardVertical(e: MotionEvent) {
        val page = verticalParent() ?: return
        if (!forwardingVertical) {
            if (abs(e.rawY - downRawY) <= touchSlop) return
            forwardingVertical = true
            // Start from where the slop was crossed rather than from the down point, so the page
            // does not jump by the slop distance on the first frame.
            lastRawY = e.rawY
            return
        }
        pendingScrollY += lastRawY - e.rawY
        lastRawY = e.rawY
        val dy = pendingScrollY.toInt()
        if (dy == 0) return
        pendingScrollY -= dy
        page.scrollBy(0, dy)
    }

    private fun releaseVertical(e: MotionEvent) {
        val tracker = velocityTracker
        val wasForwarding = forwardingVertical
        forwardingVertical = false
        pendingScrollY = 0F
        if (tracker == null) return
        if (wasForwarding && e.actionMasked == MotionEvent.ACTION_UP) {
            tracker.computeCurrentVelocity(1000, maximumFlingVelocity.toFloat())
            val velocityY = -tracker.yVelocity
            if (abs(velocityY) >= minimumFlingVelocity) flingPage(velocityY.toInt())
        }
        tracker.recycle()
        velocityTracker = null
    }

    private fun flingPage(velocityY: Int) {
        when (val page = verticalParent()) {
            is RecyclerView -> page.fling(0, velocityY)
            is NestedScrollView -> page.fling(velocityY)
            is ScrollView -> page.fling(velocityY)
            else -> Unit
        }
    }

    /**
     * Velocity in screen coordinates.
     *
     * A tracker fed the raw event measures this view's contents moving *and* the page carrying the
     * view, which on a diagonal drag reads as roughly twice the speed the finger was going.
     */
    private fun trackVelocity(e: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        val screenEvent = MotionEvent.obtain(e)
        screenEvent.offsetLocation(e.rawX - e.x, e.rawY - e.y)
        tracker.addMovement(screenEvent)
        screenEvent.recycle()
    }

    /**
     * The nearest thing above this row that scrolls vertically, or null when there is none - and
     * null too when this row scrolls vertically itself, which makes it an ordinary list whose own
     * vertical drag is nobody else's to take.
     */
    private fun verticalParent(): View? {
        if (layoutManager?.canScrollVertically() == true) return null
        var view = parent as? ViewGroup
        while (view != null) {
            if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return view
            view = view.parent as? ViewGroup
        }
        return null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        velocityTracker?.recycle()
        velocityTracker = null
        forwardingVertical = false
    }
}
