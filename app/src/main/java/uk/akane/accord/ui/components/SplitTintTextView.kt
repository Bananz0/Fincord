package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import uk.akane.accord.R

/**
 * A label that changes colour where it crosses a boundary behind it.
 *
 * The output sheet draws a destination's volume as a fill inside the row itself, so the row's own
 * name lies half over the light fill and half over the dark track. One colour cannot serve both:
 * white text disappeared into the filled half, which is the whole of the problem.
 *
 * Done with a two-stop gradient on the text paint rather than by drawing the label twice under
 * clips. `TextView.onDraw` assigns `mCurTextColor` to its paint on every pass, so a colour set from
 * outside is overwritten before a single glyph is drawn - which is why the clipped version of this
 * looked exactly like no change at all. A shader is not reset that way and takes precedence over
 * the colour, so it survives to the draw.
 *
 * The split is per pixel, and it is worth being explicit about why not per character: deciding a
 * whole glyph by which side its centre falls on puts the change up to half a character from the
 * boundary, and a wide letter straddling the line is still unreadable across one of its own halves.
 * A gradient with two stops at the same offset is a hard edge in exactly the right place.
 */
class SplitTintTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /**
     * Where the boundary sits, in this view's own pixels.
     *
     * Zero or less means the label is entirely over the track and the theme's own colour is used
     * unchanged; past the right edge means it is entirely over the fill.
     */
    var splitX: Float = 0F
        set(value) {
            if (field == value) return
            field = value
            applySplitShader()
        }

    /** Colour used left of [splitX], over the filled part of the row. */
    var colorOverFill: Int = context.getColor(R.color.output_row_label_on_fill)
        set(value) {
            if (field == value) return
            field = value
            applySplitShader()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySplitShader()
    }

    override fun setTextColor(color: Int) {
        super.setTextColor(color)
        applySplitShader()
    }

    private fun applySplitShader() {
        val width = width
        if (width <= 0) return
        val overTrack = currentTextColor
        // The canvas is translated by the label's left padding before the text is drawn, so the
        // boundary has to move with it to stay under the same pixel of the row.
        val edge = splitX - totalPaddingLeft

        paint.shader = when {
            edge <= 0F -> null
            edge >= width -> solid(colorOverFill)
            else -> LinearGradient(
                edge - HARD_EDGE,
                0F,
                edge,
                0F,
                colorOverFill,
                overTrack,
                Shader.TileMode.CLAMP,
            )
        }
        invalidate()
    }

    /** A gradient with one colour at both ends: a flat fill the paint's own colour cannot undo. */
    private fun solid(color: Int) =
        LinearGradient(0F, 0F, 1F, 0F, color, color, Shader.TileMode.CLAMP)

    private companion object {
        /**
         * The gradient's width. Sub-pixel, so the change reads as an edge rather than a blend, but
         * not zero - a zero-length gradient is undefined and drew as a single flat colour.
         */
        const val HARD_EDGE = 0.5F
    }
}
