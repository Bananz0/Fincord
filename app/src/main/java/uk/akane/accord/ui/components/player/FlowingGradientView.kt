package uk.akane.accord.ui.components.player

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.View
import androidx.preference.PreferenceManager
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * A native, artwork-derived flowing background.
 *
 * Apple-style backgrounds retain the cover itself as the colour field: several oversized copies
 * move independently underneath strong blur and colour treatment. Sampling three flat colours
 * into radial gradients looked related to the cover, but lost its light/dark geography and moved
 * like three obvious spotlights. A tiny private copy keeps this renderer inexpensive while four
 * transformed layers restore that geography. The final view blur joins their edges on Android 12+;
 * older versions still receive a naturally softened field from the deliberately low-resolution
 * source.
 */
class FlowingGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val artworkPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        val saturation = ColorMatrix().apply { setSaturation(1.45F) }
        val tone = ColorMatrix(
            floatArrayOf(
                0.66F, 0F, 0F, 0F, 0F,
                0F, 0.66F, 0F, 0F, 0F,
                0F, 0F, 0.66F, 0F, 0F,
                0F, 0F, 0F, 1F, 0F,
            )
        )
        saturation.postConcat(tone)
        colorFilter = ColorMatrixColorFilter(saturation)
    }
    private val artworkMatrix = Matrix()
    private var artwork: Bitmap? = null
    private var palette = intArrayOf(
        Color.rgb(45, 32, 55),
        Color.rgb(30, 48, 64),
        Color.rgb(70, 35, 45),
    )
    private var motionPhase = 0F
    private var lastFrameNs = 0L
    private var framePosted = false
    private var enabledStateListener: ((Boolean) -> Unit)? = null
    private val frame = object : Runnable {
        override fun run() {
            framePosted = false
            if (!shouldAnimate()) return
            invalidate()
            framePosted = true
            postOnAnimationDelayed(this, FRAME_DELAY_MS)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val radius = 38F * resources.displayMetrics.density
            setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
            )
        }
        syncEnabledState()
    }

    fun setArtwork(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.width < 1 || bitmap.height < 1) {
            Log.d("FullPlayer", "COVERDBG mesh setArtwork rejected null=${bitmap == null}") // TEMP-COVERDBG
            return
        }
        runCatching {
            // Coil returns a HARDWARE bitmap whenever the target view is hardware accelerated, and
            // its pixels live only on the GPU: both createScaledBitmap and getPixel throw on one.
            // The whole body is inside runCatching, so that threw away every frame of artwork
            // without a trace and the field silently kept the fallback palette below - which is
            // exactly what a cold start looked like. BlendView already guards this way.
            val source = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } else {
                bitmap
            }
            val scaled = Bitmap.createScaledBitmap(
                source, ARTWORK_SAMPLE_SIZE, ARTWORK_SAMPLE_SIZE, true
            )
            val next = if (scaled === source) {
                source.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                scaled
            }
            artwork?.takeUnless(Bitmap::isRecycled)?.recycle()
            artwork = next
            val points = arrayOf(0.18F to 0.22F, 0.82F to 0.30F, 0.50F to 0.78F)
            palette = points.map { (x, y) ->
                source.getPixel(
                    (x * (source.width - 1)).toInt(),
                    (y * (source.height - 1)).toInt(),
                ).forBackdrop()
            }.toIntArray()
            if (source !== bitmap && source !== next) source.recycle()
            invalidate()
        }.onFailure {
            // This catch is why a failed backdrop looks like a deliberate flat palette instead of
            // an error: the palette keeps its fallback and nothing anywhere says why.
            Log.w("FullPlayer", "COVERDBG mesh setArtwork THREW", it) // TEMP-COVERDBG
        }.onSuccess {
            Log.d("FullPlayer", "COVERDBG mesh setArtwork ok palette=${palette.joinToString { c ->
                Integer.toHexString(c)
            }}") // TEMP-COVERDBG
        }
    }

    /**
     * Kept as the common renderer API, but ambient backdrop motion is intentionally independent
     * of playback. A paused cold start should look alive exactly as an actively playing one does.
     */
    fun setPlaying(value: Boolean) {
        syncFrameLoop()
        invalidate()
    }

    /**
     * Draw whatever the Appearance toggle says.
     *
     * The toggle is about the *player's* backdrop - whether the album's colours flow behind the
     * artwork or the blurred cover sits there instead. The output sheet is a small glass card that
     * has one look either way, and deciding it from a preference about a different surface left the
     * same sheet arriving with two different backgrounds depending on a setting the user was not
     * thinking about when they opened it. A caller that sets this owns the decision.
     */
    var ignorePreference = false
        set(value) {
            if (field == value) return
            field = value
            syncEnabledState()
        }

    fun setEnabledStateListener(listener: (Boolean) -> Unit) {
        enabledStateListener = listener
        listener(ignorePreference || preferences.getBoolean(PREFERENCE_KEY, false))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawColor(palette[0].darkened(0.30F))

        val source = artwork
        if (source == null || source.isRecycled) {
            drawFallback(canvas)
            return
        }

        if (shouldAnimate()) {
            val now = System.nanoTime()
            if (lastFrameNs != 0L) {
                val advance = (now - lastFrameNs).toDouble() /
                    ANIMATION_PERIOD_NS.toDouble() * 2.0 * PI
                motionPhase = ((motionPhase + advance) % (2.0 * PI)).toFloat()
            }
            lastFrameNs = now
        } else {
            lastFrameNs = 0L
        }
        val phase = motionPhase
        val largest = max(width, height).toFloat()

        drawArtworkLayer(
            canvas, source,
            width * 0.50F, height * 0.50F,
            largest * 1.25F, phase * 8F, 210,
        )
        drawArtworkLayer(
            canvas, source,
            width * 0.50F, height * 0.50F,
            largest * 0.80F, -phase * 13F + 36F, 142,
        )
        drawArtworkLayer(
            canvas, source,
            width * (0.50F + 0.16F * sin(phase * 0.53F)),
            height * (0.50F + 0.13F * cos(phase * 0.53F)),
            largest * 0.50F, phase * 17F - 20F, 116,
        )
        drawArtworkLayer(
            canvas, source,
            width * (0.50F + 0.24F * cos(phase * 0.83F)),
            height * (0.50F + 0.20F * sin(phase * 0.83F)),
            largest * 0.25F, -phase * 21F + 12F, 92,
        )

        // Keeps white player type readable without flattening the artwork into one muddy colour.
        canvas.drawColor(Color.argb(44, 0, 0, 0))
    }

    private fun drawArtworkLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        rotation: Float,
        alpha: Int,
    ) {
        artworkMatrix.reset()
        artworkMatrix.postTranslate(-bitmap.width / 2F, -bitmap.height / 2F)
        artworkMatrix.postScale(size / bitmap.width, size / bitmap.height)
        artworkMatrix.postRotate(rotation)
        artworkMatrix.postTranslate(centerX, centerY)
        artworkPaint.alpha = alpha
        canvas.drawBitmap(bitmap, artworkMatrix, artworkPaint)
    }

    private fun drawFallback(canvas: Canvas) {
        val radius = max(width, height) * 0.9F
        drawFallbackBlob(canvas, palette[0], width * 0.2F, height * 0.2F, radius)
        drawFallbackBlob(canvas, palette[1], width * 0.8F, height * 0.4F, radius)
        drawFallbackBlob(canvas, palette[2], width * 0.5F, height * 0.85F, radius)
    }

    private fun drawFallbackBlob(
        canvas: Canvas,
        color: Int,
        x: Float,
        y: Float,
        radius: Float,
    ) {
        fallbackPaint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(color.withAlpha(210), color.withAlpha(85), Color.TRANSPARENT),
            floatArrayOf(0F, 0.55F, 1F),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0F, 0F, width.toFloat(), height.toFloat(), fallbackPaint)
        fallbackPaint.shader = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(this)
        syncEnabledState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrameNs = 0L
        syncFrameLoop()
    }

    /**
     * [shouldAnimate] reads [isShown], which an ancestor can change without this view or the window
     * changing visibility at all. The player sheet is collapsed on a cold start, so the loop was
     * never started; expanding it made the view shown but raised none of the other callbacks, and
     * the backdrop sat still until playback happened to call [setPlaying]. This is the one callback
     * that tracks aggregate visibility, so it is what the loop has to hang off.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        lastFrameNs = 0L
        syncFrameLoop()
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        removeCallbacks(frame)
        framePosted = false
        artwork?.takeUnless(Bitmap::isRecycled)?.recycle()
        artwork = null
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == PREFERENCE_KEY) syncEnabledState()
    }

    private fun syncEnabledState() {
        val enabled = ignorePreference || preferences.getBoolean(PREFERENCE_KEY, false)
        visibility = if (enabled) VISIBLE else GONE
        enabledStateListener?.invoke(enabled)
        syncFrameLoop()
        invalidate()
    }

    private fun syncFrameLoop() {
        if (shouldAnimate() && !framePosted) {
            framePosted = true
            postOnAnimation(frame)
        } else if (!shouldAnimate()) {
            removeCallbacks(frame)
            framePosted = false
        }
    }

    private fun shouldAnimate(): Boolean =
        isAttachedToWindow && visibility == VISIBLE &&
            windowVisibility == VISIBLE && isShown && display?.state == Display.STATE_ON &&
            !context.getSystemService(PowerManager::class.java).isPowerSaveMode && runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1F,
            ) > 0F
        }.getOrDefault(true)

    private fun Int.forBackdrop(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(this, hsv)
        hsv[1] = (hsv[1] * 1.12F).coerceIn(0.28F, 0.88F)
        hsv[2] = (hsv[2] * 0.72F).coerceIn(0.22F, 0.66F)
        return Color.HSVToColor(hsv)
    }

    private fun Int.darkened(factor: Float): Int = Color.rgb(
        (Color.red(this) * factor).toInt(),
        (Color.green(this) * factor).toInt(),
        (Color.blue(this) * factor).toInt(),
    )

    private fun Int.withAlpha(alpha: Int): Int = Color.argb(
        alpha, Color.red(this), Color.green(this), Color.blue(this)
    )

    companion object {
        const val PREFERENCE_KEY = "settings_ui_mesh_gradient"
        private const val ARTWORK_SAMPLE_SIZE = 48
        // The field takes 96 seconds to make one circuit; 15 fps is visually continuous for that
        // speed and halves the full-screen blur work compared with the common 30 fps default.
        private const val FRAME_DELAY_MS = 66L
        private const val ANIMATION_PERIOD_NS = 96_000_000_000L
    }
}
