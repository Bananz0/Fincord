package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import uk.akane.accord.R

/**
 * An icon that changes colour where it crosses a boundary behind it.
 *
 * The companion to [SplitTintTextView], and needed for the same reason: a destination's icon sits
 * at the left of its row, which is the part the volume fill covers first, so a white-tinted icon
 * was washed out from the moment the volume left zero. Fixing only the label left the icon beside
 * it doing exactly what the label had been doing.
 *
 * Tinted by drawing into an offscreen layer and painting over it with `SRC_IN`, rather than by
 * setting a colour filter. A filter has to be assigned to the view or its drawable, and both of
 * those invalidate - from inside a draw pass that schedules another draw, forever. The layer costs
 * one small offscreen buffer per pass and has no such side effect.
 */
class SplitTintImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /**
     * Where the boundary sits, in this view's own pixels. Zero or less means the icon is entirely
     * over the track; past the right edge means entirely over the fill.
     */
    var splitX: Float = 0F
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Colour used left of [splitX], over the filled part of the row. */
    var colorOverFill: Int = context.getColor(R.color.output_row_label_on_fill)
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Colour used right of it — whatever tint the layout gave this icon. */
    private val colorOverTrack: Int
        get() = imageTintList?.defaultColor ?: context.getColor(R.color.onSurfaceColor)

    override fun onDraw(canvas: Canvas) {
        val split = splitX
        when {
            split <= 0F -> super.onDraw(canvas)
            split >= width -> drawTinted(canvas, colorOverFill)
            else -> {
                val over = colorOverTrack
                canvas.withClip(0F, split) { drawTinted(this, colorOverFill) }
                canvas.withClip(split, width.toFloat()) { drawTinted(this, over) }
            }
        }
    }

    private fun drawTinted(canvas: Canvas, color: Int) {
        val layer = canvas.saveLayer(null, null)
        super.onDraw(canvas)
        canvas.drawColor(color, PorterDuff.Mode.SRC_IN)
        canvas.restoreToCount(layer)
    }

    private inline fun Canvas.withClip(left: Float, right: Float, block: Canvas.() -> Unit) {
        val saved = save()
        clipRect(left, 0F, right, height.toFloat())
        block()
        restoreToCount(saved)
    }
}
