package uk.akane.accord.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.logic.UserQueue
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.MainActivity
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Swipe a track row to do the two things worth doing without opening a menu.
 *
 * Right adds it to the queue; left downloads it, or in a playlist takes it out. Nothing is dragged
 * away permanently - the row springs back and the action happens, because none of these remove the
 * row from the list it is in (except the playlist case, which redraws itself anyway).
 */
object TrackSwipeActions {

    /**
     * @param trackAt what row [position] is showing, or null for rows that are not tracks - a
     *   header, a footer, an "add music" button - which must not swipe at all.
     * @param onRemove supplied by a playlist, where a left swipe means "take it out" rather than
     *   "download it".
     */
    /**
     * What a right-to-left swipe does on this list, or null where it does nothing.
     *
     * Left is deliberately empty on ordinary song lists. It used to mean "download", which in a
     * client whose whole library already lives on a server it can reach answers a question nobody
     * asked - and a panel under every row teaches people not to swipe. It survives only where the
     * trailing edge means something specific: taking a track out of a playlist or the queue, or
     * asking Lidarr for music that is not here yet.
     */
    class Trailing(
        val iconRes: Int,
        val colorRes: Int,
        /** Whether this row can do it. A row that cannot simply will not swipe that way. */
        val enabledAt: (Int) -> Boolean = { true },
        val onAction: (Int) -> Unit,
    )

