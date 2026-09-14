package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/**
 * The artwork behind a station card - the soft colour field the 1.0-stable build uses for its
 * "Made for you" cards, drawn rather than shipped as an image so every station gets its own.
 *
 * Colours and layout come from the station's id, so a station looks the same each time it appears
 * while no two look alike. Four styles rather than one, because a row of cards drawn the same way
 * reads as a repeated texture instead of as separate stations.
 *
 * Motion is driven by [ProceduralMotionTicker], shared with the carousel banners, so a card and a
 * banner are the same animation at the same rate rather than two engines that look alike.
 */
class StationArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), ProceduralMotionTicker.Host {

    private val motion = ProceduralStationMotion()

    override val motionDurationMs: Long get() = motion.durationMs

    override var motionPhase: Float
        get() = motion.phase
        set(value) { motion.phase = value }

    /**
     * Whether the field drifts.
     *
     * Only turned off to hold the large header still while a station screen is sliding in: a
     * near-full-screen redraw on every frame of the enter transition is what made opening one
     * stutter where an album, which shows a plain bitmap, did not.
     */
    var animated: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value && isAttachedToWindow) {
                ProceduralMotionTicker.register(this)
            } else {
                ProceduralMotionTicker.unregister(this)
            }
        }

    /**
     * @param seed anything stable for this station - its id. Drives both the palette and the style.
     */
    fun bind(seed: String) {
        if (motion.bind(seed)) ProceduralMotionTicker.resetPhase(this)
        invalidate()
    }

    override fun isMotionAttached(): Boolean = isAttachedToWindow

    override fun isMotionVisible(): Boolean = isShown

    override fun invalidateMotion() = invalidate()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) ProceduralMotionTicker.register(this)
    }

    override fun onDetachedFromWindow() {
        ProceduralMotionTicker.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        motion.draw(canvas, w, h)
    }
}
