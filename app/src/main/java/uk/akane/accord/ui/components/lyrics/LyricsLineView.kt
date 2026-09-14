package uk.akane.accord.ui.components.lyrics

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.res.ResourcesCompat
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.floatAnimator
import uk.akane.accord.logic.sp
import uk.akane.cupertino.utils.AnimationUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class LyricsGlyphTransform(
    val translateY: Float,
    val scale: Float,
    val blurRadius: Float,
)

/**
 * Canvas counterpart of the preview renderer's `CharAnimator.computeAwesome` transform.
 *
 * Each glyph uses 80% of the word's duration and is staggered across the remaining 20%. The
 * reference animation combines a small overshooting lift, a 10% scale pulse and a blur that peaks
 * halfway through the glyph's movement, then leaves the glyph completely settled.
 */
internal fun lyricsGlyphTransform(
    positionMs: Long,
    startMs: Long,
    endMs: Long,
    glyphIndex: Int,
    glyphCount: Int,
    travelPx: Float,
): LyricsGlyphTransform {
    val duration = (endMs - startMs).coerceAtLeast(1L)
    val span = duration * GLYPH_SETTLE_SPAN
    val stagger = if (glyphCount > 1) {
        glyphIndex.toFloat() / (glyphCount - 1)
    } else {
        0.5f
    }
    val startsAt = startMs + (duration - span) * stagger
    val progress = ((positionMs - startsAt) / span).coerceIn(0f, 1f)
    val remaining = 1f - progress

    // These are the reference renderer's strength-one curves. Translation is quantized to the
    // same 1/64-pixel boundary so a settled glyph does not shimmer between adjacent frames.
    val translateCurve = 4f * remaining * remaining - 3f * remaining
    val translateY = (-travelPx * translateCurve * 64f).roundToInt() / 64f
    val scale = 1f + GLYPH_SCALE_PULSE * progress * remaining
    val blurRadius = GLYPH_BOUNCE_BLUR_FACTOR * progress * remaining
    return LyricsGlyphTransform(translateY, scale, blurRadius)
}

private const val GLYPH_SETTLE_SPAN = 0.8f

/**
 * How much a letter swells as it is sung, at its peak halfway through its own beat.
 *
 * `progress * remaining` tops out at 0.25, so this is four times the visible pulse: 0.28 is a 7%
 * letter. The reference renderer's 10% was landing as a wobble against the reference itself, where
 * the swell is small and it is the light on the letter that carries the beat.
 */
private const val GLYPH_SCALE_PULSE = 0.28f

/**
 * The bloom under a letter as it lands, peaking at a quarter of this.
 *
 * Was 47.6 - a twelve-pixel halo on every letter of every sung word, which is what made the line
 * being sung look lit from behind rather than simply brighter than its neighbours. Four pixels
 * keeps the landing soft without the glow becoming the effect.
 */
private const val GLYPH_BOUNCE_BLUR_FACTOR = 16f