    /**
     * @param trackAt what row [position] is showing, or null for rows that are not tracks - a
     *   header, a footer, an "add music" button - which must not swipe at all.
     * @param trailing what a left swipe does here, if anything.
     */
    fun attach(
        recyclerView: RecyclerView,
        activity: MainActivity,
        trackAt: (Int) -> MediaItem?,
        trailing: Trailing? = null,
    ) {
        val panel = SwipeActionPanel(
            context = recyclerView.context,
            leading = listOf(
                SwipeActionPanel.Action(R.color.swipePlayLater, R.drawable.ic_play_later),
                SwipeActionPanel.Action(R.color.swipePlayNext, R.drawable.ic_play_next),
            ),
            trailing = trailing?.let {
                listOf(SwipeActionPanel.Action(it.colorRes, it.iconRes))
            }.orEmpty(),
        )

        var trackedHolder: RecyclerView.ViewHolder? = null
        var armed = -1
        var actionDispatched = false
        var pendingAfterRecoil: (() -> Unit)? = null

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = NEVER_SWIPE_AWAY
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                // Each edge decides for itself. A Lidarr result is not a library track and can never
                // be queued, but it is exactly the row worth asking the server to fetch, so gating
                // both directions on the same test would leave it inert.
                //
                // Absolute, not binding: inside a ConcatAdapter the binding position restarts at
                // zero for each child adapter, so every song looked like row zero.
                val position = viewHolder.absoluteAdapterPosition
                var dirs = 0
                if (trackAt(position) != null) dirs = dirs or ItemTouchHelper.RIGHT
                if (trailing?.enabledAt(position) == true) dirs = dirs or ItemTouchHelper.LEFT
                return dirs
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float,
            ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
                SETTLE_MS
            } else {
                super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                recyclerView.adapter?.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                val pending = pendingAfterRecoil
                trackedHolder = null
                armed = -1
                actionDispatched = false
                pendingAfterRecoil = null
                panel.reset()
                // Run once the row has settled, so a list that reorders itself does not do so
                // underneath a view still animating.
                pending?.invoke()
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val view = viewHolder.itemView
                val damped = panel.distance(dX, view)

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    if (isCurrentlyActive) {
                        if (trackedHolder !== viewHolder) {
                            trackedHolder = viewHolder
                            armed = -1
                            actionDispatched = false
                            pendingAfterRecoil = null
                            panel.reset()
                        }
                        val next = panel.armedIndex(damped)
                        if (next != armed) {
                            // Distinct weights, so the stops are told apart by feel alone - which
                            // is the entire point of having more than one.
                            when {
                                next < 0 -> Unit
                                panel.isFull(damped) -> Haptics.commit(view)
                                else -> Haptics.arm(view)
                            }
                            armed = next
                        }
                    } else if (!actionDispatched && armed >= 0) {
                        val position = viewHolder.absoluteAdapterPosition
                        if (damped > 0F) {
                            trackAt(position)?.let { item ->
                                if (armed >= 1) playNext(activity, item)
                                else playLater(activity, item)
                            }
                        } else if (trailing != null) {
                            Haptics.commit(view)
                            pendingAfterRecoil = { trailing.onAction(position) }
                        }
                        actionDispatched = true
                    }

                    if (panel.draw(
                            canvas, view, damped, recyclerView.frameNanos(), isCurrentlyActive
                        )
                    ) {
                        // The takeover is still moving and the finger may not be, so ask for the
                        // next frame rather than waiting for one to happen along.
                        recyclerView.invalidate()
                    }
                }
                super.onChildDraw(
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView) { position ->
            trackAt(position) != null || trailing?.enabledAt(position) == true
        }
    }

    /**
     * The same gesture on a list of things that are not library tracks - Lidarr's search results -
     * where both directions mean the one thing worth doing: request it.
     *
     * Shares the renderer with every other swipe, so it is the same capsule, the same travel and
     * the same escalation. It used to be a near-copy of the song-row implementation, which is how
     * the two drifted apart.
     *
     * @param canSwipe whether the row at this position can be requested at all.
     */
    fun attachRequest(
        recyclerView: RecyclerView,
        canSwipe: (Int) -> Boolean,
        onRequest: (Int) -> Unit,
    ) {
        val action = SwipeActionPanel.Action(R.color.accentColor, R.drawable.ic_download)
        val panel = SwipeActionPanel(
            context = recyclerView.context,
            leading = listOf(action),
            trailing = listOf(action),
        )

        var trackedHolder: RecyclerView.ViewHolder? = null
        var armed = -1
        var actionDispatched = false

        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = NEVER_SWIPE_AWAY
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = Float.MAX_VALUE

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int = if (!canSwipe(viewHolder.absoluteAdapterPosition)) 0
            else super.getSwipeDirs(recyclerView, viewHolder)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun getAnimationDuration(
                recyclerView: RecyclerView,
                animationType: Int,
                animateDx: Float,
                animateDy: Float,
            ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
                SETTLE_MS
            } else {
                super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                recyclerView.adapter?.notifyItemChanged(viewHolder.absoluteAdapterPosition)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                trackedHolder = null
                armed = -1
                actionDispatched = false
                panel.reset()
            }

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val view = viewHolder.itemView
                val damped = panel.distance(dX, view)

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && damped != 0F) {
                    if (isCurrentlyActive) {
                        if (trackedHolder !== viewHolder) {
                            trackedHolder = viewHolder
                            armed = -1
                            actionDispatched = false
                            panel.reset()
                        }
                        val next = panel.armedIndex(damped)
                        if (next != armed) {
                            if (next >= 0) Haptics.commit(view)
                            armed = next
                        }
                    } else if (!actionDispatched && armed >= 0) {
                        val position = viewHolder.absoluteAdapterPosition
                        if (canSwipe(position)) onRequest(position)
                        actionDispatched = true
                    }
                    if (panel.draw(
                            canvas, view, damped, recyclerView.frameNanos(), isCurrentlyActive
                        )
                    ) {
                        recyclerView.invalidate()
                    }
                }
                super.onChildDraw(
                    canvas, recyclerView, viewHolder, damped, dY, actionState, isCurrentlyActive
                )
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
        claimHorizontalGestures(recyclerView) { position -> canSwipe(position) }
    }

    private fun claimHorizontalGestures(
        recyclerView: RecyclerView,
        swipeableAt: (Int) -> Boolean,
    ) {
        val touchSlop = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
        var downX = 0F
        var downY = 0F
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        downY = e.y
                        // Only where a track is. Anywhere else - the header, the footer, the
                        // empty space below the list - the sideways drag still belongs to the
                        // page, and should take the user back the way they came.
                        val child = rv.findChildViewUnder(e.x, e.y)
                        val onTrack = child != null &&
                            swipeableAt(rv.getChildAdapterPosition(child))
                        rv.parent?.requestDisallowInterceptTouchEvent(onTrack)
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                            // Scrolling the list, not swiping a row - hand the gesture back.
                            rv.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                // Never consumed here; this only decides who is allowed to intercept.
                return false
            }
        })
    }

    /**
     * Two stops on the leading edge, and room to reach both.
     *
     * The travel limit sits past the second stop, or the gesture would clamp before Play Next could
     * ever arm. The gap between them is deliberately wide: they are told apart by feel, and two
     * detents a few pixels apart is one mushy detent.
     */
    /**
     * How wide one action button is. The stops are measured in these, so the gesture is the same
     * shape on a small phone as on a large one.
     */

    /** The Lidarr request gesture below still has a single stop. */
    private const val SWIPE_THRESHOLD = 0.36F
    private const val SETTLE_MS = 190L
    private const val NEVER_SWIPE_AWAY = 2F

    /** Appends to the end of the queue. */
    private fun playLater(activity: MainActivity, item: MediaItem) {
        val player = activity.getPlayer() ?: return
        if (player.mediaItemCount == 0) {
            player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
            player.prepare()
            player.play()
        } else {
            player.addMediaItem(UserQueue.mark(item))
        }
        Toast.makeText(activity, R.string.queued, Toast.LENGTH_SHORT).show()
    }

    /**
     * Slots the track in directly after whatever is playing.
     *
     * Inserted after the current index rather than at the front of the timeline: the front is
     * where playback started, not where it is now, so putting it there would queue the track
     * behind everything already played.
     */
    private fun playNext(activity: MainActivity, item: MediaItem) {
        val player = activity.getPlayer() ?: return
        if (player.mediaItemCount == 0) {
            player.setMediaItems(listOf(item), 0, C.TIME_UNSET)
            player.prepare()
            player.play()
        } else {
            player.addMediaItem(player.currentMediaItemIndex + 1, UserQueue.mark(item))
        }
        Toast.makeText(activity, R.string.queued_next, Toast.LENGTH_SHORT).show()
    }
}
