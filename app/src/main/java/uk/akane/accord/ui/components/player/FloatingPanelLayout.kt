package uk.akane.accord.ui.components.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.VelocityTracker
import android.view.WindowInsets
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginStart
import androidx.core.view.marginTop
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import kotlinx.parcelize.Parcelize
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.setOutline
import uk.akane.accord.logic.utils.CalculationUtils.lerp
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.cupertino.popup.PopupMenuHost
import uk.akane.cupertino.widget.dpToPx
import uk.akane.cupertino.widget.image.SimpleImageView
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.ResistiveSwipeHaptics
import uk.akane.accord.ui.components.resistedSwipeDistance
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

class FloatingPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes),
    GestureDetector.OnGestureListener,
    PopupMenuHost {

    private val activity: Activity
        get() = context as Activity

    private val gestureDetector = GestureDetector(context, this)
    val insetController = WindowCompat.getInsetsController(activity.window, this)

    private var fraction: Float = 0F
    private var initialMargin = IntArray(4)

    private var fullScreenView: FullPlayer
    private var previewView: View

    private var flingValueAnimator: ValueAnimator? = null
    private var previewSwipeAnimator: ValueAnimator? = null
    private var penultimateMotionTime = 0L
    private var penultimateMotionY = 0F
    private var lastMotionTime = 0L
    private var lastMotionY = 0F

    private val path = Path()

    private var boundLeft = 0F
    private var boundTop = 0F
    private var boundRight = 0F
    private var boundBottom = 0F

    private var fullLeft = 0F
    private var fullTop = 0F
    private var fullRight = 0F
    private var fullBottom = 0F

    private var previewLeft = 0F
    private var previewTop = 0F
    private var previewRight = 0F
    private var previewBottom = 0F

    private var isDragging = false

    var transitionImageView: SimpleImageView? = null

    var panelCornerRadius = 0F

    private var previewCoverBoxMetrics: Int = 0
    private var previewCoverMarginX: Float = 8.dpToPx(context).toFloat()
    private var previewCoverMarginY: Float = 8.dpToPx(context).toFloat()
    /**
     * Where the bar actually starts, which is not its start margin once it is capped.
     *
     * `preview_player_max_width` bounds the bar and centres what is left over, so on a wide canvas
     * the bar begins far inside its own margin. Reading the margin instead painted the panel's
     * surface - and the blur inside it - across the full width while the bar itself sat centred,
     * leaving unblurred bands sticking out either end of it.
     */
    private var previewViewLeft: Int = 12.dpToPx(context)
    private var previewCoverPaddingPx: Float = (0.5F.dp.px).roundToInt().toFloat()
    private var previewCoverStrokePx: Float = (0.5F.dp.px).roundToInt().toFloat()
    private var previewCoverCornerRadius: Float = 0F

    private var fullCoverX: Int = 0
    private var fullCoverY: Int = 0
    private var fullCoverScale = 1F
    private var lockTransitionCornerRadius = false

    private var state: SlideStatus = SlideStatus.COLLAPSED

    private var onSlideListeners: MutableList<OnSlideListener> = mutableListOf()

    private val contentRenderNode = RenderNode("content").apply {
        clipToOutline = true
    }

    private val popupHelper = PopupHelper(
        context,
        contentRenderNode,
        ResourcesCompat.getFont(context, R.font.inter_regular)
    )

    override val popupHostView: View
        get() = this
    private val popupBackgroundRenderNode = RenderNode("popupBackground")

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.bottomNavigationPanelColor, null)
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)

        inflate(context, R.layout.layout_floating_panel, this)

        fullScreenView = findViewById(R.id.full_player)
        previewView = findViewById(R.id.preview_player)

        doOnLayout {
            panelCornerRadius = resources.getDimensionPixelSize(R.dimen.bottom_panel_radius).toFloat()
            // First init to sync the view location
            updateTransform(fraction)
        }
    }

    fun setupMetrics(metrics: Int, previewCoverView: SimpleImageView) {
        previewCoverBoxMetrics = metrics
        previewCoverMarginX = previewCoverView.left.toFloat()
        previewCoverMarginY = previewCoverView.top.toFloat()
        previewCoverPaddingPx = previewCoverView.paddingLeft.toFloat()
        previewCoverStrokePx = previewCoverView.getStrokeWidth()
        previewCoverCornerRadius = previewCoverView.getCornerRadius().toFloat()
        previewViewLeft = previewView.left
    }

    fun setupTransitionImageView(w: Int, h: Int, mx: Int, mh: Int, bitmap: Bitmap) {
        if (transitionImageView != null) return

        fullCoverX = mx
        fullCoverY = mh

        transitionImageView = SimpleImageView(context).apply {
            id = generateViewId()
            layoutParams = LayoutParams(w, h)
            setImageBitmap(bitmap)
            updateCornerRadius(startRadius.toInt())
        }

        addView(transitionImageView)

        transitionImageView?.let {
            val constraintSet = ConstraintSet()
            constraintSet.clone(this)
            constraintSet.connect(it.id, ConstraintSet.START, previewView.id, ConstraintSet.START, 0)
            constraintSet.connect(it.id, ConstraintSet.TOP, previewView.id, ConstraintSet.TOP, 0)
            constraintSet.applyTo(this)

            it.pivotX = 0F
            it.pivotY = 0F

            it.doOnLayout {
                updateTransitionFraction(fraction)
            }
        }
    }

    fun updateTransitionTarget(targetView: View, targetRadius: Float, lockCornerRadius: Boolean,
                               targetElevation: Float? = null) {
        val imageView = transitionImageView ?: return
        if (imageView.width == 0 || imageView.height == 0) {
            deferTransitionUpdate(imageView) {
                updateTransitionTarget(targetView, targetRadius, lockCornerRadius, targetElevation)
            }
            return
        }
        if (targetView.width == 0 || targetView.height == 0) {
            deferTransitionUpdate(targetView) {
                updateTransitionTarget(targetView, targetRadius, lockCornerRadius, targetElevation)
            }
            return
        }

        var x = 0F
        var y = 0F
        var current: View = targetView
        while (current !== fullScreenView && current.parent is View) {
            x += current.left + current.translationX
            y += current.top + current.translationY
            current = current.parent as View
        }

        fullCoverX = x.toInt()
        fullCoverY = y.toInt()
        fullCoverScale = (targetView.width.toFloat() / imageView.width.toFloat()) * targetView.scaleX

        lockTransitionCornerRadius = lockCornerRadius
        if (lockCornerRadius) {
            transitionStartRadius = targetRadius
            transitionEndRadius = targetRadius
        } else {
            transitionStartRadius = startRadius
            transitionEndRadius = endRadius
        }

        transitionEndElevation = targetElevation ?: targetView.elevation
        updateTransitionFraction(fraction)
    }

    private fun deferTransitionUpdate(view: View, action: () -> Unit) {
        if (view.width > 0 && view.height > 0) {
            action()
            return
        }

        val existing = view.getTag(R.id.transition_update_listener) as? PendingTransitionUpdate
        if (existing != null) {
            existing.action = action
            return
        }

        lateinit var holder: PendingTransitionUpdate
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (v.width == 0 || v.height == 0) return
                v.removeOnLayoutChangeListener(this)
                v.setTag(R.id.transition_update_listener, null)
                holder.action()
            }
        }
        holder = PendingTransitionUpdate(action, listener)
        view.setTag(R.id.transition_update_listener, holder)
        view.addOnLayoutChangeListener(listener)
    }

    private data class PendingTransitionUpdate(
        var action: () -> Unit,
        val listener: View.OnLayoutChangeListener
    )

    private val startPadding = 5F.dp.px
    private val endElevation = 24F.dp.px
    private val startRadius = 36F.dp.px
    private val endRadius = 10F.dp.px
    private val previewCoverStrokeColor = resources.getColor(R.color.coverBorder, null)
    private var transitionStartRadius = startRadius
    private var transitionEndRadius = endRadius
    private var transitionEndElevation = endElevation

    private fun updateTransitionFraction(fraction: Float) {
        transitionImageView?.let {
            if (it.width != 0) {
                val rawDelta = fullScreenView.height - previewView.height - previewView.marginBottom
                val initialScale = previewCoverBoxMetrics / it.width.toFloat()
                val initialTranslationX = previewCoverMarginX * previewView.scaleX - previewViewLeft * fraction
                val scale = lerp(initialScale, fullCoverScale, fraction)

                it.scaleX = scale
                it.scaleY = scale
                it.translationX = lerp(initialTranslationX, fullCoverX - previewViewLeft.toFloat(), fraction)
                it.translationY = lerp(previewCoverMarginY, -rawDelta.toFloat() + fullCoverY, fraction)

                val targetVisualPadding = previewCoverPaddingPx
                val targetVisualStroke = previewCoverStrokePx
                val visualPadding = lerp(targetVisualPadding, 0F, fraction)
                val visualStroke = lerp(targetVisualStroke, 0F, fraction)
                val scaledPadding = if (scale > 0F) visualPadding / scale else 0F
                val scaledStroke = if (scale > 0F) visualStroke / scale else 0F
                it.setPadding(scaledPadding.roundToInt())
                it.setStroke(scaledStroke, previewCoverStrokeColor)

                it.elevation = lerp(0F, transitionEndElevation, fraction)
                val cornerRadius = if (scale > 0F) {
                    val startVisualRadius = if (previewCoverCornerRadius > 0F) {
                        previewCoverCornerRadius
                    } else {
                        transitionStartRadius
                    }
                    val endVisualRadius = if (lockTransitionCornerRadius) {
                        transitionStartRadius
                    } else {
                        transitionEndRadius
                    }
                    lerp(startVisualRadius, endVisualRadius, fraction) / scale
                } else {
                    0F
                }
                it.updateCornerRadius(cornerRadius.toInt())
            }
        }
    }

    /** The geometry [updateTransform] last ran against, so a re-layout can tell it has gone stale. */
    private var lastPreviewHeight = -1
    private var lastPreviewMarginBottom = -1
    private var lastFullHeight = -1
    private var lastFullWidth = -1

    /**
     * Re-derives the panel's resting shape whenever the sizes it is built from change.
     *
     * Every position in [updateTransform] comes from the preview's height and bottom margin and
     * the full player's size, and none of those are final on the first layout pass: the window
     * insets have not landed, so the bar does not yet know how much room the navigation bar and
     * the gesture area leave it. That first pass was the only one that ever ran, so the painted
     * surface kept the shape of a screen that no longer existed while the controls inside it were
     * laid out against the real one - the two disagreeing is what the bar looked mangled. Dragging
     * the panel recomputed everything, which is why opening and closing it put things right.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (previewView.height == lastPreviewHeight &&
            previewView.marginBottom == lastPreviewMarginBottom &&
            fullScreenView.height == lastFullHeight &&
            fullScreenView.width == lastFullWidth
        ) {
            return
        }
        lastPreviewHeight = previewView.height
        lastPreviewMarginBottom = previewView.marginBottom
        lastFullHeight = fullScreenView.height
        lastFullWidth = fullScreenView.width
        // Only transforms and an invalidate come out of this, so it cannot ask for another layout.
        updateTransform(fraction, force = true)
    }

    private fun updateTransform(newFraction: Float, force: Boolean = false) {
        if (!force && newFraction == fraction && fraction != 0F && fraction != 1F) return
        fraction = newFraction

        /*
         * Nothing has been measured yet.
         *
         * The scales below are ratios of these two widths, and on the layout pass that runs before
         * either view has a width that ratio is 0/0 - NaN. `View.setScaleX` does not tolerate NaN,
         * it throws `IllegalArgumentException: Cannot set 'scaleX' to Float.NaN`, and since this
         * runs from `onLayout` the throw takes the activity down before the player is ever drawn.
         *
         * Returning costs nothing: `onLayout` only calls this when one of the cached dimensions
         * changed, so the pass that gives these views a real width calls it again with
         * force = true.
         */
        if (previewView.width == 0 || fullScreenView.width == 0) return

        val deltaY = lerp(0f, (fullScreenView.height - previewView.height - previewView.marginBottom).toFloat(), fraction)

        // Preview
        previewView.scaleX = lerp(1f, fullScreenView.width.toFloat() / previewView.width, fraction)
        previewView.scaleY = previewView.scaleX
        val previewGestureX = if (fraction == 0F) previewSwipeOffsetX else 0F
        val previewGestureY = if (fraction == 0F) previewSwipeOffsetY else 0F
        previewView.translationX = previewGestureX
        previewView.translationY = -deltaY + previewGestureY

        updateTransitionFraction(fraction)

        // Full
        fullScreenView.scaleX = (previewView.width * previewView.scaleX) / fullScreenView.width
        fullScreenView.scaleY = fullScreenView.scaleX
        fullScreenView.translationY = (fullScreenView.height - previewView.marginBottom - previewView.height - deltaY)
        fullScreenView.pivotY = 0f
        fullScreenView.pivotX = fullScreenView.width / 2f

        // The bar's own edges, not the margins it was asked for: once it is capped by
        // `preview_player_max_width` the leftover space is split either side of it, and painting
        // the panel from margin to margin drew a surface wider than the bar it is meant to be.
        previewLeft = previewView.left.toFloat() + previewGestureX
        previewTop = (fullScreenView.height - previewView.height - previewView.marginBottom).toFloat() + previewGestureY
        previewRight = previewView.right.toFloat() + previewGestureX
        previewBottom = fullScreenView.height.toFloat() - previewView.marginBottom + previewGestureY

        fullLeft = 0f
        fullTop = 0f
        fullRight = fullScreenView.width.toFloat()
        fullBottom = fullScreenView.height.toFloat()

        boundLeft = lerp(previewLeft, fullLeft, fraction)
        boundTop = lerp(previewTop, fullTop, fraction)
        boundRight = lerp(previewRight, fullRight, fraction)
        boundBottom = lerp(previewBottom, fullBottom, fraction)

        path.reset()
        path.addRoundRect(
            boundLeft, boundTop, boundRight, boundBottom,
            panelCornerRadius,
            panelCornerRadius,
            Path.Direction.CW
        )

        contentRenderNode.setOutline(
            boundLeft.toInt(), boundTop.toInt(), boundRight.toInt(), boundBottom.toInt(),
            panelCornerRadius
        )

        previewView.alpha = lerp(1f, 0f, fraction * 2f)
        fullScreenView.alpha = lerp(0f, 1f, fraction * 2f)

        updateImmersiveForFraction(fraction)

        invalidate()
        triggerSlide(fraction)
    }

    private fun isInsideBoundingBox(x: Float, y: Float): Boolean {
        return x in boundLeft..boundRight && y in boundTop..boundBottom
    }

    override fun dispatchDraw(canvas: Canvas) {
        contentRenderNode.setPosition(0, 0, width, height)
        val recordingCanvas = contentRenderNode.beginRecording(width, height)
        recordingCanvas.drawPath(path, shadowPaint)
        super.dispatchDraw(recordingCanvas)
        contentRenderNode.endRecording()

        canvas.drawRenderNode(contentRenderNode)

        popupHelper.drawPopup(canvas)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        updatePreviewReleaseVelocity(ev)
        val handled = super.dispatchTouchEvent(ev)
        var recoveredPreviewGesture = false

        // A child, the system navigation gesture, or a parent can consume the terminal event after
        // this layout has already started moving the mini player. Keep a final safety net at the
        // dispatch boundary so a swipe can never be left translated with no gesture owner.
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> if (previewSwipeAxis != PreviewSwipeAxis.NONE) {
                previewChildGestureActive = false
                previewChildGestureCandidate = false
                endPreviewSwipeIfActive()
                recoveredPreviewGesture = true
            }

            MotionEvent.ACTION_CANCEL -> if (hasActivePreviewSwipe()) {
                cancelPreviewSwipeGesture()
                recoveredPreviewGesture = true
            }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            previewVelocityTracker?.recycle()
            previewVelocityTracker = null
        }
        return handled || recoveredPreviewGesture
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        if (popupHelper.transformFraction == 1F) return true
        if (ev == null || state != SlideStatus.COLLAPSED) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewChildGestureCandidate =
                    ev.x in previewLeft..previewRight && ev.y in previewTop..previewBottom
                previewChildGestureActive = false
                previewTouchDownX = ev.x
                previewTouchDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> if (previewChildGestureCandidate) {
                val dx = ev.x - previewTouchDownX
                val dy = ev.y - previewTouchDownY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    val axis = when {
                        abs(dx) > abs(dy) -> PreviewSwipeAxis.HORIZONTAL
                        dy > 0F -> PreviewSwipeAxis.DOWN
                        else -> PreviewSwipeAxis.NONE
                    }
                    if (axis != PreviewSwipeAxis.NONE) {
                        previewSwipeAxis = axis
                        previewChildGestureActive = true
                        updatePreviewSwipeOffsets(dx, dy)
                        return true
                    }
                    previewChildGestureCandidate = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Once interception starts, onTouchEvent must receive the matching terminal event.
                // Clearing ownership here used to leave the mini player at its last drag offset.
                if (previewChildGestureActive) return true
                previewChildGestureCandidate = false
            }
        }
        return false
    }

    /**
     * Closes the popup menu if one is open.
     *
     * Upstream only ever dismisses it by tapping outside, so it stayed on screen while the app
     * navigated somewhere else and hung over whatever screen came next.
     */
    fun dismissPopupMenu() {
        popupEntryClickListener = null
        if (popupHelper.transformFraction == 0F) return
        popupHelper.callUpPopup(
            true,
            null,
            invalidate = { invalidate() },
            doOnStart = { fullScreenView.freeze() },
            doOnEnd = { fullScreenView.unfreeze() }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (popupHelper.transformFraction == 1F &&
            !popupHelper.isInsidePopupMenu(event.x, event.y)) {
            // Through dismissPopupMenu, not the helper directly: tapping outside used to retract the
            // menu while leaving popupEntryClickListener set, and this layout outlives the bar that
            // opened it, so the listener kept the whole departed screen alive.
            dismissPopupMenu()
            return true
        }
        // A touch inside an open popup used to be swallowed by the guard below, so every entry in
        // the menu was decoration - the helper already knows which entry a point falls in and can
        // draw it pressed; nothing was asking it.
        if (popupHelper.transformFraction == 1F) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    if (popupHelper.updatePressedEntry(event.x, event.y)) invalidate()

                MotionEvent.ACTION_UP -> {
                    val entry = popupHelper.findEntryAt(event.x, event.y)
                    // Read before dismissing, which clears it.
                    val listener = popupEntryClickListener
                    if (popupHelper.clearPressedEntry()) invalidate()
                    dismissPopupMenu()
                    // After the dismissal, so the menu is on its way out as the action runs rather
                    // than still covering whatever the action opens.
                    entry?.let { listener?.invoke(it) }
                }

                MotionEvent.ACTION_CANCEL ->
                    if (popupHelper.clearPressedEntry()) invalidate()
            }
            return true
        }
        if (popupHelper.transformFraction != 0F) return true
        // When a drag begins on the play/next buttons, interception starts after touch slop so a
        // plain tap still belongs to the button. From that point this direct path owns the drag;
        // GestureDetector did not receive its ACTION_DOWN and therefore cannot reconstruct it.
        if (previewChildGestureActive) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> updatePreviewSwipeOffsets(
                    event.x - previewTouchDownX,
                    event.y - previewTouchDownY
                )
                MotionEvent.ACTION_UP -> {
                    updatePreviewSwipeOffsets(
                        event.x - previewTouchDownX,
                        event.y - previewTouchDownY
                    )
                    previewChildGestureActive = false
                    previewChildGestureCandidate = false
                    endPreviewSwipeIfActive()
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelPreviewSwipeGesture()
                }
            }
            return true
        }
        return if (isInsideBoundingBox(event.x, event.y) || isDragging) {
            if (gestureDetector.onTouchEvent(event)) {
                true
            } else if (event.action == MotionEvent.ACTION_UP) {
                onUp()
                true
            } else {
                super.onTouchEvent(event)
            }
        } else {
            false
        }
    }

    override fun dispatchApplyWindowInsets(platformInsets: WindowInsets): WindowInsets {
        if (initialMargin[3] != 0) return super.dispatchApplyWindowInsets(platformInsets)
        val insets = WindowInsetsCompat.toWindowInsetsCompat(platformInsets)
        val floatingInsets = insets.getInsets(
            WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
        )
        if (floatingInsets.bottom != 0) {
            initialMargin = intArrayOf(
                previewView.marginLeft,
                previewView.marginTop,
                previewView.marginRight,
                previewView.marginBottom + floatingInsets.bottom
            )
            previewView.updateLayoutParams<MarginLayoutParams> {
                bottomMargin = initialMargin[3]
            }
        }
        return super.dispatchApplyWindowInsets(platformInsets)
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean {
        // A fling ends the gesture without onUp() ever running, so the artwork swipe has to be let
        // go of here too or the cover stays wherever the finger left it.
        if (endCoverSwipeIfActive(velocityX) ||
            endPreviewSwipeIfActive(velocityX, velocityY)
        ) return true
        coverSwipeRejected = false
        isDragging = false
        val isSlidingUp = (penultimateMotionY - lastMotionY) > 0
        val lastVelocity = -(lastMotionY - penultimateMotionY) / (lastMotionTime - penultimateMotionTime) * SPEED_FACTOR
        val supposedDuration =
            ((fullTop - previewTop) / lastVelocity)
                .toLong()
                .absoluteValue
                .coerceIn(MINIMUM_ANIMATION_TIME, MAXIMUM_ANIMATION_TIME)

        if (state == SlideStatus.SLIDING) {
            flingValueAnimator?.cancel()
            flingValueAnimator = null

            ValueAnimator.ofFloat(
                fraction,
                if (isSlidingUp) 1.0F else 0F
            ).apply {
                flingValueAnimator = this
                interpolator = AnimationUtils.easingStandardInterpolator
                duration = supposedDuration

                addUpdateListener {
                    updateTransform(animatedValue as Float)
                }

                start()
            }
        }
        return true
    }

    override fun onLongPress(e: MotionEvent) {
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (handlePreviewSwipe(e1, e2)) return true

        // A sideways drag that began on the artwork belongs to the player, not to this panel. It has
        // to be decided here rather than by a touch listener on the artwork itself: this panel owns
        // the gesture from the moment it starts, and a child that consumed the press to watch for a
        // swipe would take drag-to-collapse away from the whole cover.
        val handler = coverSwipeHandler
        if (handler != null && !coverSwipeRejected && e1 != null) {
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (!coverSwipeActive) {
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) &&
                    handler.coverBounds()?.contains(e1.x, e1.y) == true
                ) {
                    coverSwipeActive = true
                } else if (abs(dy) > touchSlop) {
                    // Committed to a vertical drag; do not reconsider for the rest of the gesture.
                    coverSwipeRejected = true
                }
            }
            if (coverSwipeActive) {
                coverSwipeDx = dx
                handler.onCoverSwipeMove(dx)
                return true
            }
        }

        isDragging = true

        flingValueAnimator?.cancel()
        flingValueAnimator = null

        val deltaY = - distanceY / (fullTop - previewTop)
        if (fraction + deltaY !in 0F..1F) { return true }

        updateTransform(fraction + deltaY)

        penultimateMotionY = lastMotionY
        penultimateMotionTime = lastMotionTime
        lastMotionY = e2.y
        lastMotionTime = e2.eventTime

        return true
    }

    override fun onShowPress(e: MotionEvent) {}

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        if (state == SlideStatus.COLLAPSED) {
            flingValueAnimator?.cancel()
            flingValueAnimator = null

            ValueAnimator.ofFloat(
                fraction,
                1F
            ).apply {
                flingValueAnimator = this
                duration = (MAXIMUM_ANIMATION_TIME + MINIMUM_ANIMATION_TIME) / 2
                interpolator = AnimationUtils.easingStandardInterpolator

                addUpdateListener {
                    updateTransform(animatedValue as Float)
                }

                start()
            }
        } else if (state == SlideStatus.EXPANDED) {
            // Empty parts of the expanded player (most notably the artwork) do not consume their
            // ACTION_DOWN, so this panel owns the completed tap. Hand it back to the player after
            // GestureDetector has proved it was not a cover swipe or a collapse drag.
            coverSwipeHandler?.onPlayerSurfaceTap(e.x, e.y)
        }
        return true
    }

    private fun onUp() {
        if (endCoverSwipeIfActive() || endPreviewSwipeIfActive()) return
        if (isDragging) {
            flingValueAnimator?.cancel()
            flingValueAnimator = null

            ValueAnimator.ofFloat(
                fraction,
                if (penultimateMotionY - lastMotionY > 0) 1.0F else 0F
            ).apply {
                flingValueAnimator = this
                duration = (MAXIMUM_ANIMATION_TIME + MINIMUM_ANIMATION_TIME) / 2
                interpolator = AnimationUtils.easingStandardInterpolator

                addUpdateListener {
                    updateTransform(animatedValue as Float)
                }

                start()
            }
        }

        isDragging = false
    }

    private fun triggerSlide(progress: Float) {
        onSlideListeners.forEach {
            it.onSlide(progress)
        }
        val prevState = state
        state = when (progress) {
            1.0F -> {
                transitionImageView?.visibility = INVISIBLE
                SlideStatus.EXPANDED
            }
            0.0F -> {
                transitionImageView?.visibility = INVISIBLE
                SlideStatus.COLLAPSED
            }
            else -> {
                transitionImageView?.visibility = VISIBLE
                SlideStatus.SLIDING
            }
        }
        if (prevState != state) {
            onSlideListeners.forEach { it.onSlideStatusChanged(state) }
        }
    }

    private var statusBarHidden = false

    /**
     * The open player owns the screen, so the status bar steps out of it.
     *
     * Taking the bar away re-lays out everything behind the panel, and the page under it is
     * headed by a large title sitting right where that inset is: doing this at the two resting
     * states meant the title visibly jumped up as the player finished opening and dropped back as
     * it finished closing, in full view both times. It happens while the panel still covers the
     * screen instead - the same crossing in both directions, so the reflow lands behind the panel
     * and the title is already where it belongs by the time anything uncovers it.
     *
     * The two thresholds are deliberately apart: a drag held around a single one would toggle the
     * bar, and the window, back and forth under the finger.
     */
    private fun updateImmersiveForFraction(fraction: Float) {
        val hidden = when {
            fraction >= IMMERSIVE_HIDE_FRACTION -> true
            fraction <= IMMERSIVE_SHOW_FRACTION -> false
            else -> return
        }
        if (hidden == statusBarHidden) return
        statusBarHidden = hidden
        if (hidden) {
            // Transient rather than a lock: a swipe from the top still brings the bars back
            // without having to leave the player, and that first swipe reveals them rather than
            // pulling the notification shade down over the player.
            insetController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // Both bars, not just the status bar. The expanded player owns the screen, and leaving
            // the navigation bar up meant the gesture area kept its own surface at the bottom
            // while the top had gone edge to edge.
            insetController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // Not an unconditional show. The app has an immersive setting of its own, and putting
            // the bars back merely because the player closed took them away from a user who had
            // asked for them to be gone - opening the now playing screen and closing it again was
            // enough to undo the preference until something else re-asserted it. The activity owns
            // that decision, so hand it back rather than guessing here.
            (activity as? MainActivity)?.applySystemBarMode()
                ?: insetController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    enum class SlideStatus {
        COLLAPSED, EXPANDED, SLIDING
    }

    interface OnSlideListener {
        fun onSlideStatusChanged(status: SlideStatus)
        fun onSlide(value: Float)
    }

    val slideFraction: Float
        get() = fraction

    val slideStatus: SlideStatus
        get() = state

    fun addOnSlideListener(listener: OnSlideListener) {
        onSlideListeners.add(listener)
    }

    fun setSlideFraction(value: Float) {
        val targetFraction = value.coerceIn(0F, 1F)
        flingValueAnimator?.cancel()
        flingValueAnimator = null
        isDragging = false
        updateTransform(targetFraction)
    }

    fun animateTo(targetFraction: Float, duration: Long = DEFAULT_ANIMATION_DURATION) {
        val clampedTarget = targetFraction.coerceIn(0F, 1F)
        if (clampedTarget == fraction) return
        flingValueAnimator?.cancel()
        flingValueAnimator = null

        ValueAnimator.ofFloat(fraction, clampedTarget).apply {
            flingValueAnimator = this
            this.duration = duration
            interpolator = AnimationUtils.easingStandardInterpolator

            addUpdateListener {
                updateTransform(animatedValue as Float)
            }

            start()
        }
    }

    fun collapse(animate: Boolean = true) {
        if (animate) {
            animateTo(0F)
        } else {
            setSlideFraction(0F)
        }
    }

    fun expand(animate: Boolean = true) {
        if (animate) {
            animateTo(1F)
        } else {
            setSlideFraction(1F)
        }
    }

    /** Opens a tablet bar destination after the shared artwork has finished expanding. */
    fun expandTo(contentType: FullPlayer.ContentType) {
        expand(animate = true)
        postDelayed(
            { fullScreenView.showContentFromPreview(contentType) },
            DEFAULT_ANIMATION_DURATION,
        )
    }

    fun showPlayerPopupFromPreview(anchor: View) {
        fullScreenView.showPopupFromPreview(anchor)
    }

    fun setPreviewCover(drawable: Drawable?) {
        (previewView as? PreviewPlayer)?.setCover(drawable)
    }

    /** Makes a dismissed mini player available again as soon as a new queue is started. */
    fun showForPlayback() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE
        previewSwipeHaptics.reset()
        previewSwipeAnimator?.cancel()
        previewSwipeAnimator = null
        previewSwipeOffsetX = 0F
        previewSwipeOffsetY = 0F
        if (fraction != 0F) fraction = 0F
        updateTransform(0F)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && hasActivePreviewSwipe()) resetPreviewSwipeImmediately()
    }

    override fun onDetachedFromWindow() {
        resetPreviewSwipeImmediately()
        onSlideListeners.clear()
        removeView(transitionImageView)
        transitionImageView = null
        super.onDetachedFromWindow()
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        return SavedState(superState, fraction)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            fraction = state.savedValue
            doOnLayout {
                updateTransform(fraction)
            }
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private fun recordPopupBackground(backgroundView: View): RenderNode? {
        if (width == 0 || height == 0 || backgroundView.width == 0 || backgroundView.height == 0) {
            return null
        }

        popupBackgroundRenderNode.setPosition(0, 0, width, height)
        val recordingCanvas = popupBackgroundRenderNode.beginRecording(width, height)

        val backgroundLocation = IntArray(2)
        val containerLocation = IntArray(2)
        backgroundView.getLocationOnScreen(backgroundLocation)
        getLocationOnScreen(containerLocation)

        val offsetX = backgroundLocation[0] - containerLocation[0]
        val offsetY = backgroundLocation[1] - containerLocation[1]

        recordingCanvas.translate(offsetX.toFloat(), offsetY.toFloat())
        backgroundView.draw(recordingCanvas)

        popupBackgroundRenderNode.endRecording()
        return popupBackgroundRenderNode
    }

    /** What to run when an entry in the open popup is tapped; see [onTouchEvent]. */
    private var popupEntryClickListener: ((PopupHelper.PopupEntry) -> Unit)? = null

    /**
     * Lets the player claim sideways drags that start on the artwork. See [onScroll] for why this
     * cannot simply be a touch listener on the artwork.
     */
    interface CoverSwipeHandler {
        /** Where the artwork is, in this panel's coordinates, or null when it cannot be swiped. */
        fun coverBounds(): RectF?

        /** A completed, non-drag tap on otherwise non-interactive expanded-player space. */
        fun onPlayerSurfaceTap(x: Float, y: Float)

        /** Called continuously with the distance dragged from where the finger went down. */
        fun onCoverSwipeMove(dx: Float)

        /** Called once when the finger lifts, with the final distance. */
        fun onCoverSwipeEnd(dx: Float, velocityX: Float)
    }

    var coverSwipeHandler: CoverSwipeHandler? = null

    private var coverSwipeActive = false
    private var coverSwipeRejected = false
    private var coverSwipeDx = 0F
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minimumFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private enum class PreviewSwipeAxis { NONE, HORIZONTAL, DOWN }

    private var previewSwipeAxis = PreviewSwipeAxis.NONE
    private var previewSwipeRejected = false
    private var previewSwipeRawX = 0F
    private var previewSwipeRawY = 0F
    private var previewSwipeOffsetX = 0F
    private var previewSwipeOffsetY = 0F
    private var previewChildGestureCandidate = false
    private var previewChildGestureActive = false
    private var previewTouchDownX = 0F
    private var previewTouchDownY = 0F
    private val previewSwipeHaptics = ResistiveSwipeHaptics()
    private var previewVelocityTracker: VelocityTracker? = null
    private var previewReleaseVelocityX = 0F
    private var previewReleaseVelocityY = 0F

    private fun updatePreviewReleaseVelocity(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewVelocityTracker?.recycle()
                previewVelocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                previewReleaseVelocityX = 0F
                previewReleaseVelocityY = 0F
            }

            MotionEvent.ACTION_MOVE -> previewVelocityTracker?.addMovement(event)

            MotionEvent.ACTION_UP -> previewVelocityTracker?.let {
                it.addMovement(event)
                it.computeCurrentVelocity(1000)
                previewReleaseVelocityX = it.xVelocity
                previewReleaseVelocityY = it.yVelocity
            }

            MotionEvent.ACTION_CANCEL -> {
                previewReleaseVelocityX = 0F
                previewReleaseVelocityY = 0F
            }
        }
    }

    private fun hasActivePreviewSwipe(): Boolean =
        previewSwipeAxis != PreviewSwipeAxis.NONE || previewChildGestureActive

    private fun cancelPreviewSwipeGesture() {
        previewSwipeHaptics.release(previewView)
        previewChildGestureCandidate = false
        previewChildGestureActive = false
        previewSwipeRejected = false
        previewSwipeAxis = PreviewSwipeAxis.NONE
        previewSwipeRawX = 0F
        previewSwipeRawY = 0F
        previewReleaseVelocityX = 0F
        previewReleaseVelocityY = 0F
        isDragging = false
        animatePreviewSwipe(0F, 0F)
    }

    private fun resetPreviewSwipeImmediately() {
        previewSwipeHaptics.reset()
        previewChildGestureCandidate = false
        previewChildGestureActive = false
        previewSwipeRejected = false
        previewSwipeAxis = PreviewSwipeAxis.NONE
        previewSwipeRawX = 0F
        previewSwipeRawY = 0F
        previewReleaseVelocityX = 0F
        previewReleaseVelocityY = 0F
        isDragging = false
        previewSwipeAnimator?.cancel()
        previewSwipeAnimator = null
        previewSwipeOffsetX = 0F
        previewSwipeOffsetY = 0F
        updateTransform(fraction)
    }

    /** @return true when a swipe was in progress and has now been handed back to the player. */
    private fun endCoverSwipeIfActive(velocityX: Float = previewReleaseVelocityX): Boolean {
        coverSwipeRejected = false
        if (!coverSwipeActive) return false
        coverSwipeActive = false
        isDragging = false
        coverSwipeHandler?.onCoverSwipeEnd(coverSwipeDx, velocityX)
        coverSwipeDx = 0F
        return true
    }

    /**
     * The collapsed player is itself a carousel: left advances, right goes back, and a downward
     * pull dismisses playback. Offsets are damped so the bar resists rather than leaving the finger.
     */
    private fun handlePreviewSwipe(e1: MotionEvent?, e2: MotionEvent): Boolean {
        if (state != SlideStatus.COLLAPSED || e1 == null || previewSwipeRejected) return false
        val startInsidePreview = e1.x in previewLeft..previewRight && e1.y in previewTop..previewBottom
        if (!startInsidePreview) return false

        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        if (previewSwipeAxis == PreviewSwipeAxis.NONE) {
            if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return false
            previewSwipeAxis = when {
                abs(dx) > abs(dy) -> PreviewSwipeAxis.HORIZONTAL
                dy > 0F -> PreviewSwipeAxis.DOWN
                else -> {
                    // An upward drag still expands the full player.
                    previewSwipeRejected = true
                    return false
                }
            }
        }

        updatePreviewSwipeOffsets(dx, dy)
        return true
    }

    private fun updatePreviewSwipeOffsets(dx: Float, dy: Float) {
        previewSwipeRawX = dx
        previewSwipeRawY = dy
        when (previewSwipeAxis) {
            PreviewSwipeAxis.HORIZONTAL -> {
                val limit = previewView.width * PREVIEW_MAX_HORIZONTAL_TRAVEL
                val player = (activity as? MainActivity)?.getPlayer()
                val actionAvailable = when {
                    dx < 0F -> player?.hasNextMediaItem() == true
                    dx > 0F -> player?.hasPreviousMediaItem() == true
                    else -> false
                }
                previewSwipeHaptics.update(
                    previewView,
                    dx,
                    previewView.width * PREVIEW_HORIZONTAL_THRESHOLD,
                    actionAvailable,
                )
                previewSwipeOffsetX = resistedSwipeDistance(dx, limit, PREVIEW_SWIPE_FOLLOW)
                previewSwipeOffsetY = 0F
            }
            PreviewSwipeAxis.DOWN -> {
                previewSwipeOffsetX = 0F
                val limit = previewView.height * PREVIEW_MAX_DOWN_TRAVEL
                val downDistance = dy.coerceAtLeast(0F)
                previewSwipeHaptics.update(
                    previewView,
                    downDistance,
                    previewView.height * PREVIEW_DOWN_THRESHOLD,
                )
                previewSwipeOffsetY = resistedSwipeDistance(
                    downDistance,
                    limit,
                    PREVIEW_SWIPE_FOLLOW,
                )
            }
            PreviewSwipeAxis.NONE -> return
        }
        previewSwipeAnimator?.cancel()
        previewSwipeAnimator = null
        updateTransform(0F)
    }

    /** @return true when a collapsed-player swipe was active and has been completed. */
    private fun endPreviewSwipeIfActive(
        velocityX: Float = previewReleaseVelocityX,
        velocityY: Float = previewReleaseVelocityY,
    ): Boolean {
        previewSwipeRejected = false
        val axis = previewSwipeAxis
        if (axis == PreviewSwipeAxis.NONE) return false
        previewSwipeAxis = PreviewSwipeAxis.NONE
        isDragging = false

        val player = (activity as? MainActivity)?.getPlayer()
        val flingThreshold = minimumFlingVelocity * PREVIEW_FLING_VELOCITY_MULTIPLIER
        when (axis) {
            PreviewSwipeAxis.HORIZONTAL -> {
                val hasFling = abs(velocityX) >= flingThreshold
                val releaseDirection = if (hasFling) velocityX else previewSwipeRawX
                val distanceReached =
                    abs(previewSwipeRawX) >= previewView.width * PREVIEW_HORIZONTAL_THRESHOLD
                val canMove = when {
                    releaseDirection < 0F -> player?.hasNextMediaItem() == true
                    releaseDirection > 0F -> player?.hasPreviousMediaItem() == true
                    else -> false
                }
                val committed = (distanceReached || hasFling) && canMove
                if (committed) {
                    if (releaseDirection < 0F) {
                        previewSwipeHaptics.commit(previewView)
                        player?.seekToNextMediaItem()
                    } else {
                        previewSwipeHaptics.commit(previewView)
                        player?.seekToPreviousMediaItem()
                    }
                    val momentumTarget = if (releaseDirection < 0F) {
                        -previewView.width * PREVIEW_MAX_HORIZONTAL_TRAVEL
                    } else {
                        previewView.width * PREVIEW_MAX_HORIZONTAL_TRAVEL
                    }
                    animatePreviewSwipe(momentumTarget, 0F, PREVIEW_MOMENTUM_MS) {
                        animatePreviewSwipe(0F, 0F, PREVIEW_SETTLE_MS)
                    }
                } else {
                    previewSwipeHaptics.release(previewView)
                    val projectedRaw = previewSwipeRawX +
                        velocityX * PREVIEW_MOMENTUM_PROJECTION_SECONDS
                    val projectedOffset = resistedSwipeDistance(
                        projectedRaw,
                        previewView.width * PREVIEW_MAX_HORIZONTAL_TRAVEL,
                        PREVIEW_SWIPE_FOLLOW,
                    )
                    if (abs(projectedOffset) > abs(previewSwipeOffsetX)) {
                        animatePreviewSwipe(projectedOffset, 0F, PREVIEW_MOMENTUM_MS) {
                            animatePreviewSwipe(0F, 0F, PREVIEW_SETTLE_MS)
                        }
                    } else {
                        animatePreviewSwipe(0F, 0F, PREVIEW_SETTLE_MS)
                    }
                }
            }
            PreviewSwipeAxis.DOWN -> {
                val hasDownFling = velocityY >= flingThreshold
                if (previewSwipeRawY >= previewView.height * PREVIEW_DOWN_THRESHOLD || hasDownFling) {
                    previewSwipeHaptics.commit(previewView)
                    val dismissalTarget = previewView.height + previewView.marginBottom.toFloat()
                    val remaining = (dismissalTarget - previewSwipeOffsetY).coerceAtLeast(0F)
                    val duration = if (velocityY > 0F) {
                        ((remaining / velocityY) * 1000F).toLong()
                            .coerceIn(PREVIEW_DISMISS_MIN_MS, PREVIEW_DISMISS_MAX_MS)
                    } else {
                        PREVIEW_DISMISS_MAX_MS
                    }
                    animatePreviewSwipe(0F, dismissalTarget, duration) {
                        player?.stop()
                        player?.clearMediaItems()
                        visibility = GONE
                        previewSwipeOffsetX = 0F
                        previewSwipeOffsetY = 0F
                    }
                } else {
                    previewSwipeHaptics.release(previewView)
                    val projectedRaw = (previewSwipeRawY +
                        velocityY.coerceAtLeast(0F) * PREVIEW_MOMENTUM_PROJECTION_SECONDS)
                        .coerceAtLeast(0F)
                    val projectedOffset = resistedSwipeDistance(
                        projectedRaw,
                        previewView.height * PREVIEW_MAX_DOWN_TRAVEL,
                        PREVIEW_SWIPE_FOLLOW,
                    )
                    if (projectedOffset > previewSwipeOffsetY) {
                        animatePreviewSwipe(0F, projectedOffset, PREVIEW_MOMENTUM_MS) {
                            animatePreviewSwipe(0F, 0F, PREVIEW_SETTLE_MS)
                        }
                    } else {
                        animatePreviewSwipe(0F, 0F, PREVIEW_SETTLE_MS)
                    }
                }
            }
            PreviewSwipeAxis.NONE -> Unit
        }
        previewSwipeRawX = 0F
        previewSwipeRawY = 0F
        previewReleaseVelocityX = 0F
        previewReleaseVelocityY = 0F
        return true
    }

    private fun animatePreviewSwipe(
        targetX: Float,
        targetY: Float,
        animationDuration: Long = DEFAULT_ANIMATION_DURATION,
        onEnd: (() -> Unit)? = null,
    ) {
        previewSwipeAnimator?.cancel()
        val startX = previewSwipeOffsetX
        val startY = previewSwipeOffsetY
        previewSwipeAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
            duration = animationDuration
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                previewSwipeOffsetX = lerp(startX, targetX, progress)
                previewSwipeOffsetY = lerp(startY, targetY, progress)
                updateTransform(0F)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var wasCancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (previewSwipeAnimator === animation) previewSwipeAnimator = null
                    // A new drag cancels the previous settle. Its old dismissal callback must not
                    // stop playback or hide the mini player after the new gesture has taken over.
                    if (!wasCancelled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    fun callUpPopup(
        entryList: PopupHelper.PopupEntries,
        locationX: Int,
        locationY: Int,
        anchorFromTop: Boolean = false,
        backgroundView: View? = null,
        dismissAction: (() -> Unit)? = null,
        entryClickListener: ((PopupHelper.PopupEntry) -> Unit)? = null
    ) {
        // The helper drops requests that arrive mid-animation, telling the caller by way of the
        // dismiss action. Take the same exit before storing the listener, or one for a popup that
        // never opened would sit here holding its owner until some later popup replaced it.
        if (popupHelper.transformFraction != 0F && popupHelper.transformFraction != 1F) {
            dismissAction?.invoke()
            return
        }
        popupEntryClickListener = entryClickListener
        val backgroundRenderNode = backgroundView?.let { recordPopupBackground(it) }
        popupHelper.callUpPopup(
            false,
            entryList,
            locationX,
            locationY,
            anchorFromTop,
            backgroundRenderNode = backgroundRenderNode,
            dismissAction = dismissAction,
            invalidate = {
                invalidate()
            },
            doOnStart = {
                fullScreenView.freeze()
            },
            doOnEnd = {
                fullScreenView.unfreeze()
            }
        )
    }

    override fun showPopupMenu(
        entries: PopupHelper.PopupEntries,
        locationX: Int,
        locationY: Int,
        anchorFromTop: Boolean,
        backgroundView: View?,
        onDismiss: (() -> Unit)?,
        onEntryClick: ((PopupHelper.PopupEntry) -> Unit)?
    ) {
        callUpPopup(
            entries, locationX, locationY, anchorFromTop, backgroundView, onDismiss, onEntryClick
        )
    }

    @Suppress("CanBeParameter")
    @Parcelize
    private class SavedState(val superStateInternal: Parcelable?, val savedValue: Float) : BaseSavedState(superStateInternal)

    companion object {
        /**
         * How far open the panel is when the status bar changes. High enough that the panel has
         * the page behind it covered, so the reflow that follows is never seen.
         */
        private const val IMMERSIVE_HIDE_FRACTION = 0.94F
        private const val IMMERSIVE_SHOW_FRACTION = 0.86F

        const val MINIMUM_ANIMATION_TIME = 220L
        const val MAXIMUM_ANIMATION_TIME = 320L
        const val SPEED_FACTOR = 2F
        const val DEFAULT_ANIMATION_DURATION = (MINIMUM_ANIMATION_TIME + MAXIMUM_ANIMATION_TIME) / 2
        private const val PREVIEW_SWIPE_FOLLOW = 0.56F
        private const val PREVIEW_HORIZONTAL_THRESHOLD = 0.32F
        private const val PREVIEW_DOWN_THRESHOLD = 0.68F
        private const val PREVIEW_MAX_HORIZONTAL_TRAVEL =
            PREVIEW_HORIZONTAL_THRESHOLD * PREVIEW_SWIPE_FOLLOW
        private const val PREVIEW_MAX_DOWN_TRAVEL =
            PREVIEW_DOWN_THRESHOLD * PREVIEW_SWIPE_FOLLOW
        private const val PREVIEW_FLING_VELOCITY_MULTIPLIER = 1.35F
        private const val PREVIEW_MOMENTUM_PROJECTION_SECONDS = 0.07F
        private const val PREVIEW_MOMENTUM_MS = 70L
        private const val PREVIEW_SETTLE_MS = 190L
        private const val PREVIEW_DISMISS_MIN_MS = 90L
        private const val PREVIEW_DISMISS_MAX_MS = 220L
    }

}

/**
 * True while [FloatingPanelLayout] has faded this half of the panel out of sight.
 *
 * The panel cross-fades the mini bar and the full player with alpha, and alpha is not part of hit
 * testing: a view faded to nothing still takes touches exactly as if it were solid. The panel is
 * laid over the whole window, in front of the bottom navigation, so a collapsed player's chrome
 * kept catching taps meant for the tabs underneath it - the toolbar's three dots sits over the
 * Search tab once the player is scaled down to the bar, so pressing Search opened the playing
 * song's menu, and its star sits near the Library tab, where a press quietly favourited the song.
 * Expanded, the same thing happens the other way round: the scaled-up mini bar lies invisibly
 * across the player's toolbar with its transport buttons over those three dots.
 *
 * Read on ACTION_DOWN only. A gesture that began while the view was visible keeps its target for
 * the rest of the stream, so a drag that collapses the panel under its own finger is not cut off
 * halfway through.
 */
internal fun View.isFadedOutOfPanel(): Boolean = alpha <= PANEL_GHOST_ALPHA

/** Below this the view is no longer on screen in any meaningful sense; the fade over/undershoots. */
private const val PANEL_GHOST_ALPHA = 0.01F
