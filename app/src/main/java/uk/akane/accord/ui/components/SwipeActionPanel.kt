package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.logic.dp
import kotlin.math.abs

/**
 * The look and feel of every swipe in the app, in one place.
 *
 * There were three separate implementations - song rows, the queue, Lidarr results - each with its
 * own geometry, its own travel limit and its own idea of what a revealed action looks like. They
 * drifted, which is how the queue ended up with a panel that stopped short while the song lists
 * grew capsules. One renderer means a gesture learned anywhere is the same gesture everywhere.
 *
 * Actions are listed in the order they emerge from the edge. Each has a capsule of its own and
 * appears in turn: the first grows out on its own, holds still while the next grows beside it, and
 * when the last one is fully out it takes the whole panel and keeps growing. That final escalation
 * is animated rather than switched, because a panel that changes shape between two frames reads as
 * a glitch rather than as an answer changing.
 */
class SwipeActionPanel(
    context: Context,
    private val leading: List<Action> = emptyList(),
    private val trailing: List<Action> = emptyList(),
) {

    class Action(val colorRes: Int, val iconRes: Int)

    private class Resolved(val color: Int, val icon: Drawable?)

    private val resources = context.resources
    private val leadingResolved = leading.map(::resolve)
    private val trailingResolved = trailing.map(::resolve)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsuleWidth = CAPSULE_WIDTH_DP.dp.px
    private val gap = GAP_DP.dp.px
    private val verticalInset = INSET_DP.dp.px
    private val iconSize = ICON_SIZE_DP.dp.px.toInt()

    /** How far the finger has to travel before every action on an edge is showing. */
    fun revealDistance(edge: Int): Float = slotEnd(count(edge) - 1)

    /**
     * The row's offset for a given finger offset.
     *
     * One-to-one until everything is revealed, then heavily damped. Following at a fraction the
     * whole way made the row feel disconnected from the finger and, worse, meant reaching the last
     * action took nearly twice the travel it looks like it should - the complaint that this gesture
     * needed a long swipe was that fraction, not the distances.
     */
    fun distance(rawDx: Float, view: View): Float {
        val edge = if (rawDx > 0F) LEADING else TRAILING
        if (count(edge) == 0) return 0F
        val soft = revealDistance(edge)
        val magnitude = abs(rawDx)
        val eased = if (magnitude <= soft) {
            magnitude
        } else {
            soft + (magnitude - soft) * OVERSHOOT_FOLLOW
        }.coerceAtMost(soft + OVERSHOOT_DP.dp.px)
        return if (rawDx < 0F) -eased else eased
    }

    /**
     * Which action is armed at this offset, or -1 for none.
     *
     * An action is armed once its own capsule is fully out, which is also the moment it becomes the
     * thing a release would do - so what the panel shows and what the gesture promises never
     * disagree.
     */
    fun armedIndex(damped: Float): Int {
        val edge = if (damped > 0F) LEADING else TRAILING
        val n = count(edge)
        if (n == 0) return -1
        val reach = abs(damped)
        var armed = -1
        for (i in 0 until n) if (reach >= slotEnd(i)) armed = i
        return armed
    }

    /** True once the last action has taken the panel; the release does that one. */
    fun isFull(damped: Float): Boolean {
        val edge = if (damped > 0F) LEADING else TRAILING
        return count(edge) > 0 && armedIndex(damped) == count(edge) - 1
    }

    /**
     * Advances the takeover animation and draws the panel.
     *
     * Returns true while the animation still has somewhere to go, so the caller can ask for another
     * frame. onChildDraw only runs when something moves; without that nudge a takeover triggered by
     * a finger that has stopped would freeze part-way.
     */
    fun draw(
        canvas: Canvas,
        view: View,
        damped: Float,
        frameNanos: Long,
        active: Boolean = true,
    ): Boolean {
        if (damped == 0F) return false
        val edge = if (damped > 0F) LEADING else TRAILING
        val actions = resolved(edge)
        if (actions.isEmpty()) return false

        // Once the finger is up the escalation is frozen where it was, and only the row's recoil
        // shrinks the panel. Letting it animate back would replay the phases in reverse - a release
        // from a full swipe would flash the first action on the way out, advertising a choice that
        // has already been made and not the one that was made.
        val animating = if (active) advance(if (isFull(damped)) 1F else 0F, frameNanos) else false

        val top = view.top + verticalInset
        val bottom = view.bottom - verticalInset
        val reach = abs(damped)
        val last = actions.lastIndex

        // The escalated shape: one capsule holding the whole reveal.
        val fullNear = gap
        val fullFar = reach

        for (index in actions.indices) {
            val slotNear = slotStart(index)
            val slotFar = (reach - slotNear).coerceIn(0F, capsuleWidth) + slotNear
            if (slotFar <= slotNear && takeover == 0F) continue

            val near: Float
            val far: Float
            val alpha: Float
            if (index == last) {
                near = lerp(slotNear, fullNear, takeover)
                far = lerp(slotFar, fullFar, takeover)
                alpha = 1F
            } else {
                // The earlier capsules are swallowed by the one taking over, rather than simply
                // vanishing: they slide under it and fade as it passes.
                near = slotNear
                far = slotFar
                alpha = 1F - takeover
            }
            if (alpha <= 0.01F || far <= near) continue

            val action = actions[index]
            draw(canvas, action, view, edge, near, far, top, bottom, alpha)
        }
        return animating
    }

    /** Resets between gestures, so a new swipe does not inherit the last one's escalation. */
    fun reset() {
        takeover = 0F
        lastFrameNanos = 0L
    }

    private var takeover = 0F
    private var lastFrameNanos = 0L

    private fun advance(target: Float, frameNanos: Long): Boolean {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameNanos
            takeover = target
            return false
        }
        val deltaMs = (frameNanos - lastFrameNanos) / 1_000_000F
        lastFrameNanos = frameNanos
        if (takeover == target) return false
        val step = (deltaMs / TAKEOVER_MS).coerceIn(0F, 1F)
        takeover += (target - takeover) * (step * TAKEOVER_STIFFNESS).coerceAtMost(1F)
        if (abs(target - takeover) < 0.005F) {
            takeover = target
            return false
        }
        return true
    }

    private fun draw(
        canvas: Canvas,
        action: Resolved,
        view: View,
        edge: Int,
        near: Float,
        far: Float,
        top: Float,
        bottom: Float,
        alpha: Float,
    ) {
        // "Near" and "far" are measured from whichever edge the panel grows out of, so the layout
        // maths is written once instead of mirrored.
        val left: Float
        val right: Float
        if (edge == LEADING) {
            left = view.left + near
            right = view.left + far
        } else {
            left = view.right - far
            right = view.right - near
        }
        val capsule = RectF(left, top, right, bottom)
        val radius = (bottom - top) / 2F
        paint.color = action.color
        paint.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(capsule, radius, radius, paint)
        paint.alpha = 255

        val icon = action.icon ?: return
        val half = iconSize / 2F
        val margin = radius / 2F
        val centerY = ((top + bottom) / 2F).toInt()
        val raw = (left + right) / 2F
        // Held clear of the edge the capsule grows from, so it is never half outside one narrower
        // than itself - and clipped to that capsule, so it can never spill onto its neighbour.
        val centerX = if (edge == LEADING) {
            raw.coerceAtMost(right - half - margin)
        } else {
            raw.coerceAtLeast(left + half + margin)
        }.toInt()
        icon.alpha = (255 * alpha).toInt()
        icon.setTint(Color.WHITE)
        icon.setBounds(
            centerX - iconSize / 2, centerY - iconSize / 2,
            centerX + iconSize / 2, centerY + iconSize / 2,
        )
        canvas.save()
        canvas.clipRect(capsule)
        icon.draw(canvas)
        canvas.restore()
    }

    private fun count(edge: Int) = resolved(edge).size

    private fun resolved(edge: Int) =
        if (edge == LEADING) leadingResolved else trailingResolved

    private fun slotStart(index: Int) = gap + index * (capsuleWidth + gap)

    private fun slotEnd(index: Int) =
        if (index < 0) 0F else slotStart(index) + capsuleWidth

    private fun resolve(action: Action) = Resolved(
        color = resources.getColor(action.colorRes, null),
        icon = ResourcesCompat.getDrawable(resources, action.iconRes, null),
    )

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    companion object {
        /** One capsule. Wide enough for a glyph with air around it, narrow enough to fit two. */
        private const val CAPSULE_WIDTH_DP = 76

        private const val GAP_DP = 8
        private const val INSET_DP = 6
        private const val ICON_SIZE_DP = 22

        /** How far past a full reveal the row will stretch, and how hard it resists getting there. */
        private const val OVERSHOOT_DP = 40
        private const val OVERSHOOT_FOLLOW = 0.28F

        private const val TAKEOVER_MS = 160F
        private const val TAKEOVER_STIFFNESS = 1.9F

        const val LEADING = 1
        const val TRAILING = -1
    }
}

/** Keeps the frame clock in one place; every panel animates off the same one. */
internal fun RecyclerView.frameNanos(): Long = drawingTime * 1_000_000L
