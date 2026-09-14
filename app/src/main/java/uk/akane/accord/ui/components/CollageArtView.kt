package uk.akane.accord.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * A mosaic of the covers in a mix, where each cover's tile is sized by how much of the mix it is.
 *
 * The grid used to be six fixed slots filled in order, and any slot without its own cover repeated
 * the first one - so a mix drawn mostly from one album showed that sleeve five or six times as
 * separate tiles, which reads as a bug rather than as a collage.
 *
 * Now a cover appears once, and its share of the artwork matches its share of the mix: three
 * appearances out of six is half the area, not six identical squares. A mix from a single album is
 * simply that sleeve, full size.
 */
class CollageArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    /** One entry per distinct cover, carrying how many of the supplied covers it accounted for. */
    private class Slice(val uri: Uri, val weight: Int, var bitmap: Bitmap?)

    private var currentUris: List<Uri> = emptyList()
    private var slices: List<Slice> = emptyList()
    private var loadJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val MAX_SLICES = 6
        private const val MIN_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        private val cacheBytes = (Runtime.getRuntime().maxMemory() / 16L)
            .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
            .toInt()
        private val cache = object : android.util.LruCache<Uri, Bitmap>(cacheBytes) {
            override fun sizeOf(key: Uri, value: Bitmap): Int = value.allocationByteCount
        }
        private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val decodeSlots = Semaphore(4)
        private val inFlight = ConcurrentHashMap<Uri, Deferred<Bitmap?>>()

        /** Starts with the covers nearest the top of Home before RecyclerView asks for them. */
        fun prefetch(context: Context, uris: Iterable<Uri>, limit: Int = 48) {
            val appContext = context.applicationContext
            uris.asSequence().distinct().take(limit).forEach { uri ->
                if (cache.get(uri) == null) imageScope.launch { loadShared(appContext, uri) }
            }
        }

        /**
         * One decode per URI across every collage currently being laid out.
         *
         * The application context, unwrapped here rather than inside the decode. This coroutine is
         * shared, parked in a static map until it finishes, and queued behind three other decodes
         * before it starts - so the lambda outlives the screen that asked for the cover by a long
         * way. Capturing the caller's Activity kept a destroyed MainActivity alive, and with it
         * every bitmap and view it owned: LeakCanary put the bill at 34.6 MB.
         */
        private suspend fun loadShared(context: Context, uri: Uri): Bitmap? {
            cache.get(uri)?.let { return it }
            val appContext = context.applicationContext
            val candidate = imageScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                decodeSlots.withPermit {
                    val request = ImageRequest.Builder(appContext)
                        .data(uri)
                        .size(256, 256)
                        .build()
                    val result = appContext.imageLoader.execute(request)
                    if (result is SuccessResult) {
                        result.image.toBitmap().also { cache.put(uri, it) }
                    } else null
                }
            }
            val shared = inFlight.putIfAbsent(uri, candidate)
            if (shared == null) {
                candidate.invokeOnCompletion { inFlight.remove(uri, candidate) }
                candidate.start()
                return candidate.await()
            }
            candidate.cancel()
            return shared.await()
        }
    }

    /**
     * @param uris the covers of the mix, duplicates included - the repeats are the weighting, so
     *   passing a de-duplicated list gives every cover an equal share.
     */
    fun setCovers(uris: List<Uri>) {
        if (currentUris == uris) return
        currentUris = uris
        loadJob?.cancel()

        if (uris.isEmpty()) {
            slices = emptyList()
            invalidate()
            return
        }

        // Heaviest first so the biggest tile is the cover the mix actually leans on.
        slices = uris.groupingBy { it }.eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_SLICES)
            .map { (uri, count) -> Slice(uri, count, cache.get(uri)) }
        loadMissingSlices()
    }

    private fun loadMissingSlices() {
        // A view may detach while its decode is in flight and reattach without another bind.
        // Pull anything prefetched in the meantime from the shared cache before starting work.
        slices.forEach { slice ->
            if (slice.bitmap == null) slice.bitmap = cache.get(slice.uri)
        }
        invalidate()
        if (slices.all { it.bitmap != null }) return

        loadJob?.cancel()
        loadJob = scope.launch {
            val pending = slices
            // A six-cover collage used to await six image requests serially. Coil's disk cache is
            // still the source of truth, but these independent decodes can finish in parallel.
            val fetched = pending.map { slice ->
                async { slice.bitmap ?: loadShared(context, slice.uri) }
            }.awaitAll()
            // Discard if setCovers ran again while this was in flight.
            if (slices !== pending) return@launch
            pending.forEachIndexed { index, slice -> slice.bitmap = fetched[index] }
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        loadJob?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (currentUris.isNotEmpty() && slices.any { it.bitmap == null }) loadMissingSlices()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = null
        paint.color = 0xFF1A1A1C.toInt()
        canvas.drawRect(0f, 0f, w, h, paint)

        val drawable = slices.filter { it.bitmap != null }
        if (drawable.isEmpty()) return

        val tiles = tileRects(RectF(0f, 0f, w, h), drawable.map { it.weight.toFloat() })
        tiles.forEachIndexed { index, rect ->
            drawable[index].bitmap?.let { drawCenterCropped(canvas, it, rect) }
        }
        // Only interior edges need a seam; the outer border is the card's own boundary.
        if (tiles.size > 1) tiles.forEach { canvas.drawRect(it, linePaint) }
    }

    /**
     * Splits [bounds] into one rect per weight, each with an area proportional to its weight.
     *
     * Slice-and-dice: halve the weights into two groups of roughly equal total, cut the rectangle
     * across its longer side in that ratio, and recurse into each half. Cutting the longer side
     * keeps tiles near-square rather than letting a heavy cover become a thin band, which matters
     * because these are album sleeves.
     */
    private fun tileRects(bounds: RectF, weights: List<Float>): List<RectF> {
        if (weights.size <= 1) return listOf(bounds)

        val total = weights.sum()
        if (total <= 0f) return listOf(bounds)

        // Split point closest to half the total weight, keeping at least one on each side.
        var running = 0f
        var splitAt = 0
        var bestDelta = Float.MAX_VALUE
        for (index in 0 until weights.size - 1) {
            running += weights[index]
            val delta = kotlin.math.abs(running - total / 2f)
            if (delta < bestDelta) {
                bestDelta = delta
                splitAt = index + 1
            }
        }

        val firstWeights = weights.subList(0, splitAt)
        val secondWeights = weights.subList(splitAt, weights.size)
        val firstFraction = firstWeights.sum() / total

        val (firstBounds, secondBounds) = if (bounds.width() >= bounds.height()) {
            val cut = bounds.left + bounds.width() * firstFraction
            RectF(bounds.left, bounds.top, cut, bounds.bottom) to
                RectF(cut, bounds.top, bounds.right, bounds.bottom)
        } else {
            val cut = bounds.top + bounds.height() * firstFraction
            RectF(bounds.left, bounds.top, bounds.right, cut) to
                RectF(bounds.left, cut, bounds.right, bounds.bottom)
        }

        return tileRects(firstBounds, firstWeights) + tileRects(secondBounds, secondWeights)
    }

    private fun drawCenterCropped(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val targetRatio = dst.width() / dst.height()
        val bitmapRatio = bw / bh

        val src: Rect = if (bitmapRatio > targetRatio) {
            val srcW = bh * targetRatio
            val left = (bw - srcW) / 2f
            Rect(left.toInt(), 0, (left + srcW).toInt(), bh.toInt())
        } else {
            val srcH = bw / targetRatio
            val top = (bh - srcH) / 2f
            Rect(0, top.toInt(), bw.toInt(), (top + srcH).toInt())
        }

        canvas.drawBitmap(bitmap, src, dst, paint)
    }
}
