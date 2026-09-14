package uk.akane.accord.logic.utils

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R

/**
 * The shape a collection grid takes on a given canvas.
 *
 * Every browse grid used to be two hard-coded columns, which is right on a phone and wrong on
 * anything wider: a tablet stretched two cards across 800dp and read as a phone screen that had
 * been zoomed. The count now comes from `browse_grid_columns`, qualified by the window's current
 * width, so the same card size survives a rotation or a resized window.
 */
object BrowseGrid {

    fun columnCount(context: Context): Int =
        context.resources.getInteger(R.integer.browse_grid_columns).coerceAtLeast(1)

    /**
     * Insets that hold the outer margin and the gaps between cards while leaving every card the
     * same width.
     *
     * A grid layout manager hands each column an equal slice of the row, so insetting the first
     * and last columns more than the middle ones - which is all two columns ever needed - makes
     * the middle cards visibly wider once there are more than two. The offsets are measured from
     * where each card should actually land instead.
     *
     * [fullSpan] marks the rows that span the whole grid (a header or a control row), which are
     * left alone because they carry their own margins.
     */
    fun spacing(
        context: Context,
        columns: Int,
        outerDp: Int = 22,
        gapDp: Int = 16,
        topDp: Int = 12,
        bottomDp: Int = 4,
        fullSpan: (Int) -> Boolean = { false },
    ): RecyclerView.ItemDecoration = object : RecyclerView.ItemDecoration() {

        private val density = context.resources.displayMetrics.density
        private val outer = (outerDp * density).toInt()
        private val gap = (gapDp * density).toInt()
        private val top = (topDp * density).toInt()
        private val bottom = (bottomDp * density).toInt()

        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            if (fullSpan(position)) {
                outRect.set(0, 0, 0, 0)
                return
            }

            // A grid whose width is not EXACTLY specified is measured before it is laid out, and
            // offsets are asked for in that pass, when getWidth() is still zero.
            val measured = if (parent.width > 0) parent.width else parent.measuredWidth
            val width = measured - parent.paddingLeft - parent.paddingRight
            if (width <= 0) return
            val column = (view.layoutParams as? GridLayoutManager.LayoutParams)?.spanIndex
                ?.takeIf { it != GridLayoutManager.LayoutParams.INVALID_SPAN_ID }
                ?: (position % columns)

            // Where the card belongs, against the slice the layout manager gave the column.
            val card = (width - outer * 2 - gap * (columns - 1)).toFloat() / columns
            val slice = width.toFloat() / columns
            val start = outer + column * (card + gap)

            outRect.left = (start - column * slice).toInt()
            outRect.right = ((column + 1) * slice - (start + card)).toInt()
            outRect.top = top
            outRect.bottom = bottom
        }
    }
}
