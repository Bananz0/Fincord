package uk.akane.accord.ui.components.lyrics

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.core.view.forEach
import androidx.core.view.isNotEmpty
import uk.akane.accord.logic.dp
import kotlin.math.roundToInt

class LyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {
    /**
     * Where the first line sits, and where the current line rests once scrolling starts.
     *
     * 160dp put the top line most of a thumb below the toolbar with nothing in the gap.
     * Enough to breathe, not enough to look like the text failed to load.
     */
    val contentPaddingTop = 28.dp.px.roundToInt()

    /** Set by whoever owns the player, so tapping a line can jump to it. */
    var onSeek: ((Long) -> Unit)? = null

    fun update(lyrics: Lyrics) {
        forEach { child: View ->
            child as LyricsLineView
            child.release()
        }
        removeAllViews()
        val deviceHeight = resources.displayMetrics.heightPixels.toFloat()
        lyrics.lyrics.forEachIndexed { index, line ->
            val view = LyricsLineView(context, line) { timestamp -> onSeek?.invoke(timestamp) }
            view.setAnimations(index, 0f, deviceHeight)
            addView(view)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        var layoutHeight = contentPaddingTop
        forEach { child: View ->
            child.measure(widthMeasureSpec, heightMeasureSpec)
            child as LyricsLineView
            child.animations.setGlobalOffset(layoutHeight.toFloat())
            layoutHeight += child.measuredHeight
        }
        if (isNotEmpty()) {
            val lastChild = getChildAt(childCount - 1)
            layoutHeight += rootView.height - contentPaddingTop - lastChild.measuredHeight
        }

        setMeasuredDimension(width, layoutHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var top = contentPaddingTop
        forEach { child: View ->
            val height = child.measuredHeight
            child.layout(0, top, child.measuredWidth, top + height)
            top += height
        }
    }
}