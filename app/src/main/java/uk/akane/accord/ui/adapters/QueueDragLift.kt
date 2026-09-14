package uk.akane.accord.ui.adapters

import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewOutlineProvider
import uk.akane.accord.R
import uk.akane.accord.logic.dp

/**
 * How a queue row looks while it is being moved.
 *
 * A dragged row used to be the whole width of the list with square corners and a shadow around it,
 * which is the shape of the list itself rather than the shape of something picked up out of it -
 * it read as a seam across the panel, not as a card in hand. It is now inset, rounded, and given a
 * surface of its own.
 *
 * One shape does both jobs. An earlier version drew a full-width rounded background and then
 * clipped it to a narrower rounded outline, and the two curves disagreeing left a visible line
 * across the card where the fill met the clip. The fill is inset by the drawable itself now, and
 * the outline exists only to cast the shadow around that same rectangle, so there is nothing for
 * the two to disagree about and no clipping to do.
 */
internal object QueueDragLift {

    /** Barely there. A dragged row should look picked up, not thrown at the screen. */
    const val SCALE = 1.02f

    private val CORNER = 14.dp
    private val INSET = 12.dp
    private val ELEVATION = 8.dp

    val elevation: Float get() = ELEVATION.px

    /** Rounds and insets the row, and gives it the surface that makes it a card. */
    fun lift(view: View) {
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = CORNER.px
            setColor(view.context.getColor(R.color.queueDragCardColor))
        }
        val inset = INSET.px.toInt()
        view.background = InsetCard(card, inset)
        view.outlineProvider = shadowOutline
        view.clipToOutline = false
    }

    /**
     * The card, inset from the row's edges, reporting no padding while it does it.
     *
     * A View takes its padding from whatever background it is handed, and [InsetDrawable] reports
     * its inset as exactly that. Picking a row up therefore pushed its own artwork and text 12dp
     * inwards - the row visibly narrowing under the finger - and put them back only if something
     * later set a different padding, which nothing does: removing a background leaves the padding
     * it defined behind. That is why rows went on sitting inset from their neighbours long after
     * the drag ended.
     */
    private class InsetCard(drawable: Drawable, inset: Int) :
        InsetDrawable(drawable, inset, 0, inset, 0) {
        override fun getPadding(padding: Rect): Boolean {
            padding.set(0, 0, 0, 0)
            return false
        }
    }

    /** Puts the row back into the list: no surface, no corners, no shadow of its own. */
    fun settle(view: View) {
        view.background = null
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
        view.translationZ = 0f
    }

    /**
     * The shadow's shape, matching the inset fill exactly.
     *
     * The inset is here and in the drawable rather than in the layout because the row is being
     * dragged: a margin would change what the row measures to and shift every position under it.
     */
    private val shadowOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val inset = INSET.px.toInt()
            outline.setRoundRect(inset, 0, view.width - inset, view.height, CORNER.px)
        }
    }
}
