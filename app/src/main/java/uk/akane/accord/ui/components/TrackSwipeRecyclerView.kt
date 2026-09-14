package uk.akane.accord.ui.components

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical track list whose horizontal movement belongs to row actions.
 *
 * FragmentSwitcherView asks descendants whether they can scroll horizontally before taking a
 * gesture as page-back. ItemTouchHelper does not make RecyclerView report that ability itself, so
 * without this marker the parent can cancel a right-swipe before the queue action sees it.
 */
class TrackSwipeRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun canScrollHorizontally(direction: Int): Boolean = childCount > 0
}
