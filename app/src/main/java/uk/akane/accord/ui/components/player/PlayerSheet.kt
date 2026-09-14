package uk.akane.accord.ui.components.player

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import kotlin.math.min

/**
 * The one floating card this app puts over the player.
 *
 * Play on introduced it - a narrow, rounded, glass panel that floats above the bottom edge rather
 * than filling the width like a stock bottom sheet - and everything else that needs to say
 * something about what is playing should be the same object rather than a second sheet that merely
 * resembles it. The signal-path sheet behind the quality badge is the second such caller.
 *
 * What a caller still owns is its own content: this handles the window, the shape, the glass, and
 * the system bars.
 */
object PlayerSheet {

    /** A sheet with the shared theme. Fill it, then hand it to [present]. */
    fun create(context: Context): BottomSheetDialog =
        BottomSheetDialog(context, R.style.Theme_Accord_OutputPicker)

    /**
     * Shows [sheet] as the floating card, over [root].
     *
     * @param onPositioned runs once the card has been laid out at its resting place, which is when
     *   a backdrop capture is worth taking.
     */
    fun present(
        activity: Activity,
        sheet: BottomSheetDialog,
        root: View,
        onPositioned: (() -> Unit)? = null,
    ) {
        val resources = activity.resources
        sheet.show()
        keepSystemBars(activity, sheet)
        sheet.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = DIM_AMOUNT }
        }
        sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            val configuration = resources.configuration
            val maxWidthDp = if (
                min(configuration.screenWidthDp, configuration.screenHeightDp) >= TABLET_WINDOW_DP
            ) TABLET_MAX_WIDTH_DP else MAX_WIDTH_DP
            updateLayoutParams<CoordinatorLayout.LayoutParams> {
                width = min(
                    maxWidthDp.dp.px.toInt(),
                    resources.displayMetrics.widthPixels - 24.dp.px.toInt(),
                )
                // BottomSheetBehavior owns the vertical position. Including BOTTOM here made it
                // apply the card height twice on short landscape windows, leaving only the header
                // visible at the bottom edge.
                gravity = Gravity.CENTER_HORIZONTAL
            }
            elevation = 24.dp.px
            val margin = (
                if (configuration.screenHeightDp < 480) 10F else BOTTOM_MARGIN_DP
            ).dp.px
            doOnLayout {
                val navigationInset = rootWindowInsets?.let {
                    WindowInsetsCompat.toWindowInsetsCompat(it)
                        .getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                } ?: 0
                translationY = -(margin + navigationInset)
                onPositioned?.let { root.post(it) }
            }
        }
        sheet.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
        }
    }

    /**
     * Gives the sheet the same system bars as the screen it opens over.
     *
     * A dialog is its own window, and a new window comes up with the bars showing: opening Play on
     * from the expanded player put the status bar back over it, and closing the sheet did not
     * always take it away again. Whether the bars belong on screen is the host window's answer, so
     * read it there rather than deciding here - a sheet opened from a browsing screen with the bars
     * up keeps them up. The window takes focus only once it has been told, so the swap is never
     * visible.
     */
    private fun keepSystemBars(activity: Activity, sheet: BottomSheetDialog) {
        val window = sheet.window ?: return
        val hostInsets = activity.window.decorView.rootWindowInsets ?: return
        val barsVisible = WindowInsetsCompat.toWindowInsetsCompat(hostInsets)
            .isVisible(WindowInsetsCompat.Type.statusBars())
        if (barsVisible) return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Copies what is behind the card into [backdrop] and blurs it, which is what makes the glass.
     *
     * A single snapshot, not a live blur: cheap, and the sheet is only up for a moment. Callers
     * whose content outlives a track change take it again from there.
     */
    fun captureBackdrop(
        activity: Activity,
        sheet: BottomSheetDialog,
        root: View,
        backdrop: ImageView,
        onCaptured: (Bitmap) -> Unit,
    ) {
        if (!sheet.isShowing || root.width <= 0 || root.height <= 0) return
        // Every capture is posted - from a layout pass, from a track change, from the height cap -
        // and any of those can land after the activity behind the sheet has been stopped. Its
        // window keeps answering questions about itself long after it has given up its surface,
        // and `PixelCopy.request` throws IllegalArgumentException the moment it finds one missing.
        if (activity.isFinishing || activity.isDestroyed) return
        val source = activity.window.peekDecorView() ?: return
        if (!source.isAttachedToWindow || source.windowToken == null) return
        val rootPosition = IntArray(2).also(root::getLocationOnScreen)
        val sourcePosition = IntArray(2).also(source::getLocationOnScreen)
        val sourceRect = Rect(
            rootPosition[0] - sourcePosition[0],
            rootPosition[1] - sourcePosition[1],
            rootPosition[0] - sourcePosition[0] + root.width,
            rootPosition[1] - sourcePosition[1] + root.height,
        )
        val windowBounds = Rect(0, 0, source.width, source.height)
        if (!sourceRect.intersect(windowBounds) || sourceRect.isEmpty) return
        val next = Bitmap.createBitmap(
            (sourceRect.width() / BACKDROP_DOWNSAMPLE).coerceAtLeast(1),
            (sourceRect.height() / BACKDROP_DOWNSAMPLE).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        // Guarded above and still wrapped: the surface can go away between the check and the call,
        // and this is a decoration. A sheet with a flat backdrop is worth far less than a crash.
        val requested = runCatching {
            PixelCopy.request(activity.window, sourceRect, next, { result ->
                if (result != PixelCopy.SUCCESS || !sheet.isShowing) {
                    next.recycle()
                    return@request
                }
                backdrop.setImageBitmap(next)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val blur = BACKDROP_BLUR_DP.dp.px
                    backdrop.setRenderEffect(
                        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.MIRROR)
                    )
                }
                onCaptured(next)
            }, Handler(Looper.getMainLooper()))
        }
        requested.exceptionOrNull()?.let {
            Log.d(TAG, "Backdrop capture skipped: $it")
            next.recycle()
        }
    }

    private const val TAG = "PlayerSheet"
    private const val TABLET_WINDOW_DP = 600
    private const val MAX_WIDTH_DP = 356F
    private const val TABLET_MAX_WIDTH_DP = 420F
    private const val BOTTOM_MARGIN_DP = 18F
    private const val DIM_AMOUNT = 0.18F
    const val BACKDROP_DOWNSAMPLE = 4
    private const val BACKDROP_BLUR_DP = 18F
}