@Suppress("ViewConstructor")
class LyricsLineView internal constructor(
    context: Context,
    private val line: LyricsLine,
    /** Where to jump to when this line is tapped; see [performClick]. */
    private val onSeek: ((Long) -> Unit)? = null,
) : View(context) {
    private val horizontalPadding = 32.dp.px
    /** How far a letter sits above its line at the moment it is sung. */
    private val charSettleTravel = 3.dp.px
    private val verticalPadding = 14.dp.px

    lateinit var animations: Animations
        private set

    private lateinit var staticLayout: StaticLayout
    private lateinit var karaokeBaseLayout: StaticLayout
    private val paint = TextPaint().apply {
        textSize = 34.sp.px
        color = Color.WHITE
        typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
    }
    private val karaokeBasePaint = TextPaint(paint).apply {
        alpha = (UPCOMING_WORD_ALPHA * 255).roundToInt()
    }
    private val contentPaint = Paint().apply {
        xfermode = AnimationUtils.addXfermode
    }

    private val blurRenderNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        RenderNode(BLUR_NODE_NAME)
    } else {
        null
    }

    val hasWordTimings: Boolean
        get() = line.wordTimings.isNotEmpty()

    private val isInterlude: Boolean
        get() = line.isInterlude

    /** Whether this line's own drawing moves between line changes and so needs a frame clock. */
    val needsFrameClock: Boolean
        get() = hasWordTimings || isInterlude

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // onDraw runs once per display frame for the line being sung, so the paths it clips with
    // cannot be allocated there. The old version built a fresh Path per frame - and inside
    // wordPath, one rect per character of the active word on top of that - which at 120 Hz is a
    // steady stream of garbage produced by the one view that must never miss a frame.
    private val reusableWordPath = Path()
    private val reusableHighlightPath = Path()

    private var isActiveLine = false
    private var playbackPositionMs = Long.MIN_VALUE

    /** Diagnostic only; set by the view model so [LyricsSyncProbe] can report it per frame. */
    internal var followErrorMs: Long = 0L

    var textOffset: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            translationY = value
        }

    var textAlpha: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blurRenderNode?.alpha = textAlpha
            } else {
                alpha = value
            }
        }

    var textScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var blurRadius: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val roundedValue = value.roundToInt()
            val renderEffect =
                if (roundedValue == 0) null
                else blurs?.get(roundedValue)
            blurRenderNode?.setRenderEffect(renderEffect)
        }

    init {
        isClickable = true
        isFocusable = true
        // No ripple. A lyric is text, not a button, and a grey box flashing over the words
        // reads as a control - the line coming into focus is the feedback.
        contentDescription = if (line.isInterlude) {
            context.getString(R.string.lyrics_interlude)
        } else {
            line.text
        }
    }

    fun setAnimations(index: Int, globalOffset: Float, deviceHeight: Float) {
        animations = Animations(index, globalOffset, deviceHeight)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.discardDisplayList()
        }
    }

    /** Advances the sung line and the counting dots; a paused, unchanged position redraws nothing. */
    fun updatePlaybackPosition(positionMs: Long) {
        if (!needsFrameClock || playbackPositionMs == positionMs) return
        playbackPositionMs = positionMs
        // invalidate(), not postInvalidateOnAnimation(). This is always called from the main
        // thread inside the Choreographer's animation callback, and traversal runs after that
        // callback in the same frame - so invalidate() draws this position now, while
        // postInvalidateOnAnimation() queued a callback that could only be serviced by the
        // *next* frame. That was a whole refresh of lag on every word, unconditionally.
        if (isActiveLine) invalidate()
    }

    private fun setActiveLine(active: Boolean) {
        if (isActiveLine == active) return
        isActiveLine = active
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (height < 0 || width < 0) return
        val hP = horizontalPadding.roundToInt()
        val vP = verticalPadding.roundToInt()

        val text = line.text
        val layoutWidth = MeasureSpec.getSize(widthMeasureSpec)
        val textWidth = layoutWidth - hP * 2

        if (isInterlude) {
            // Dots are drawn straight onto the canvas rather than through the blur node: they are
            // three circles, so a blur of their own is a smudge, and the fade that carries them in
            // and out is already in their alpha.
            setMeasuredDimension(
                layoutWidth,
                (DOT_RADIUS.px * 2f + INTERLUDE_ROW_PADDING.px * 2f).roundToInt(),
            )
            return
        }

        staticLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, textWidth)
            .build()
        karaokeBaseLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, karaokeBasePaint, textWidth)
            .build()

        val layoutHeight = staticLayout.height + vP * 2
        setMeasuredDimension(layoutWidth, layoutHeight)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blurRenderNode?.apply {
                setPosition(hP, vP, layoutWidth - hP, layoutHeight - vP)
                // Relative to the node, not to the view. The node starts at the padding, so a
                // pivot measured in the view's coordinates scaled the current line about a
                // point below its own centre and left it sitting lower than its neighbours -
                // the line that looked misaligned while the others agreed with each other.
                pivotX = (layoutWidth - hP * 2) / 2f
                pivotY = (layoutHeight - vP * 2) / 2f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val probing = isActiveLine && LyricsSyncProbe.isEnabled
        val startedNanos = if (probing) System.nanoTime() else 0L
        drawContent(canvas)
        if (probing) {
            LyricsSyncProbe.onFrameDrawn(
                System.nanoTime() - startedNanos,
                playbackPositionMs,
                followErrorMs,
            )
        }
    }

    private fun drawContent(canvas: Canvas) {
        if (isInterlude) {
            drawInterludeDots(canvas)
            return
        }
        val count = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), contentPaint)
        val staticLayout = staticLayout
        val scale = textScale
        if (isActiveLine && hasWordTimings) {
            drawKaraokeLine(canvas, staticLayout, scale)
            canvas.restoreToCount(count)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (canvas.isHardwareAccelerated && blurRenderNode != null) {
                with(blurRenderNode) {
                    if (!hasDisplayList()) {
                        val recordingCanvas = beginRecording()
                        staticLayout.draw(recordingCanvas)
                        endRecording()
                    }
                    scaleX = scale
                    scaleY = scale
                    canvas.drawRenderNode(this)
                }
            } else {
                canvas.translate(horizontalPadding, verticalPadding)
                canvas.scale(scale, scale)
                paint.alpha = (textAlpha * 255).roundToInt()
                staticLayout.draw(canvas)
            }
        } else {
            canvas.translate(horizontalPadding, verticalPadding)
            canvas.scale(scale, scale)
            staticLayout.draw(canvas)
        }
        canvas.restoreToCount(count)
    }

    /**
     * Draws the wait before the next lyric as three dots that fill across it.
     *
     * Each dot owns a third of the gap and brightens and grows as its third passes, so the row
     * reads as a countdown rather than as decoration: one, two, three, words. Underneath that, all
     * three breathe together on a slow cycle, which is what keeps a long instrumental from looking
     * like a frozen screen. The last moment before the line arrives swells and fades the group out,
     * handing the eye over to the words instead of switching them on beside a still-lit row.
     */
    private fun drawInterludeDots(canvas: Canvas) {
        val progress = line.interludeProgressAt(playbackPositionMs)
        val diameter = DOT_RADIUS.px * 2f
        val spacing = DOT_SPACING.px
        val centerY = height / 2f

        // The handover: the group leaves before the line it was counting to arrives.
        val exit = ((progress - DOT_EXIT_FROM) / (1f - DOT_EXIT_FROM)).coerceIn(0f, 1f)
        val groupAlpha = textAlpha * (1f - exit)
        if (groupAlpha <= 0.01f) return

        // Breathing is real time, not song time: it has to keep moving while a dot's own third
        // waits its turn, and it must not stall when playback is paused mid-gap.
        val breathPhase = (android.os.SystemClock.uptimeMillis() % DOT_BREATH_MS) /
            DOT_BREATH_MS.toFloat()
        val breath = 1f + DOT_BREATH_SCALE *
            kotlin.math.sin(breathPhase * 2f * Math.PI.toFloat())
        val groupScale = textScale * breath * (1f + DOT_EXIT_SWELL * exit)

        for (index in 0 until DOT_COUNT) {
            // Each dot's own third of the wait, eased so it arrives rather than snaps.
            val share = 1f / DOT_COUNT
            val own = ((progress - index * share) / share).coerceIn(0f, 1f)
            val eased = own * own * (3f - 2f * own)
            val alpha = DOT_IDLE_ALPHA + (DOT_LIT_ALPHA - DOT_IDLE_ALPHA) * eased
            val radius = DOT_RADIUS.px * (1f + DOT_LIT_GROWTH * eased) * groupScale
            dotPaint.alpha = (alpha * groupAlpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(
                horizontalPadding + diameter / 2f + index * (diameter + spacing),
                centerY,
                radius,
                dotPaint,
            )
        }

        // Breathing needs the next frame even when the clock has not moved, which is exactly the
        // case while paused inside a gap.
        if (isActiveLine) postInvalidateOnAnimation()
    }

    /**
     * Draws the quiet full line first, then reveals the sung copy through a character-aware clip.
     * Clipping each laid-out row separately makes long wrapped lyrics fill naturally instead of a
     * single vertical wipe cutting through every row at once.
     */
    private fun drawKaraokeLine(canvas: Canvas, layout: StaticLayout, scale: Float) {
        val alphaLayer = canvas.saveLayerAlpha(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            (textAlpha * 255).roundToInt(),
        )
        canvas.translate(horizontalPadding, verticalPadding)
        canvas.scale(scale, scale, layout.width / 2f, layout.height / 2f)

        // The word being sung is drawn letter by letter below so that each letter can carry its
        // own offset; it is cut out of both full-line passes to keep from being drawn twice.
        val active = activeWord()
        val activePath = active?.let { wordPath(layout, it.first) }

        val base = canvas.save()
        activePath?.let { canvas.clipOutPath(it) }
        karaokeBaseLayout.draw(canvas)
        canvas.restoreToCount(base)

        val highlightOffset = line.highlightOffsetAt(playbackPositionMs).coerceIn(0f, line.text.length.toFloat())
        if (highlightOffset > 0f) {
            val highlighted = canvas.save()
            if (highlightOffset < line.text.length) {
                canvas.clipPath(highlightPath(layout, highlightOffset))
            }
            activePath?.let { canvas.clipOutPath(it) }
            layout.draw(canvas)
            canvas.restoreToCount(highlighted)
        }

        active?.let { (range, timing) ->
            drawSungWord(canvas, layout, range, timing, highlightOffset)
        }
        canvas.restoreToCount(alphaLayer)
    }

    /** The character range being sung right now and its timing, or null between words. */
    private fun activeWord(): Pair<IntRange, LyricsWordTiming>? {
        var start = 0
        line.wordTimings.forEach { timing ->
            val end = timing.endOffset.coerceIn(start, line.text.length)
            if (end > start && playbackPositionMs >= timing.startTimestamp &&
                playbackPositionMs <= timing.endTimestamp
            ) {
                val range = start until end
                traceWord(range, timing)
                return range to timing
            }
            start = end
        }
        traceWord(null, null)
        return null
    }

    /**
     * Logs what is lit against what the file asked for, so drift can be read rather than guessed.
     *
     * Enough here to tell the two failures apart. A word arriving late shows as a large positive
     * `into` - the position is already well inside the word's window before it lights - while the
     * clock running backwards shows as a `REWIND` line, which is what makes the highlight jump to
     * an earlier word and everything after it read as behind. `gap` marks the position falling
     * between two words, where nothing is lit at all.
     *
     * One line per change of state, not per frame: this runs from the draw pass.
     *
     * `adb logcat -s LyricsSync`
     */
    private fun traceWord(range: IntRange?, timing: LyricsWordTiming?) {
        if (!isActiveLine || !Log.isLoggable(TRACE_TAG, Log.DEBUG)) return
        val position = playbackPositionMs
        val previous = lastTracedPositionMs
        lastTracedPositionMs = position
        val rewound = previous != Long.MIN_VALUE && position < previous
        if (rewound) {
            Log.d(
                TRACE_TAG,
                "REWIND ${previous}ms -> ${position}ms (-${previous - position}ms) line=\"${line.text}\""
            )
        }
        // Compared as longs before anything is built. This runs inside the draw pass of a view
        // that invalidates every frame, and the first version cut the substring and formatted the
        // key *before* checking whether either had changed - two allocations per frame per active
        // line, which is a GC pump, and it showed up as jank rather than as anything to do with
        // lyrics. The dedup was always the point; it just has to happen first.
        val start = timing?.startTimestamp ?: GAP_MARKER
        if (!rewound && start == lastTracedStartMs) return
        lastTracedStartMs = start
        val word = range?.let { line.text.substring(it.first, it.last + 1) }
        if (timing == null) {
            Log.d(TRACE_TAG, "pos=${position}ms  gap - nothing lit  line=\"${line.text}\"")
            return
        }
        Log.d(
            TRACE_TAG,
            "pos=${position}ms  lit=\"$word\"  lrc=[${timing.startTimestamp}..${timing.endTimestamp}]" +
                "  into=+${position - timing.startTimestamp}ms  of=${timing.endTimestamp - timing.startTimestamp}ms"
        )
    }

    /** The start timestamp last reported, or [GAP_MARKER] for "nothing was lit". */
    private var lastTracedStartMs = Long.MIN_VALUE
    private var lastTracedPositionMs = Long.MIN_VALUE

    /** The cells [range] occupies, so the full-line passes can be cut around it. */
    private fun wordPath(layout: StaticLayout, range: IntRange): Path {
        val path = reusableWordPath.apply { rewind() }
        val length = line.text.length
        for (index in range) {
            val layoutLine = layout.getLineForOffset(index)
            val left = layout.getPrimaryHorizontal(index)
            val next = index + 1
            val right = if (next < length && layout.getLineForOffset(next) == layoutLine) {
                layout.getPrimaryHorizontal(next)
            } else {
                layout.getLineRight(layoutLine)
            }
            path.addRect(
                min(left, right),
                layout.getLineTop(layoutLine).toFloat(),
                max(left, right),
                layout.getLineBottom(layoutLine).toFloat(),
                Path.Direction.CW,
            )
        }
        return path
    }

    /**
     * Draws the word being sung, letter by letter, each one settling on its own beat.
     *
     * A held word used to be a boundary crawling through the middle of it - the longer a note, the
     * less the word looked like the one being sung. The letters now arrive in sequence across the
     * note and settle, so length reads as emphasis instead of as a slow wipe. Only this word is
     * drawn this way: everything else keeps the laid-out text, and with it the kerning that
     * per-character drawing gives up.
     */
    private fun drawSungWord(
        canvas: Canvas,
        layout: StaticLayout,
        range: IntRange,
        timing: LyricsWordTiming,
        highlightOffset: Float,
    ) {
        val count = range.count()
        // The same window the sung colour crosses, for the same reason: an Enhanced LRC "word"
        // that runs until the next marker can be seconds long, and staggering the letters across
        // all of it makes them crawl. See wordFillDurationMs.
        val settleEndMs = timing.startTimestamp +
            wordFillDurationMs(timing.endTimestamp - timing.startTimestamp, count)
        for (index in range) {
            val layoutLine = layout.getLineForOffset(index)
            val transform = lyricsGlyphTransform(
                positionMs = playbackPositionMs,
                startMs = timing.startTimestamp,
                endMs = settleEndMs,
                glyphIndex = index - range.first,
                glyphCount = count,
                travelPx = charSettleTravel,
            )
            val baseline = layout.getLineBaseline(layoutLine).toFloat() + transform.translateY
            val startX = layout.getPrimaryHorizontal(index)
            val next = index + 1
            val endX = if (next < line.text.length && layout.getLineForOffset(next) == layoutLine) {
                layout.getPrimaryHorizontal(next)
            } else {
                layout.getLineRight(layoutLine)
            }
            val saved = canvas.save()
            canvas.scale(transform.scale, transform.scale, (startX + endX) / 2f, baseline)
            paint.maskFilter = glyphBlur(transform.blurRadius)
            karaokeBasePaint.maskFilter = paint.maskFilter

            // The sung colour crosses this glyph rather than switching it on.
            //
            // highlightOffsetAt already returns a *fractional* character offset, so the boundary
            // usually falls partway through a letter. Drawing the whole active word in the sung
            // colour threw that away and lit the word in one step; splitting each glyph at the
            // boundary spends that fraction on a left-to-right sweep instead. The letter is drawn
            // twice, unsung then sung, with the sung half clipped to where the boundary has
            // actually reached - so the colour advances continuously across the word.
            val fill = (highlightOffset - index).coerceIn(0f, 1f)
            if (fill < 1f) {
                canvas.drawText(line.text, index, index + 1, startX, baseline, karaokeBasePaint)
            }
            if (fill > 0f) {
                val sung = canvas.save()
                canvas.clipRect(startX, 0f, startX + (endX - startX) * fill, height.toFloat())
                canvas.drawText(line.text, index, index + 1, startX, baseline, paint)
                canvas.restoreToCount(sung)
            }
            canvas.restoreToCount(saved)
        }
        paint.maskFilter = null
        karaokeBasePaint.maskFilter = null
    }

    private fun highlightPath(layout: StaticLayout, offset: Float): Path {
        val textLength = line.text.length
        val wholeOffset = floor(offset).toInt().coerceIn(0, textLength)
        val drawableOffset = wholeOffset.coerceAtMost((textLength - 1).coerceAtLeast(0))
        val currentLine = layout.getLineForOffset(drawableOffset)
        val path = reusableHighlightPath.apply { rewind() }

        for (layoutLine in 0 until currentLine) {
            path.addRect(
                layout.getLineLeft(layoutLine),
                layout.getLineTop(layoutLine).toFloat(),
                layout.getLineRight(layoutLine),
                layout.getLineBottom(layoutLine).toFloat(),
                Path.Direction.CW,
            )
        }

        val fraction = offset - wholeOffset
        val startX = layout.getPrimaryHorizontal(wholeOffset)
        val nextOffset = (wholeOffset + 1).coerceAtMost(textLength)
        val nextLine = layout.getLineForOffset(nextOffset.coerceAtMost((textLength - 1).coerceAtLeast(0)))
        val endX = if (nextLine == currentLine) layout.getPrimaryHorizontal(nextOffset) else {
            if (layout.getParagraphDirection(currentLine) > 0) layout.getLineRight(currentLine)
            else layout.getLineLeft(currentLine)
        }
        val edge = startX + (endX - startX) * fraction
        val left = layout.getLineLeft(currentLine)
        val right = layout.getLineRight(currentLine)
        val top = layout.getLineTop(currentLine).toFloat()
        val bottom = layout.getLineBottom(currentLine).toFloat()
        if (layout.getParagraphDirection(currentLine) > 0) {
            path.addRect(left, top, edge.coerceIn(left, right), bottom, Path.Direction.CW)
        } else {
            path.addRect(edge.coerceIn(left, right), top, right, bottom, Path.Direction.CW)
        }
        return path
    }

    /** Tapping a line jumps to it. This was left as a comment upstream and did nothing. */
    override fun performClick(): Boolean {
        super.performClick()
        onSeek?.invoke(line.timestamp)
        return true
    }

    inner class Animations(
        private val index: Int,
        private var globalOffset: Float,
        private val deviceHeight: Float
    ) {
        private var targetOffset = 0f

        private val offsetFractionAnimator = floatAnimator(700L, interpolator = offsetFractionInterpolator) {
            textOffset = targetOffset * (1f - it.currentValue)
        }

        private val alphaAnimator = floatAnimator(500L, interpolator = AnimationUtils.decelerateInterpolator) {
            textAlpha = it.currentValue
        }

        private val scaleAnimator = floatAnimator(500L, interpolator = AnimationUtils.decelerateInterpolator) {
            textScale = it.currentValue
        }

        private val blurRadiusAnimator = floatAnimator(100L) {
            blurRadius = it.currentValue
        }

        fun getGlobalOffset() = globalOffset

        fun setGlobalOffset(offset: Float) {
            globalOffset = offset
        }

        fun cancelBlur() {
            blurRadiusAnimator.snapTo(0f)
        }

        fun checkIsInScreen(scrollOffset: Float, targetOffset: Float): Boolean {
            val previousScrollOffset = scrollOffset - targetOffset
            val height = height
            return if (previousScrollOffset < scrollOffset) {
                globalOffset + height > previousScrollOffset && globalOffset < scrollOffset + deviceHeight
            } else {
                globalOffset + height > scrollOffset && globalOffset < previousScrollOffset + deviceHeight
            }
        }

        fun updateImmediately(targetIndex: Int) {
            val isActivated = index == targetIndex
            setActiveLine(isActivated)
            val targetAlpha = alphaFor(index, targetIndex)
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius = (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            offsetFractionAnimator.snapTo(1f)
            alphaAnimator.snapTo(targetAlpha)
            scaleAnimator.snapTo(targetScale)
            blurRadiusAnimator.snapTo(targetBlurRadius)
        }

        fun update(targetIndex: Int, preventBlurUpdate: Boolean = false) {
            val isActivated = index == targetIndex
            setActiveLine(isActivated)
            val targetAlpha = alphaFor(index, targetIndex)
            val targetScale = if (isActivated) ACTIVE_SCALE else INACTIVE_SCALE
            val targetBlurRadius = (abs(index - targetIndex) * blurRadiusStep).coerceAtMost(maxBlurRadius)

            val delay = if (index < targetIndex) {
                0L
            } else {
                ((index - targetIndex) * 20L + 10L).coerceAtMost(190L)
            }
            // Brightness follows the movement rather than arriving after it. At 250 ms the line
            // had finished travelling before it began to light, so every change was two events -
            // the sheet moved, then a beat later it changed its mind about which line mattered.
            val secondaryDelay = delay + 110L

            targetOffset = textOffset
            offsetFractionAnimator.startDelay = delay
            offsetFractionAnimator.start()

            if (alphaAnimator.targetValue != targetAlpha) {
                alphaAnimator.startDelay = secondaryDelay
                alphaAnimator.animateTo(targetAlpha)
            }

            if (scaleAnimator.targetValue != targetScale) {
                scaleAnimator.startDelay = secondaryDelay
                scaleAnimator.animateTo(targetScale)
            }

            if (!preventBlurUpdate) {
                if (blurRadiusAnimator.targetValue != targetBlurRadius) {
                    blurRadiusAnimator.startDelay = secondaryDelay
                    blurRadiusAnimator.animateTo(targetBlurRadius)
                }
            }
        }
    }

    private companion object {
        /** `adb logcat -s LyricsSync` — see [traceWord]. */
        const val TRACE_TAG = "LyricsSync"

        /** Stands in for "no word is lit", so the fast path stays a long comparison. */
        const val GAP_MARKER = Long.MIN_VALUE + 1

        const val BLUR_NODE_NAME = "LyricsLineViewBlurNode"

        const val DOT_COUNT = 3
        val DOT_RADIUS = 5.dp
        val DOT_SPACING = 7.dp
        val INTERLUDE_ROW_PADDING = 16.dp

        /** Unlit, but present: the wait is three dots from the start, not one that gains two. */
        const val DOT_IDLE_ALPHA = 0.3f
        const val DOT_LIT_ALPHA = 0.95f
        const val DOT_LIT_GROWTH = 0.22f

        /** A slow, shallow pulse. Anything faster reads as a spinner, which promises a wait. */
        const val DOT_BREATH_MS = 2_400L
        const val DOT_BREATH_SCALE = 0.06f

        /** The last stretch of the gap, where the dots hand over to the line they counted to. */
        const val DOT_EXIT_FROM = 0.9f
        const val DOT_EXIT_SWELL = 0.35f

        /**
         * How visible a line is, by how far it is from the one being sung.
         *
         * Three lines carry the song - the one before, the one now, the one next - so those are
         * the ones lifted out. The rest stay legible rather than being erased: the whole lyric is
         * still there to read ahead in and scroll through, just quieter.
         */
        fun alphaFor(index: Int, targetIndex: Int): Float = when (abs(index - targetIndex)) {
            0 -> ACTIVE_ALPHA
            1 -> NEIGHBOUR_ALPHA
            else -> INACTIVE_ALPHA
        }

        const val NEIGHBOUR_ALPHA = 0.45f

        const val ACTIVE_ALPHA = 0.9f

        /**
         * The rest of the line being sung, ahead of the sung colour.
         *
         * At 0.32 the line barely existed until the sweep reached it, so the active line read as
         * one lit word beside a dim one rather than as the line being sung - the sweep was a
         * reveal, and the eye had nothing to read ahead in. Held clearly above the neighbouring
         * lines: the whole line is what is being sung, and the colour says where in it.
         */
        const val UPCOMING_WORD_ALPHA = 0.55f

        // Readable, not shouting. At 0.2 the rest of the song disappeared entirely against
        // a pale album backdrop, which is not the same as being de-emphasised.
        const val INACTIVE_ALPHA = 0.34f
        const val ACTIVE_SCALE = 1f
        const val INACTIVE_SCALE = 0.96f
        // Depth, not distance. Eight pixels put the fourth line away into fog, so a sheet only
        // ever showed three readable lines and scrolling through it meant waiting for each line
        // to sharpen. Far lines stay quiet through their alpha; the blur only separates them.
        val maxBlurRadius = 5.dp.px
        val blurRadiusStep = 1.5f.dp.px

        /**
         * The line change.
         *
         * The old curve held for the first third of its 700 ms and then rushed - which is what
         * made the sheet lurch from line to line instead of moving with the song. It leaves
         * straight away now and spends its length settling, so a line change is one movement.
         */
        val offsetFractionInterpolator = PathInterpolator(0.33f, 0f, 0.15f, 1f)

        val blurs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (1..maxBlurRadius.roundToInt()).associateWith {
                val radius = it.toFloat()
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
            }
        } else {
            null
        }

        /** Quarter-pixel blur steps avoid allocating a mask filter for every glyph and frame. */
        val glyphBlurs = (1..48).associateWith {
            BlurMaskFilter(it / 4f, BlurMaskFilter.Blur.NORMAL)
        }

        fun glyphBlur(radius: Float): BlurMaskFilter? = if (radius < 0.125f) {
            null
        } else {
            glyphBlurs[(radius * 4f).roundToInt().coerceIn(1, 48)]
        }

    }
}
