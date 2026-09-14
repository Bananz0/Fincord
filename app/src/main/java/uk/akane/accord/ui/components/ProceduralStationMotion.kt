package uk.akane.accord.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * Allocation-free, seed-stable motion shared by station cards and their large carousel banners.
 * Every expression is periodic over exactly one phase, so phase 1 is pixel-identical to phase 0.
 */
internal class ProceduralStationMotion {

    enum class Style { ORBITS, AURORA, RINGS, BLOOM }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()
    private var seed: String? = null
    private var width = 0f
    private var height = 0f
    private var radialShaders: Array<RadialGradient> = emptyArray()
    private var bandShaders: Array<LinearGradient> = emptyArray()
    private var sweepShader: SweepGradient? = null

    var style = Style.ORBITS
        private set
    var palette: IntArray = DEFAULT_PALETTE
        private set
    var phase = 0f
    var durationMs = 24_000L
        private set

    private var variant = 0
    private var direction = 1f

    /** Returns true only when a recycled view genuinely changes station. */
    fun bind(newSeed: String): Boolean {
        if (seed == newSeed) return false
        seed = newSeed
        val hash = newSeed.fold(7L) { acc, c -> acc * 31 + c.code }
        style = Style.entries[((hash ushr 8) and 0xFF).toInt() % Style.entries.size]
        variant = ((hash ushr 20) and 0xFF).toInt() % 4
        direction = if ((hash and 1L) == 0L) 1f else -1f
        durationMs = 18_000L + (((hash ushr 16) and 0x3) * 4_000L)
        phase = (((hash ushr 32) and 0x3FF).toFloat() / 1024f).coerceIn(0f, 1f)
        palette = paletteFor(hash)
        rebuildShaders()
        return true
    }

    fun resize(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        rebuildShaders()
    }

    fun draw(canvas: Canvas, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        resize(w, h)
        paint.alpha = 255
        paint.shader = null
        paint.color = palette[0]
        canvas.drawRect(0f, 0f, w, h, paint)

        when (style) {
            Style.ORBITS -> drawOrbits(canvas, w, h)
            Style.AURORA -> drawAurora(canvas, w, h)
            Style.RINGS -> drawRings(canvas, w, h)
            Style.BLOOM -> drawBloom(canvas, w, h)
        }
        paint.alpha = 255
        paint.shader = null
    }

    private fun rebuildShaders() {
        if (width <= 0f || height <= 0f) return
        val radius = maxOf(width, height) * 0.88f
        radialShaders = Array(palette.size - 1) { slot ->
            val color = palette[slot + 1]
            RadialGradient(
                0f, 0f, radius,
                intArrayOf(color, ColorUtils.setAlphaComponent(color, 0)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        bandShaders = Array(palette.size - 1) { slot ->
            val color = palette[slot + 1]
            LinearGradient(
                -width * 0.8f, 0f, width * 0.8f, 0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(color, 0),
                    color,
                    ColorUtils.setAlphaComponent(color, 0),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        sweepShader = SweepGradient(0f, 0f, palette, null)
    }

    /** Independent integer-frequency orbits: varied motion with an exact closed endpoint. */
    private fun drawOrbits(canvas: Canvas, w: Float, h: Float) {
        val theta = phase * TWO_PI * direction
        radialShaders.forEachIndexed { slot, shader ->
            val xFrequency = 1 + ((variant + slot) % 2)
            val yFrequency = 1 + ((variant + slot + 1) % 3)
            val offset = slot * 1.93f + variant * 0.47f
            val cx = w * (0.5f + 0.3f * cos(theta * xFrequency + offset))
            val cy = h * (0.5f + 0.3f * sin(theta * yFrequency + offset))
            matrix.reset()
            matrix.setTranslate(cx, cy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    /** Bands sway and roll rather than translating through a discontinuous wrap point. */
    private fun drawAurora(canvas: Canvas, w: Float, h: Float) {
        val theta = phase * TWO_PI * direction
        bandShaders.forEachIndexed { slot, shader ->
            val offset = slot * 1.71f + variant * 0.38f
            val swayX = sin(theta + offset) * w * 0.17f
            val swayY = cos(theta * (1 + slot % 2) + offset) * h * 0.16f
            val tilt = 42f + sin(theta + offset) * (8f + variant * 2f)
            matrix.reset()
            matrix.setRotate(tilt)
            matrix.postTranslate(w * 0.5f + swayX, h * (0.28f + slot * 0.22f) + swayY)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    /** A turning spectrum whose centre itself follows a small closed orbit. */
    private fun drawRings(canvas: Canvas, w: Float, h: Float) {
        val theta = phase * TWO_PI * direction
        val cx = w * (0.18f + 0.07f * cos(theta))
        val cy = h * (0.38f + 0.07f * sin(theta))
        sweepShader?.let { shader ->
            matrix.reset()
            matrix.setRotate(phase * 360f * direction + variant * 24f)
            matrix.postTranslate(cx, cy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        val step = maxOf(w, h) / (palette.size + 1)
        palette.forEachIndexed { index, color ->
            val pulse = 1f + 0.055f * sin(theta + index * 0.8f)
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(color, 72 + variant * 6)
            canvas.drawCircle(cx, cy, step * (palette.size - index) * pulse, paint)
        }
    }

    /** Soft lights breathe in place, deliberately calmer than the orbital profiles. */
    private fun drawBloom(canvas: Canvas, w: Float, h: Float) {
        val theta = phase * TWO_PI * direction
        radialShaders.forEachIndexed { slot, shader ->
            val offset = slot * (TWO_PI / radialShaders.size) + variant * 0.31f
            val breath = 0.82f + 0.18f * (0.5f + 0.5f * sin(theta + offset))
            val cx = w * (0.5f + 0.2f * cos(offset))
            val cy = h * (0.5f + 0.2f * sin(offset))
            matrix.reset()
            matrix.setScale(breath, breath)
            matrix.postTranslate(cx, cy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            paint.alpha = (205 + 50 * (1f - breath)).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun paletteFor(hash: Long): IntArray {
        val index = ((hash xor (hash ushr 32)) and Long.MAX_VALUE)
            .rem(VIBRANT_PALETTES.size).toInt()
        return VIBRANT_PALETTES[index].clone()
    }

    companion object {
        private const val TWO_PI = (Math.PI * 2).toFloat()
        private val DEFAULT_PALETTE = intArrayOf(Color.BLACK, Color.DKGRAY, Color.GRAY, Color.LTGRAY)
        private val VIBRANT_PALETTES = arrayOf(
            intArrayOf(0xFF170724.toInt(), 0xFF9B5DE5.toInt(), 0xFFF9D423.toInt(), 0xFFFF3D81.toInt()),
            intArrayOf(0xFF061D2B.toInt(), 0xFF00F5D4.toInt(), 0xFFF15BB5.toInt(), 0xFF7B61FF.toInt()),
            intArrayOf(0xFF211006.toInt(), 0xFFFF7A00.toInt(), 0xFF00C2FF.toInt(), 0xFFFF2E88.toInt()),
            intArrayOf(0xFF071F18.toInt(), 0xFFB8F500.toInt(), 0xFF7A2CFF.toInt(), 0xFFFFB000.toInt()),
            intArrayOf(0xFF24070D.toInt(), 0xFFFF3158.toInt(), 0xFF33E6FF.toInt(), 0xFFFFC857.toInt()),
            intArrayOf(0xFF07142C.toInt(), 0xFF386BFF.toInt(), 0xFFFF4ECD.toInt(), 0xFFFFE66D.toInt()),
        )
    }
}
