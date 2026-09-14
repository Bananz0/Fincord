package uk.akane.accord.ui.adapters

import android.content.Context
import uk.akane.accord.ui.components.frameNanos
import uk.akane.accord.ui.components.Haptics
import uk.akane.accord.ui.components.SwipeActionPanel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.load
import coil3.request.ImageRequest
import uk.akane.accord.R
import uk.akane.accord.logic.UserQueue
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.components.ResistiveSwipeHaptics
import uk.akane.accord.ui.components.resistedSwipeDistance
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.cupertino.utils.AnimationUtils.FASTEST_DURATION
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * @param sectionLabel the heading drawn above this row, on the first row of each run - the tracks
 *   the user queued by hand, then wherever the rest is coming from. Null everywhere else.
 */
data class QueueItem(
    val uid: Any,
    val mediaItem: MediaItem,
    val sectionLabel: String? = null,
    val isCurrent: Boolean = false,
)

class QueuePreviewAdapter(
    private val items: MutableList<QueueItem>,
    private val onMove: ((QueueItem, QueueItem) -> Unit)? = null,
    private val onItemClick: ((QueueItem) -> Unit)? = null,
    private val dragStartListener: DragStartListener? = null
) : RecyclerView.Adapter<QueuePreviewAdapter.ViewHolder>() {

    var isDragging = false
        private set

    private val diffScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var diffJob: Job? = null
    /** Invalidates both a calculating diff and a result already posted behind RecyclerView layout. */
    private var updateGeneration = 0L
    /** Latest authoritative list received while ItemTouchHelper owns the rows. */
    private var pendingItems: List<QueueItem>? = null
    /** One receiver/player reorder is committed when the drag is released, never per row crossed. */
    private var pendingMove: Pair<QueueItem, QueueItem>? = null
    private var attachedRecyclerView: RecyclerView? = null

    interface DragStartListener {
        fun onStartDrag(viewHolder: RecyclerView.ViewHolder)
    }

    fun updateItems(newItems: List<QueueItem>) {
        val snapshot = newItems.toList()
        val generation = ++updateGeneration
        diffJob?.cancel()
        if (isDragging) {
            // Timeline changes are expected during a reorder. Dropping them left a section title
            // attached to whichever track originally carried it, so moving that track down also
            // moved "Playing Next From" down and stranded tracks above their own heading.
            pendingItems = snapshot
            return
        }

        val oldList = items.toList()
        diffJob = diffScope.launch {
            val diffCallback = QueueDiffCallback(oldList, snapshot)
            val diffResult = DiffUtil.calculateDiff(diffCallback)

            if (!isActive) return@launch
            withContext(Dispatchers.Main.immediate) {
                if (generation != updateGeneration) return@withContext

                // A rapid series of cover swipes advances the player faster than the hidden queue
                // can finish laying out each removal at position zero. Updating the adapter while
                // RecyclerView is in that pass can leave its ChildHelper and ViewHolder attachment
                // state disagreeing, and the next layout then crashes with "child is not detached".
                // Post behind the active pass, and let the generation check coalesce any further
                // transitions that arrive before this result gets there.
                val commit = Runnable {
                    if (generation != updateGeneration) return@Runnable
                    if (isDragging) {
                        pendingItems = snapshot
                        return@Runnable
                    }
                    items.clear()
                    items.addAll(snapshot)
                    diffResult.dispatchUpdatesTo(this@QueuePreviewAdapter)
                    attachedRecyclerView?.let { prefetchCovers(it, snapshot) }
                }
                val recyclerView = attachedRecyclerView
                if (recyclerView?.isComputingLayout == true) recyclerView.post(commit)
                else commit.run()
            }
        }
    }

    fun onDragStart() {
        if (isDragging) return
        isDragging = true
        pendingMove = null
    }

    fun onDragEnd() {
        isDragging = false
        val move = pendingMove
        pendingMove = null
        if (move != null) {
            // Every snapshot received during this drag predates the single move we are about to
            // commit. Applying one now makes the rows snap back, then forward again when Player's
            // real timeline arrives. Keep the optimistic order until that authoritative callback.
            pendingItems = null
            onMove?.invoke(move.first, move.second)
        } else {
            pendingItems?.let { latest ->
                pendingItems = null
                updateItems(latest)
            }
        }
        attachedRecyclerView?.invalidateItemDecorations()
    }

    /**
     * Fetches the covers of the rows just below the fold, before anything asks for them.
     *
     * The queue is a wall of artwork the user scrolls immediately, and each row asking for its own
     * cover at bind time means a network round trip per row while the finger is already moving -
     * which is the loading the artwork visibly does. Coil de-duplicates and caches these, so the
     * bind that follows is a memory-cache hit. Only the near future is worth warming: a hundred
     * covers pulled in at once would evict the ones on screen.
     */
    private fun prefetchCovers(recyclerView: RecyclerView, from: List<QueueItem>) {
        val context = recyclerView.context
        val size = COVER_SIZE_DP.dp.px.toInt()
        from.asSequence()
            .mapNotNull { it.mediaItem.mediaMetadata.artworkUri }
            .distinct()
            .take(PREFETCH_COVERS)
            .forEach { uri ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context).data(uri).size(size, size).build()
                )
            }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_queue_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.mediaItem.mediaMetadata.title?.toString()
            ?: holder.itemView.context.getString(R.string.unknown_track)
        holder.subtitle.text = item.mediaItem.mediaMetadata.artist?.toString()
            ?: holder.itemView.context.getString(R.string.unknown_artist)

        holder.cover.load(item.mediaItem.mediaMetadata.artworkUri) {
            size(COVER_SIZE_DP.dp.px.toInt(), COVER_SIZE_DP.dp.px.toInt())
        }
        holder.reorderHandle.visibility = if (item.isCurrent) View.INVISIBLE else View.VISIBLE

        // A row arrives from the pool wearing whatever the last drag left on it. The card surface
        // used to stand in for "already cleaned up", but the surface is only taken off at the end
        // of the settle animation: a rebind landing inside that window skipped the reset, and the
        // row kept the lift's 2% scale for the rest of its life - which is what left tracks in the
        // queue standing a few pixels in from their neighbours. The one row this must not touch is
        // the one under the finger, which is lifted on purpose and is the only lifted row there is.
        if (!isDragging || holder.itemView.translationZ == 0f) {
            holder.itemView.animate().cancel()
            QueueDragLift.settle(holder.itemView)
            holder.itemView.alpha = 1f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
            holder.itemView.translationX = 0f
            holder.itemView.translationZ = 0f
        }

        // Handle item click to play song
        holder.itemView.setOnClickListener {
            if (!isDragging && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                items.getOrNull(holder.bindingAdapterPosition)?.let { onItemClick?.invoke(it) }
            }
        }

        // Consume clicks on the drag handle to prevent triggering onItemClick on the parent
        holder.reorderHandle.setOnClickListener { }

        // Handle touch on handle to start dragging
        holder.reorderHandle.setOnTouchListener { v, event ->
            if (item.isCurrent) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> dragStartListener?.onStartDrag(holder)
                MotionEvent.ACTION_UP -> v.performClick()
                MotionEvent.ACTION_CANCEL -> Unit
            }
            // The handle owns the complete gesture. Returning false lets its ACTION_UP reach the
            // row after the drag helper settles, which occasionally starts playing that row.
            true
        }
    }

    override fun getItemCount(): Int = items.size

    fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
            return false
        }
        val moved = items[fromPosition]
        val target = items[toPosition]
        if (moved.isCurrent || target.isCurrent) return false

        // One detent per row crossed. The queue is a list of positions and this is the only signal
        // that one has actually been passed - the rows slide under a finger that is not touching
        // them, so without it a reorder is a silent guess about where the track has landed.
        attachedRecyclerView?.let(Haptics::tick)

        // ItemTouchHelper describes a move, not a swap. Keeping the preview to the same semantics
        // as Player.moveMediaItem matters when a fast drag crosses more than one row at a time.
        items.removeAt(fromPosition)
        items.add(toPosition, moved)
        redistributeSectionLabels()
        notifyItemMoved(fromPosition, toPosition)
        // Item decorations cache their top offsets. The labels were redistributed above, so a
        // move notification alone leaves the old empty space/header painted on the wrong row.
        attachedRecyclerView?.invalidateItemDecorations()
        pendingMove = moved to target
        return true
    }

    /** Keeps section decorations at the start of their run during the optimistic drag preview. */
    private fun redistributeSectionLabels() {
        val userLabel = items.firstOrNull {
            UserQueue.isUserQueued(it.mediaItem) && it.sectionLabel != null
        }?.sectionLabel
        val sourceLabel = items.firstOrNull {
            !UserQueue.isUserQueued(it.mediaItem) && it.sectionLabel != null
        }?.sectionLabel

        for (index in items.indices) items[index] = items[index].copy(sectionLabel = null)
        if (userLabel != null) {
            val index = items.indexOfFirst { !it.isCurrent && UserQueue.isUserQueued(it.mediaItem) }
            if (index >= 0) items[index] = items[index].copy(sectionLabel = userLabel)
        }
        if (sourceLabel != null) {
            val index = items.indexOfFirst { !it.isCurrent && !UserQueue.isUserQueued(it.mediaItem) }
            if (index >= 0) items[index] = items[index].copy(sectionLabel = sourceLabel)
        }
    }

    fun itemAt(position: Int): QueueItem? = items.getOrNull(position)

    fun indexOf(uid: Any): Int = items.indexOfFirst { it.uid == uid }

    /** The heading above [position], for the section decoration. */
    fun sectionLabelAt(position: Int): String? = items.getOrNull(position)?.sectionLabel

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val cover: android.widget.ImageView = view.findViewById(R.id.cover)
        val reorderHandle: View = view.findViewById(R.id.reorder_handle)
    }

    companion object {
        /** The row's artwork, in the layout and in the warm-up, at one size. */
        private const val COVER_SIZE_DP = 54F

        /** Far enough ahead to cover a flick, short enough not to evict what is on screen. */
        private const val PREFETCH_COVERS = 24
    }
}

