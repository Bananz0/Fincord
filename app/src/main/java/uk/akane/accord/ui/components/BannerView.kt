package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.sp
import uk.akane.cupertino.widget.continuousRoundRect

/**
 * Unified Station Banner View supporting software-generated procedural shader animations
 * (MESH, AURORA, RINGS) derived dynamically per station/playlist seed with pixel-perfect typography.
 */
class BannerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), ProceduralMotionTicker.Host {

    private val motion = ProceduralStationMotion()

    override val motionDurationMs: Long get() = motion.durationMs

    override var motionPhase: Float
        get() = motion.phase
        set(value) { motion.phase = value }

    private var textString: String = ""
    private var gradientText: String = ""

    private val clipPath = Path()
    private val roundCornerSize = 18.dp.px
    private lateinit var linearGradient: LinearGradient

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bannerDrawable = ResourcesCompat.getDrawable(resources, R.drawable.ic_banner_logo, null)!!
    private val bannerHeight = 14.dp.px
    private val bannerWidth = (bannerDrawable.intrinsicWidth.toFloat() / bannerDrawable.intrinsicHeight.toFloat() * bannerHeight).toInt()

    private val titleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resources.getColor(R.color.systemWhite, null)
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
        textSize = 21.sp.px
    }

    private val subtitleTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(resources.getColor(R.color.systemWhite, null), 215)
        typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
        textSize = 12.5.sp.px
    }
    private var titleLayout: StaticLayout? = null
    private var subtitleLayout: StaticLayout? = null

    /**
     * Binds banner content dynamically.
     * Calculates a unique procedural shader palette and style derived from [seed].
     */
    fun bind(
        title: String,
        artistsSummary: String? = null,
        seed: String = title
    ) {
        this.textString = title
        this.gradientText = artistsSummary.orEmpty()

        if (motion.bind(seed)) ProceduralMotionTicker.resetPhase(this)
        updateGradientShader()
        rebuildTextLayouts()
        invalidate()
    }

    override fun isMotionAttached(): Boolean = isAttachedToWindow

    override fun isMotionVisible(): Boolean = isShown

    override fun invalidateMotion() = invalidate()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clipPath.reset()
        clipPath.continuousRoundRect(
            0F, 0F,
            w.toFloat(), h.toFloat(),
            roundCornerSize
        )
        updateGradientShader()
        motion.resize(w.toFloat(), w.toFloat())
        rebuildTextLayouts()

        val left = 18.dp.px
        val top = 18.dp.px
        val right = left + bannerWidth
        val bottom = top + bannerHeight

        bannerDrawable.setBounds(
            left.toInt(), top.toInt(),
            right.toInt(), bottom.toInt()
        )

        bannerDrawable.setTint(
            resources.getColor(R.color.systemWhite, null)
        )
    }

    private fun updateGradientShader() {
        if (width <= 0) return
        linearGradient = LinearGradient(
            0F, width.toFloat(),
            width.toFloat(), height.toFloat(),
            intArrayOf(
                motion.palette[0],
                motion.palette[1]
            ),
            null,
            Shader.TileMode.CLAMP
        )
        gradientPaint.shader = linearGradient
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ProceduralMotionTicker.register(this)
    }

    override fun onDetachedFromWindow() {
        ProceduralMotionTicker.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        canvas.save()
        canvas.clipPath(clipPath)

        // Draw top square shader artwork
        drawProceduralShader(canvas)

        // Draw bottom gradient info bar
        drawBottomBar(canvas)

        // Draw header logo, title, and subtitle text
        drawLogo(canvas)
        drawTitleText(canvas)
        drawSubtitleText(canvas)

        canvas.restore()
    }

    private fun drawProceduralShader(canvas: Canvas) {
        val w = width.toFloat()
        val artHeight = w // Top area is square

        motion.draw(canvas, w, artHeight)
    }

    private fun drawBottomBar(canvas: Canvas) {
        val w = width.toFloat()
        val artHeight = w
        canvas.drawRect(0f, artHeight, w, height.toFloat(), gradientPaint)
    }

    private fun drawLogo(canvas: Canvas) {
        bannerDrawable.draw(canvas)
    }

    private fun drawTitleText(canvas: Canvas) {
        val layout = titleLayout ?: return
        val bounds = bannerDrawable.bounds
        val x = bounds.left.toFloat()
        val yTop = bounds.bottom + 10.dp.px

        canvas.withTranslation(x, yTop) {
            layout.draw(this)
        }
    }

    private fun drawSubtitleText(canvas: Canvas) {
        val layout = subtitleLayout ?: return

        val rectTop = width.toFloat()
        val rectBottom = height.toFloat()
        val rectHeight = rectBottom - rectTop

        val textHeight = layout.height
        val x = (width - layout.width) / 2f
        val y = rectTop + (rectHeight - textHeight) / 2f

        canvas.withTranslation(x, y) {
            layout.draw(this)
        }
    }

    /** Text shaping is content/size dependent, not animation dependent. */
    private fun rebuildTextLayouts() {
        if (width <= 0) return
        val maxWidth = (width - 32.dp.px).coerceAtLeast(100f).toInt()
        titleLayout = if (textString.isEmpty()) null else {
            titleTextPaint.textSize = when {
                textString.length > 20 -> 18.sp.px
                textString.length > 10 -> 21.sp.px
                else -> 24.sp.px
            }
            StaticLayout.Builder.obtain(textString, 0, textString.length, titleTextPaint, maxWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
        subtitleLayout = if (gradientText.isEmpty()) null else {
            StaticLayout.Builder.obtain(
                gradientText, 0, gradientText.length, subtitleTextPaint, maxWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
        }
    }

}