/**
 * Reorder by dragging the handle, remove by swiping.
 *
 * Draws through the shared [SwipeActionPanel], like every other swipe in the app. It used to have
 * geometry of its own - a flat grey panel that stopped part-way across the row while the song lists
 * had grown capsules that ran the full width. Two implementations of one gesture is how that
 * happens; there is now one.
 */
class QueueItemTouchHelperCallback(
    private val adapter: QueuePreviewAdapter,
    context: Context,
    private val onRemove: (Int) -> Unit,
) : ItemTouchHelper.Callback() {
    private var currentDragViewHolder: RecyclerView.ViewHolder? = null
    private var trackedSwipeHolder: RecyclerView.ViewHolder? = null
    private var armed = -1
    private var removalDispatched = false
    private var pendingRemovalUid: Any? = null

    /**
     * Removal on either edge.
     *
     * The trailing edge is where it belongs, but the leading one is the drag handle's neighbour and
     * a queue row has nothing else a sideways drag could mean - so the same thing happens whichever
     * way it goes, rather than one direction quietly doing nothing.
     */
    private val panel = SwipeActionPanel(
        context = context,
        leading = listOf(
            SwipeActionPanel.Action(R.color.swipeDestructive, R.drawable.ic_trash)
        ),
        trailing = listOf(
            SwipeActionPanel.Action(R.color.swipeDestructive, R.drawable.ic_trash)
        ),
    )

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = true

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (adapter.itemAt(viewHolder.bindingAdapterPosition)?.isCurrent == true) {
            return makeMovementFlags(0, 0)
        }
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        val swipeFlags = ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        return makeMovementFlags(dragFlags, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
    }

    // The row always recoils before it is removed. ItemTouchHelper's completed-swipe path would
    // throw it off-screen and make the reveal vanish separately from the row.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = NEVER_SWIPE_AWAY

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

    override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

    override fun getAnimationDuration(
        recyclerView: RecyclerView,
        animationType: Int,
        animateDx: Float,
        animateDy: Float,
    ): Long = if (animationType == ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL) {
        SWIPE_SETTLE_MS
    } else {
        super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(
                canvas, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive
            )
            return
        }

        val view = viewHolder.itemView
        val damped = panel.distance(dX, view)

        if (damped != 0F) {
            if (isCurrentlyActive) {
                if (trackedSwipeHolder !== viewHolder) {
                    trackedSwipeHolder = viewHolder
                    armed = -1
                    removalDispatched = false
                    pendingRemovalUid = null
                    panel.reset()
                    adapter.onDragStart()
                }
                val next = panel.armedIndex(damped)
                if (next != armed) {
                    if (next >= 0) Haptics.commit(view)
                    armed = next
                }
            } else if (armed >= 0 && !removalDispatched) {
                pendingRemovalUid = adapter.itemAt(viewHolder.bindingAdapterPosition)?.uid
                removalDispatched = true
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

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)

        when (actionState) {
            ItemTouchHelper.ACTION_STATE_DRAG -> {
                adapter.onDragStart()
                currentDragViewHolder = viewHolder
                viewHolder?.itemView?.let { view ->
                    Haptics.gestureStart(view)
                    view.animate().cancel()
                    QueueDragLift.lift(view)
                    view.animate()
                        .translationZ(QueueDragLift.elevation)
                        .scaleX(QueueDragLift.SCALE)
                        .scaleY(QueueDragLift.SCALE)
                        .setDuration(FASTEST_DURATION)
                        .setInterpolator(AnimationUtils.easingStandardInterpolator)
                        .start()
                }
            }
            ItemTouchHelper.ACTION_STATE_IDLE -> {
                currentDragViewHolder?.itemView?.let(QueueDragLift::settle)
                currentDragViewHolder = null
            }
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        adapter.onDragEnd()

        val removalUid = pendingRemovalUid
        trackedSwipeHolder = null
        armed = -1
        removalDispatched = false
        pendingRemovalUid = null
        panel.reset()
        if (removalUid != null) {
            val position = adapter.indexOf(removalUid)
            if (position >= 0) onRemove(position)
        }

        if (viewHolder.itemView.translationZ != 0f) Haptics.gestureEnd(viewHolder.itemView)
        viewHolder.itemView.animate().cancel()
        viewHolder.itemView.animate()
            .translationZ(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(FASTEST_DURATION)
            // The card is only put away once it has landed: dropping the surface and the corners
            // at the start of the animation makes the row square and flat while it is still
            // visibly moving, which reads as the drag being cancelled rather than committed.
            .withEndAction { QueueDragLift.settle(viewHolder.itemView) }
            .start()
    }

    companion object {
        // Geometry now lives in SwipeActionPanel; only the two ItemTouchHelper knobs remain.
        private const val NEVER_SWIPE_AWAY = 10F
        private const val SWIPE_SETTLE_MS = 190L
    }
}

class QueueDiffCallback(
    private val oldList: List<QueueItem>,
    private val newList: List<QueueItem>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size
    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].uid == newList[newItemPosition].uid
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldMeta = oldList[oldItemPosition].mediaItem.mediaMetadata
        val newMeta = newList[newItemPosition].mediaItem.mediaMetadata
        return oldMeta.title == newMeta.title &&
                oldMeta.artist == newMeta.artist &&
                oldMeta.artworkUri == newMeta.artworkUri &&
                // Included, or a row keeps a heading that has moved on to another track.
                oldList[oldItemPosition].sectionLabel == newList[newItemPosition].sectionLabel &&
                // The current row has no drag handle. Omitting this left the handle state attached
                // to whichever row happened to occupy the position before a track advance.
                oldList[oldItemPosition].isCurrent == newList[newItemPosition].isCurrent
    }
}
