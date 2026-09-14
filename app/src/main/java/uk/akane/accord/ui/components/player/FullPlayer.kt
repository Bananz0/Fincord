package uk.akane.accord.ui.components.player

import android.Manifest
import uk.akane.accord.ui.viewmodels.MediaControllerViewModel
import uk.akane.accord.ui.viewmodels.registerLifecycleCallback
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.KeyEvent
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.marginBottom
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop
import androidx.core.view.updateLayoutParams
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import coil3.asDrawable
import coil3.imageLoader
import coil3.load
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import android.widget.TextView
import androidx.media3.common.Tracks
import androidx.annotation.DrawableRes
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import org.akanework.gramophone.logic.data.automix.Automix
import org.akanework.gramophone.logic.data.automix.AutomixQueueOrdering
import org.akanework.gramophone.logic.data.jellyfin.JellyfinItemResolver
import org.akanework.gramophone.logic.data.jellyfin.JellyfinKaraoke
import org.akanework.gramophone.logic.data.jellyfin.JellyfinRemoteTargets
import org.akanework.gramophone.logic.data.jellyfin.KaraokeMediaItems
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.RepeatMode
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader.Companion.EXTRA_SOURCE_CONTAINER
import org.akanework.gramophone.logic.data.jellyfin.StreamQuality
import org.akanework.gramophone.logic.utils.AudioQuality
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import uk.akane.accord.ui.components.lyrics.Lyrics
import uk.akane.accord.ui.components.lyrics.LyricsLine
import uk.akane.accord.ui.components.lyrics.LyricsWordTiming
import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat
import androidx.media3.session.SessionCommand
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import android.media.AudioDeviceCallback
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import org.akanework.gramophone.logic.utils.AudioOutput
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min
import uk.akane.accord.R
import uk.akane.accord.logic.ArtistCredits
import uk.akane.accord.logic.dp
import uk.akane.accord.logic.inverseLerp
import uk.akane.accord.logic.playOrPause
import uk.akane.accord.logic.setTextAnimation
import uk.akane.accord.logic.utils.CalculationUtils.convertDurationToTimeStamp
import uk.akane.accord.logic.utils.CalculationUtils.lerp
import uk.akane.accord.ui.adapters.QueueItemTouchHelperCallback
import uk.akane.accord.logic.UserQueue
import uk.akane.accord.ui.adapters.QueuePreviewAdapter
import androidx.core.view.updatePaddingRelative
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaStatus
import uk.akane.accord.logic.cast.FincordCast
import uk.akane.accord.logic.player.OutputRouting
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import uk.akane.accord.logic.cast.CastOptionsProvider
import uk.akane.accord.ui.components.Haptics
import uk.akane.accord.ui.components.ProceduralMotionTicker
import uk.akane.accord.ui.components.QueueSectionDecoration
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.QueueItem
import uk.akane.accord.ui.adapters.browse.PlaylistAdapter
import uk.akane.accord.ui.components.FadingVerticalEdgeLayout
import uk.akane.accord.ui.components.ResistiveSwipeHaptics
import uk.akane.accord.ui.components.SplitTintImageView
import uk.akane.accord.ui.components.SplitTintTextView
import uk.akane.accord.ui.components.performPressHaptic
import uk.akane.accord.ui.components.resistedSwipeDistance
import uk.akane.accord.ui.components.lyrics.LyricsViewModel
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import uk.akane.cupertino.widget.text.OverlayTextView
import uk.akane.cupertino.widget.button.AnimatedVectorButton
import uk.akane.cupertino.widget.button.OverlayBackgroundButton
import uk.akane.cupertino.widget.button.OverlayButton
import uk.akane.cupertino.widget.button.OverlayPillButton
import uk.akane.cupertino.widget.button.StarTransformButton
import uk.akane.cupertino.widget.button.StateAnimatedVectorButton
import uk.akane.cupertino.widget.divider.OverlayDivider
import uk.akane.cupertino.widget.image.OverlayHintView
import uk.akane.cupertino.widget.image.SimpleImageView
import uk.akane.cupertino.widget.slider.OverlaySlider
import uk.akane.cupertino.widget.special.BlendView
import uk.akane.cupertino.utils.AnimationUtils
import uk.akane.cupertino.utils.AnimationUtils.LONG_DURATION
import uk.akane.cupertino.utils.AnimationUtils.MID_DURATION
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd
import androidx.preference.PreferenceManager
import org.akanework.gramophone.logic.data.jellyfin.QueuePrefetcher
import org.akanework.gramophone.logic.data.EmbeddedArtworkStore
import org.akanework.gramophone.logic.data.library.songListSnapshot
import org.akanework.gramophone.logic.data.lyrics.AutomaticLyricsTranslator
import org.akanework.gramophone.logic.data.AutoplayQueue
import uk.akane.accord.logic.player.UsbHiFiStatus
import uk.akane.accord.logic.player.AfFormatInfo
import uk.akane.accord.logic.player.BtCodecInfo

@androidx.annotation.OptIn(UnstableApi::class)
class FullPlayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr, defStyleRes),
    FloatingPanelLayout.OnSlideListener, FloatingPanelLayout.CoverSwipeHandler, Player.Listener {

    private val activity
        get() = context as MainActivity
    private val instance: MediaController?
        get() = activity.getPlayer()

    private var initialMargin = IntArray(4)

    private var meshGradientView: FlowingGradientView
    private var liquidGradientView: dev.kawarp.KawarpView
    private var blendView: BlendView
    private var overlayDivider: OverlayDivider
    private var fadingEdgeLayout: FadingVerticalEdgeLayout
    private var lyricsBtn: Button
    private var volumeOverlaySlider: OverlaySlider
    private var progressOverlaySlider: OverlaySlider
    private var speakerHintView: OverlayHintView
    private var speakerFullHintView: OverlayHintView
    private var currentTimestampTextView: OverlayTextView
    private var leftTimestampTextView: OverlayTextView
    private var coverSimpleImageView: SimpleImageView
    private var titleTextView: OverlayTextView
    private var subtitleTextView: OverlayTextView
    private var listOverlayButton: OverlayButton
    private var airplayOverlayButton: OverlayButton
    private var captionOverlayButton: OverlayButton
    private var karaokeOverlayButton: OverlayButton
    private var karaokeStatus: TextView
    private var starTransformButton: StarTransformButton
    private var controllerButton: StateAnimatedVectorButton
    private var previousButton: AnimatedVectorButton
    private var nextButton: AnimatedVectorButton
    private var ellipsisButton: OverlayBackgroundButton
    private var qualityBadge: TextView
    private var bitrateBadge: TextView
    private var qualityAvailableHint: TextView
    private var currentQualityDetails: AudioQuality.Details? = null
    /** How far through the two-second announcement the badge is; 1 whenever it is permanent. */
    private var qualityFlashAlpha = 1F
    private var qualityFlashing = false
    private var qualityFlashAnimator: ValueAnimator? = null
    private var currentUsbHiFiStatus: UsbHiFiStatus? = null
    private var currentAfFormatInfo: AfFormatInfo? = null
    private var currentBtCodecInfo: BtCodecInfo? = null
    /** Rebinds an open Play on sheet when the service reports a new codec/HAL route. */
    private var outputPickerFormatChanged: (() -> Unit)? = null
    private var outputPickerDialog: BottomSheetDialog? = null
    /** Re-binds the open output picker to the current track; null when no sheet is up. */
    private var outputPickerRefresh: (() -> Unit)? = null
    private var castSessionJob: kotlinx.coroutines.Job? = null

    /**
     * True while something other than this phone's speaker is playing.
     *
     * Asked of the controller rather than of Cast or Finnect directly, and deliberately so. The
     * playback service puts whichever player owns the output in front of the session, so the
     * controller already describes the thing that is actually playing - its position, its queue,
     * its volume. Reading a receiver's state around the side of the session is what used to leave
     * the expanded player showing one thing and the notification, the mini bar and every other app
     * showing another.
     */
    private val remoteOutputActive: Boolean
        get() = instance?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
    /** True while a finger is on one of the picker's volume sliders; see `renderRows`. */
    private var outputVolumeDragging = false

    /**
     * Whether a sheet volume gesture is still in flight, momentum included.
     *
     * [outputVolumeDragging] goes false when the finger lifts, but the fling carries the bar on
     * afterwards. Re-rendering a row or re-reading the level during that window snaps the slider
     * back to where the finger left it - the same stall the player's own bar had.
     */
    private fun outputVolumeBusy(): Boolean =
        outputVolumeDragging ||
            outputPickerPhoneVolumeSlider?.isMomentumFlingOngoing == true ||
            routeVolumeSliders.values.any { it.isMomentumFlingOngoing }
    private var outputPickerPhoneVolumeSlider: OverlaySlider? = null
    private val routeVolumeSliders = mutableMapOf<String, OverlaySlider>()
    private var outputPickerOpening = false
    private var outputDeviceIcon: ImageView
    private var outputDeviceName: TextView
    private var remoteTargetJob: kotlinx.coroutines.Job? = null
    private var remotePlaybackJob: kotlinx.coroutines.Job? = null
    private var remoteTargetsWarmupJob: kotlinx.coroutines.Job? = null
    private var karaokeJob: Job? = null
    private var karaokeMediaId: String? = null
    private var karaokeSwitchMediaId: String? = null
    private var karaokeAvailable = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private val outputRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
        .apply {
            // Cast registers itself as a MediaRouter provider, so adding its category is all it
            // takes for Chromecasts and speaker groups to arrive in the list the picker already
            // renders - one route stack rather than a second one competing beside it. Added only
            // when the framework actually came up, so a device without Play Services does not
            // advertise a category nothing can serve.
            if (FincordCast.isAvailable) {
                addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastOptionsProvider.RECEIVER_APPLICATION_ID
                    )
                )
            }
        }
        .build()
    private val outputMediaRouter by lazy(LazyThreadSafetyMode.NONE) {
        MediaRouter.getInstance(context)
    }
    private var outputRouteCallbackRegistered = false
    private val outputRouteCallback = object : MediaRouter.Callback() {
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshOutputDevice()
        }

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            refreshOutputDevice()
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            if (route.isSelected) refreshOutputDevice()
        }
    }

    private var fullPlayerToolbar: FullPlayerToolbar
    private var queueContainer: View
    private var queueShuffleButton: OverlayPillButton
    private var queueRepeatButton: OverlayPillButton
    private var queueAutoplayButton: OverlayPillButton
    private var queueAutomixButton: OverlayPillButton
    private var queueTextView: OverlayTextView
    private var queueRecyclerView: RecyclerView
    private var queueItemTouchHelper: ItemTouchHelper? = null

    private var lyricsViewModel: LyricsViewModel? = null

    /** onViewCreated builds the line views, so it must run once and not on every toggle. */
    private var lyricsViewAttached = false
    private val floatingPanelLayout: FloatingPanelLayout
        get() = parent as FloatingPanelLayout

    private var firstTime = false
    private var positionUpdateRunning = false
    private var isUserScrubbing = false
    private var isUserVolumeScrubbing = false
    private var coverBaseScale = 1F
    private var coverBaseTranslationX = 0F
    /**
     * How far the artwork is displaced from where it belongs, by a drag or a track change.
     *
     * Kept as an offset rather than by writing translationX directly, so it composes with the
     * paused-state shrink and the panel's slide transform instead of racing them. Suppressing
     * those during a slide and snapping at the end is what made the movement finish with a jolt.
     */
    private var coverSlideOffsetX = 0F
    private var coverSlideAnimator: ValueAnimator? = null
    private val coverSwipeHaptics = ResistiveSwipeHaptics()
    private var pendingCoverReleaseVelocity = 0F
    /** The item the cover currently shows, so a same-track playlist replacement can skip the slide. */
    private var lastTransitionMediaId: String? = null

    /**
     * Re-requests the cover when a swipe left the current track without one.
     *
     * Swiping through several tracks cancels each load as the next transition arrives, which is
     * right - that artwork is no longer wanted. What it cannot know is which track the finger
     * finally stops on: if that one's request was the last to be cancelled, nothing re-issues it
     * and the cover stays on the placeholder for the rest of the song.
     *
     * Deliberately keyed on the identity rather than a flag. It fires only when the current track
     * is neither showing its artwork nor waiting on a live request for it, so a load still in
     * flight is left alone rather than restarted.
     */
    private val coverIntegrityCheck = Runnable {
        val item = instance?.currentMediaItem ?: return@Runnable
        val artwork = item.mediaMetadata.artworkUri ?: return@Runnable
        val identity = "${item.mediaId}:$artwork"
        if (appliedArtworkIdentity != identity && loadedArtworkIdentity != identity) {
            Log.d(TAG, "COVERDBG integrity retry for ${item.mediaMetadata.title}") // TEMP-COVERDBG
            loadCoverForImageView()
        }
    }

    /** Brings the cover back even if the artwork has not arrived; see [startCoverSlideOut]. */
    private val coverSlideInDeadline = Runnable {
        if (coverSlideInFlight && !coverSlideArtReady) {
            coverSlideArtReady = true
            slideCoverInIfReady()
        }
    }

    /**
     * Points the artwork at the side it will leave by, with a deadline.
     *
     * The direction has to be set before the seek, because the transition it describes can arrive
     * in the same frame. The backstop is what makes that safe: a skip the player declines to make
     * emits no transition at all, and without it the direction stays armed until something else
     * causes one - by which time it describes a movement nobody asked for.
     *
     * A swipe passes the shorter [COVER_SLIDE_BACKSTOP_MS] because its cover is already displaced
     * and has to snap back promptly. A button press has moved nothing yet, so it can afford to
     * wait long enough for a receiver on the other side of the network to answer.
     */
    private fun armCoverSlide(direction: Int, timeoutMs: Long = COVER_SLIDE_ARM_TIMEOUT_MS) {
        pendingCoverSlide = direction
        removeCallbacks(coverSlideBackstop)
        postDelayed(coverSlideBackstop, timeoutMs)
    }

    /** Puts the cover back if a requested skip turned out not to happen. */
    private val coverSlideBackstop = Runnable {
        if (!coverSlideInFlight && pendingCoverSlide != SLIDE_NONE) {
            pendingCoverSlide = SLIDE_NONE
            pendingCoverReleaseVelocity = 0F
            animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
        }
    }
    /** Where the artwork sits when nothing is moving it; see [updateCoverTransform]. */
    private var coverRestingTranslationX = 0F
    /** Which way the artwork should travel on the next track change, or [SLIDE_NONE]. */
    private var pendingCoverSlide = SLIDE_NONE
    /**
     * True from the moment the outgoing artwork starts moving until the incoming one has landed.
     *
     * While it is set, nothing else may write the cover's translationX. The paused-state shrink and
     * the panel's slide transform both do so whenever playback state changes - which is exactly what
     * a track change causes - and their writes landing mid-animation is what made the movement
     * stutter.
     */
    private var coverSlideInFlight = false
    private var coverSlideOutDone = false
    private var coverSlideArtReady = false
    /**
     * True once the returning cover has been given its drawable, which is the moment after which
     * late artwork belongs on the view rather than in [pendingCoverDrawable].
     *
     * [coverSlideArtReady] cannot answer that question: the deadline sets it to mean "stop waiting",
     * not "the artwork is here", and it fires at 90 ms while the cover can still be sliding out at
     * 180 ms. Artwork landing in that window was written straight to the view - correctly, as far
     * as the old test could tell - and then painted over with the placeholder when the slide-out
     * finished and found nothing pending. The big cover kept the empty sleeve for the rest of the
     * track while the mini bar, the toolbar and the backdrop all showed the real one.
     */
    private var coverSlideInStarted = false
    /** Last artwork applied, also used to seed the transition layer created on first layout. */
    private var appliedCoverBitmap: android.graphics.Bitmap? = null
    /** The artwork the backdrop is currently built from, so an unchanged cover leaves it running. */
    private var lastBackdropArtwork: Uri? = null
    /** The artwork [applyCover] is currently delivering, used to key the backdrop. */
    private var currentArtworkUri: Uri? = null
    /**
     * The identity [appliedCoverBitmap] actually holds, as opposed to [loadedArtworkIdentity],
     * which names the load most recently *started*. The two differ for the length of every load.
     */
    private var appliedArtworkIdentity: String? = null
    /**
     * The incoming artwork, held back until the outgoing cover has left.
     *
     * Cached artwork arrives within a frame or two, so applying it as soon as it loads meant the
     * cover that slid away was already showing the *new* track - the old one never left, and the
     * same picture slid out and back in.
     */
    private var pendingCoverDrawable: android.graphics.drawable.Drawable? = null
    private var slideFraction = 0F
    private var coverBaseTranslationY = 0F
    private var coverPauseScale = 1F
    private var coverPauseAnimator: ValueAnimator? = null
    private var maxDeviceVolume = 0
    private var volumeUpdateAnimator: ValueAnimator? = null
    private val audioManager by lazy {
        ContextCompat.getSystemService(context, AudioManager::class.java)
    }
    private var isVolumeReceiverRegistered = false
    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "android.media.VOLUME_CHANGED_ACTION",
                "android.media.MASTER_VOLUME_CHANGED_ACTION",
                "android.media.MASTER_MUTE_CHANGED_ACTION",
                "android.media.STREAM_MUTE_CHANGED_ACTION" -> {
                    updateVolumeSlider()
                    updatePhoneOutputSlider()
                }
            }
        }
    }
    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgressDisplay()
            if (positionUpdateRunning) {
                postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    init {
        inflate(context, R.layout.layout_full_player, this)

        meshGradientView = findViewById(R.id.mesh_gradient)
        liquidGradientView = findViewById(R.id.liquid_gradient)
        blendView = findViewById(R.id.blend_view)
        liquidGradientView.engine?.apply {
            // The crossfade between covers, so a track change dissolves the backdrop instead of
            // cutting to the next one. Matched to the app's own longer transitions rather than the
            // cover slide, which is a much faster movement over a field that should still be
            // settling behind it.
            setTransitionDuration(BACKDROP_CROSSFADE_MS)
            // This is an ambient player backdrop, not a playback meter. It remains alive while a
            // restored queue is paused, including the first frame of a cold start.
            setPlaybackReactive(false)
            setSaturation(1.25F)
            setAutoDarken(0.8F)
        }
        // These are alternative renderers, not layers. Drawing the old BlendView over the mesh
        // produced a second blur boundary through the lower action row and kept two animations
        // running for one background.
        meshGradientView.setEnabledStateListener { enabled ->
            // One preference, two implementations: kawarp needs AGSL, so it renders nothing at all
            // below API 33 and the older four-layer field stays in charge there. Above it, kawarp
            // is the better renderer of the same setting and the older one steps aside rather than
            // drawing a second animated background underneath.
            val liquid = enabled && dev.kawarp.KawarpEngine.isSupported()
            liquidGradientView.visibility = if (liquid) VISIBLE else GONE
            if (liquid) {
                // Set after FlowingGradientView has shown itself in syncEnabledState, and before
                // it starts its frame loop - so it never animates a field nobody can see.
                meshGradientView.visibility = GONE
                liquidGradientView.setCover(appliedCoverBitmap.readablePixels())
            }
            blendView.visibility = if (enabled) GONE else VISIBLE
            if (enabled) {
                blendView.stopRotationAnimation()
            } else {
                // Switching back must populate the renderer that was deliberately not fed while
                // hidden, then leave it moving whether playback is paused or active.
                blendView.setImageBitmap(appliedCoverBitmap.readablePixels())
                blendView.startRotationAnimation()
            }
            meshGradientView.setPlaying(true)
            liquidGradientView.setPlaying(true)
        }
        overlayDivider = findViewById(R.id.divider)
        fadingEdgeLayout = findViewById(R.id.fading)
        lyricsBtn = findViewById(R.id.lyrics)
        volumeOverlaySlider = findViewById(R.id.volume_slider)
        progressOverlaySlider = findViewById(R.id.progressBar)
        speakerHintView = findViewById(R.id.speaker_hint)
        speakerFullHintView = findViewById(R.id.speaker_full_hint)
        currentTimestampTextView = findViewById(R.id.current_timestamp)
        leftTimestampTextView = findViewById(R.id.left_timeStamp)
        coverSimpleImageView = findViewById(R.id.cover)
        titleTextView = findViewById(R.id.title)
        subtitleTextView = findViewById(R.id.subtitle)
        titleTextView.isSelected = true
        subtitleTextView.isSelected = true
        subtitleTextView.setOnClickListener { view ->
            val item = instance?.currentMediaItem ?: return@setOnClickListener
            val artist = ArtistCredits.primaryArtist(item)
            if (artist.isBlank() || artist == "(Unknown Artist)") return@setOnClickListener
            view.performPressHaptic()
            activity.collapseNowPlaying()
            activity.fragmentSwitcherView.addFragmentToCurrentStack(
                ArtistDetailFragment.newInstance(artist)
            )
        }
        listOverlayButton = findViewById(R.id.list)
        airplayOverlayButton = findViewById(R.id.airplay)
        captionOverlayButton = findViewById(R.id.caption)
        karaokeOverlayButton = findViewById(R.id.karaoke)
        karaokeStatus = findViewById(R.id.karaoke_status)
        configureWideLayout()
        starTransformButton = findViewById(R.id.star)
        ellipsisButton = findViewById(R.id.ellipsis)
        qualityBadge = findViewById(R.id.quality_badge)
        bitrateBadge = findViewById(R.id.bitrate_badge)
        qualityAvailableHint = findViewById(R.id.quality_available_hint)
        val openQualityDetails = View.OnClickListener {
            it.performPressHaptic()
            showQualityDetails()
        }
        qualityBadge.setOnClickListener(openQualityDetails)
        bitrateBadge.setOnClickListener(openQualityDetails)
        // Both badges sit inside the play button's 100dp square, and the button is declared after
        // them, so it was taking every press aimed at the badge - a control the size of a stamp
        // losing to one with 100dp of slack around its glyph. Children are hit-tested in reverse
        // draw order, and both the lift in the layout and this reordering put these two first.
        qualityBadge.bringToFront()
        bitrateBadge.bringToFront()
        outputDeviceIcon = findViewById(R.id.output_device_icon)
        outputDeviceName = findViewById(R.id.output_device_name)
        val openOutputPicker = View.OnClickListener {
            it.performPressHaptic()
            showOutputPicker()
        }
        outputDeviceIcon.setOnClickListener(openOutputPicker)
        outputDeviceName.setOnClickListener(openOutputPicker)
        controllerButton = findViewById(R.id.main_control_btn)
        previousButton = findViewById(R.id.backward_btn)
        nextButton = findViewById(R.id.forward_btn)
        fullPlayerToolbar = findViewById(R.id.full_player_tool_bar)
        queueContainer = findViewById(R.id.queue_container)
        queueShuffleButton = findViewById(R.id.btnShuffle)
        queueRepeatButton = findViewById(R.id.btnRepeat)
        queueAutoplayButton = findViewById(R.id.btnAutoplay)
        queueAutomixButton = findViewById(R.id.btnAutomix)
        queueTextView = findViewById(R.id.queue)
        queueRecyclerView = findViewById(R.id.queue_list)
        queueRecyclerView.layoutManager = LinearLayoutManager(context)
        // Advancing through tracks removes the first visible queue row. Several fast cover swipes
        // can otherwise stack predictive remove layouts for the same position, leaving a holder in
        // ChildHelper's attached set after LayoutManager believes it was detached. Queue motion is
        // supplied by ItemTouchHelper while the user reorders, so the default item animator adds no
        // useful interaction here and is unsafe under this bursty update pattern.
        queueRecyclerView.itemAnimator = null
        val queueAdapter = QueuePreviewAdapter(
            mutableListOf(),
            { moved, target ->
                instance?.let { player ->
                    val from = player.currentTimeline.indexOfWindow(moved.uid)
                    val to = player.currentTimeline.indexOfWindow(target.uid)
                    if (from >= 0 && to >= 0) player.moveMediaItem(from, to)
                }
            },
            { item ->
                // One path for every output. Seeking to a queue index on the controller reaches
                // whichever player owns playback, and each of them knows how to express that to
                // its own target - a Cast queue jump, a Jellyfin play-at-index, or a local seek.
                instance?.let { player ->
                    val index = player.currentTimeline.indexOfWindow(item.uid)
                    if (index >= 0) {
                        player.seekTo(index, C.TIME_UNSET)
                        player.play()
                    }
                }
            },
            object : QueuePreviewAdapter.DragStartListener {
                override fun onStartDrag(viewHolder: RecyclerView.ViewHolder) {
                    queueItemTouchHelper?.startDrag(viewHolder)
                }
            }
        )
        queueRecyclerView.adapter = queueAdapter
        queueItemTouchHelper = ItemTouchHelper(
            QueueItemTouchHelperCallback(queueAdapter, context) { index ->
                instance?.let { player ->
                    val item = queueAdapter.itemAt(index)
                    val timelineIndex = item?.let {
                        player.currentTimeline.indexOfWindow(it.uid)
                    } ?: -1
                    if (timelineIndex >= 0) player.removeMediaItem(timelineIndex)
                }
            }
        ).apply {
            attachToRecyclerView(queueRecyclerView)
        }
        queueRecyclerView.addItemDecoration(
            QueueSectionDecoration(queueRecyclerView) { position ->
                (queueRecyclerView.adapter as? QueuePreviewAdapter)?.sectionLabelAt(position)
            }
        )
        queueContainer.doOnLayout {
            queueEnterOffset = resolveQueueEnterOffset()
        }

        ellipsisButton.setOnCheckedChangeListener { v, _ ->
            v.performPressHaptic()
            callUpPlayerPopupMenu(v)
        }

        ellipsisButton.setOnLongClickListener {
            // TODO tell floating panel to intercept gesture
            it.performPressHaptic()
            callUpPlayerPopupMenu(it)
            true
        }

        fullPlayerToolbar.setOnEllipsisCheckedChangeListener(
            OverlayBackgroundButton.OnCheckedChangeListener { button, _ ->
                button.performPressHaptic()
                callUpPlayerPopupMenu(button)
            }
        )
        starTransformButton.setOnClickListener {
            toggleFavoriteForCurrentSong()
        }
        fullPlayerToolbar.setOnStarClickListener {
            toggleFavoriteForCurrentSong()
        }

        clipToOutline = true

        fadingEdgeLayout.visibility = GONE
        queueContainer.visibility = INVISIBLE
        lyricsViewModel = LyricsViewModel(
            context,
            // The controller's clock is the playing clock, whichever device is doing the playing.
            // Reading the local player here is why lyrics stood still during a cast: that player
            // is paused for the whole handoff, so its position never moved.
            positionProvider = { instance?.currentPosition ?: 0L },
            isPlayingProvider = { instance?.isPlaying == true },
            // The controller's position advances at the playback rate, so the local fill-in
            // between its updates has to as well.
            speedProvider = { instance?.playbackParameters?.speed ?: 1f },
            // Tapping a lyric jumps to it, which is the whole reason the timestamps are there.
            onSeek = { timestamp -> instance?.seekTo(timestamp) },
        )

        // The hidden button belongs to the pre-rewrite lyrics prototype. The three bottom buttons
        // are the actual mode controls in the working APK.
        lyricsBtn.visibility = GONE
        captionOverlayButton.setOnClickListener {
            it.performPressHaptic()
            toggleContentMode(ContentType.LYRICS)
        }
        karaokeOverlayButton.setOnClickListener {
            it.performPressHaptic()
            toggleKaraoke()
        }

        volumeOverlaySlider.addEmphasizeListener(object : OverlaySlider.EmphasizeListener {
            override fun onEmphasizeProgressLeft(translationX: Float) {
                speakerHintView.translationX = -translationX
            }

            override fun onEmphasizeProgressRight(translationX: Float) {
                speakerFullHintView.translationX = translationX
            }

            override fun onEmphasizeAll(fraction: Float) {
                speakerHintView.transformValue = fraction
                speakerFullHintView.transformValue = fraction
            }

            override fun onEmphasizeStartLeft() {
                speakerHintView.playAnim()
            }

            override fun onEmphasizeStartRight() {
                speakerFullHintView.playAnim()
            }
        })
        // Grab and release, not a tick per pixel. A slider fires value changes continuously and
        // the guidance is explicit that a cue at that rate stops being information; the two ends of
        // the gesture are the moments worth marking.
        val sliderTracking = object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) = Haptics.gestureStart(slider)
            override fun onStopTracking(slider: OverlaySlider) = Haptics.gestureEnd(slider)
        }
        volumeOverlaySlider.addValueChangeListener(sliderTracking)
        progressOverlaySlider.addValueChangeListener(sliderTracking)

        volumeOverlaySlider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) {
                isUserVolumeScrubbing = true
                volumeUpdateAnimator?.cancel()
            }

            override fun onValueChanged(slider: OverlaySlider, value: Float, fromUser: Boolean, fromMomentum: Boolean) {
                // Momentum counts, exactly as it does for the sheet's own sliders. Taking only
                // `fromUser` meant the bar kept travelling after the finger left while the volume
                // stayed at wherever it lifted - the bar ended up at one level and the sound at
                // another, which is precisely what a fling looked like.
                if (!fromUser && !fromMomentum) return
                setDeviceVolume(value.toInt())
            }

            override fun onStopTracking(slider: OverlaySlider) {
                setDeviceVolume(slider.value.toInt())
                isUserVolumeScrubbing = false
            }

            /** The fling outlives the finger; this is where its final value is committed. */
            override fun onStopFlinging(slider: OverlaySlider, value: Float) {
                setDeviceVolume(value.toInt())
            }
        })

        progressOverlaySlider.addEmphasizeListener(object : OverlaySlider.EmphasizeListener {
            override fun onEmphasizeVertical(translationX: Float, translationY: Float) {
                currentTimestampTextView.translationY = translationY
                currentTimestampTextView.translationX = -translationX
                leftTimestampTextView.translationY = translationY
                leftTimestampTextView.translationX = translationX
            }
        })

        progressOverlaySlider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) {
                isUserScrubbing = true
                stopPositionUpdates()
            }

            override fun onValueChanged(slider: OverlaySlider, value: Float, fromUser: Boolean, fromMomentum: Boolean) {
                if (!fromUser) return
                val duration = resolveDurationMs() ?: return
                updateProgressTexts(value.toLong(), duration)
            }

            override fun onStopTracking(slider: OverlaySlider) {
                val duration = resolveDurationMs()
                if (duration != null) {
                    val position = slider.value.toLong().coerceIn(0L, duration)
                    instance?.seekTo(position)
                    updateProgressTexts(position, duration)
                }
                isUserScrubbing = false
                if (instance?.isPlaying == true) {
                    startPositionUpdates()
                } else {
                    updateProgressDisplay()
                }
            }
        })

        coverSimpleImageView.doOnLayout {
            Log.d(TAG, "csi: ${coverSimpleImageView.left}, ${coverSimpleImageView.top}")
            floatingPanelLayout.setupTransitionImageView(
                coverSimpleImageView.width,
                coverSimpleImageView.height,
                coverSimpleImageView.left,
                coverSimpleImageView.top,
                // The controller can restore the current item (and Coil can satisfy its artwork
                // request from memory) before this first layout pass. Initialising the transition
                // layer with the placeholder then puts a blank cover over the real one for the
                // lifetime of the view, because there is no second media-item transition to
                // refresh it. Seed it from the cover that is actually on screen instead.
                appliedCoverBitmap ?: AppCompatResources
                    .getDrawable(context, R.drawable.default_cover)!!
                    .toBitmap()
            )

            updateTransitionTargetForContentType(contentType)
            // The restored controller can deliver its initial media-item callback before this
            // view has a size. That callback cannot make a valid Coil request, and no second track
            // transition follows on a paused cold start. Retry now that the target dimensions are
            // real so both the cover and the selected backdrop receive the restored artwork.
            loadCoverForImageView()
        }

        // Insets, multi-window resizing and the tablet max-width constraint can move the target
        // after its first layout. The transition layer used to keep that first phone-shaped
        // rectangle, which made the artwork stretch and then jump into place near the end of an
        // expansion. Follow the real target bounds whenever they change.
        coverSimpleImageView.addOnLayoutChangeListener { _, left, top, right, bottom,
                                                         oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                post { updateTransitionTargetForContentType(contentType) }
            }
        }
        fullPlayerToolbar.getCoverView().addOnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                post { updateTransitionTargetForContentType(contentType) }
            }
        }

        listOverlayButton.setOnClickListener {
            it.performPressHaptic()
            toggleContentMode(ContentType.PLAYLIST)
        }

        queueShuffleButton.setOnClickListener {
            val controller = instance ?: return@setOnClickListener
            it.performPressHaptic()
            // Shuffle is a property of the player that owns the queue. Whether that queue lives in
            // this process, on a receiver, or in another Jellyfin session, the controller is what
            // reaches it - and what reports back whether the request was honoured at all.
            if (!controller.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)) {
                queueShuffleButton.isChecked = controller.shuffleModeEnabled
                return@setOnClickListener
            }
            controller.shuffleModeEnabled = !controller.shuffleModeEnabled
            queueShuffleButton.isChecked = controller.shuffleModeEnabled
        }

        queueAutoplayButton.isChecked = isAutoplayEnabled()
        queueAutoplayButton.setOnClickListener {
            it.performPressHaptic()
            val enabled = !isAutoplayEnabled()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(PREF_AUTOPLAY, enabled).apply()
            queueAutoplayButton.isChecked = enabled
            Toast.makeText(
                context,
                if (enabled) R.string.autoplay_on else R.string.autoplay_off,
                Toast.LENGTH_SHORT
            ).show()
            // Turning it on with a queue already near its end should not require waiting for
            // another track to finish first.
            if (enabled) topUpQueueIfNeeded()
        }

        // Half wired. The preference now gates real work - with it on, the playback service has the
        // next track's tempo, beat grid and key analysed on this phone before the transition
        // arrives - but nothing mixes yet, so the toast still says tracks change as usual. See the
        // Automix section of TODO.md for what is left, which is the two-source mixer media3 does
        // not provide.
        queueAutomixButton.isChecked = isAutomixEnabled()
        queueAutomixButton.setOnClickListener {
            it.performPressHaptic()
            // Mixing two tracks means owning their samples, and on a remote output this phone
            // never sees one: Cast is handed a URL and the receiver fetches it, and a Finnect
            // session is told to play an item id by another Jellyfin client entirely. Neither is
            // a limitation to engineer around - there is no PCM path to reach. Say so rather than
            // letting the pill light for an output that cannot honour it.
            if (remoteOutputActive) {
                Toast.makeText(context, R.string.automix_local_only, Toast.LENGTH_SHORT).show()
                queueAutomixButton.isChecked = isAutomixEnabled()
                return@setOnClickListener
            }
            val enabled = !isAutomixEnabled()
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putBoolean(Automix.PREF_KEY, enabled).apply()
            queueAutomixButton.isChecked = enabled
            Toast.makeText(
                context,
                if (enabled) R.string.automix_on else R.string.automix_off,
                Toast.LENGTH_SHORT
            ).show()
        }

        // Long press resequences what is left of the queue so that more of its transitions can be
        // mixed. On the pill rather than in a menu because it is the same subject, and on a long
        // press rather than a tap because it is a deliberate act: a queue somebody built by hand
        // keeps its order until they ask for it not to.
        queueAutomixButton.setOnLongClickListener {
            it.performPressHaptic()
            reorderQueueForAutomix()
            true
        }

        queueRepeatButton.setOnClickListener {
            val controller = instance ?: return@setOnClickListener
            it.performPressHaptic()
            val nextRepeatMode = nextRepeatMode(controller.repeatMode)
            controller.repeatMode = nextRepeatMode
            updateRepeatButton(nextRepeatMode)
        }

        airplayOverlayButton.setOnClickListener {
            it.performPressHaptic()
            animateBottomButtonPress(airplayOverlayButton)
            showOutputPicker()
        }


        // The service resolves lyrics off the main thread and announces the result with this
        // command once it has them, which for a Jellyfin lookup is well after the track started.
        activity.controllerViewModel.customCommandListeners.addCallback(activity.lifecycle) {
                _, command, _ ->
            when (command.customAction) {
                GramophonePlaybackService.SERVICE_GET_LYRICS -> {
                    refreshLyrics()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                GramophonePlaybackService.SERVICE_GET_AUDIO_FORMAT -> {
                    refreshAudioOutputStatus()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> {
                // The dispatcher walks listeners until one claims the command, so anything not
                // handled here has to decline rather than swallow it.
                    Futures.immediateFuture(
                        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
                    )
                }
            }
        }

        activity.controllerViewModel.addControllerCallback(activity.lifecycle) { player, controllerLifecycle ->
            firstTime = true
            player.registerLifecycleCallback(
                MediaControllerViewModel.LifecycleIntersection(activity.lifecycle, controllerLifecycle).lifecycle,
                this@FullPlayer,
            )
            onRepeatModeChanged(instance?.repeatMode ?: Player.REPEAT_MODE_OFF)
            onShuffleModeEnabledChanged(instance?.shuffleModeEnabled == true)
            onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
            instance?.currentTimeline?.let {
                onTimelineChanged(it, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
            onMediaItemTransition(
                instance?.currentMediaItem,
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            )
            onMediaMetadataChanged(instance?.mediaMetadata ?: MediaMetadata.EMPTY)
            instance?.currentTracks?.let { onTracksChanged(it) }
            refreshLyrics()
            refreshAudioOutputStatus()
            updateVolumeSlider()
            firstTime = false
        }

        controllerButton.setOnClickListener {
            it.performPressHaptic()
            instance?.playOrPause()
        }

        previousButton.setOnClickListener {
            it.performPressHaptic()
            armCoverSlide(SLIDE_PREVIOUS)
            instance?.seekToPrevious()
        }
        nextButton.setOnClickListener {
            it.performPressHaptic()
            armCoverSlide(SLIDE_NEXT)
            instance?.seekToNext()
        }

        doOnLayout {
            floatingPanelLayout.addOnSlideListener(this)
            floatingPanelLayout.coverSwipeHandler = this

            finalTranslationX = 32.dp.px - coverSimpleImageView.left
            finalTranslationY = (20 - 18).dp.px
            // A rapid content-mode tap can arrive during the transient layout where the artwork
            // is present but has a zero height. Dividing here produced Infinity; lerping from it
            // at fraction zero then produced NaN and Android rejected scaleX. Keep the neutral
            // scale until the next real layout instead.
            finalScale = coverSimpleImageView.height.takeIf { it > 0 }
                ?.let { 74.dp.px / it }
                ?.takeIf { it.isFinite() }
                ?: 1F
        }

        updateVolumeSlider()
    }

    /** Accord's unified output picker: live Android routes plus live Finnect clients. */
    /** Opens the combined Android-output and Jellyfin Connect picker from player or Settings UI. */
    fun showOutputPicker() {
        if (outputPickerOpening || outputPickerDialog?.isShowing == true) return
        val owner = findViewTreeLifecycleOwner() ?: return
        outputPickerOpening = true
        owner.lifecycleScope.launch {
            try {
            val finnectEnabled = JellyfinRemoteTargets.isEnabled(context)
            var targets = if (finnectEnabled) JellyfinRemoteTargets.cachedAvailable() else emptyList()
            val sheet = PlayerSheet.create(context)
            outputPickerDialog = sheet
            // The sheet is a dialog, so it owns the window while it is up and MainActivity's
            // volume-key handling never runs: the keys fell through to the system, which moved the
            // phone's stream instead of the receiver and drew its own overlay across this card.
            sheet.setOnKeyListener { _, keyCode, event -> handleSheetVolumeKey(keyCode, event) }
            // Whatever was already counting down belongs to the screen behind this sheet.
            removeCallbacks(hideControlsRunnable)
            val root = LayoutInflater.from(context).inflate(
                R.layout.layout_output_picker,
                null,
                false,
            )
            val backdrop = root.findViewById<ImageView>(R.id.output_picker_backdrop)
            var backdropBitmap: Bitmap? = null
            // Same renderer as the player's backdrop, told to ignore the Appearance toggle so this
            // card is one thing rather than two depending on a setting about a different surface.
            val pickerGradient = root.findViewById<FlowingGradientView>(R.id.output_picker_gradient)
            pickerGradient.ignorePreference = true
            pickerGradient.setPlaying(instance?.isPlaying == true)

            fun captureBackdrop() {
                PlayerSheet.captureBackdrop(activity, sheet, root, backdrop) { captured ->
                    backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                    backdropBitmap = captured
                }
            }
            fun bindNowPlaying() {
                val current = instance?.currentMediaItem
                root.findViewById<TextView>(R.id.output_picker_track).text =
                    current?.mediaMetadata?.title ?: context.getString(R.string.default_track)
                root.findViewById<TextView>(R.id.output_picker_artist).text =
                    current?.let(ArtistCredits::primaryArtist)
                        ?: context.getString(R.string.default_artist)
                appliedCoverBitmap?.let {
                    root.findViewById<SimpleImageView>(R.id.output_picker_now_art)
                        .setImageBitmap(it)
                }
            }
            bindNowPlaying()
            cachedBackdrop?.let(pickerGradient::setArtwork)
            val remoteActions = root.findViewById<View>(R.id.output_picker_remote_actions)
            val remoteConnected =
                FincordCast.isCasting || JellyfinRemoteTargets.active.value != null
            remoteActions.visibility = if (remoteConnected) VISIBLE else GONE
            // The third answer: keep the target, hear it here. The two above both give the target
            // up, which is the wrong price for skipping one song through the phone.
            val keepHere = root.findViewById<TextView>(R.id.output_picker_keep_here)
            keepHere.visibility = if (remoteConnected) VISIBLE else GONE
            if (remoteConnected) {
                val target = currentRemoteTargetName()
                val local = OutputRouting.playLocally.value
                keepHere.text = when {
                    local && target != null -> context.getString(R.string.output_resume_remote, target)
                    target != null -> context.getString(R.string.output_keep_here, target)
                    else -> context.getString(R.string.output_keep_here_generic)
                }
                keepHere.setOnClickListener {
                    Haptics.press(it)
                    sheet.dismiss()
                    OutputRouting.setPlayLocally(!local)
                }
            }
            root.findViewById<View>(R.id.output_picker_play_here_too).setOnClickListener {
                Haptics.press(it)
                sheet.dismiss()
                when {
                    FincordCast.isCasting -> FincordCast.endSession(stopReceiver = false)
                    JellyfinRemoteTargets.active.value != null ->
                        selectLocalRoute(outputMediaRouter.defaultRoute, stopRemote = false)
                }
            }
            root.findViewById<View>(R.id.output_picker_move_here).setOnClickListener {
                Haptics.press(it)
                sheet.dismiss()
                when {
                    FincordCast.isCasting -> FincordCast.endSession(stopReceiver = true)
                    JellyfinRemoteTargets.active.value != null ->
                        selectLocalRoute(outputMediaRouter.defaultRoute, stopRemote = true)
                }
            }
            // The blur is a single PixelCopy of whatever was behind the sheet when it opened, so a
            // track change underneath left it showing the previous song's colours - and the header
            // kept that song's name and cover too. Both are refreshed from onMediaItemTransition
            // for as long as this sheet is up.
            outputPickerRefresh = {
                bindNowPlaying()
                // The gradient reads the cover, so it follows a track change like the header does.
                cachedBackdrop?.let(pickerGradient::setArtwork)
                captureBackdrop()
            }
            val rows = root.findViewById<LinearLayout>(R.id.output_picker_rows)
            val rowScroller = root.findViewById<View>(R.id.output_picker_scroll)
            root.findViewById<View>(R.id.output_picker_more).setOnClickListener {
                Haptics.press(it)
                sheet.dismiss()
                startSystemMediaControl()
            }
            sheet.setContentView(root)

            fun capPickerHeight() {
                root.post {
                    if (!sheet.isShowing) return@post
                    val maxHeight = min(
                        OUTPUT_PICKER_MAX_HEIGHT_DP.dp.px.toInt(),
                        (resources.displayMetrics.heightPixels * OUTPUT_PICKER_HEIGHT_FRACTION).toInt(),
                    )
                    val excess = (root.height - maxHeight).coerceAtLeast(0)
                    rowScroller.updateLayoutParams<LinearLayout.LayoutParams> {
                        height = if (excess == 0) {
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        } else {
                            (rowScroller.height - excess).coerceAtLeast(
                                OUTPUT_PICKER_MIN_LIST_HEIGHT_DP.dp.px.toInt()
                            )
                        }
                    }
                    root.post(::captureBackdrop)
                }
            }

            fun renderRows(animateHeight: Boolean = false) {
                // Rebuilding replaces every row, and a finger on a volume slider is holding one of
                // them. Route providers report a volume change as an ordinary route change too, so
                // this is the backstop for the ones that do not use onRouteVolumeChanged.
                if (outputVolumeBusy()) return
                rowScroller.updateLayoutParams<LinearLayout.LayoutParams> {
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                }
                val oldTop = IntArray(2)
                if (animateHeight && root.isLaidOut) {
                    root.getLocationOnScreen(oldTop)
                    root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                        override fun onLayoutChange(
                            view: View,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                            oldLeft: Int,
                            oldTopValue: Int,
                            oldRight: Int,
                            oldBottom: Int,
                        ) {
                            view.removeOnLayoutChangeListener(this)
                            val newPosition = IntArray(2)
                            view.getLocationOnScreen(newPosition)
                            val offset = (oldTop[1] - newPosition[1]).toFloat()
                            if (kotlin.math.abs(offset) > 1F) {
                                view.animate().cancel()
                                view.translationY = offset
                                view.animate()
                                    .translationY(0F)
                                    .setDuration(OUTPUT_PICKER_RESIZE_MS)
                                    .setInterpolator(AnimationUtils.easingStandardInterpolator)
                                    .start()
                            }
                        }
                    })
                }
                val activeRemote = JellyfinRemoteTargets.active.value
                val selectedRoute = outputMediaRouter.selectedRoute
                val defaultRoute = outputMediaRouter.defaultRoute
                val discoveredLocalRoutes = outputMediaRouter.routes
                    .asSequence()
                    .filter { route ->
                        route.isEnabled &&
                            route.id != defaultRoute.id &&
                            (route.isSystemRoute || route.matchesSelector(outputRouteSelector))
                    }
                    .distinctBy { it.id }
                    .toList()
                // Dynamic Cast route objects are replaced while a session connects. MediaRouter's
                // selectedRoute can consequently fall back to Phone even though CastSession is
                // active (the logs call the old route "removed"). Match the live session's stable
                // Cast device ID, with friendly name only as a fallback for group routes.
                val activeCastRouteId = activeCastRoute(discoveredLocalRoutes)?.id
                val routeIsSelected: (MediaRouter.RouteInfo) -> Boolean = { route ->
                    activeRemote == null &&
                        (route.id == activeCastRouteId ||
                            (activeCastRouteId == null && route.isSelected))
                }
                val localRoutes = discoveredLocalRoutes
                    .sortedWith(
                        compareByDescending<MediaRouter.RouteInfo>(routeIsSelected)
                            .thenBy { it.name.toString().lowercase() }
                    )

                val localSection = context.getString(R.string.output_section_local)
                val finnectSection = context.getString(R.string.output_section_finnect)
                val entries = buildList {
                    add(
                        OutputEntry(
                            section = localSection,
                            icon = R.drawable.ic_output_phone,
                            iconUri = defaultRoute.iconUri,
                            title = context.getString(R.string.output_this_phone),
                            subtitle = null,
                            selected = activeRemote == null && activeCastRouteId == null &&
                                selectedRoute.id == defaultRoute.id,
                            localRoute = defaultRoute,
                        ) { selectLocalRoute(defaultRoute) }
                    )
                    localRoutes.forEach { route ->
                        add(
                            OutputEntry(
                                section = localSection,
                                icon = routeIconFor(route),
                                iconUri = route.iconUri,
                                title = route.name.toString(),
                                subtitle = routeSubtitle(route),
                                selected = routeIsSelected(route),
                                localRoute = route,
                            ) { selectLocalRoute(route) }
                        )
                        // A speaker group is one destination to play to but several speakers to
                        // balance, so its members are listed under it while it is the one playing -
                        // each is an ordinary route, and gets its own slider from the same code.
                        if (routeIsSelected(route) && route.isGroup) {
                            route.asGroup()?.routesInGroup.orEmpty().forEach { member ->
                                if (member.id == route.id) return@forEach
                                add(
                                    OutputEntry(
                                        section = localSection,
                                        icon = routeIconFor(member),
                                        iconUri = member.iconUri,
                                        title = member.name.toString(),
                                        subtitle = null,
                                        selected = false,
                                        localRoute = member,
                                        isGroupMember = true,
                                        // Tapping a member must not switch playback to it; the
                                        // row is here to carry that member's volume.
                                    ) { }
                                )
                            }
                        }
                    }
                    targets.forEach { target ->
                        add(
                            OutputEntry(
                                section = finnectSection,
                                icon = clientIconFor(target.client),
                                iconUri = null,
                                title = target.deviceName.ifBlank { target.client },
                                subtitle = target.nowPlaying?.let {
                                    context.getString(R.string.output_playing_now, it)
                                } ?: target.client,
                                selected = activeRemote?.sessionId == target.sessionId,
                            ) { selectFinnectTarget(target) }
                        )
                    }
                }

                rows.removeAllViews()
                routeVolumeSliders.clear()
                outputPickerPhoneVolumeSlider = null
                // Decided once, for the whole list, from the most detailed row in it. Sizing each
                // row to its own contents produced pills of three different heights, which reads
                // as three kinds of control rather than one list of destinations.
                val badgeLabel = if (showOutputCodecBadge()) currentOutputSignalLabel() else null
                val anyBadge = badgeLabel != null &&
                    entries.any { it.selected && it.localRoute != null }
                val rowHeight = resources.getDimensionPixelSize(
                    when {
                        anyBadge -> R.dimen.output_row_height_badge
                        entries.any { it.subtitle != null } -> R.dimen.output_row_height_subtitle
                        else -> R.dimen.output_row_height_compact
                    }
                )
                entries.forEach { entry ->
                    val row = LayoutInflater.from(context)
                        .inflate(R.layout.layout_output_picker_row, rows, false)
                    row.minimumHeight = rowHeight
                    row.setBackgroundResource(
                        if (entry.selected) R.drawable.bg_output_picker_row_selected
                        else R.drawable.bg_output_picker_row
                    )
                    bindOutputIcon(
                        row.findViewById(R.id.output_row_icon),
                        entry.icon,
                        entry.iconUri,
                        entry.localRoute,
                    )
                    row.findViewById<TextView>(R.id.output_row_title).text = entry.title
                    row.findViewById<TextView>(R.id.output_row_subtitle).apply {
                        text = entry.subtitle
                        visibility = if (entry.subtitle == null) GONE else VISIBLE
                    }
                    row.findViewById<TextView>(R.id.output_row_signal_badge).apply {
                        val signal = badgeLabel
                            ?.takeIf { entry.selected && entry.localRoute != null }
                        text = signal
                        visibility = if (signal == null) GONE else VISIBLE
                    }
                    val routeVolume = row.findViewById<OverlaySlider>(R.id.output_row_volume)
                    val isPhoneOutput = entry.localRoute?.id == defaultRoute.id
                    // Volume lives on the destination being played through, and on a group's
                    // members so a pair can be balanced. Everywhere else the row is a place to tap,
                    // and a drag surface over it would only get in the way of tapping it - which is
                    // also how the picker this is modelled on behaves.
                    bindRouteVolume(
                        routeVolume,
                        entry.localRoute,
                        isPhoneOutput,
                        showVolume = entry.selected || entry.isGroupMember,
                    )
                    bindRowLabelSplit(row, routeVolume)
                    if (isPhoneOutput) {
                        outputPickerPhoneVolumeSlider = routeVolume
                    } else {
                        entry.localRoute?.let { routeVolumeSliders[it.id] = routeVolume }
                    }
                    if (entry.isGroupMember) {
                        // Indented under the group, with no radio of its own: it is a speaker to
                        // balance, not a destination to switch to.
                        row.updatePaddingRelative(start = OUTPUT_MEMBER_INDENT_DP.dp.px.toInt())
                        row.isClickable = false
                        row.findViewById<View>(R.id.output_row_selection).visibility = GONE
                    }
                    row.findViewById<ImageView>(R.id.output_row_check).visibility =
                        if (entry.selected) VISIBLE else GONE
                    row.findViewById<View>(R.id.output_row_selection).setBackgroundResource(
                        if (entry.selected) R.drawable.bg_output_picker_radio_selected
                        else R.drawable.bg_output_picker_radio_unselected
                    )
                    row.setOnClickListener {
                        Haptics.press(it)
                        sheet.dismiss()
                        entry.onSelect()
                    }
                    rows.addView(row)
                }
                capPickerHeight()
            }

            // Codec and AudioFlinger changes arrive through the media session, independently of
            // MediaRouter. Keep the visible sheet subscribed too, so switching SSC/UHQ changes the
            // pill in place rather than requiring the sheet to be reopened.
            val sheetRenderer = { renderRows() }
            outputPickerFormatChanged = sheetRenderer

            val sheetRouteCallback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    renderRows()
                }

                override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    renderRows()
                }

                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    // A volume change arrives as an ordinary route change as well, and rebuilding
                    // replaces the very row that is easing towards the new level - the fresh one is
                    // assigned its value outright, so the bar arrives instantly. That is why a
                    // rocker press stepped the sheet while the player's bar behind it glided.
                    //
                    // Only the rebuild is skipped. The level itself is already being applied, by
                    // onRouteVolumeChanged or by the volume broadcast, and both of those re-target
                    // the running animation rather than fighting it.
                    if (sheetSliderAnimators.isNotEmpty()) return
                    renderRows()
                }

                /**
                 * Deliberately does not re-render.
                 *
                 * Moving a slider calls `requestSetVolume`, the route reports the new volume back,
                 * and rebuilding every row on that reply threw away the very view the finger was
                 * holding - so a drag moved a little and then died. The slider is already showing
                 * the value the user chose; there is nothing to redraw.
                 */
                override fun onRouteVolumeChanged(
                    router: MediaRouter,
                    route: MediaRouter.RouteInfo,
                ) {
                    if (outputVolumeBusy()) return
                    routeVolumeSliders[route.id]?.let { slider ->
                        slider.valueTo = route.volumeMax.coerceAtLeast(1).toFloat()
                        animateSheetSliderTo(
                            slider,
                            route.volume.toFloat().coerceIn(0F, slider.valueTo),
                        )
                    }
                }

                override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    renderRows()
                }

                override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo) {
                    renderRows()
                }
            }
            sheet.setOnDismissListener {
                outputMediaRouter.removeCallback(sheetRouteCallback)
                backdrop.setImageDrawable(null)
                backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                backdropBitmap = null
                if (outputPickerFormatChanged === sheetRenderer) {
                    outputPickerFormatChanged = null
                }
                if (outputPickerDialog === sheet) outputPickerDialog = null
                outputPickerRefresh = null
                cancelSheetSliderAnimations()
                outputPickerPhoneVolumeSlider = null
                routeVolumeSliders.clear()
                // Cleared above first: the scheduler checks whether this sheet is still showing.
                scheduleControlsHide()
            }
            renderRows()
            refreshAudioOutputStatus()
            outputMediaRouter.addCallback(
                outputRouteSelector,
                sheetRouteCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
            )
            PlayerSheet.present(activity, sheet, root) { captureBackdrop() }
            // The local picker is useful without Jellyfin and should never wait for the network.
            // Remote clients join the already-visible sheet when the server answers.
            outputPickerOpening = false
            val refreshedTargets = if (finnectEnabled) {
                JellyfinRemoteTargets.available()
            } else emptyList()
            if (sheet.isShowing && refreshedTargets != targets) {
                targets = refreshedTargets
                renderRows(animateHeight = true)
            }
            } finally {
                outputPickerOpening = false
            }
        }
    }

    private class OutputEntry(
        val section: String,
        @DrawableRes val icon: Int,
        val iconUri: Uri?,
        val title: String,
        val subtitle: String?,
        val selected: Boolean,
        val localRoute: MediaRouter.RouteInfo? = null,
        /** One speaker inside the group that is playing: indented, and not selectable itself. */
        val isGroupMember: Boolean = false,
        val onSelect: () -> Unit,
    )

    /**
     * Reacts to a receiver session opening or closing. Chrome only.
     *
     * Nothing here moves the music any more, and that is the point. Selecting a Cast route opens a
     * session through [MediaRouter.selectRoute] like any other route, and the playback service's
     * [CastPlayer][androidx.media3.cast.CastPlayer] is what notices it, carries the queue and
     * position across, and takes over the MediaSession. Handing over from this view instead left
     * the session pointed at a paused local player for the whole cast - and the notification, the
     * lock screen, Android Auto and every other controller show whatever the session says, so they
     * all reported "paused" while a receiver was audibly playing.
     *
     * A session this phone did not start needs no special case either. The remote player builds
     * its timeline out of the receiver's own queue, so an Intent-to-Join, a session another sender
     * started, or one that outlived this process is adopted rather than overwritten.
     */
    private fun onCastSessionChanged(
        @Suppress("UNUSED_PARAMETER") session: com.google.android.gms.cast.framework.CastSession?,
    ) {
        refreshOutputDevice()
        updateVolumeSlider()
        updateProgressDisplay()
    }

    private fun selectLocalRoute(
        route: MediaRouter.RouteInfo,
        stopRemote: Boolean = true,
    ) {
        val owner = findViewTreeLifecycleOwner() ?: return
        val player = instance ?: return
        owner.lifecycleScope.launch {
            val returningFromRemote = JellyfinRemoteTargets.active.value != null
            val remoteState = if (returningFromRemote) {
                JellyfinRemoteTargets.refreshActiveState()
                    ?: JellyfinRemoteTargets.playbackState.value
            } else null
            val currentItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            val restoredQueue = remoteState?.let { resolveRemoteQueue(it, currentItems) }
            // Selecting a speaker is an explicit request to begin playing there, so the intent to
            // play is set before the route changes. The service's CastPlayer carries it, along
            // with the queue and position, onto the receiver when the session opens; a connection
            // that inherited the phone's paused state used to load a valid queue and then sit
            // silently until Play was pressed a second time.
            val castingOut = route.matchesSelector(outputRouteSelector) && !route.isSystemRoute
            if (castingOut) player.playWhenReady = true
            runCatching { outputMediaRouter.selectRoute(route) }
                .onFailure {
                    Toast.makeText(context, R.string.media_control_text_error, Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                }
            if (returningFromRemote) {
                val shouldPlay = remoteState?.isPaused != true
                val resumePosition = remoteState?.projectedPositionMs() ?: player.currentPosition
                if (stopRemote) JellyfinRemoteTargets.sendTransport(PlaystateCommand.STOP)
                JellyfinRemoteTargets.playLocally()
                if (restoredQueue != null && restoredQueue.items.isNotEmpty()) {
                    player.setMediaItems(
                        restoredQueue.items,
                        restoredQueue.currentIndex,
                        resumePosition,
                    )
                    player.prepare()
                } else {
                    val index = remoteState?.queueIds
                        ?.indexOf(remoteState.itemId?.replace("-", "")?.lowercase())
                        ?.takeIf { it in 0 until player.mediaItemCount }
                        ?: player.currentMediaItemIndex
                    player.seekTo(index, resumePosition)
                }
                if (shouldPlay) player.play() else player.pause()
            }
        }
    }

    private data class ResolvedRemoteQueue(
        val items: List<MediaItem>,
        val currentIndex: Int,
    )

    /** Resolves the server's queue back into this device's library without losing its order. */
    private suspend fun resolveRemoteQueue(
        state: JellyfinRemoteTargets.RemotePlaybackState,
        currentItems: List<MediaItem>,
    ): ResolvedRemoteQueue? = withContext(Dispatchers.IO) {
        val normalize: (String) -> String = { it.replace("-", "").lowercase() }
        val byRemote = LinkedHashMap<String, MediaItem>()
        suspend fun index(items: List<MediaItem>) {
            items.forEach { item ->
                JellyfinItemResolver.remoteIdForMediaId(context, item.mediaId)?.let {
                    byRemote.putIfAbsent(normalize(it), item)
                }
            }
        }
        index(currentItems)
        val wanted = state.queueIds.map(normalize)
        if (wanted.any { it !in byRemote }) index(activity.reader.songListSnapshot())
        val orderedIds = wanted.ifEmpty { byRemote.keys.toList() }
        val resolved = orderedIds.mapNotNull { id -> byRemote[id]?.let { id to it } }
        if (resolved.isEmpty()) return@withContext null
        val currentId = state.itemId?.let(normalize)
        val currentIndex = resolved.indexOfFirst { it.first == currentId }.coerceAtLeast(0)
        ResolvedRemoteQueue(resolved.map { it.second }, currentIndex)
    }

    /** Returns true when Finnect owns transport, so callers do not also touch the local player. */
    private fun sendRemoteTransport(
        command: PlaystateCommand,
        seekPositionMs: Long? = null,
    ): Boolean {
        if (JellyfinRemoteTargets.active.value == null) return false
        val owner = findViewTreeLifecycleOwner() ?: return false
        owner.lifecycleScope.launch {
            JellyfinRemoteTargets.sendTransport(command, seekPositionMs)
        }
        return true
    }

    /**
     * Hands the queue to another client and stops playing it here.
     *
     * The local player is paused rather than cleared: coming back should not mean rebuilding the
     * queue, and the other device is playing from the server anyway. Position travels with it, so
     * the track resumes where it was rather than restarting.
     */
    private fun handOverTo(target: JellyfinRemoteTargets.Target) {
        val owner = findViewTreeLifecycleOwner() ?: return
        val player = instance ?: return
        val startIndex = player.currentMediaItemIndex
        val positionMs = player.currentPosition
        val localIds = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaId
        }
        owner.lifecycleScope.launch {
            val mappedQueue = withContext(Dispatchers.IO) {
                localIds.mapNotNull { localId ->
                    JellyfinItemResolver.remoteIdForMediaId(context, localId)
                        ?.let { remoteId -> localId to remoteId }
                }
            }
            if (mappedQueue.isEmpty()) {
                Toast.makeText(context, R.string.output_nothing_to_send, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // A queue can contain a local-only item. Removing that item changes the index, so the
            // original Media3 index cannot be sent to Jellyfin unchanged. Preserve the current
            // track when it is mapped; otherwise begin at the first playable server item.
            val currentLocalId = localIds.getOrNull(startIndex)
            val remoteStartIndex = mappedQueue.indexOfFirst { it.first == currentLocalId }
                .takeIf { it >= 0 }
                ?: 0
            val remoteIds = mappedQueue.map { it.second }
            val sent = JellyfinRemoteTargets.playOn(
                target = target,
                remoteIds = remoteIds,
                startIndex = remoteStartIndex,
                startPositionMs = if (mappedQueue[remoteStartIndex].first == currentLocalId) {
                    positionMs
                } else 0L,
            )
            if (sent) {
                player.pause()
            } else {
                Toast.makeText(context, R.string.output_handover_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Joins live Fincord playback; only an idle target receives this phone's queue. */
    private fun selectFinnectTarget(target: JellyfinRemoteTargets.Target) {
        if (target.nowPlaying == null) {
            handOverTo(target)
            return
        }
        val owner = findViewTreeLifecycleOwner() ?: return
        val player = instance ?: return
        owner.lifecycleScope.launch {
            if (JellyfinRemoteTargets.claim(target)) {
                player.pause()
            } else {
                // It may have stopped between discovery and the tap; in that case the device is
                // now an idle destination and the original hand-off action is the useful fallback.
                handOverTo(target)
            }
        }
    }

    /**
     * Takes playback back.
     *
     * Stops the other device first, or two things are playing at once - which is what the user was
     * trying to avoid by moving it in the first place.
     */
    private fun startSystemMediaControl() {
        if (!SystemOutputSwitcherDialogController.showDialog(context)) {
            Toast.makeText(context, R.string.media_control_text_error, Toast.LENGTH_SHORT).show()
        }
    }

    private var finalTranslationX = 0F
    private var finalTranslationY = 0F
    private var initialCoverRadius =
        resources.getDimensionPixelSize(R.dimen.full_cover_radius).toFloat()
    private var endCoverRadius = 22.dp.px
    private var initialElevation = 24.dp.px
    private var finalScale = 0F
    private val queueCoverRadius = 5.dp.px
    private var queueEnterOffset = 0F
    private val queueStartFraction = 1F / 1.2F
    private val lyricsStartFraction = 0.08F

    private fun updateTransitionTargetForContentType(value: ContentType) {
        // A wide canvas never folds the artwork into the toolbar, so the shared image keeps
        // landing on the full-size cover it actually flies to.
        val compactMode = !isWideLayout &&
            (value == ContentType.PLAYLIST || value == ContentType.LYRICS)
        val targetView = if (compactMode) {
            fullPlayerToolbar.getCoverView()
        } else {
            coverSimpleImageView
        }
        val lockCornerRadius = compactMode
        val targetRadius = if (lockCornerRadius) queueCoverRadius else 0F
        val targetElevation = if (compactMode) null else initialElevation

        val update = {
            floatingPanelLayout.updateTransitionTarget(
                targetView,
                targetRadius,
                lockCornerRadius,
                targetElevation
            )
        }
        if (targetView.isLaidOut) {
            update()
        } else {
            targetView.doOnLayout { update() }
        }
    }

    private fun updateRepeatButton(repeatMode: Int) {
        val iconRes = if (repeatMode == Player.REPEAT_MODE_ONE) {
            R.drawable.ic_nowplaying_repeat_one
        } else {
            R.drawable.ic_nowplaying_repeat
        }
        queueRepeatButton.setIconResource(iconRes)
        queueRepeatButton.isChecked = repeatMode != Player.REPEAT_MODE_OFF
    }

    private fun nextRepeatMode(current: Int): Int = when (current) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
        else -> Player.REPEAT_MODE_OFF
    }

    private fun Int.toCastRepeatMode(): Int = when (this) {
        Player.REPEAT_MODE_ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
        Player.REPEAT_MODE_ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
        else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
    }

    private fun Int.castRepeatModeToPlayer(): Int = when (this) {
        MediaStatus.REPEAT_MODE_REPEAT_ALL,
        MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE -> Player.REPEAT_MODE_ALL
        MediaStatus.REPEAT_MODE_REPEAT_SINGLE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private fun toggleFavoriteForCurrentSong() {
        val mediaItem = instance?.currentMediaItem ?: return
        val key = buildSongKey(mediaItem)
        val keys = PlaylistAdapter.loadFavoriteKeys(context)
        val isFavorite = keys.contains(key)
        if (isFavorite) {
            keys.removeAll { it == key }
        } else {
            keys.add(0, key)
        }
        PlaylistAdapter.saveFavoriteKeys(context, keys)
        updateFavoriteButtons(!isFavorite)
        // Upstream keeps favourites in local preferences and nothing else. Favourites belong to the
        // server here, so the star has to reach it - otherwise starring a track on this phone is
        // invisible to the web client and gets undone by the next sync.
        CoroutineScope(Dispatchers.IO).launch {
            JellyfinReporter(context).setFavourite(mediaItem.mediaId, !isFavorite)
        }
        // Deliberately not refreshing the library here. Upstream called updateLibrary on every tap,
        // which on this fork means a full Jellyfin sync - the better part of a minute of requests
        // for one star.
    }

    private fun syncFavoriteButtonsForCurrentItem() {
        val mediaItem = instance?.currentMediaItem ?: return updateFavoriteButtons(false)
        val key = buildSongKey(mediaItem)
        val keys = PlaylistAdapter.loadFavoriteKeys(context)
        updateFavoriteButtons(keys.contains(key))
    }

    private fun updateFavoriteButtons(checked: Boolean) {
        if (starTransformButton.isChecked != checked) {
            starTransformButton.toggle()
        }
        fullPlayerToolbar.setStarChecked(checked)
    }

    private fun buildSongKey(item: MediaItem): String {
        val mediaId = item.mediaId
        if (mediaId.isNotBlank()) return mediaId
        return item.localConfiguration?.uri?.toString() ?: item.hashCode().toString()
    }

    private fun resolveDurationMs(): Long? {
        val duration = instance?.contentDuration
        if (duration != null && duration != C.TIME_UNSET) {
            return duration
        }
        return instance?.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0L }
    }

    /**
     * The scale this slider runs on: the phone's own steps locally, the remote device's remotely.
     *
     * Both the value and the write target come from the controller, and that single source is the
     * fix for the slider that moved a receiver but never showed what the receiver was set to. The
     * two used to be read from different places - the sheet showed Android's stream volume while
     * Now Playing wrote Cast volume - so they could not agree, and a change made on the speaker
     * itself reached neither.
     */
    private fun resolveMaxDeviceVolume(): Int {
        instance?.takeIf { it.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME) }
            ?.deviceInfo?.maxVolume?.takeIf { it > 0 }
            ?.let { return it }
        if (maxDeviceVolume <= 0) {
            maxDeviceVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
        return maxDeviceVolume
    }

    private fun resolveDeviceVolume(): Int {
        val controller = instance
        return if (controller != null && controller.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)) {
            controller.deviceVolume
        } else {
            audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        }
    }

    private fun updateVolumeSlider(volume: Int? = null) {
        // The fling outlives the finger. onStopTracking - and so isUserVolumeScrubbing going false -
        // fires the moment the touch ends, while the bar is still travelling, so a volume change
        // arriving during that window animated the slider towards the real level and fought the
        // fling: the bar stopped dead and then set off again, which is exactly what a fling looked
        // like. The gesture is not over until the momentum is.
        if (isUserVolumeScrubbing || volumeOverlaySlider.isMomentumFlingOngoing) return
        val maxVolume = resolveMaxDeviceVolume()
        if (maxVolume <= 0) return

        volumeOverlaySlider.valueFrom = 0f
        volumeOverlaySlider.valueTo = maxVolume.toFloat()
        val currentVolume = (volume ?: resolveDeviceVolume()).coerceIn(0, maxVolume).toFloat()
        animateVolumeSliderTo(currentVolume)
    }

    private fun setDeviceVolume(volume: Int) {
        val maxVolume = resolveMaxDeviceVolume()
        if (maxVolume <= 0) return
        val boundedVolume = volume.coerceIn(0, maxVolume)
        // Whatever owns playback owns its own volume: the receiver's level while casting, the
        // remote session's while on Finnect, this phone's stream otherwise. Each player converts
        // to whatever its target expects, so there is nothing to branch on here.
        val controller = instance
        if (controller != null && controller.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS )) {
            controller.setDeviceVolume(boundedVolume, 0)
        } else {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, boundedVolume, 0)
        }
    }

    /**
     * Animators for the output sheet's rows, one per slider.
     *
     * The player's bar has always eased towards a new level; the sheet's rows assigned it, so a
     * volume key stepped the active endpoint's slider while the bar behind the sheet glided. Same
     * gesture, same value, two different animations.
     *
     * Held so each can be cancelled: a running ValueAnimator sits in the platform's thread-local
     * AnimationHandler and keeps its update listener - and through it a sheet row, and the player -
     * alive until it finishes or is cancelled.
     */
    private val sheetSliderAnimators = mutableMapOf<OverlaySlider, ValueAnimator>()

    private fun cancelSheetSliderAnimation(slider: OverlaySlider) {
        // Remove before cancelling: cancel() runs the end listener, which would otherwise remove
        // whatever is under this key by the time it does.
        sheetSliderAnimators.remove(slider)?.cancel()
    }

    private fun cancelSheetSliderAnimations() {
        sheetSliderAnimators.values.toList().forEach { it.cancel() }
        sheetSliderAnimators.clear()
    }

    /** Eases [slider] to [targetValue], the way the player's own volume bar moves. */
    private fun animateSheetSliderTo(slider: OverlaySlider, targetValue: Float) {
        cancelSheetSliderAnimation(slider)
        val startValue = slider.value
        if (startValue == targetValue) return
        sheetSliderAnimators[slider] = ValueAnimator.ofFloat(startValue, targetValue).apply {
            duration = LONG_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener {
                slider.value = it.animatedValue as Float
                slider.invalidate()
            }
            doOnEnd { sheetSliderAnimators.remove(slider) }
            start()
        }
    }

    private fun animateVolumeSliderTo(targetValue: Float) {
        val startValue = volumeOverlaySlider.value
        if (startValue == targetValue) return
        volumeUpdateAnimator?.cancel()
        volumeUpdateAnimator = ValueAnimator.ofFloat(startValue, targetValue).apply {
            duration = LONG_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener {
                volumeOverlaySlider.value = it.animatedValue as Float
                volumeOverlaySlider.invalidate()
            }
            start()
        }
    }

    private fun updateProgressTexts(positionMs: Long, durationMs: Long) {
        val safeDuration = durationMs.coerceAtLeast(1L)
        val boundedPosition = positionMs.coerceIn(0L, safeDuration)
        val remaining = (safeDuration - boundedPosition).coerceAtLeast(0L)

        // Both timestamps are wrap_content, and TextView.setText on a wrap_content view always
        // calls requestLayout() - so writing them unconditionally remeasured the whole player on
        // every position tick, twice a second, for the entire length of every track. The rendered
        // text only changes once a second at most, and usually not at all between ticks, so the
        // comparison removes the great majority of those layout passes for the cost of two string
        // equality checks.
        val position = convertDurationToTimeStamp(boundedPosition)
        if (currentTimestampTextView.text != position) {
            currentTimestampTextView.text = position
        }
        val left = context.getString(R.string.time_remaining, convertDurationToTimeStamp(remaining))
        if (leftTimestampTextView.text != left) {
            leftTimestampTextView.text = left
        }
    }

    private fun updateProgressDisplay() {
        val mediaDuration = resolveDurationMs()
        // Both come from the controller, which tracks whichever player owns the output. Reading
        // the local player here is what left the scrubber parked at the moment of the hand-off:
        // that player is paused for the whole cast, so its position never moves, and a drag was
        // being measured against a timeline that was not the one playing.
        val currentPosition = instance?.currentPosition ?: 0L
        if (mediaDuration == null || instance?.mediaItemCount == 0) {
            val placeholder = context.getString(R.string.default_duration)
            currentTimestampTextView.text = placeholder
            leftTimestampTextView.text = placeholder
            progressOverlaySlider.valueTo = 1f
            progressOverlaySlider.value = 0f
            progressOverlaySlider.invalidate()
            return
        }

        if (isUserScrubbing) return

        val safeDuration = mediaDuration.coerceAtLeast(1L)
        val boundedPosition = currentPosition.coerceIn(0L, safeDuration)

        updateProgressTexts(boundedPosition, safeDuration)
        // valueTo is the track's duration - constant for the whole song - but assigning it can
        // itself request a layout, so it is only written when it actually changes.
        val duration = safeDuration.toFloat()
        if (progressOverlaySlider.valueTo != duration) {
            progressOverlaySlider.valueTo = duration
        }
        progressOverlaySlider.value = boundedPosition.toFloat()
        progressOverlaySlider.invalidate()
    }

    private fun startPositionUpdates() {
        if (positionUpdateRunning) return
        positionUpdateRunning = true
        removeCallbacks(positionUpdateRunnable)
        post(positionUpdateRunnable)
    }

    private fun stopPositionUpdates() {
        positionUpdateRunning = false
        removeCallbacks(positionUpdateRunnable)
    }

    private fun animateCoverChange(fraction: Float) {
        coverBaseTranslationX = lerp(0f, finalTranslationX, fraction)
        coverBaseTranslationY = lerp(0f, finalTranslationY, fraction)
        coverRestingTranslationX = coverBaseTranslationX
        applyCoverTranslation()
        coverSimpleImageView.translationY = coverBaseTranslationY
        coverSimpleImageView.pivotX = 0F
        coverSimpleImageView.pivotY = 0F
        coverBaseScale = lerp(1f, finalScale, fraction)
        applyCoverScale()
        coverSimpleImageView.elevation = lerp(initialElevation, 5f.dp.px, fraction)
        coverSimpleImageView.updateCornerRadius(
            lerp(
                initialCoverRadius,
                endCoverRadius,
                fraction
            ).toInt()
        )

        coverSimpleImageView.visibility = if (fraction == 1F) INVISIBLE else VISIBLE

        val coverTranslationY =
            coverBaseTranslationY - coverSimpleImageView.height * (1f - coverBaseScale)
        titleTextView.translationY = coverTranslationY
        starTransformButton.translationY = coverTranslationY
        ellipsisButton.translationY = coverTranslationY
        subtitleTextView.translationY = coverTranslationY

        val quickFraction = (fraction * 1.2f).coerceIn(0F, 1F)
        titleTextView.alpha = lerp(1F, 0F, quickFraction)
        subtitleTextView.alpha = lerp(1F, 0F, quickFraction)
        starTransformButton.alpha = lerp(1F, 0F, quickFraction)
        ellipsisButton.alpha = lerp(1F, 0F, quickFraction)

        fullPlayerToolbar.animateFade(fraction)
        animateQueuePanel(fraction)
    }

    /**
     * The same content change on a canvas wide enough to put the pane beside the player.
     *
     * The artwork never moves and never shrinks here - it is in the left half, which the pane does
     * not want. On a tablet the controls do not move either: its column holds artwork and controls
     * together and the pane takes the right of the screen, so the transport stays live under the
     * lyrics. A landscape phone has only two halves, so the rows that share the right half with
     * the pane step aside for it; they are taken out of hit testing at the end so a scrubber that
     * has faded to nothing cannot still catch a drag meant for the queue.
     */
    private fun animateWideContentChange(fraction: Float) {
        // Lyrics already own the chrome on a phone: they hide it on a timer and give it back on a
        // tap, restoring alpha and visibility themselves. Displacing the same rows underneath that
        // left them revealed but dead, because the reveal never knew to re-enable them.
        if (!isTabletLayout) {
            applyWidePhoneDisplacement(if (contentType == ContentType.LYRICS) 0F else fraction)
        }
        animateQueuePanel(fraction)
    }

    /**
     * Steps the rows that share a landscape phone's right half aside for the pane taking it.
     *
     * They are taken out of hit testing at the far end so a scrubber faded to nothing cannot still
     * catch a drag meant for the queue over it.
     */
    private fun applyWidePhoneDisplacement(fraction: Float) {
        val alpha = lerp(1F, 0F, (fraction * 1.2F).coerceIn(0F, 1F))
        listOf(
            titleTextView, subtitleTextView, starTransformButton, ellipsisButton,
            progressOverlaySlider, currentTimestampTextView, leftTimestampTextView,
            qualityBadge, qualityAvailableHint, controllerButton, previousButton, nextButton,
            volumeOverlaySlider, speakerHintView, speakerFullHintView,
        ).forEach { it.alpha = alpha }
        listOf(
            starTransformButton, ellipsisButton, progressOverlaySlider,
            controllerButton, previousButton, nextButton, volumeOverlaySlider,
        ).forEach { it.isEnabled = alpha > 0F }
    }

    private fun resolveQueueEnterOffset(): Float {
        return 0F
    }

    private fun animateQueuePanel(fraction: Float) {
        if (contentType != ContentType.PLAYLIST) {
            queueContainer.visibility = INVISIBLE
            setQueueChildrenAlpha(0F)
            return
        }
        val queueFraction = inverseLerp(queueStartFraction, 1F, fraction, clamp = true)
        if (queueFraction <= 0F) {
            queueEnterOffset = resolveQueueEnterOffset()
            queueContainer.translationY = queueEnterOffset
            queueContainer.visibility = INVISIBLE
            setQueueChildrenAlpha(0F)
            return
        }

        queueContainer.visibility = VISIBLE
        queueContainer.translationY = lerp(queueEnterOffset, 0F, queueFraction)
        setQueueChildrenAlpha(queueFraction)
    }

    private fun setQueueChildrenAlpha(alpha: Float) {
        queueShuffleButton.alpha = alpha
        queueRepeatButton.alpha = alpha
        queueAutoplayButton.alpha = alpha
        queueAutomixButton.alpha = alpha
        queueTextView.alpha = alpha
        queueRecyclerView.alpha = alpha
    }

    private fun callUpPlayerPopupMenu(v: View) {
        val anchorView = if (contentType != ContentType.NORMAL) {
            fullPlayerToolbar.getEllipsisView()
        } else {
            v
        }
        val showBelow = contentType != ContentType.NORMAL
        PlayerPopupMenu.show(
            host = floatingPanelLayout,
            anchorView = anchorView,
            showBelow = showBelow
        ) {
            ellipsisButton.isChecked = false
            fullPlayerToolbar.setEllipsisChecked(false)
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
                marginLeft,
                marginTop + floatingInsets.top,
                marginRight,
                marginBottom + floatingInsets.bottom
            )
            Log.d(TAG, "initTop: ${initialMargin[1]}")
        }
        applySymmetricEdges(floatingInsets.top, floatingInsets.bottom)
        Log.d(
            TAG,
            "marginBottom: ${marginBottom}, InsetsBottom: ${floatingInsets.bottom}, marginTop: ${floatingInsets.top}"
        )
        return super.dispatchApplyWindowInsets(platformInsets)
    }

    /** The layout's own margins, before any inset is added to them. */
    private var baseGrabberMargin = -1
    private var baseBottomControlsMargin = -1

    /**
     * Centres the player's contents between the screen's real edges.
     *
     * Two separate faults, both visible on a punch-hole phone with the expanded player immersive.
     * The grabber carried a flat 18dp and no inset at all, so on this display it sat inside the
     * 78px camera cutout - hiding the status bar removes `systemBars`, but the camera is still
     * physically there and only `displayCutout` reports it. And the bottom already started from
     * 48dp against the grabber's 18dp before the navigation bar was added underneath, so the
     * chin held far more space than the top even once both insets were applied.
     *
     * Both edges now resolve to the same total, so what is left above the grabber and below the
     * bottom row is equal and the contents sit centred between them.
     */
    private fun applySymmetricEdges(insetTop: Int, insetBottom: Int) {
        val grabber = overlayDivider.layoutParams as? MarginLayoutParams ?: return
        val bottom = listOverlayButton.layoutParams as? MarginLayoutParams ?: return
        if (baseGrabberMargin < 0) baseGrabberMargin = grabber.topMargin
        if (baseBottomControlsMargin < 0) baseBottomControlsMargin = bottom.bottomMargin

        val edge = maxOf(baseGrabberMargin + insetTop, baseBottomControlsMargin + insetBottom)
        if (grabber.topMargin == edge && bottom.bottomMargin == edge) return
        grabber.topMargin = edge
        bottom.bottomMargin = edge
        overlayDivider.layoutParams = grabber
        listOverlayButton.layoutParams = bottom
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Collapsed, this whole view is invisible behind the mini bar and scaled down over the
        // navigation bar; see [isFadedOutOfPanel]. Refusing the press hands it to the tabs below.
        if (event.actionMasked == MotionEvent.ACTION_DOWN && isFadedOutOfPanel()) return false
        if (contentType == ContentType.LYRICS && isWideLayout) {
            return dispatchWideLyricsTouch(event)
        }
        if (contentType == ContentType.LYRICS) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    removeCallbacks(hideControlsRunnable)
                    if (lyricsControlsHidden) {
                        revealingControlsGesture = true
                        showLyricsControls(scheduleHide = false)
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    scheduleControlsHide()
                    if (revealingControlsGesture) {
                        revealingControlsGesture = false
                        return true
                    }
                }
            }
            if (revealingControlsGesture) return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onSlideStatusChanged(status: FloatingPanelLayout.SlideStatus) {
        when (status) {
            FloatingPanelLayout.SlideStatus.EXPANDED -> {
                coverSimpleImageView.alpha = 1F
                // Re-read rather than trusting what was set the last time the player was open:
                // Settings is a screen away, and both badge choices can have changed since.
                updateQualityBadgeText()
                flashQualityBadge()
            }

            else -> {
                coverSimpleImageView.alpha = 0F
            }
        }
    }

    var previousState = false
    fun freeze() {
        previousState = blendView.isRunning
        blendView.stopRotationAnimation()
    }

    fun unfreeze() {
        // TODO: Make it on demand
        if (previousState) {
            blendView.startRotationAnimation()
        } else {
            blendView.stopRotationAnimation()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        Log.d(TAG, "onMeasured")
    }

    override fun onSlide(value: Float) {
        slideFraction = value
        if (contentType == ContentType.PLAYLIST || contentType == ContentType.LYRICS) {
            fullPlayerToolbar.getCoverView().alpha = if (value >= 1F) 1F else 0F
        }
        // At rest the panel fills the window, so the home feed's procedurally drawn cards and
        // banners are completely hidden - but they are still `isShown`, and were still asking for
        // an invalidate every vsync. Each one pulled the blurred backdrop through another GPU pass
        // for a surface nobody could see.
        ProceduralMotionTicker.setPaused(value >= 1F)
    }

    private var transformationFraction = 0F
    /**
     * Whether the panes sit beside the player rather than on top of it.
     *
     * A phone in portrait folds the artwork away into a toolbar to make room for lyrics or the
     * queue, because there is no room beside it. Every wide canvas has that room, and all of the
     * references keep the artwork at full size on the left with the pane in the right half, so the
     * fold must not run there. [isTabletLayout] adds that the controls stay too: only the phone's
     * right half is shared between the transport and the pane.
     */
    private var isWideLayout = false
    private var isTabletLayout = false

    /** A destination restored from a rotation, waiting for a layout to reopen itself into. */
    private var pendingContentType: ContentType? = null

    private var contentTypeAnimator: ValueAnimator? = null
    private var selectedBottomButton: OverlayButton? = null
    private val bottomButtonAnimators = mutableMapOf<OverlayButton, ValueAnimator>()

    private var contentType = ContentType.NORMAL
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            syncBottomButtons(value)

            when (value) {
                ContentType.LYRICS -> {
                    cancelControlsHide()
                    syncQualityBadgeVisibility()
                    showLyrics()
                    // Armed here rather than from the opening animation's completion, so the delay
                    // is measured from the moment the lyrics open and is the same every time. Hung
                    // off the animation it was skipped whenever that animation was cancelled - by
                    // a rotation, or by toggling twice quickly - and the chrome then stayed up for
                    // good, which is why the wait never felt like the same wait twice.
                    if (isWideLayout) {
                        // Opening the lyrics is the request to read them, so the chrome goes at
                        // once and they are scrollable and seekable from the first frame. Long
                        // enough only for the opening animation to be under way first.
                        removeCallbacks(hideControlsRunnable)
                        postDelayed(hideControlsRunnable, CONTROLS_OPEN_HIDE_WIDE_MS)
                    } else {
                        scheduleControlsHide()
                    }
                    if (previous == ContentType.PLAYLIST) {
                        // Swapping pane for pane never runs the transform, so hand the rows back
                        // explicitly - the lyrics chrome takes over from here.
                        if (isWideLayout && !isTabletLayout) applyWidePhoneDisplacement(0F)
                        animateContentSwap(queueContainer, fadingEdgeLayout)
                    } else {
                        animatePlayerTransform(1F, animateLyrics = true)
                    }
                }

                ContentType.NORMAL -> {
                    cancelControlsHide()
                    animatePlayerTransform(
                        target = 0F,
                        animateLyrics = previous == ContentType.LYRICS,
                    ) {
                        hideLyrics()
                        instance?.currentTracks?.let { onTracksChanged(it) }
                    }
                }

                ContentType.PLAYLIST -> {
                    cancelControlsHide()
                    syncQualityBadgeVisibility()
                    queueContainer.visibility = VISIBLE
                    queueContainer.translationY = queueEnterOffset
                    setQueueChildrenAlpha(0F)
                    queueContainer.bringToFront()
                    if (previous == ContentType.LYRICS) {
                        // Same swap the other way: the queue does want the right half to itself.
                        if (isWideLayout && !isTabletLayout) applyWidePhoneDisplacement(1F)
                        animateContentSwap(fadingEdgeLayout, queueContainer)
                    } else {
                        animatePlayerTransform(1F)
                    }
                }
            }
            updateTransitionTargetForContentType(value)
        }

    private fun toggleContentMode(mode: ContentType) {
        if (contentTypeAnimator != null) return
        contentType = if (contentType == mode) ContentType.NORMAL else mode
    }

    /**
     * Carries the open destination across a rotation.
     *
     * The panel already saved how far it was open, so a rotation came back to an expanded player -
     * but the destination inside it did not travel, and the field reset to NORMAL while the lyrics
     * container came back at its inflated visibility. What the listener got was the pane they had
     * been reading, blank, with no way back to it but closing and reopening.
     */
    @Suppress("DEPRECATION")
    override fun onSaveInstanceState(): Parcelable =
        Bundle().apply {
            putParcelable(STATE_SUPER, super.onSaveInstanceState())
            putInt(STATE_CONTENT_TYPE, contentType.ordinal)
        }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.getParcelable(STATE_SUPER))
        pendingContentType = ContentType.values()
            .getOrNull(state.getInt(STATE_CONTENT_TYPE, ContentType.NORMAL.ordinal))
            ?.takeIf { it != ContentType.NORMAL }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Reopened from a laid-out player, so the transform it animates has real sizes to use.
        // Dropped if the window went away first: a second configuration change can detach this
        // view while the post is still queued, and the buttons release their bitmaps on detach.
        val pending = pendingContentType ?: return
        pendingContentType = null
        post {
            if (!isAttachedToWindow) return@post
            contentType = pending
        }
    }

    /**
     * Holds the screen on while the lyrics are up and something is playing.
     *
     * Lyrics are the one screen in this app people leave running and look back at, and the display
     * timing out mid-song is the whole reason to be on it. Tied to playback rather than to the view
     * alone, so a paused player left open on the lyrics does not hold the screen on indefinitely -
     * the request is for the screen to survive a song, not for the phone to never sleep.
     *
     * `keepScreenOn` only applies while this window is visible, so backgrounding the app releases
     * it without anything having to notice.
     */
    private fun updateLyricsKeepScreenOn() {
        keepScreenOn = fadingEdgeLayout.visibility == VISIBLE && instance?.isPlaying == true
    }

    private fun showLyrics() {
        fadingEdgeLayout.visibility = VISIBLE
        fadingEdgeLayout.alpha = 0F
        fadingEdgeLayout.bringToFront()
        bringPlayerChromeToFront()
        if (!lyricsViewAttached) {
            lyricsViewAttached = true
            lyricsViewModel?.onViewCreated(fadingEdgeLayout)
        }
        refreshLyrics()
        updateLyricsKeepScreenOn()
    }

    /**
     * The lyrics surface fills the player so it can expand when the controls auto-hide. Keep the
     * interactive chrome later in the drawing/touch order or the lyrics scroll view consumes every
     * tap before the buttons can see it.
     */
    private fun bringPlayerChromeToFront() {
        listOf<View>(
            fullPlayerToolbar,
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            karaokeOverlayButton,
            airplayOverlayButton,
            outputDeviceIcon,
            outputDeviceName,
            listOverlayButton,
            karaokeStatus,
        ).forEach { it.bringToFront() }
    }

    private fun hideLyrics() {
        fadingEdgeLayout.visibility = INVISIBLE
        updateLyricsKeepScreenOn()
        fadingEdgeLayout.alpha = 0F
        fadingEdgeLayout.translationY = 0F
        fadingEdgeLayout.scaleX = 1F
        fadingEdgeLayout.scaleY = 1F
    }

    private fun animatePlayerTransform(
        target: Float,
        animateLyrics: Boolean = false,
        onEnd: (() -> Unit)? = null,
    ) {
        contentTypeAnimator?.cancel()
        var cancelled = false
        contentTypeAnimator = ValueAnimator.ofFloat(transformationFraction, target).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                transformationFraction = fraction
                if (isWideLayout) animateWideContentChange(fraction) else animateCoverChange(fraction)
                if (animateLyrics) animateLyricsEntrance(fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    contentTypeAnimator = null
                    onEnd?.invoke()
                    updateTransitionTargetForContentType(contentType)
                }
            })
            start()
        }
    }

    /**
     * Puts the content transform back where the open destination says it belongs.
     *
     * The player's own title, artist and artwork are moved and faded out by [animateCoverChange],
     * and nothing else writes their alpha - so if that transform does not run, or is cancelled
     * before it reaches the end, the header stays drawn at full strength on top of whatever pane
     * replaced it. Leaving the app with the lyrics open and coming back to it did exactly that:
     * the lyrics returned, the transform did not, and the song's name sat over the words.
     *
     * Instant and idempotent, because it is a statement of where things already are rather than a
     * transition to somewhere new. It defers to a transform that is genuinely mid-flight.
     */
    private fun syncContentTransformState() {
        if (contentTypeAnimator != null) return
        val target = if (contentType == ContentType.NORMAL) 0F else 1F
        transformationFraction = target
        if (isWideLayout) animateWideContentChange(target) else animateCoverChange(target)
        if (contentType == ContentType.LYRICS) animateLyricsEntrance(target)
    }

    private fun animateLyricsEntrance(fraction: Float) {
        val lyricsFraction = inverseLerp(lyricsStartFraction, 1F, fraction, clamp = true)
        val enterOffset = maxOf(
            progressOverlaySlider.top.toFloat() - fadingEdgeLayout.top.toFloat(),
            40.dp.px,
        )
        fadingEdgeLayout.translationY = lerp(enterOffset, 0F, lyricsFraction)
        fadingEdgeLayout.alpha = lyricsFraction
    }

    private fun animateContentSwap(from: View, to: View, onEnd: (() -> Unit)? = null) {
        contentTypeAnimator?.cancel()
        to.visibility = VISIBLE
        to.alpha = 0F
        to.scaleX = 0.92F
        to.scaleY = 0.92F
        var cancelled = false
        contentTypeAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.easingStandardInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                from.alpha = 1F - fraction
                from.scaleX = lerp(1F, 0.92F, fraction)
                from.scaleY = from.scaleX
                to.alpha = fraction
                to.scaleX = lerp(0.92F, 1F, fraction)
                to.scaleY = to.scaleX
                if (from === queueContainer) setQueueChildrenAlpha(1F - fraction)
                if (to === queueContainer) setQueueChildrenAlpha(fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    from.visibility = INVISIBLE
                    from.alpha = 0F
                    from.scaleX = 1F
                    from.scaleY = 1F
                    to.alpha = 1F
                    to.scaleX = 1F
                    to.scaleY = 1F
                    to.translationY = 0F
                    if (from === queueContainer) setQueueChildrenAlpha(0F)
                    if (to === queueContainer) setQueueChildrenAlpha(1F)
                    contentTypeAnimator = null
                    onEnd?.invoke()
                    updateTransitionTargetForContentType(contentType)
                }
            })
            start()
        }
    }

    private fun syncBottomButtons(value: ContentType) {
        setBottomButtonSelected(captionOverlayButton, value == ContentType.LYRICS)
        setBottomButtonSelected(listOverlayButton, value == ContentType.PLAYLIST)
        selectedBottomButton = when (value) {
            ContentType.LYRICS -> captionOverlayButton
            ContentType.PLAYLIST -> listOverlayButton
            ContentType.NORMAL -> null
        }
    }

    private fun setBottomButtonSelected(button: OverlayButton, selected: Boolean) {
        if (button.isChecked != selected) button.isChecked = selected
        animateBottomButtonScale(button, if (selected) 1.1F else 1F, selected)
    }

    private fun animateBottomButtonScale(
        button: OverlayButton,
        target: Float,
        selecting: Boolean,
    ) {
        if (button.scaleX == target && button.scaleY == target) return
        bottomButtonAnimators.remove(button)?.cancel()
        val animator = ValueAnimator.ofFloat(button.scaleX, target).apply {
            duration = if (selecting) 260L else 220L
            interpolator = OvershootInterpolator(if (selecting) 3.6F else 3.45F)
            addUpdateListener {
                val scale = it.animatedValue as Float
                button.scaleX = scale
                button.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (bottomButtonAnimators[button] === animation) {
                        bottomButtonAnimators.remove(button)
                    }
                }
            })
            start()
        }
        bottomButtonAnimators[button] = animator
    }

    private fun animateBottomButtonPress(button: OverlayButton) {
        animateBottomButtonScale(button, 1.1F, selecting = true)
        button.removeCallbacks(resetAirplayButton)
        button.postDelayed(resetAirplayButton, 180L)
    }

    private val resetAirplayButton = Runnable {
        animateBottomButtonScale(airplayOverlayButton, 1F, selecting = false)
    }

    private var controlsAnimator: ValueAnimator? = null
    private var controlsHideFraction = 0F
    private var lyricsControlsHidden = false
    private var revealingControlsGesture = false
    private val lyricsClipRect = Rect()
    private val hideControlsRunnable = Runnable { hideLyricsControls() }

    private fun scheduleControlsHide() {
        removeCallbacks(hideControlsRunnable)
        // The output sheet is a conversation with the chrome it was opened from. Letting the timer
        // run underneath it meant the lyrics and the transport slid away mid-drag, while a finger
        // was on a volume slider - the one moment the controls are demonstrably in use. The clock
        // starts again when the sheet closes, which is when interaction with it has actually
        // stopped.
        if (outputPickerDialog?.isShowing == true) return
        // A tablet's controls are beside the lyrics rather than under them, so they have nothing
        // to get out of the way of - `06`, `09` and `10` all keep the transport up while reading.
        if (isTabletLayout) return
        if (contentType == ContentType.LYRICS) {
            // Landscape shares one half between the chrome and the lyrics, so the chrome does not
            // linger over them: the wait exists only to let you finish reading the transport after
            // a tap put it back.
            postDelayed(
                hideControlsRunnable,
                if (isWideLayout) CONTROLS_HIDE_DELAY_WIDE_MS else CONTROLS_HIDE_DELAY_MS,
            )
        }
    }

    private fun cancelControlsHide() {
        removeCallbacks(hideControlsRunnable)
        controlsAnimator?.cancel()
        controlsAnimator = null
        lyricsControlsHidden = false
        setControlsVisibility(true)
        applyControlsHideFraction(0F)
    }

    private fun hideLyricsControls() {
        if (lyricsControlsHidden || contentType != ContentType.LYRICS) return
        lyricsControlsHidden = true
        // The output sheet is not this timer's to close. It is a conversation with the user -
        // choosing a speaker, dragging a volume - held in front of the chrome being tidied away,
        // and dismissing it cancelled what the user was doing on top of it. The chrome still goes;
        // the sheet stays until the user is done with it.
        animateControlsTo(1F) {
            setControlsVisibility(false)
        }
    }

    private var lyricsTouchDownX = 0F
    private var lyricsTouchDownY = 0F
    private var lyricsTouchDownAt = 0L

    /**
     * The lyrics keep their own touches on a landscape canvas.
     *
     * In portrait, a touch while the chrome is hidden is swallowed whole to bring it back, which
     * is fine there because the lyrics fill the screen and the chrome is what you are reaching
     * for. Landscape gives the lyrics half a screen and takes the chrome out of it entirely, so
     * swallowing the gesture left them unscrollable and their lines unseekable - the only thing a
     * touch could ever do was put the chrome back. Nothing is consumed here: a drag scrolls the
     * lyrics and a tap reaches the line under it.
     *
     * Recalling the chrome is everywhere *except* the lyrics - the artwork beside them, or any of
     * the space around it. A tap on a line already means something else there; asking it to seek
     * and to summon the transport at the same time made every seek flash the chrome over the words
     * that had just been jumped to.
     */
    private fun dispatchWideLyricsTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(hideControlsRunnable)
                lyricsTouchDownX = event.x
                lyricsTouchDownY = event.y
                lyricsTouchDownAt = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val tapped = abs(event.x - lyricsTouchDownX) <= slop &&
                    abs(event.y - lyricsTouchDownY) <= slop &&
                    event.eventTime - lyricsTouchDownAt <= LYRICS_TAP_TIMEOUT_MS
                if (tapped && !isInsideLyricsPane(lyricsTouchDownX, lyricsTouchDownY) &&
                    lyricsControlsHidden
                ) {
                    showLyricsControls(scheduleHide = true)
                } else {
                    scheduleControlsHide()
                }
            }

            MotionEvent.ACTION_CANCEL -> scheduleControlsHide()
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Non-interactive player space falls through to [FloatingPanelLayout], which owns vertical
     * collapse and artwork-swipe detection. Its confirmed tap comes back here; lyric taps never
     * take this path because the lyrics scroll view keeps its own gesture from ACTION_DOWN.
     */
    override fun onPlayerSurfaceTap(x: Float, y: Float) {
        if (contentType == ContentType.LYRICS && isWideLayout && lyricsControlsHidden &&
            !isInsideLyricsPane(x, y)
        ) {
            showLyricsControls(scheduleHide = true)
        }
    }

    private fun isInsideLyricsPane(x: Float, y: Float): Boolean =
        x >= fadingEdgeLayout.left && x <= fadingEdgeLayout.right &&
            y >= fadingEdgeLayout.top && y <= fadingEdgeLayout.bottom

    private fun showLyricsControls(scheduleHide: Boolean) {
        removeCallbacks(hideControlsRunnable)
        if (contentType != ContentType.LYRICS) return
        val needsAnimation = lyricsControlsHidden || controlsHideFraction > 0F
        lyricsControlsHidden = false
        setControlsVisibility(true)
        if (needsAnimation) {
            animateControlsTo(0F)
        }
        if (scheduleHide) scheduleControlsHide()
    }

    private fun animateControlsTo(target: Float, onEnd: (() -> Unit)? = null) {
        controlsAnimator?.cancel()
        var cancelled = false
        controlsAnimator = ValueAnimator.ofFloat(controlsHideFraction, target).apply {
            duration = MID_DURATION
            interpolator = AnimationUtils.fastOutSlowInInterpolator
            addUpdateListener {
                applyControlsHideFraction(it.animatedValue as Float)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    controlsAnimator = null
                    applyControlsHideFraction(target)
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun applyControlsHideFraction(fraction: Float) {
        controlsHideFraction = fraction
        listOf<View>(
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            karaokeOverlayButton,
            airplayOverlayButton,
            listOverlayButton,
            outputDeviceIcon,
            outputDeviceName,
            qualityBadge,
            bitrateBadge,
            karaokeStatus,
        ).forEach { applyControlCollapse(it, fraction) }
        identityRowThatCollapses().forEach { applyControlCollapse(it, fraction) }
        applyQualityBadgeAlpha()
        updateLyricsClip(fraction)
    }

    /**
     * The track's name, artist, favourite and overflow, when they are the chrome's to take away.
     *
     * In portrait they are not: folding the artwork into the toolbar carries all four up with it.
     * A wide canvas does not fold, so on a landscape phone - where lyrics want the whole right half
     * that these four sit at the top of - they leave with the rest of the chrome and come back with
     * it. A tablet keeps them: its column is beside the lyrics, not underneath them, and every
     * reference shows the name still there while the lyrics run.
     */
    private fun identityRowThatCollapses(): List<View> =
        if (isWideLayout && !isTabletLayout) {
            listOf(titleTextView, subtitleTextView, starTransformButton, ellipsisButton)
        } else {
            emptyList()
        }

    private fun applyControlCollapse(view: View, fraction: Float) {
        view.alpha = 1F - fraction
        val translation = maxOf(height.toFloat() - view.bottom, 24.dp.px)
        view.translationY = fraction * translation
    }

    private fun setControlsVisibility(visible: Boolean) {
        val visibility = if (visible) VISIBLE else INVISIBLE
        listOf<View>(
            progressOverlaySlider,
            currentTimestampTextView,
            leftTimestampTextView,
            controllerButton,
            previousButton,
            nextButton,
            volumeOverlaySlider,
            speakerHintView,
            speakerFullHintView,
            captionOverlayButton,
            listOverlayButton,
        ).forEach { it.visibility = visibility }
        identityRowThatCollapses().forEach { it.visibility = visibility }
        // GONE, not INVISIBLE: this button sits in the bottom row's horizontal chain, so an
        // invisible one still reserves its slot and leaves the three permanent buttons laid out
        // as though there were four - the middle one drifting off the centre its own device label
        // is aligned to.
        karaokeOverlayButton.visibility = if (visible && karaokeAvailable) VISIBLE else GONE
        if (visible) {
            refreshOutputDevice()
            syncQualityBadgeVisibility()
        } else {
            airplayOverlayButton.visibility = INVISIBLE
            outputDeviceIcon.visibility = INVISIBLE
            outputDeviceName.visibility = INVISIBLE
            qualityBadge.visibility = INVISIBLE
            bitrateBadge.visibility = INVISIBLE
        }
    }

    private fun updateLyricsClip(fraction: Float) {
        val height = fadingEdgeLayout.height
        if (height <= 0) {
            fadingEdgeLayout.doOnLayout { updateLyricsClip(fraction) }
            return
        }
        if (fraction >= 1F) {
            fadingEdgeLayout.clipBounds = null
            fadingEdgeLayout.setBottomFadeOffset(0)
            return
        }
        val controlsTop = (
            progressOverlaySlider.top - fadingEdgeLayout.top - 16.dp.px
        ).toInt().coerceIn(0, height)
        val clipBottom = (
            controlsTop + (height - controlsTop) * fraction
        ).toInt().coerceIn(0, height)
        // The identity row is above the lyrics rather than folded away into a toolbar here, so the
        // pane has to start below it or the current line is drawn straight through the title.
        // It opens back up as the row leaves.
        val identityBottom = if (identityRowThatCollapses().isEmpty()) {
            0
        } else {
            (subtitleTextView.bottom - fadingEdgeLayout.top + 12.dp.px).toInt().coerceIn(0, height)
        }
        val clipTop = (identityBottom * (1F - fraction)).toInt().coerceIn(0, clipBottom)
        lyricsClipRect.set(0, clipTop, fadingEdgeLayout.width, clipBottom)
        fadingEdgeLayout.clipBounds = lyricsClipRect
        fadingEdgeLayout.setBottomFadeOffset(height - clipBottom)
    }

    private var lastDisposable: Disposable? = null
    private var lyricsRefreshJob: Job? = null
    private var artworkLookupJob: Job? = null
    private var embeddedArtworkJob: Job? = null
    private var loadedArtworkIdentity: String? = null

    /**
     * The badge describes the format the player selected, which is only known once the tracks for
     * the new item have been read - hence here rather than on the media item transition.
     */
    /**
     * Pulls the lyrics the playback service resolved for the current track - embedded tags, a
     * matching .lrc, or the Jellyfin server - and hands them to the lyrics view. The service does
     * the resolving off the main thread and announces a result with this same command, so this runs
     * both on transition and when that announcement arrives.
     */
    private fun refreshLyrics() {
        val controller = instance ?: return
        // The sync offset is a setting now rather than a constant, and this is the moment the
        // lyrics sheet is next about to matter - so it is where a changed slider is picked up.
        lyricsViewModel?.refreshOffset()
        val mediaId = controller.currentMediaItem?.mediaId ?: return
        cachedLyrics.takeIf { cachedLyricsMediaId == mediaId }?.let {
            // Recreation should paint the last confirmed value immediately. The service may still
            // be resolving the same lyrics, but an unchanged item must not flash empty meanwhile.
            lyricsViewModel?.setLyrics(it)
        }
        lyricsRefreshJob?.cancel()
        lyricsRefreshJob = (findViewTreeLifecycleOwner()?.lifecycleScope
            ?: CoroutineScope(Dispatchers.Main)).launch {
            // Two steps on purpose. MediaController rejects calls from any thread but the one it
            // was built on, so the command has to be sent from here; waiting on the reply blocks,
            // so that part cannot be. Doing both off-main threw IllegalStateException every time,
            // which is why lyrics never appeared.
            val resolvedResult = runCatching {
                val future = controller.sendCustomCommand(
                    SessionCommand(GramophonePlaybackService.SERVICE_GET_LYRICS, Bundle.EMPTY),
                    Bundle.EMPTY
                )
                withContext(Dispatchers.IO) {
                    @Suppress("UNCHECKED_CAST")
                    BundleCompat.getParcelableArray(
                        future.get().extras, "lyrics", MediaStoreUtils.Lyric::class.java
                    ) as Array<MediaStoreUtils.Lyric>?
                }?.toList()
            }.onFailure { Log.e(TAG, "fetching lyrics failed", it) }
            // A failed refresh says nothing about whether the previous lyrics are still valid.
            // Keep them for the same media id; only a successful empty response means "no lyrics".
            if (resolvedResult.isFailure && cachedLyricsMediaId == mediaId) return@launch
            val resolved = resolvedResult.getOrNull()
            val translated = if (
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("automatic_lyrics_translation", false)
            ) {
                withContext(Dispatchers.IO) {
                    AutomaticLyricsTranslator.translateMissing(resolved.orEmpty())
                }
            } else {
                resolved.orEmpty()
            }
            val mapped = translated
                // The service prepends an empty element as a lead-in; it has no text to show.
                .filter { !it.content.isNullOrBlank() }
                .map { lyric ->
                    LyricsLine(
                        timestamp = lyric.startTimestamp ?: 0L,
                        agent = null,
                        text = lyric.content,
                        background = lyric.translationContent,
                        wordTimings = lyric.wordTimestamps.map { timing ->
                            LyricsWordTiming(
                                endOffset = timing.first,
                                startTimestamp = timing.second,
                                endTimestamp = timing.third,
                            )
                        },
                    )
                }
            withContext(Dispatchers.Main) {
                // An empty list renders as a black screen with nothing in it, which reads as a bug
                // rather than as "this track has no lyrics". Say so instead.
                val display = if (mapped.isEmpty()) {
                        Lyrics(listOf(LyricsLine(0L, null, context.getString(R.string.no_lyrics), null)))
                    } else {
                        Lyrics(mapped)
                    }
                if (instance?.currentMediaItem?.mediaId != mediaId) return@withContext
                if (cachedLyricsMediaId != mediaId || cachedLyrics != display) {
                    cachedLyricsMediaId = mediaId
                    cachedLyrics = display
                    lyricsViewModel?.setLyrics(display)
                }
            }
        }
    }

    /**
     * A seek is the one position change the lyrics clock must not smooth over.
     *
     * It follows the player deliberately slowly, so that media3's periodic position corrections
     * cannot step the highlight - which means a real jump has to say so, or the words would slide
     * towards the new place over the next several seconds instead of arriving with the sound.
     */
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            lyricsViewModel?.resync()
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        val details = AudioQuality.detailsOf(tracks)
        currentQualityDetails = details
        if (details == null) {
            qualityBadge.visibility = GONE
            showAvailableQualityHint()
        } else {
            updateQualityBadgeText()
            flashQualityBadge()
        }
        refreshAudioOutputStatus()
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.REPEAT_NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
    }

    /** Reads the exact USB mixer mode that Android accepted for this stream. */
    private fun refreshAudioOutputStatus() {
        val controller = instance ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val extras = runCatching {
                val future = controller.sendCustomCommand(
                    SessionCommand(
                        GramophonePlaybackService.SERVICE_GET_AUDIO_FORMAT,
                        Bundle.EMPTY,
                    ),
                    Bundle.EMPTY,
                )
                withContext(Dispatchers.IO) { future.get().extras }
            }.onFailure { Log.e(TAG, "fetching audio output format failed", it) }.getOrNull()
                ?: return@launch
            currentUsbHiFiStatus = BundleCompat.getParcelable(
                extras,
                "usb_hifi",
                UsbHiFiStatus::class.java,
            )
            currentAfFormatInfo = BundleCompat.getParcelable(
                extras,
                "hal_format",
                AfFormatInfo::class.java,
            )
            currentBtCodecInfo = BundleCompat.getParcelable(
                extras,
                "bt_codec",
                BtCodecInfo::class.java,
            )
            updateQualityBadgeText()
            outputPickerFormatChanged?.invoke()
        }
    }

    /**
     * What the badge says, and for how long, are two separate Settings choices.
     *
     * "Lossless" is a claim about the file; "FLAC · 1411 kbps" is the measurement behind it, and
     * which of the two is wanted on screen is a matter of taste rather than of correctness. The
     * timing is the same kind of choice: a permanent label becomes furniture, so it can instead
     * announce itself for two seconds when the track changes and get out of the way.
     */
    private enum class QualityBadgeContent { LABEL, CODEC }

    private fun qualityBadgeContent(): QualityBadgeContent =
        if (PreferenceManager.getDefaultSharedPreferences(context)
                .getString(QUALITY_BADGE_CONTENT, QUALITY_BADGE_CONTENT_LABEL) ==
            QUALITY_BADGE_CONTENT_CODEC
        ) QualityBadgeContent.CODEC else QualityBadgeContent.LABEL

    private fun qualityBadgeFlashes(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(QUALITY_BADGE_FLASH, false)

    private fun updateQualityBadgeText() {
        val details = currentQualityDetails ?: return
        when (qualityBadgeContent()) {
            QualityBadgeContent.LABEL -> {
                qualityBadge.setText(qualityBadgeText(details))
                bitrateBadge.text = null
            }

            QualityBadgeContent.CODEC -> {
                // The codec name is the point of this mode, but a stream media3 could not name
                // still deserves a badge, so the label stands in rather than leaving a blank pill.
                qualityBadge.text = details.codec
                    ?: context.getString(qualityBadgeText(details))
                bitrateBadge.text = details.bitrateBps
                    ?.let { context.getString(R.string.music_quality_bitrate, it / 1000) }
            }
        }
        syncQualityBadgeVisibility()
    }

    /**
     * Shows the badge for two seconds and takes it away again.
     *
     * Only in the flashing mode, and only from the moments worth announcing: a new track, and the
     * player being opened. Alpha is not written directly - the lyrics chrome fades these two views
     * as well, and whichever wrote last would win - so this drives one factor and
     * [applyQualityBadgeAlpha] multiplies the two together.
     */
    private fun cancelQualityFlash() {
        // cancel() also invokes onAnimationEnd: remove the continuation before cancelling,
        // otherwise an interrupted fade-in starts a fade-out that we immediately lose track of.
        qualityFlashAnimator?.removeAllListeners()
        qualityFlashAnimator?.cancel()
        qualityFlashAnimator = null
    }

    private fun flashQualityBadge() {
        if (!isAttachedToWindow) return
        if (!qualityBadgeFlashes()) return
        if (currentQualityDetails == null) return
        cancelQualityFlash()
        qualityFlashAlpha = 0F
        qualityFlashing = true
        syncQualityBadgeVisibility()
        qualityFlashAnimator = ValueAnimator.ofFloat(0F, 1F).apply {
            duration = QUALITY_HINT_FADE_MS
            addUpdateListener {
                qualityFlashAlpha = it.animatedValue as Float
                applyQualityBadgeAlpha()
            }
            doOnEnd {
                qualityFlashAnimator = ValueAnimator.ofFloat(1F, 0F).apply {
                    duration = QUALITY_HINT_FADE_MS
                    startDelay = QUALITY_BADGE_FLASH_HOLD_MS
                    addUpdateListener {
                        qualityFlashAlpha = it.animatedValue as Float
                        applyQualityBadgeAlpha()
                    }
                    doOnEnd {
                        qualityFlashing = false
                        syncQualityBadgeVisibility()
                    }
                    start()
                }
            }
            start()
        }
    }

    /** The flash and the lyrics-chrome fade, combined, so neither erases the other. */
    private fun applyQualityBadgeAlpha() {
        val alpha = qualityFlashAlpha * (1F - controlsHideFraction)
        qualityBadge.alpha = alpha
        bitrateBadge.alpha = alpha
    }

    private fun qualityBadgeText(details: AudioQuality.Details): Int = when {
        details.quality == AudioQuality.DOLBY_ATMOS -> details.quality.label
        !isRemotePlayback() && currentUsbHiFiStatus?.bitPerfect == true ->
            R.string.music_quality_bit_perfect
        !isRemotePlayback() && currentUsbHiFiStatus != null -> R.string.music_quality_usb_lossless
        else -> details.quality.label
    }

    private fun isRemotePlayback(): Boolean =
        instance?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE

    /**
     * Mentions, once and briefly, that the source is better than what is playing.
     *
     * Only reachable when there is no badge, which is exactly the case worth saying something
     * about: the badge describes what the decoder produced, so a lossless file streamed under a
     * cap arrives as AAC and shows nothing at all. Left permanent it would be a label complaining
     * about a setting the user chose deliberately; three seconds is enough to answer "could this
     * sound better?" without becoming furniture.
     */
    private fun showAvailableQualityHint() {
        qualityAvailableHint.animate().cancel()
        qualityAvailableHint.alpha = 0f

        val container = currentSourceContainer() ?: return
        if (container !in LOSSLESS_CONTAINERS) return
        // Nothing to advertise when the untouched file is already what is playing.
        if (StreamQuality.streamingQuality(context).isOriginal) return

        // Lossless, not Hi-Res: the library stores a container and nothing about bit depth or
        // sample rate, so the difference between 16/44 and 24/96 is not knowable here. Saying
        // Hi-Res on a guess would be worse than saying the smaller true thing.
        qualityAvailableHint.setText(R.string.quality_lossless_available)
        qualityAvailableHint.animate()
            .alpha(1f)
            .setDuration(QUALITY_HINT_FADE_MS)
            .withEndAction {
                qualityAvailableHint.animate()
                    .alpha(0f)
                    .setStartDelay(QUALITY_HINT_HOLD_MS)
                    .setDuration(QUALITY_HINT_FADE_MS)
                    .start()
            }
            .start()
    }

    /** The container of the file on the server, which the library knows even when playing AAC. */
    private fun currentSourceContainer(): String? =
        instance?.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_SOURCE_CONTAINER)
            ?.lowercase()

    private fun syncQualityBadgeVisibility() {
        // A badge whose two seconds are up is taken out of the layout rather than left at alpha 0:
        // faded out is not the same as gone, and an invisible view still answers taps - which is
        // exactly how the play button's oversized square came to swallow presses meant for this row.
        if (!qualityBadgeFlashes()) {
            cancelQualityFlash()
            qualityFlashing = false
            qualityFlashAlpha = 1F
        }
        val flashedOut = qualityBadgeFlashes() && !qualityFlashing && qualityFlashAlpha <= 0F
        qualityBadge.visibility = when {
            currentQualityDetails == null -> GONE
            lyricsControlsHidden || flashedOut -> INVISIBLE
            else -> VISIBLE
        }
        bitrateBadge.visibility = when {
            qualityBadge.visibility != VISIBLE -> qualityBadge.visibility
            bitrateBadge.text.isNullOrEmpty() -> GONE
            else -> VISIBLE
        }
        applyQualityBadgeAlpha()
    }

    /**
     * Names the format behind the badge - "FLAC 24-bit/96 kHz" - which is the one thing the badge
     * itself cannot say, since 24/48 and 24/192 both read "Hi-Res Lossless".
     */
    private fun showQualityDetails() {
        val details = currentQualityDetails ?: return
        val sheet = PlayerSheet.create(context)
        val root = LayoutInflater.from(context)
            .inflate(R.layout.layout_audio_quality_sheet, null, false)
        val backdrop = root.findViewById<ImageView>(R.id.audio_quality_backdrop)
        var backdropBitmap: Bitmap? = null
        val rows = root.findViewById<LinearLayout>(R.id.audio_quality_rows)

        fun render() {
            root.findViewById<TextView>(R.id.audio_quality_title)
                .setText(qualityBadgeText(details))
            rows.removeAllViews()
            val messages = qualityDetailRows(details)
                .ifEmpty { listOf(context.getString(R.string.music_quality_unknown)) }
            messages.forEachIndexed { index, message ->
                val row = LayoutInflater.from(context)
                    .inflate(R.layout.layout_audio_quality_row, rows, false)
                row.findViewById<TextView>(R.id.audio_quality_row_text).text = message
                row.findViewById<View>(R.id.audio_quality_row_divider).visibility =
                    if (index == messages.lastIndex) GONE else VISIBLE
                rows.addView(row)
            }
        }

        render()
        sheet.setContentView(root)
        sheet.setOnDismissListener {
            backdrop.setImageDrawable(null)
            backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            backdropBitmap = null
        }
        PlayerSheet.present(activity, sheet, root) {
            PlayerSheet.captureBackdrop(activity, sheet, root, backdrop) { captured ->
                backdropBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                backdropBitmap = captured
            }
        }

        // Asked for after the sheet is up, not before it. The negotiated Bluetooth codec is behind
        // BLUETOOTH_CONNECT, and gating the whole sheet on that permission meant a tap on the badge
        // could answer with nothing at all - a permission the user declined once is not a reason to
        // withhold the format of the file they are listening to. The row appears when it is known.
        if (!isRemotePlayback() &&
            currentAfFormatInfo?.routedDeviceType == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestBluetoothCodecPermission { granted ->
                if (!granted) return@requestBluetoothCodecPermission
                refreshAudioOutputStatus()
                postDelayed({ if (sheet.isShowing) render() }, CODEC_PERMISSION_SETTLE_MS)
            }
        }
    }

    /** The signal path as lines of text: the source file, the DAC, the codec, the HAL output. */
    private fun qualityDetailRows(details: AudioQuality.Details): List<String> = buildList {
            val source = buildList {
                details.codec?.let { add(it) }
                val bitDepth = details.bitDepth
                val sampleRate = details.sampleRateHz
                if (bitDepth != null && sampleRate != null) {
                    add(context.getString(R.string.music_quality_depth_rate, bitDepth, sampleRate.khz()))
                } else if (sampleRate != null) {
                    add(context.getString(R.string.music_quality_rate, sampleRate.khz()))
                }
            }.joinToString(" ")
            if (source.isNotEmpty()) add(context.getString(R.string.music_quality_source, source))

            currentUsbHiFiStatus?.takeUnless { isRemotePlayback() }?.let { usb ->
                add(context.getString(R.string.music_quality_usb_device, usb.deviceName))
                val output = buildString {
                    usb.encoding.bitDepthName()?.let { append(it).append("/") }
                    append(usb.sampleRateHz.khz()).append(" kHz")
                }
                add(context.getString(R.string.music_quality_output, output))
                add(
                    context.getString(
                        if (usb.bitPerfect) R.string.music_quality_bit_perfect_detail
                        else R.string.music_quality_usb_exact_detail
                    )
                )
            }

            val bluetoothSignal = if (isRemotePlayback()) null
                else bluetoothOutputSignalLabel(includeQuality = true)
            bluetoothSignal?.let { signal ->
                add(context.getString(R.string.music_quality_bluetooth_codec, signal))
                if (signal.startsWith("SSC UHQ")) {
                    add(context.getString(R.string.music_quality_ssc_uhq_detail))
                }
            }

            currentAfFormatInfo?.takeUnless { isRemotePlayback() }?.let { hal ->
                hal.routedDeviceName?.takeIf { it.isNotBlank() && it != "null" }?.let {
                    add(context.getString(R.string.music_quality_output_device, it))
                }
                val actual = buildList {
                    hal.audioFormat?.let { add(it.audioFormatLabel()) }
                    hal.sampleRateHz?.toInt()?.takeIf { it > 0 }?.let {
                        add("${it.khz()} kHz")
                    }
                    hal.channelCount?.takeIf { it > 0 }?.let {
                        add(context.resources.getQuantityString(
                            R.plurals.music_quality_channels,
                            it,
                            it,
                        ))
                    }
                }.joinToString(" · ")
                if (actual.isNotEmpty()) {
                    add(context.getString(R.string.music_quality_hal_output, actual))
                }
                if (hal.isBluetoothOffload == true) {
                    add(context.getString(R.string.music_quality_bluetooth_offload))
                }
            }
        }

    /** 44100 reads as "44.1", 48000 as "48" - trailing zeroes here are noise. */
    private fun Int.khz(): String = "%.1f".format(this / 1000f).removeSuffix(".0")

    /** Turns AudioFlinger's constant-like spelling into a compact listener-facing label. */
    private fun String.audioFormatLabel(): String = removePrefix("AUDIO_FORMAT_")
        .replace("PCM_", "PCM ")
        .replace("_BIT", "-bit")
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar(Char::uppercase)

    /**
     * The compact signal-path label shown under the active device in Play on.
     *
     * Prefer the negotiated Bluetooth codec. AudioFlinger's mixer format fills any fields the
     * protected Bluetooth API did not expose, and is also the truthful label for wired/USB/local
     * PCM routes. This describes the output path, not whether the source file itself is lossless.
     */
    /**
     * Whether the destination being played through shows what it is actually receiving.
     *
     * The same choice the now-playing badge offers, for the same reason: the codec, depth and rate
     * are the point for some people and clutter on a small card for everyone else. On by default,
     * because a Bluetooth route that has quietly fallen back to SBC is worth being told about.
     */
    private fun showOutputCodecBadge(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(OUTPUT_CODEC_BADGE, true)

    private fun currentOutputSignalLabel(): String? {
        // A2DP/HAL observations describe the phone's output and remain cached while Cast or a
        // Finnect player owns playback. They must never override the remote player's source.
        if (isRemotePlayback()) return remotePlayerSignalLabel()
        val hal = currentAfFormatInfo
        val halRate = hal?.sampleRateHz?.toInt()?.takeIf { it > 0 }
        val halDepth = hal?.audioFormat?.audioFormatBitDepth()

        bluetoothOutputSignalLabel()?.let { return it }

        currentUsbHiFiStatus?.let { usb ->
            return buildSignalLabel(
                codec = "PCM",
                bitDepth = usb.encoding.bitDepthName()?.removeSuffix("-bit")?.toIntOrNull(),
                sampleRate = usb.sampleRateHz,
            )
        }

        return buildSignalLabel(
            codec = hal?.audioFormat?.let { "PCM" },
            bitDepth = halDepth,
            sampleRate = halRate,
        )
    }

    /** Best truthful signal label for a remote player, which has no phone-side audio sink. */
    private fun remotePlayerSignalLabel(): String? {
        val sourceCodec = currentSourceContainer()?.uppercase()
        val details = currentQualityDetails
        val codec = sourceCodec ?: details?.codec
        // Dimensions reported for a different decoder route can linger across a Cast handoff.
        // Only retain them when the selected track's codec agrees with the stored source.
        val matchingDetails = details?.takeIf {
            sourceCodec == null || it.codec.equals(sourceCodec, ignoreCase = true)
        }
        return buildSignalLabel(codec, matchingDetails?.bitDepth, matchingDetails?.sampleRateHz)
    }

    /**
     * Reconciles Android's codec broadcast with the stream route AudioFlinger is using now.
     *
     * Samsung can retain an SSC 24/48 codec broadcast after switching its UHQ mixer to 24/96.
     * For a recognized Galaxy Buds route that exact 24/96 HAL signature is the newer observation,
     * so it wins. The cached broadcast is otherwise only used while the live route is A2DP; this
     * prevents a disconnected headset's codec from appearing below the phone speaker or a DAC.
     */
    private fun bluetoothOutputSignalLabel(includeQuality: Boolean = false): String? {
        val hal = currentAfFormatInfo ?: return null
        if (hal.routedDeviceType != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return null

        inferredSamsungUhqSignal()?.let { return it }

        val bluetooth = currentBtCodecInfo ?: return null
        val sampleRate = bluetooth.sampleRateHz
            ?: hal.sampleRateHz?.toInt()?.takeIf { it > 0 }
        val bitDepth = bluetooth.bitsPerSample ?: hal.audioFormat?.audioFormatBitDepth()
        val codec = bluetooth.codec?.let {
            if (it.equals("SSC", ignoreCase = true) && (sampleRate ?: 0) >= 96_000) {
                "SSC UHQ"
            } else it
        }
        val base = buildSignalLabel(codec, bitDepth, sampleRate) ?: return null
        return if (includeQuality && !bluetooth.quality.isNullOrBlank()) {
            "$base · ${bluetooth.quality}"
        } else base
    }

    private fun buildSignalLabel(codec: String?, bitDepth: Int?, sampleRate: Int?): String? {
        val parts = buildList {
            codec?.takeIf { it.isNotBlank() }?.let(::add)
            bitDepth?.let { add("$it-bit") }
            sampleRate?.let { add("${it.khz()} kHz") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun String.audioFormatBitDepth(): Int? = when {
        contains("8_24_BIT") || contains("24_BIT") -> 24
        contains("32_BIT") || contains("FLOAT") -> 32
        contains("16_BIT") -> 16
        contains("8_BIT") -> 8
        else -> null
    }

    /**
     * One UI does not allow an ordinary app to synchronously query BluetoothCodecStatus unless it
     * owns a CompanionDeviceManager association. Its actual 24/96 A2DP mixer route is still
     * visible through AudioFlinger. Restrict this inference to Galaxy Buds whose model glyph we
     * recognize; 24/96 on an arbitrary headset might be LDAC or another codec.
     */
    private fun inferredSamsungUhqSignal(): String? {
        val hal = currentAfFormatInfo ?: return null
        val name = hal.routedDeviceName?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        if (hal.routedDeviceType != android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return null
        if (samsungBudsIconForName(name.lowercase()) == null) return null
        if ((hal.sampleRateHz?.toInt() ?: 0) < 96_000) return null
        if (hal.audioFormat?.contains("24_BIT") != true) return null
        return "SSC UHQ · 24-bit · ${hal.sampleRateHz!!.toInt().khz()} kHz"
    }

    private fun Int.bitDepthName(): String? = when (this) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> "8-bit"
        android.media.AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
        android.media.AudioFormat.ENCODING_PCM_32BIT,
        android.media.AudioFormat.ENCODING_PCM_FLOAT -> "32-bit"
        else -> null
    }

    /**
     * Where the artwork is, for the panel's gesture handling. Null while the player is not fully
     * open, where the cover is a thumbnail in the collapsed bar and swiping it means nothing.
     */
    override fun coverBounds(): RectF? {
        // onSlide keeps this in step with the panel; a swipe only means anything once the player is
        // fully open, where the cover is a large target rather than a thumbnail in the collapsed bar.
        if (slideFraction < 1F) return null
        val location = IntArray(2)
        val panelLocation = IntArray(2)
        coverSimpleImageView.getLocationOnScreen(location)
        floatingPanelLayout.getLocationOnScreen(panelLocation)
        val left = (location[0] - panelLocation[0]).toFloat()
        val top = (location[1] - panelLocation[1]).toFloat()
        return RectF(
            left, top,
            left + coverSimpleImageView.width, top + coverSimpleImageView.height
        )
    }

    /** Which way the cover is being pulled, and whether there is a track that way. */
    private var coverSwipeDirection = 0
    private var coverSwipeActionAvailable = false

    private fun isCoverSwipeActionAvailable(direction: Int): Boolean {
        val player = instance
        val remote = JellyfinRemoteTargets.playbackState.value
        val remoteIndex = remote?.queueIds?.indexOf(remote.itemId)
        return if (direction < 0) {
            if (remote != null) remoteIndex != null && remoteIndex in 0 until remote.queueIds.lastIndex
            else player?.hasNextMediaItem() == true
        } else {
            if (remote != null) (remoteIndex ?: -1) > 0 || remote.projectedPositionMs() > 0L
            else player?.hasPreviousMediaItem() == true || (player?.currentPosition ?: 0L) > 0L
        }
    }

    /**
     * The cover follows the finger at half distance rather than one-to-one: it is anchored in the
     * layout and cannot actually leave, and a cover that tracks the finger exactly reads as
     * something that should come away in your hand.
     */
    override fun onCoverSwipeMove(dx: Float) {
        coverSlideAnimator?.cancel()
        coverSlideAnimator = null
        // Answered once per direction rather than once per touch event. Whether there is a track
        // that way cannot change under the finger, and on a Finnect target the question walks the
        // whole receiver queue looking for the current id - an O(n) scan landing between the frames
        // of a gesture whose only job is to keep up with a thumb.
        val direction = if (dx < 0F) -1 else 1
        if (direction != coverSwipeDirection) {
            coverSwipeDirection = direction
            coverSwipeActionAvailable = isCoverSwipeActionAvailable(direction)
        }
        val actionAvailable = coverSwipeActionAvailable
        coverSwipeHaptics.update(
            coverSimpleImageView,
            dx,
            coverSimpleImageView.width * COVER_SWIPE_THRESHOLD,
            actionAvailable,
        )
        coverSlideOffsetX = resistedSwipeDistance(
            dx,
            coverSimpleImageView.width * COVER_SWIPE_MAX_TRAVEL,
            COVER_SWIPE_FOLLOW,
        )
        applyCoverTranslation()
    }

    override fun onCoverSwipeEnd(dx: Float, velocityX: Float) {
        coverSwipeDirection = 0
        val flingThreshold = ViewConfiguration.get(context).scaledMinimumFlingVelocity *
            COVER_FLING_VELOCITY_MULTIPLIER
        val hasFling = abs(velocityX) >= flingThreshold
        val releaseDirection = if (hasFling) velocityX else dx
        if (abs(dx) >= coverSimpleImageView.width * COVER_SWIPE_THRESHOLD || hasFling) {
            // Dragging the current cover away to the left brings on the next track, matching
            // how every carousel on the platform reads. The cover is not sent home here - the
            // track change does that, carrying it the rest of the way out and bringing the new
            // one in.
            val player = instance
            val forwards = releaseDirection < 0F
            // At the end of the queue seekToNext does nothing, so no track change arrives and
            // nothing would ever bring the cover back - it sat where the finger left it.
            val remote = JellyfinRemoteTargets.playbackState.value
            val remoteIndex = remote?.queueIds?.indexOf(remote.itemId)
            val willMove = if (forwards) {
                if (remote != null) remoteIndex != null && remoteIndex in 0 until remote.queueIds.lastIndex
                else player?.hasNextMediaItem() == true
            } else {
                if (remote != null) (remoteIndex ?: -1) > 0 || remote.projectedPositionMs() > 0L
                else player?.hasPreviousMediaItem() == true || (player?.currentPosition ?: 0L) > 0L
            }
            if (willMove) {
                coverSwipeHaptics.commit(coverSimpleImageView)
                pendingCoverReleaseVelocity = velocityX
                // The backstop covers the cases the check above cannot see - a repeat mode
                // changing under us, or a queue emptied while the finger was down.
                armCoverSlide(
                    if (forwards) SLIDE_NEXT else SLIDE_PREVIOUS,
                    COVER_SLIDE_BACKSTOP_MS,
                )
                if (forwards) player?.seekToNext() else player?.seekToPrevious()
                return
            }
        }
        // Not far enough to count. Back where it was.
        coverSwipeHaptics.release(coverSimpleImageView)
        val projectedOffset = resistedSwipeDistance(
            dx + velocityX * COVER_MOMENTUM_PROJECTION_SECONDS,
            coverSimpleImageView.width * COVER_SWIPE_MAX_TRAVEL,
            COVER_SWIPE_FOLLOW,
        )
        val continuesCurrentDirection = coverSlideOffsetX == 0F ||
            projectedOffset * coverSlideOffsetX > 0F
        if (continuesCurrentDirection && abs(projectedOffset) > abs(coverSlideOffsetX)) {
            animateCoverOffset(
                projectedOffset,
                COVER_MOMENTUM_MS,
                LinearInterpolator(),
            ) {
                animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
            }
        } else {
            animateCoverOffset(0F, COVER_SWIPE_SETTLE_MS, settleInterpolator)
        }
    }

    /** Shows the route actually selected for playback, never merely a connected device. */
    private fun refreshOutputDevice() {
        resyncVolumeForOutput()
        JellyfinRemoteTargets.active.value?.let { target ->
            showOutput(clientIconFor(target.client), target.deviceName.ifBlank { target.client })
            return
        }
        FincordCast.session.value?.castDevice?.let { device ->
            val route = activeCastRoute(outputMediaRouter.routes)
            showOutput(
                route?.let(::routeIconFor) ?: R.drawable.ic_output_speaker,
                device.friendlyName ?: route?.name?.toString() ?: context.getString(R.string.output_device),
                route?.iconUri,
                route,
            )
            return
        }
        val route = outputMediaRouter.selectedRoute
        if (route.id == outputMediaRouter.defaultRoute.id) {
            outputDeviceIcon.visibility = GONE
            outputDeviceName.visibility = GONE
            airplayOverlayButton.visibility = VISIBLE
            return
        }
        showOutput(routeIconFor(route), route.name.toString(), route.iconUri, route)
    }

    private fun activeCastRoute(
        routes: List<MediaRouter.RouteInfo>,
    ): MediaRouter.RouteInfo? {
        val device = FincordCast.session.value?.castDevice ?: return null
        val deviceId = device.deviceId
        routes.firstOrNull { route ->
            runCatching { CastDevice.getFromBundle(route.extras) }.getOrNull()?.deviceId == deviceId
        }?.let { return it }
        val friendlyName = device.friendlyName ?: return null
        return routes.firstOrNull { route ->
            route.matchesSelector(outputRouteSelector) &&
                route.name.toString().equals(friendlyName, ignoreCase = true)
        }
    }

    private fun showOutput(
        @DrawableRes icon: Int,
        name: String,
        iconUri: Uri? = null,
        localRoute: MediaRouter.RouteInfo? = null,
    ) {
        bindOutputIcon(outputDeviceIcon, icon, iconUri, localRoute)
        outputDeviceIcon.visibility = VISIBLE
        outputDeviceName.text = name
        outputDeviceName.visibility = VISIBLE
        // Hidden rather than removed: the device icon is constrained to this button's bounds, so it
        // still has to occupy its place in the row.
        airplayOverlayButton.visibility = INVISIBLE
    }

    private fun bindOutputIcon(
        view: ImageView,
        @DrawableRes fallback: Int,
        iconUri: Uri?,
        localRoute: MediaRouter.RouteInfo? = null,
    ) {
        if (iconUri != null) {
            // Samsung and other route providers can expose model-specific artwork here (for
            // example a Buds glyph). Keep Accord's device-class icon as a safe fallback.
            view.setImageResource(fallback)
            view.load(iconUri) {
                listener(onError = { _, _ -> view.setImageResource(fallback) })
            }
            return
        }

        // AndroidX does not populate iconUri for Samsung's built-in LE Audio route. SystemUI still
        // has the semantic route drawable, so prefer the one from the installed One UI build. A
        // renamed/missing resource simply falls through to Accord's extracted model fallback.
        val systemDrawable = localRoute?.let(::systemRouteDrawable)
        if (systemDrawable != null) view.setImageDrawable(systemDrawable)
        else view.setImageResource(fallback)
    }

    @SuppressLint("DiscouragedApi")
    private fun systemRouteDrawable(route: MediaRouter.RouteInfo): Drawable? {
        if (platformRouteType(route) != MediaRoute2Info.TYPE_BLE_HEADSET) return null
        if (samsungBudsIconForName(route.name.toString().lowercase()) == null) return null
        return runCatching {
            val systemUi = context.createPackageContext(
                "com.android.systemui",
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val resources = systemUi.resources
            val candidates = listOf(
                // Samsung One UI: the exact glyph shown for an LE Audio/Auracast earbuds route.
                "list_ic_earbuds_stem_auracast",
                "ic_bt_le_audio_sharing",
                "ic_bt_le_audio",
            )
            candidates.firstNotNullOfOrNull { name ->
                resources.getIdentifier(name, "drawable", systemUi.packageName)
                    .takeIf { it != 0 }
                    ?.let(systemUi::getDrawable)
            }
        }.getOrNull()
    }

    private fun platformRouteType(route: MediaRouter.RouteInfo): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val router = MediaRouter2.getInstance(context)
            val sameName: (MediaRoute2Info) -> Boolean = {
                it.name.toString().equals(route.name.toString(), ignoreCase = true)
            }
            router.systemController.selectedRoutes.firstOrNull(sameName)?.type
                ?: router.routes.firstOrNull(sameName)?.type
        }.getOrNull()
    }

    @DrawableRes
    private fun routeIconFor(route: MediaRouter.RouteInfo): Int {
        val name = route.name.toString().lowercase()
        when (platformRouteType(route)) {
            MediaRoute2Info.TYPE_BLE_HEADSET -> return if (samsungBudsIconForName(name) != null) {
                R.drawable.ic_output_samsung_buds_auracast
            } else {
                R.drawable.ic_output_earbuds
            }
            MediaRoute2Info.TYPE_USB_DEVICE,
            MediaRoute2Info.TYPE_USB_ACCESSORY,
            MediaRoute2Info.TYPE_USB_HEADSET,
            MediaRoute2Info.TYPE_WIRED_HEADSET,
            MediaRoute2Info.TYPE_WIRED_HEADPHONES -> return R.drawable.ic_headphones
            MediaRoute2Info.TYPE_HDMI,
            MediaRoute2Info.TYPE_HDMI_ARC,
            MediaRoute2Info.TYPE_HDMI_EARC -> return R.drawable.ic_output_desktop
        }
        samsungBudsIconForName(name)?.let { return it }
        when (platformRouteType(route)) {
            MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
            MediaRoute2Info.TYPE_HEARING_AID -> return R.drawable.ic_headphones
        }
        return when {
            "usb" in name || "dac" in name -> R.drawable.ic_headphones
            "airpods" in name || "earbud" in name ->
                R.drawable.ic_output_earbuds
            "headphone" in name || "headset" in name -> R.drawable.ic_headphones
            "car" in name || "auto" in name -> R.drawable.ic_output_car
            "tv" in name || "display" in name || "monitor" in name || "chromecast" in name ->
                R.drawable.ic_output_desktop
            "phone" in name || "galaxy" in name || "pixel" in name -> R.drawable.ic_output_phone
            route.isBluetooth -> R.drawable.ic_output_earbuds
            else -> R.drawable.ic_output_speaker
        }
    }

    /** Model glyphs taken from the One UI build on this phone, with generic brands excluded. */
    @DrawableRes
    private fun samsungBudsIconForName(name: String): Int? = when {
        "pixel buds" in name -> null
        "buds4" in name || "buds 4" in name -> R.drawable.ic_output_buds4
        "buds3 fe" in name || "buds 3 fe" in name || "buds fe" in name ||
            "buds core" in name -> R.drawable.ic_output_samsung_buds_fe
        "buds3" in name || "buds 3" in name -> R.drawable.ic_output_samsung_buds3
        "buds2" in name || "buds 2" in name -> R.drawable.ic_output_samsung_buds2
        "buds live" in name -> R.drawable.ic_output_samsung_buds_live
        "galaxy buds" in name || "buds pro" in name || "buds+" in name ->
            R.drawable.ic_output_samsung_buds_classic
        else -> null
    }

    /**
     * Puts a working volume slider on a destination this app is allowed to move.
     *
     * Only routes that report [MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE] get one: a Bluetooth
     * or wired route is the system's to control and its slider would do nothing. Cast speakers and
     * groups do report it, which is what makes this useful.
     *
     * `requestSetVolume` is the route-level call rather than the Cast session's, so it works the
     * same for a group and for one member of that group - a member is just another route.
     */
    /**
     * Volume keys while the output sheet is open.
     *
     * Mirrors what MainActivity does for the player behind it: whatever owns playback owns its own
     * volume, so a receiver gets the keys while casting and the phone's stream gets them otherwise.
     * The overlay is suppressed the same way - by not passing FLAG_SHOW_UI - because this card has
     * a volume bar of its own and the system's panel lands on top of it.
     */
    private fun handleSheetVolumeKey(keyCode: Int, event: KeyEvent): Boolean {
        val raise = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        // The release is swallowed too, or the system acts on it and shows the panel anyway.
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val controller = instance
        val remote = controller != null &&
            controller.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE &&
            controller.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
        if (remote) {
            if (raise) controller.increaseDeviceVolume(0) else controller.decreaseDeviceVolume(0)
        } else {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                0,
            )
            updatePhoneOutputSlider()
        }
        return true
    }

    private fun bindRouteVolume(
        slider: OverlaySlider,
        route: MediaRouter.RouteInfo?,
        isPhoneOutput: Boolean = false,
        showVolume: Boolean = true,
    ) {
        if (!showVolume) {
            slider.visibility = GONE
            return
        }
        if (isPhoneOutput) {
            val manager = audioManager
            val maximum = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            if (manager == null || maximum <= 0) {
                slider.visibility = GONE
                return
            }
            slider.visibility = VISIBLE
            slider.valueFrom = 0F
            slider.valueTo = maximum.toFloat()
            slider.value = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                .toFloat().coerceIn(0F, slider.valueTo)
            slider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
                override fun onStartTracking(slider: OverlaySlider) {
                    slider.parent?.requestDisallowInterceptTouchEvent(true)
                    cancelSheetSliderAnimation(slider)
                    outputVolumeDragging = true
                }

                override fun onValueChanged(
                    slider: OverlaySlider,
                    value: Float,
                    fromUser: Boolean,
                    fromMomentum: Boolean,
                ) {
                    if (fromUser || fromMomentum) {
                        manager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
                    }
                }

                override fun onStopTracking(slider: OverlaySlider) {
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, slider.value.toInt(), 0)
                    slider.parent?.requestDisallowInterceptTouchEvent(false)
                    outputVolumeDragging = false
                }

                /** The fling outlives the finger; this is where its final value is committed. */
                override fun onStopFlinging(slider: OverlaySlider, value: Float) {
                    manager.setStreamVolume(AudioManager.STREAM_MUSIC, value.toInt(), 0)
                }
            })
            return
        }
        val adjustable = route != null &&
            route.volumeHandling == MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE &&
            route.volumeMax > 0
        if (!adjustable) {
            slider.visibility = GONE
            return
        }
        slider.visibility = VISIBLE
        slider.valueFrom = 0F
        slider.valueTo = route.volumeMax.toFloat()
        slider.value = route.volume.toFloat().coerceIn(0F, route.volumeMax.toFloat())
        slider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onStartTracking(slider: OverlaySlider) {
                // The row underneath is a click target that would switch playback to this
                // destination; dragging its volume is not a request to move the music there.
                slider.parent?.requestDisallowInterceptTouchEvent(true)
                cancelSheetSliderAnimation(slider)
                outputVolumeDragging = true
            }

            override fun onValueChanged(
                slider: OverlaySlider,
                value: Float,
                fromUser: Boolean,
                fromMomentum: Boolean,
            ) {
                // Momentum counts: the fling carries the value on after the finger has gone, and
                // ignoring it would leave the speaker at wherever the finger happened to lift.
                if (fromUser || fromMomentum) route.requestSetVolume(value.toInt())
            }

            override fun onStopTracking(slider: OverlaySlider) {
                route.requestSetVolume(slider.value.toInt())
                slider.parent?.requestDisallowInterceptTouchEvent(false)
                outputVolumeDragging = false
            }

            /** The fling outlives the finger; this is where its final value is committed. */
            override fun onStopFlinging(slider: OverlaySlider, value: Float) {
                route.requestSetVolume(value.toInt())
            }
        })
    }

    /**
     * Re-reads the volume after the output has changed, everywhere it is shown.
     *
     * Each output carries its own volume, and its own maximum: a headset runs an absolute scale the
     * phone speaker does not share, so both the position and the range move when the route does.
     * Nothing announced that. `VOLUME_CHANGED_ACTION` fires when a level changes, not when the
     * device under it is swapped, so unplugging a pair of buds left every slider showing the level
     * they had been at until the user happened to nudge one - at which point it jumped.
     *
     * The cached maximum is dropped rather than reused, because a stale range is the subtler half
     * of the same fault: the right index against the wrong maximum draws in the wrong place.
     *
     * Read twice. The route is swapped before the mixer has finished following it, so the first
     * read can still answer with the outgoing device's level.
     */
    private fun resyncVolumeForOutput() {
        maxDeviceVolume = 0
        updateVolumeSlider()
        updatePhoneOutputSlider()
        removeCallbacks(volumeResyncRunnable)
        postDelayed(volumeResyncRunnable, OUTPUT_VOLUME_SETTLE_MS)
    }

    private val volumeResyncRunnable = Runnable {
        maxDeviceVolume = 0
        updateVolumeSlider()
        updatePhoneOutputSlider()
    }

    /**
     * Keeps a row's labels readable where the volume fill passes underneath them.
     *
     * The fill is light and the labels are white, so the half of a name sitting over the fill
     * disappeared into it. [SplitTintTextView] draws each label twice under complementary clips;
     * this is the part that tells it where the boundary currently is.
     *
     * The slider spans the whole row with no side padding, so the boundary in row coordinates is
     * simply the row's width times the value - then shifted into each label's own coordinates,
     * because a label sits inset behind the icon and its padding.
     */
    private fun bindRowLabelSplit(row: View, slider: OverlaySlider) {
        val title = row.findViewById<SplitTintTextView>(R.id.output_row_title)
        val subtitle = row.findViewById<SplitTintTextView>(R.id.output_row_subtitle)
        // The icon sits at the left of the row, which is the part the fill covers first, so it goes
        // under the fill before either label does. Treating only the labels left it washed out from
        // the moment the volume left zero.
        val icon = row.findViewById<SplitTintImageView>(R.id.output_row_icon)
        val apply = {
            val span = (slider.valueTo - slider.valueFrom).takeIf { it > 0F } ?: 1F
            val fraction = if (slider.visibility == VISIBLE) {
                ((slider.value - slider.valueFrom) / span).coerceIn(0F, 1F)
            } else {
                0F
            }
            val fillEdge = row.width * fraction
            title.splitX = fillEdge - offsetInRow(title, row)
            subtitle.splitX = fillEdge - offsetInRow(subtitle, row)
            icon.splitX = fillEdge - offsetInRow(icon, row)
        }
        slider.addValueChangeListener(object : OverlaySlider.ValueChangeListener {
            override fun onValueChanged(
                slider: OverlaySlider,
                value: Float,
                fromUser: Boolean,
                fromMomentum: Boolean,
            ) = apply()
        })
        row.doOnLayout { apply() }
    }

    /** How far [view] sits from [row]'s left edge, through however many parents lie between. */
    private fun offsetInRow(view: View, row: View): Int {
        var offset = 0
        var current: View = view
        while (current !== row) {
            offset += current.left
            current = current.parent as? View ?: break
        }
        return offset
    }

    private fun updatePhoneOutputSlider() {
        if (outputVolumeBusy()) return
        val slider = outputPickerPhoneVolumeSlider ?: return
        val manager = audioManager ?: return
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        slider.valueTo = maximum.toFloat()
        animateSheetSliderTo(
            slider,
            manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                .toFloat().coerceIn(0F, slider.valueTo),
        )
    }

    private fun routeSubtitle(route: MediaRouter.RouteInfo): String {
        when (platformRouteType(route)) {
            MediaRoute2Info.TYPE_BLE_HEADSET ->
                return context.getString(R.string.output_route_le_audio)
            MediaRoute2Info.TYPE_USB_DEVICE,
            MediaRoute2Info.TYPE_USB_ACCESSORY,
            MediaRoute2Info.TYPE_USB_HEADSET ->
                return context.getString(R.string.output_route_usb)
            MediaRoute2Info.TYPE_WIRED_HEADSET,
            MediaRoute2Info.TYPE_WIRED_HEADPHONES ->
                return context.getString(R.string.output_route_wired)
            MediaRoute2Info.TYPE_HDMI,
            MediaRoute2Info.TYPE_HDMI_ARC,
            MediaRoute2Info.TYPE_HDMI_EARC ->
                return context.getString(R.string.output_route_display)
        }
        // A Cast route's description is the *running receiver application's* name for as long as a
        // session is open, so connecting to "Bedroom Group" relabelled it "Default Media Receiver"
        // - Google's receiver app, not the speaker in the room. The model belongs to the device
        // and says the same thing whether or not it is playing.
        runCatching { CastDevice.getFromBundle(route.extras) }.getOrNull()?.let { device ->
            device.modelName?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val description = route.description?.toString()?.takeIf {
            it.isNotBlank() && !it.equals(route.name.toString(), ignoreCase = true)
        }
        if (description != null) return description

        val name = route.name.toString().lowercase()
        return when {
            route.isBluetooth -> context.getString(R.string.output_route_bluetooth)
            "usb" in name || "dac" in name -> context.getString(R.string.output_route_usb)
            "wired" in name || "headphone" in name || "headset" in name ->
                context.getString(R.string.output_route_wired)
            "tv" in name || "display" in name || "hdmi" in name ->
                context.getString(R.string.output_route_display)
            else -> context.getString(R.string.output_route_system)
        }
    }

    /**
     * A glyph for the client being played to.
     *
     * Matched on the client name because Jellyfin does not serve an icon for one - it reports what
     * the app called itself and nothing else. Known names get something recognisable and the rest
     * fall back to the cast glyph, which is at least honest about being a device somewhere else.
     */
    @DrawableRes
    private fun clientIconFor(client: String): Int {
        val name = client.lowercase()
        return when {
            "web" in name || "desktop" in name || "media player" in name ->
                R.drawable.ic_output_desktop
            "android" in name || "findroid" in name || "accord" in name || "ios" in name ->
                R.drawable.ic_output_phone
            "kodi" in name || "tv" in name -> R.drawable.ic_output_desktop
            else -> R.drawable.ic_airplay_radio
        }
    }

    /** Starts, cancels, enables, or disables the reversible instrumental stream for this track. */
    private fun toggleKaraoke() {
        if (JellyfinRemoteTargets.active.value != null) {
            Toast.makeText(context, R.string.karaoke_local_only, Toast.LENGTH_SHORT).show()
            return
        }
        val player = instance ?: return
        val item = player.currentMediaItem ?: return
        if (KaraokeMediaItems.isActive(item)) {
            karaokeJob?.cancel()
            switchKaraokeItem(KaraokeMediaItems.deactivate(item), active = false)
            showKaraokeStatus(context.getString(R.string.karaoke_off), transient = true)
            return
        }
        if (karaokeJob?.isActive == true) {
            karaokeJob?.cancel()
            karaokeJob = null
            hideKaraokeStatus()
            Toast.makeText(context, R.string.karaoke_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        val mediaId = item.mediaId
        karaokeJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            showKaraokeStatus(context.getString(R.string.karaoke_checking))
            var state = JellyfinKaraoke.prepare(context, mediaId).getOrElse {
                showKaraokeFailure(it.message)
                return@launch
            }
            while (karaokeMediaId == mediaId &&
                state.status in setOf(JellyfinKaraoke.Status.QUEUED, JellyfinKaraoke.Status.PROCESSING)
            ) {
                renderKaraokeState(state)
                delay(KARAOKE_POLL_INTERVAL_MS)
                state = JellyfinKaraoke.status(context, mediaId).getOrElse {
                    showKaraokeFailure(it.message)
                    return@launch
                }
            }
            if (karaokeMediaId != mediaId) return@launch
            when (state.status) {
                JellyfinKaraoke.Status.READY -> {
                    val stream = state.streamUrl
                    val current = instance?.currentMediaItem
                    if (stream == null || current?.mediaId != mediaId) return@launch
                    switchKaraokeItem(KaraokeMediaItems.activate(current, stream), active = true)
                    showKaraokeStatus(context.getString(R.string.karaoke_ready), transient = true)
                }
                JellyfinKaraoke.Status.FAILED -> showKaraokeFailure(state.message)
                else -> hideKaraokeStatus()
            }
        }
    }

    private fun switchKaraokeItem(item: MediaItem, active: Boolean) {
        val player = instance ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        karaokeSwitchMediaId = item.mediaId
        setBottomButtonSelected(karaokeOverlayButton, active)
        player.replaceMediaItem(index, item)
        player.seekTo(index, position)
        player.prepare()
        player.playWhenReady = playWhenReady
        postDelayed({
            if (karaokeSwitchMediaId == item.mediaId) karaokeSwitchMediaId = null
        }, KARAOKE_SWITCH_GUARD_MS)
    }

    /** Checks availability without preparing anything, so unsupported/local tracks stay uncluttered. */
    private fun refreshKaraoke(mediaItem: MediaItem?) {
        karaokeJob?.cancel()
        karaokeJob = null
        karaokeMediaId = mediaItem?.mediaId
        val active = KaraokeMediaItems.isActive(mediaItem)
        setBottomButtonSelected(karaokeOverlayButton, active)
        karaokeAvailable = active
        karaokeOverlayButton.visibility = if (active && !lyricsControlsHidden) VISIBLE else GONE
        karaokeStatus.removeCallbacks(hideKaraokeStatusRunnable)
        karaokeStatus.visibility = GONE
        val mediaId = mediaItem?.mediaId?.takeIf(String::isNotBlank) ?: return
        karaokeJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
            val state = JellyfinKaraoke.status(context, mediaId).getOrNull() ?: return@launch
            if (karaokeMediaId != mediaId) return@launch
            karaokeAvailable = true
            if (!lyricsControlsHidden) karaokeOverlayButton.visibility = VISIBLE
        }
    }

    private fun renderKaraokeState(state: JellyfinKaraoke.State) {
        val text = when (state.status) {
            JellyfinKaraoke.Status.QUEUED -> context.getString(R.string.karaoke_queued)
            JellyfinKaraoke.Status.PROCESSING -> when (state.phase) {
                "encoding" -> context.getString(R.string.karaoke_encoding)
                else -> context.getString(R.string.karaoke_separating)
            }
            else -> return
        }
        showKaraokeStatus(text)
    }

    private fun showKaraokeFailure(message: String?) {
        hideKaraokeStatus()
        Toast.makeText(
            context,
            context.getString(R.string.karaoke_failed, message ?: "Unknown error"),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showKaraokeStatus(text: String, transient: Boolean = false) {
        karaokeStatus.removeCallbacks(hideKaraokeStatusRunnable)
        karaokeStatus.text = text
        karaokeStatus.visibility = VISIBLE
        qualityBadge.visibility = GONE
        qualityAvailableHint.visibility = GONE
        if (transient) karaokeStatus.postDelayed(hideKaraokeStatusRunnable, KARAOKE_STATUS_HOLD_MS)
    }

    private fun hideKaraokeStatus() {
        karaokeStatus.removeCallbacks(hideKaraokeStatusRunnable)
        karaokeStatus.visibility = GONE
        syncQualityBadgeVisibility()
    }

    private val hideKaraokeStatusRunnable = Runnable { hideKaraokeStatus() }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int
    ) {
        Log.d(TAG, "COVERDBG transition track=${mediaItem?.mediaMetadata?.title} reason=$reason " +
            "count=${instance?.mediaItemCount} karaokeSwitch=${karaokeSwitchMediaId != null}") // TEMP-COVERDBG
        if (karaokeSwitchMediaId == mediaItem?.mediaId) {
            karaokeSwitchMediaId = null
            lastTransitionMediaId = mediaItem?.mediaId
            fullPlayerToolbar.onMediaItemTransition(mediaItem, reason)
            qualityBadge.visibility = GONE
            showCachedLyricsOrEmpty(mediaItem)
            refreshLyrics()
            refreshKaraoke(mediaItem)
            updateProgressDisplay()
            return
        }
        coverSwipeHaptics.reset()
        fullPlayerToolbar.onMediaItemTransition(mediaItem, reason)
        // The headings are relative to what is playing, and advancing a track moves that line
        // without changing the timeline - so they have to be recomputed here as well, or the track
        // now playing keeps the "Playing Next" that was true a moment ago.
        instance?.currentTimeline?.let { timeline ->
            (queueRecyclerView.adapter as? QueuePreviewAdapter)
                ?.updateItems(buildQueueItems(timeline))
        }
        // Hide until the new item's tracks arrive, so the previous track's badge does not linger
        // over a different song.
        qualityBadge.visibility = GONE
        refreshKaraoke(mediaItem)
        showCachedLyricsOrEmpty(mediaItem)
        refreshLyrics()
        if (instance?.mediaItemCount != 0) {
            cancelCoverLoad()
            // A track that ended on its own is still going forwards, so it gets the same movement
            // as pressing next; only the very first item appears without travelling. A playlist
            // replacement that keeps the same track (handing playback to Cast or Finnect, or
            // coming back from either) does not move at all: the artwork is unchanged, so a slide
            // would be a needless round trip.
            //
            // Note this *disarms* rather than merely declining to arm. Not arming was not enough:
            // seekToNext/seekToPrevious set the direction before the seek, and a skip that turns
            // out not to move - the last track with repeat off, a queue emptied under the press -
            // leaves it set with nothing to consume it. The next transition to arrive was then a
            // hand-off for the very same song, which slid the cover off as if it had been skipped
            // and left it on the placeholder, because an unchanged track never reloads its art.
            val sameTrack = mediaItem?.mediaId == lastTransitionMediaId
            if (!sameTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                offerOutputChoice()
            }
            if (sameTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                Log.d(TAG, "COVERDBG disarm sameTrack inFlight=$coverSlideInFlight " +
                    "pending=$pendingCoverSlide offset=$coverSlideOffsetX outDone=$coverSlideOutDone") // TEMP-COVERDBG
                pendingCoverSlide = SLIDE_NONE
                pendingCoverReleaseVelocity = 0F
                removeCallbacks(coverSlideBackstop)
            } else if (pendingCoverSlide == SLIDE_NONE && !firstTime && !sameTrack) {
                pendingCoverSlide = SLIDE_NEXT
            }
            lastTransitionMediaId = mediaItem?.mediaId
            startCoverSlideOut()
            loadCoverForImageView()
            // Armed after every transition and cancelled by the next, so a burst of swipes runs it
            // once, when the queue finally settles on something.
            removeCallbacks(coverIntegrityCheck)
            postDelayed(coverIntegrityCheck, COVER_INTEGRITY_DELAY_MS)

            titleTextView.setTextAnimation(
                mediaItem?.mediaMetadata?.title ?: "",
                skipAnimation = firstTime
            )
            subtitleTextView.setTextAnimation(
                mediaItem?.mediaMetadata?.artist ?: context.getString(R.string.default_artist),
                skipAnimation = firstTime
            )
            updateProgressDisplay()
            syncFavoriteButtonsForCurrentItem()
            topUpQueueIfNeeded()
        } else {
            Log.d(TAG, "COVERDBG transition EMPTY-TIMELINE branch: cancelling cover load") // TEMP-COVERDBG
            cancelCoverLoad()
            lastTransitionMediaId = mediaItem?.mediaId
            updateProgressDisplay()
            updateFavoriteButtons(false)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val current = instance?.currentMediaItem ?: return
        if (!KaraokeMediaItems.isActive(current)) return
        Log.w(TAG, "Instrumental stream failed; restoring original track", error)
        Toast.makeText(context, R.string.karaoke_stream_failed, Toast.LENGTH_LONG).show()
        switchKaraokeItem(KaraokeMediaItems.deactivate(current), active = false)
    }

    /**
     * Carries the outgoing artwork off the side it is leaving by.
     *
     * Deliberately not tied to the artwork finishing loading: the cover has to start moving the
     * instant the track changes, or a slow network makes the player look stuck.
     */
    private fun startCoverSlideOut() {
        if (pendingCoverSlide == SLIDE_NONE) {
            Log.d(TAG, "COVERDBG slideOut skipped (disarmed) " +
                "inFlight=$coverSlideInFlight offset=$coverSlideOffsetX") // TEMP-COVERDBG
            return
        }
        Log.d(TAG, "COVERDBG slideOut dir=$pendingCoverSlide wasInFlight=$coverSlideInFlight " +
            "offset=$coverSlideOffsetX") // TEMP-COVERDBG
        coverSlideInFlight = true
        coverSlideOutDone = false
        coverSlideArtReady = false
        coverSlideInStarted = false
        // Whatever is parked belongs to the track now leaving, not the one arriving. It is only
        // ever set for the item that was current when its load landed, so carrying it across a
        // transition made the incoming track slide in wearing the previous song's sleeve - the
        // cover said one album while the title said another.
        pendingCoverDrawable = null

        val target = -pendingCoverSlide * coverSlideDistance()
        // A swipe has already carried the cover part of the way, so the rest of the journey is
        // shorter and has to take proportionally less time. At a fixed duration the cover
        // visibly changed speed the instant the finger left it.
        val remaining = abs(target - coverSlideOffsetX)
        val velocityDuration = abs(pendingCoverReleaseVelocity)
            .takeIf {
                it >= ViewConfiguration.get(context).scaledMinimumFlingVelocity *
                    COVER_FLING_VELOCITY_MULTIPLIER
            }
            ?.let { ((remaining / it) * 1000F).toLong() }
        val duration = (velocityDuration ?: (COVER_SLIDE_OUT_MS *
            (remaining / coverSlideDistance())).toLong())
            .coerceIn(COVER_SLIDE_MIN_MS, COVER_SLIDE_OUT_MS)
        pendingCoverReleaseVelocity = 0F

        // Linear, not accelerating: a swipe hands over at speed, and easing in from a moving
        // finger reads as the cover briefly slowing down before it leaves.
        animateCoverOffset(target, duration, LinearInterpolator()) {
            coverSlideOutDone = true
            slideCoverInIfReady()
        }
        // The artwork is not going to be waited for indefinitely. On a shuffled library the
        // next cover is never cached, so waiting meant the screen sat empty for a network
        // round trip every time - which is the pause people describe as the app hanging.
        removeCallbacks(coverSlideInDeadline)
        postDelayed(coverSlideInDeadline, COVER_ART_WAIT_MS)
    }

    /**
     * Puts the new artwork everywhere it belongs.
     *
     * Everything except the large cover takes it at once - the blended background and the collapsed
     * bar should follow the track immediately. The large cover waits until it has finished leaving.
     */
    private fun applyCover(
        drawable: android.graphics.drawable.Drawable?,
        bitmap: android.graphics.Bitmap?
    ) {
        Log.d(TAG, "COVERDBG applyCover uri=$currentArtworkUri drawable=${
            drawable?.let { Integer.toHexString(System.identityHashCode(it)) }
        } track=${instance?.currentMediaItem?.mediaMetadata?.title}") // TEMP-COVERDBG
        appliedCoverBitmap = bitmap
        applyBackdrop(bitmap, currentArtworkUri)
        // Driven from here rather than the transition, so the sheet re-blurs against the colours
        // that are actually on screen behind it instead of the ones on their way out.
        outputPickerRefresh?.invoke()
        fullPlayerToolbar.setImageViewCover(drawable)
        floatingPanelLayout.transitionImageView?.setImageDrawable(drawable)
        floatingPanelLayout.setPreviewCover(drawable)
        if (coverSlideInFlight && !coverSlideInStarted) {
            // Still on its way out, or waiting to come back - hold it until it is out of sight.
            Log.d(TAG, "COVERDBG park drawable=${drawable != null}") // TEMP-COVERDBG
            pendingCoverDrawable = drawable
            onCoverArtReady()
        } else {
            Log.d(TAG, "COVERDBG direct drawable=${drawable != null} " +
                "inFlight=$coverSlideInFlight started=$coverSlideInStarted offset=$coverSlideOffsetX") // TEMP-COVERDBG
            // Either nothing is moving, or the cover came back before the artwork did and is
            // showing the placeholder; either way it belongs on screen now.
            coverSimpleImageView.setImageDrawable(drawable)
        }
    }

    private fun isAutoplayEnabled() =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_AUTOPLAY, false)

    /** Whether the user has asked for mixed transitions. */
    private fun isAutomixEnabled() = Automix.isEnabled(context)

    /**
     * Resequences what is left of the queue so that more of its transitions can be mixed.
     *
     * The counterpart to a station that arrives already sequenced: a playlist somebody made by
     * hand is a fixed set of tracks, and the order they are heard in is the only freedom left.
     * [AutomixQueueOrdering] owns the rules - nothing already heard moves, nothing is added or
     * dropped - and this owns telling the listener what happened, which matters more than usual
     * here because the honest answer is often "nothing".
     *
     * Off the main thread to read the analyses, back on it to move anything: the controller allows
     * nothing else. The queue is re-read after the wait rather than trusted from before it, because
     * a track can end while the database is being read and the plan would then be one place out.
     */
    private fun reorderQueueForAutomix() {
        val player = instance ?: return
        val currentIndex = player.currentMediaItemIndex
        val queue = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val appContext = context.applicationContext

        Toast.makeText(context, R.string.automix_reordering, Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.Main).launch {
            val order = withContext(Dispatchers.IO) {
                AutomixQueueOrdering.reorder(appContext, queue, currentIndex)
            }
            val live = instance ?: return@launch
            // Only apply to the queue the plan was made against. Anything else - a track ended, the
            // listener skipped, something was added - and the indices mean different items now.
            if (live.mediaItemCount != queue.size || live.currentMediaItemIndex != currentIndex) {
                return@launch
            }
            if (order == null) {
                val analysed = withContext(Dispatchers.IO) {
                    AutomixQueueOrdering.mixableAdjacencies(appContext, queue)
                }
                Toast.makeText(
                    context,
                    // Two different nothings, and saying which one is the whole value of the
                    // message: a queue that already mixes as well as it can is a success, and a
                    // queue nobody has analysed is a "come back once it has played a while".
                    if (analysed > 0) R.string.automix_reorder_none
                    else R.string.automix_reorder_unanalysed,
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            AutomixQueueOrdering.applyOrder(queue.size, currentIndex, order) { from, to ->
                live.moveMediaItem(from, to)
            }
            val reordered = List(live.mediaItemCount) { live.getMediaItemAt(it) }
            val mixable = withContext(Dispatchers.IO) {
                AutomixQueueOrdering.mixableAdjacencies(appContext, reordered)
            }
            Toast.makeText(
                context,
                resources.getQuantityString(R.plurals.automix_reordered, mixable, mixable),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Adds more music when the queue is running out and infinity play is on.
     *
     * Runs on every track change rather than only at the very end, so the queue is topped up
     * before the gap is audible. Choosing the tracks touches the network, so it happens off the
     * main thread; adding them has to be back on it, because the controller allows nothing else.
     */
    private fun topUpQueueIfNeeded() {
        if (!isAutoplayEnabled()) return
        val player = instance ?: return
        val remaining = player.mediaItemCount - 1 - player.currentMediaItemIndex
        if (remaining > AutoplayQueue.TOP_UP_THRESHOLD) return

        val seed = player.currentMediaItem
        val queued = buildSet {
            for (index in 0 until player.mediaItemCount) {
                add(player.getMediaItemAt(index).mediaId)
            }
        }
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Main).launch {
            val library = activity.reader.songListSnapshot()
            val batch = withContext(Dispatchers.IO) {
                AutoplayQueue.nextBatch(appContext, seed, library, queued)
            }
            if (batch.isNotEmpty()) instance?.addMediaItems(batch)
        }
    }

    /** Called when the new artwork is available. */
    private fun onCoverArtReady() {
        if (!coverSlideInFlight) return
        coverSlideArtReady = true
        slideCoverInIfReady()
    }

    /**
     * Brings the incoming artwork in from the opposite side.
     *
     * Waits for both the outgoing movement to finish and the new artwork to exist. Cached artwork
     * arrives in a few milliseconds, and starting the return before the cover had left teleported it
     * across the screen mid-flight.
     */
    private fun slideCoverInIfReady() {
        if (!coverSlideOutDone || !coverSlideArtReady) return
        val direction = pendingCoverSlide
        pendingCoverSlide = SLIDE_NONE
        if (direction == SLIDE_NONE) {
            Log.d(TAG, "COVERDBG STRANDED offset=$coverSlideOffsetX " +
                "pendingDrawable=${pendingCoverDrawable != null} started=$coverSlideInStarted") // TEMP-COVERDBG
            coverSlideInFlight = false
            return
        }
        removeCallbacks(coverSlideInDeadline)
        coverSlideInStarted = true
        // Swapped now, out of sight, so the cover that comes back is the new track's. When it
        // has not loaded yet the placeholder comes back instead and the real artwork appears
        // in place a moment later - far better than an empty screen while the network answers.
        val incoming = pendingCoverDrawable
        if (incoming != null) {
            coverSimpleImageView.setImageDrawable(incoming)
        } else {
            coverSimpleImageView.setImageDrawable(
                AppCompatResources.getDrawable(context, R.drawable.default_cover)
            )
        }
        pendingCoverDrawable = null
        coverSlideOffsetX = direction * coverSlideDistance()
        applyCoverTranslation()
        // Ends at exactly zero displacement, so nothing is left to correct and there is no snap.
        animateCoverOffset(0F, COVER_SLIDE_IN_MS, settleInterpolator) {
            coverSlideInFlight = false
        }
    }

    /**
     * Hands the backdrop its new artwork once the cover has finished arriving.
     *
     * The backdrop swap is the most expensive thing the player does: three ImageSwitchers each
     * crossfade for 400ms, so six images are drawn at once inside a blurred, upscaled RenderNode.
     * Firing that from [applyCover] put it directly on top of the cover slide, and the slide -
     * which is only animating `translationX` - stalled behind it on the RenderThread. Deferring
     * costs nothing visually, because the cover is what the eye is following.
     */
    /**
     * The one place the backdrop's artwork is written.
     *
     * Keyed on the artwork itself rather than the track: consecutive songs from one album resolve
     * to the same cover, and rebuilding the field for them restarted an identical picture and cut
     * its animation. An unchanged cover now leaves the backdrop - and its loop - completely alone.
     *
     * Whichever renderer is active takes it: [blendView] when the backdrop is the blurred cover,
     * [meshGradientView] when the animated gradient is switched on in Appearance. Both are fed,
     * because the preference can change while the player is open and the hidden one must already
     * be holding the right picture when it is shown.
     */
    private fun applyBackdrop(bitmap: android.graphics.Bitmap?, artwork: Uri?) {
        Log.d(TAG, "COVERDBG backdrop in bitmap=${bitmap != null} artwork=$artwork " +
            "last=$lastBackdropArtwork blendVisible=${blendView.visibility == VISIBLE}") // TEMP-COVERDBG
        if (bitmap != null && artwork != null && artwork == lastBackdropArtwork) {
            Log.d(TAG, "COVERDBG backdrop SKIPPED (unchanged artwork)") // TEMP-COVERDBG
            return
        }
        // No artwork is not the same as "erase the backdrop". A cold start with nothing restored
        // yet calls this twice with a null cover before the controller has a queue, and passing
        // that through cleared all three of BlendView's image views - which is a flat black field,
        // held until the first real artwork happens to arrive. The placeholder belongs on the
        // cover; behind it, the last good backdrop (or the renderer's own fallback) looks far more
        // deliberate than black, and it also keeps [cachedBackdrop] intact for the next attach.
        if (bitmap == null) {
            Log.d(TAG, "COVERDBG backdrop IGNORED (null bitmap, keeping current)") // TEMP-COVERDBG
            return
        }
        lastBackdropArtwork = artwork
        // Every backdrop renderer reads the artwork's pixels - kawarp's Kawase pass through
        // getPixels, the older field through getPixel and createScaledBitmap - and none of that
        // works on the Config#HARDWARE bitmap Coil returns for a hardware-accelerated target: it
        // throws, and kawarp's runs on its own thread where it took the process down. Converting
        // once here also replaces the copy BlendView and FlowingGradientView each made
        // separately, so a track change now costs one conversion instead of three.
        // Every renderer shrinks whatever it is handed - BlendView to 60px, kawarp and the
        // gradient to 128 - so passing the full 932px cover meant three independent downscales
        // from a multi-megabyte source on the main thread per track change. Scaling once here, to
        // comfortably more than any of them consume, makes each of those cheap. Nothing is lost:
        // the result is blurred past recognition in every renderer.
        val readable = bitmap.readablePixels()?.scaledForBackdrop()
        // Kept past this view's lifetime so a rotation can put it straight back; see
        // [restoreCachedBackdrop].
        cachedBackdrop = readable
        cachedBackdropArtwork = lastBackdropArtwork
        // Feed only the renderer that can actually draw. BlendView makes its own treated copies;
        // doing that for every rapid swipe while it was hidden multiplied bitmap pressure for no
        // visible result. The preference listener seeds a renderer when it becomes active.
        if (blendView.visibility == VISIBLE) {
            blendView.setImageBitmap(readable)
            blendView.startRotationAnimation()
        } else {
            // kawarp holds the outgoing cover itself and dissolves to this one over
            // BACKDROP_CROSSFADE_MS; FlowingGradientView is the API 31-32 fallback.
            liquidGradientView.setCover(readable)
            meshGradientView.setArtwork(readable)
        }
        // Restart whichever renderer just received this, because being handed content is the only
        // moment it can be sure it has something to draw.
        //
        // setPlaying() is otherwise reached only from onPlaybackStateChanged, and on a cold start
        // that fires *before* the artwork arrives: the renderer is told to run while it is still
        // empty, gives up, and nothing wakes it when the cover finally lands. The backdrop then
        // stayed blank until the next playback-state change - which is why pressing play appeared
        // to fix it, and why pausing again did not undo the fix.
        meshGradientView.setPlaying(true)
        liquidGradientView.setPlaying(true)
    }

    /**
     * Puts the backdrop back after the view has been rebuilt.
     *
     * A rotation recreates this view, which resets [appliedCoverBitmap] and [lastBackdropArtwork]
     * to null - and no media-item transition follows a rotation, so nothing ever handed the
     * backdrop a picture again and it fell back to a flat palette until the next track change.
     * Re-feeding the cached bitmap needs no image request, so unlike re-running the cover load it
     * cannot race the transition's own.
     */
    private fun restoreCachedBackdrop() {
        if (lastBackdropArtwork != null) return
        val cached = cachedBackdrop?.takeUnless(android.graphics.Bitmap::isRecycled) ?: return
        lastBackdropArtwork = cachedBackdropArtwork
        if (blendView.visibility == VISIBLE) {
            blendView.setImageBitmap(cached)
        } else {
            liquidGradientView.setCover(cached)
            meshGradientView.setArtwork(cached)
        }
    }

    /** See [applyBackdrop]: a bitmap whose pixels a backdrop renderer is allowed to read. */
    private fun android.graphics.Bitmap?.readablePixels(): android.graphics.Bitmap? = when {
        this == null -> null
        config == android.graphics.Bitmap.Config.HARDWARE ->
            copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        else -> this
    }

    /** Downsamples to the largest size any backdrop renderer actually consumes. */
    private fun android.graphics.Bitmap.scaledForBackdrop(): android.graphics.Bitmap =
        if (width <= BACKDROP_SOURCE_PX && height <= BACKDROP_SOURCE_PX) this
        else android.graphics.Bitmap.createScaledBitmap(
            this, BACKDROP_SOURCE_PX, BACKDROP_SOURCE_PX, true,
        )

    /**
     * Leaves quickly and settles softly, so the arrival reads as a landing rather than a stop.
     */
    private val settleInterpolator = PathInterpolator(0.17F, 0.89F, 0.32F, 1F)

    /** Far enough that the cover is clear of the screen rather than parked at its own edge. */
    private fun coverSlideDistance(): Float =
        (coverSimpleImageView.width + 48.dp.px).coerceAtLeast(1F)

    /**
     * Drops any artwork request still in flight.
     *
     * [loadedArtworkIdentity] goes with it. It records the identity a load was *started* for, and
     * a disposed request never delivers - so leaving it set made [enqueueCover] treat the artwork
     * as already on its way and skip re-requesting it. Two quick skips dispose the second track's
     * request on the way to the third, and the third then sat on the placeholder for good.
     */
    /**
     * Asks, once per connected target, whether a song should go to it or stay on the phone.
     *
     * Raised from a genuine start of playback - a new queue with a different track - and not from
     * the hand-off itself, which replaces the queue with the *same* track and is the moment the
     * user just chose the speaker on purpose. Asking there would be asking them to confirm what
     * they had done a second earlier.
     *
     * Once per target, not once per song: the answer is remembered in [OutputRouting] until the
     * target goes away, and either answer can be changed at any time from the output picker.
     */
    private fun offerOutputChoice() {
        if (OutputRouting.playLocally.value) return
        val targetId = FincordCast.session.value?.castDevice?.deviceId
            ?: JellyfinRemoteTargets.active.value?.sessionId
            ?: return
        if (!OutputRouting.markPrompted(targetId)) return
        val name = currentRemoteTargetName() ?: context.getString(R.string.output_device)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.output_choice_title)
            .setMessage(context.getString(R.string.output_choice_message, name))
            .setPositiveButton(context.getString(R.string.output_choice_remote, name)) { _, _ ->
                OutputRouting.setPlayLocally(false)
            }
            .setNegativeButton(R.string.output_choice_local) { _, _ ->
                OutputRouting.setPlayLocally(true)
            }
            .show()
    }

    /** What the connected remote target calls itself, for the picker's own labels. */
    private fun currentRemoteTargetName(): String? =
        FincordCast.session.value?.castDevice?.friendlyName?.takeIf(String::isNotBlank)
            ?: JellyfinRemoteTargets.active.value?.deviceName?.takeIf(String::isNotBlank)

    private fun cancelCoverLoad() {
        lastDisposable?.dispose()
        lastDisposable = null
        loadedArtworkIdentity = null
    }

    private fun loadCoverForImageView() {
        if (lastDisposable != null) {
            cancelCoverLoad()
            Log.e(TAG, "raced while loading cover in onMediaItemTransition?")
        }
        val mediaItem = instance?.currentMediaItem
        Log.d(TAG, "load cover for ${mediaItem?.mediaMetadata?.title} considered")
        if (coverSimpleImageView.width != 0 && coverSimpleImageView.height != 0) {
            Log.d(
                TAG,
                "load cover for ${mediaItem?.mediaMetadata?.title} at ${coverSimpleImageView.width} ${coverSimpleImageView.height}"
            )
            // The track's own embedded picture comes first, because it is already on this device
            // and needs no server at all: it decodes straight off local storage, which is what
            // stops playback beginning before the cover has caught up. Jellyfin's copy is the same
            // image served over HTTP, so it is the fallback rather than the source - used the first
            // time a track is played, and whenever the file carried no picture of its own.
            mediaItem?.mediaId?.let { id ->
                embeddedArtworkFile(id).takeIf(File::isFile)?.let {
                    enqueueCover(id, Uri.fromFile(it))
                    return
                }
            }

            val artwork = mediaItem?.mediaMetadata?.artworkUri
            if (artwork != null) {
                enqueueCover(mediaItem.mediaId, artwork)
                return
            }

            // A MediaSession queue restored after process death can contain an incomplete copy of
            // metadata even though the freshly cached library has the complete item. Do not pass
            // null to Coil (which clears a perfectly useful cached drawable); recover the URI by
            // stable media id, then by stable album id for older saved queues.
            val mediaId = mediaItem?.mediaId ?: return
            artworkLookupJob?.cancel()
            artworkLookupJob = (findViewTreeLifecycleOwner()?.lifecycleScope
                ?: CoroutineScope(Dispatchers.Main)).launch {
                val resolved = resolveCachedArtwork(mediaItem)
                if (resolved != null && instance?.currentMediaItem?.mediaId == mediaId) {
                    enqueueCover(mediaId, resolved)
                } else if (appliedCoverBitmap == null) {
                    applyCover(AppCompatResources.getDrawable(context, R.drawable.default_cover), null)
                }
            }
        }
    }

    fun showContentFromPreview(value: ContentType) {
        if (contentType != value) contentType = value
    }

    fun showPopupFromPreview(anchor: View) {
        callUpPlayerPopupMenu(anchor)
    }

    /**
     * The portrait constraint chain is intentionally album-art-first. On a short landscape phone
     * that same vertical chain leaves almost no room for controls, while on a tablet it inflates
     * the cover to poster size. Split a genuinely wide canvas at a bounded 46% instead: artwork
     * on the left, metadata and controls on the right. A portrait tablet is still a portrait
     * player; its artwork is capped by the layout resource rather than being pushed into a
     * landscape arrangement merely because its smallest width crosses Android's tablet boundary.
     */
    private fun configureWideLayout() {
        val configuration = resources.configuration
        val widthDp = configuration.screenWidthDp
        val heightDp = configuration.screenHeightDp

        // The *window's* smallest side, not the device's. `smallestScreenWidthDp` still reports
        // 600+ for a phone-sized split-screen or freeform window on a tablet, which would take a
        // layout whose column and full control stack need far more room than the window has.
        isTabletLayout = min(widthDp, heightDp) >= TABLET_WINDOW_DP
        if (isTabletLayout) configureTabletActionRow()

        if (widthDp <= heightDp) return
        isWideLayout = true

        // The rows are fractions of the canvas, but what stands on them is fixed dp: two lines of
        // 21sp between the title and the scrubber, a 72dp transport button, a 48dp action row.
        // Below these heights a fraction hands a row less room than its contents need and they
        // collide, so the older margin-driven chain - which can crowd but cannot overlap - keeps
        // the short windows.
        if (isTabletLayout && heightDp >= TABLET_RHYTHM_MIN_HEIGHT_DP) {
            configureTabletLandscape()
            return
        }
        // A tablet-sized window too short for that column is a landscape phone in every way that
        // matters here, including the controls stepping aside for the pane.
        isTabletLayout = false
        val proportional = heightDp >= PHONE_RHYTHM_MIN_HEIGHT_DP

        val split = Guideline(context).apply { id = View.generateViewId() }
        addView(split, LayoutParams(0, 0).apply {
            orientation = LayoutParams.VERTICAL
            guidePercent = 0.46F
        })

        // Rows measured off `11-compact-landscape-player.jpg`. The stack used to hang off fixed
        // margins and drifted low as the pane grew: the scrubber sat at 45% where the reference
        // has it at 36%, and the volume row at 78% against 66%.
        val titleRow = if (proportional) horizontalGuide(0.200F) else null
        val progressRow = if (proportional) horizontalGuide(0.335F) else null
        val volumeRow = if (proportional) horizontalGuide(0.635F) else null
        val actionRow = if (proportional) horizontalGuide(0.805F) else null

        ConstraintSet().apply {
            clone(this@FullPlayer)

            // The control column is the right-hand pane here, uncapped: the artwork it is normally
            // sized against has moved out of it. Everything anchored to the column follows, so the
            // re-anchors below only have to cover what the column does not already carry.
            clear(R.id.content_column, ConstraintSet.START)
            clear(R.id.content_column, ConstraintSet.END)
            connect(R.id.content_column, ConstraintSet.START, split.id, ConstraintSet.END)
            connect(R.id.content_column, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainMaxWidth(R.id.content_column, 0)

            // Both skip buttons bias inside the pane, so the two gaps either side of the play
            // button are equal. Biasing the back one across the whole width - which is what the
            // parent anchor did - made it drift left as the pane grew, and the trio came out
            // lopsided against every reference.
            clear(R.id.backward_btn, ConstraintSet.START)
            connect(R.id.backward_btn, ConstraintSet.START, split.id, ConstraintSet.END)
            clear(R.id.forward_btn, ConstraintSet.END)
            connect(R.id.forward_btn, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            // Measured from the canvas, not from the divider: the divider's own height pushed the
            // artwork down until it ran past the action row instead of centring in the half.
            clear(R.id.cover)
            connect(R.id.cover, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 18.dp.px.toInt())
            connect(R.id.cover, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 18.dp.px.toInt())
            connect(R.id.cover, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 28.dp.px.toInt())
            connect(R.id.cover, ConstraintSet.END, split.id, ConstraintSet.END, 28.dp.px.toInt())
            sizeCoverSquare(
                min(0.46F * configuration.screenWidthDp - 56F, configuration.screenHeightDp - 36F)
            )

            clear(R.id.title, ConstraintSet.TOP)
            clear(R.id.title, ConstraintSet.START)
            connect(R.id.title, ConstraintSet.START, split.id, ConstraintSet.END, 34.dp.px.toInt())

            clear(R.id.progressBar, ConstraintSet.START)
            connect(R.id.progressBar, ConstraintSet.START, split.id, ConstraintSet.END, 28.dp.px.toInt())
            connect(R.id.progressBar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 28.dp.px.toInt())

            if (titleRow != null && progressRow != null && volumeRow != null && actionRow != null) {
                connect(R.id.title, ConstraintSet.TOP, titleRow.id, ConstraintSet.TOP)

                clear(R.id.progressBar, ConstraintSet.TOP)
                connect(R.id.progressBar, ConstraintSet.TOP, progressRow.id, ConstraintSet.TOP)

                clear(R.id.speaker_hint, ConstraintSet.BOTTOM)
                connect(R.id.speaker_hint, ConstraintSet.TOP, volumeRow.id, ConstraintSet.TOP)

                clear(R.id.list, ConstraintSet.BOTTOM)
                connect(R.id.list, ConstraintSet.TOP, actionRow.id, ConstraintSet.TOP)
            } else {
                connect(R.id.title, ConstraintSet.TOP, R.id.divider, ConstraintSet.BOTTOM, 34.dp.px.toInt())
                setMargin(R.id.progressBar, ConstraintSet.TOP, 18.dp.px.toInt())
            }

            clear(R.id.main_control_btn, ConstraintSet.START)
            clear(R.id.main_control_btn, ConstraintSet.END)
            connect(R.id.main_control_btn, ConstraintSet.START, split.id, ConstraintSet.END)
            connect(R.id.main_control_btn, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            clear(R.id.caption, ConstraintSet.START)
            connect(R.id.caption, ConstraintSet.START, split.id, ConstraintSet.END)
            clear(R.id.speaker_hint, ConstraintSet.START)
            connect(R.id.speaker_hint, ConstraintSet.START, split.id, ConstraintSet.END, 34.dp.px.toInt())

            clear(R.id.quality_badge, ConstraintSet.START)
            connect(R.id.quality_badge, ConstraintSet.START, split.id, ConstraintSet.END)
            clear(R.id.quality_available_hint, ConstraintSet.START)
            connect(R.id.quality_available_hint, ConstraintSet.START, split.id, ConstraintSet.END)
            clear(R.id.karaoke_status, ConstraintSet.START)
            connect(R.id.karaoke_status, ConstraintSet.START, split.id, ConstraintSet.END)

            // Lyrics and the queue are the right half, beside artwork that stays at full size.
            for (pane in intArrayOf(R.id.fading, R.id.queue_container)) {
                clear(pane, ConstraintSet.START)
                clear(pane, ConstraintSet.TOP)
                clear(pane, ConstraintSet.BOTTOM)
                connect(pane, ConstraintSet.START, split.id, ConstraintSet.END, 28.dp.px.toInt())
                connect(pane, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 28.dp.px.toInt())
                if (pane == R.id.fading) {
                    // Use the artwork itself as the ruler. It is a fixed square centred between
                    // the screen-edge constraints, so repeating those constraints on the lyrics
                    // produces a taller box. Direct anchors make both blurred edges line up while
                    // leaving FadingVerticalEdgeLayout's top and bottom fades intact.
                    connect(pane, ConstraintSet.TOP, R.id.cover, ConstraintSet.TOP)
                    connect(pane, ConstraintSet.BOTTOM, R.id.cover, ConstraintSet.BOTTOM)
                } else {
                    // The queue has no clip of its own, so it keeps clear of the row by hand.
                    connect(pane, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 18.dp.px.toInt())
                    connect(pane, ConstraintSet.BOTTOM, R.id.list, ConstraintSet.TOP, 8.dp.px.toInt())
                }
            }
            applyTo(this@FullPlayer)
        }
    }

    private fun horizontalGuide(percent: Float) =
        Guideline(context).apply { id = View.generateViewId() }.also {
            addView(it, LayoutParams(0, 0).apply {
                orientation = LayoutParams.HORIZONTAL
                guidePercent = percent
            })
        }

    /**
     * Sizes the artwork to an exact square of [sideDp], rather than asking for one.
     *
     * A square *asked* for - both dimensions free, a `1:1` ratio and a cap - lands well under its
     * cap, because the artwork sits in a vertical chain with the title and a free dimension in a
     * chain takes a share of the leftover space before the ratio is applied. It came out at 467dp
     * inside a 560dp cap in portrait and 247dp inside 327dp on a landscape phone, and prefixing
     * the ratio did not help. The caller has already reduced the two directions to one number, so
     * the size is simply stated. It can never overflow: [sideDp] is the smaller direction.
     */
    private fun ConstraintSet.sizeCoverSquare(sideDp: Float) {
        val side = sideDp.coerceAtLeast(0F).dp.px.toInt()
        setDimensionRatio(R.id.cover, "")
        constrainWidth(R.id.cover, side)
        constrainHeight(R.id.cover, side)
        constrainMaxWidth(R.id.cover, side)
        constrainMaxHeight(R.id.cover, side)
    }

    /**
     * The output button goes to one end of the row and the two destinations to the other.
     *
     * A phone spreads lyrics, output and queue evenly because the row is the width of a phone. On
     * a tablet the references press the two destinations together at the far end with the output
     * alone at the near one - `01`, `09` and `10` all show it - so the row does not read as three
     * unrelated taps strung across half a metre of glass.
     */
    private fun configureTabletActionRow() {
        ConstraintSet().apply {
            clone(this@FullPlayer)

            clear(R.id.airplay, ConstraintSet.START)
            clear(R.id.airplay, ConstraintSet.END)
            connect(R.id.airplay, ConstraintSet.START, R.id.content_column, ConstraintSet.START)

            clear(R.id.karaoke, ConstraintSet.START)
            clear(R.id.karaoke, ConstraintSet.END)
            connect(R.id.karaoke, ConstraintSet.END, R.id.caption, ConstraintSet.START)

            clear(R.id.caption, ConstraintSet.START)
            clear(R.id.caption, ConstraintSet.END)
            connect(R.id.caption, ConstraintSet.END, R.id.list, ConstraintSet.START, 8.dp.px.toInt())

            clear(R.id.list, ConstraintSet.START)
            connect(R.id.list, ConstraintSet.END, R.id.content_column, ConstraintSet.END)

            // Portrait only: `01` puts the artwork at 78% of the width, and the same bare-ratio
            // shortfall was leaving it at 58%. Landscape sizes its own in configureTabletLandscape.
            val configuration = resources.configuration
            if (configuration.screenWidthDp <= configuration.screenHeightDp) {
                sizeCoverSquare(
                    min(0.78F * configuration.screenWidthDp, 0.56F * configuration.screenHeightDp)
                )
            }

            applyTo(this@FullPlayer)
        }
    }

    /**
     * The landscape tablet, which is not a wider landscape phone.
     *
     * A landscape phone splits the canvas in two and hands the whole right half to the controls,
     * so opening lyrics or the queue has to take that half back off them. A tablet does not: the
     * references keep artwork *and* every control together in one narrow left column and reserve
     * the right for lyrics or the queue, which is why the transport does not move when either
     * opens. Running the phone's arrangement here also left the artwork at a phone's 360dp
     * ceiling and the control rows pinned to the top and bottom of a pane twice as tall, with two
     * voids where the reference has an even rhythm.
     *
     * The percentages are measured off `06-landscape-lyrics-split.png` (right half) and
     * `10-landscape-player-lyrics-alt.png`, which agree to within a percent of each other. They
     * are fractions of the canvas rather than fixed margins so the rhythm survives any height.
     */
    private fun configureTabletLandscape() {
        fun guide(percent: Float, vertical: Boolean) =
            Guideline(context).apply { id = View.generateViewId() }.also {
                addView(it, LayoutParams(0, 0).apply {
                    orientation = if (vertical) LayoutParams.VERTICAL else LayoutParams.HORIZONTAL
                    guidePercent = percent
                })
            }

        // The column the artwork and every control share, and the row each of them sits on.
        val columnStart = guide(0.05F, vertical = true)
        val columnEnd = guide(0.40F, vertical = true)
        val paneStart = guide(0.45F, vertical = true)
        val titleRow = guide(0.585F, vertical = false)
        val progressRow = guide(0.665F, vertical = false)
        val volumeRow = guide(0.859F, vertical = false)
        val actionRow = guide(0.911F, vertical = false)

        ConstraintSet().apply {
            clone(this@FullPlayer)

            clear(R.id.content_column, ConstraintSet.START)
            clear(R.id.content_column, ConstraintSet.END)
            connect(R.id.content_column, ConstraintSet.START, columnStart.id, ConstraintSet.START)
            connect(R.id.content_column, ConstraintSet.END, columnEnd.id, ConstraintSet.END)
            constrainMaxWidth(R.id.content_column, 0)

            // Uncapped: the column is the cap, and the artwork fills whatever the rows leave it.
            clear(R.id.cover)
            connect(R.id.cover, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 18.dp.px.toInt())
            connect(R.id.cover, ConstraintSet.BOTTOM, titleRow.id, ConstraintSet.TOP, 18.dp.px.toInt())
            connect(R.id.cover, ConstraintSet.START, columnStart.id, ConstraintSet.START)
            connect(R.id.cover, ConstraintSet.END, columnEnd.id, ConstraintSet.END)
            val configuration = resources.configuration
            sizeCoverSquare(
                min(0.35F * configuration.screenWidthDp, 0.585F * configuration.screenHeightDp - 36F)
            )

            clear(R.id.title, ConstraintSet.TOP)
            connect(R.id.title, ConstraintSet.TOP, titleRow.id, ConstraintSet.TOP)
            // Flush with the column, and the scrubber's track pulled out to meet it: the slider
            // insets its own track by 16dp, which is exactly what the phone's 36/20 margin pair
            // cancels out.
            setMargin(R.id.title, ConstraintSet.START, 0)
            setMargin(R.id.ellipsis, ConstraintSet.END, 0)

            clear(R.id.progressBar, ConstraintSet.TOP)
            connect(R.id.progressBar, ConstraintSet.TOP, progressRow.id, ConstraintSet.TOP)
            setMargin(R.id.progressBar, ConstraintSet.START, (-16).dp.px.toInt())
            setMargin(R.id.progressBar, ConstraintSet.END, (-16).dp.px.toInt())

            clear(R.id.speaker_hint, ConstraintSet.BOTTOM)
            connect(R.id.speaker_hint, ConstraintSet.TOP, volumeRow.id, ConstraintSet.TOP)

            clear(R.id.list, ConstraintSet.BOTTOM)
            connect(R.id.list, ConstraintSet.TOP, actionRow.id, ConstraintSet.TOP)

            // Lyrics and the queue are the right-hand pane, not a sheet over the controls. Both
            // run the full height of it, which is why neither displaces the transport.
            for (pane in intArrayOf(R.id.fading, R.id.queue_container)) {
                clear(pane, ConstraintSet.START)
                clear(pane, ConstraintSet.TOP)
                clear(pane, ConstraintSet.BOTTOM)
                connect(pane, ConstraintSet.START, paneStart.id, ConstraintSet.START)
                connect(pane, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, 40.dp.px.toInt())
                connect(pane, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, 24.dp.px.toInt())
                connect(pane, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 24.dp.px.toInt())
            }

            applyTo(this@FullPlayer)
        }
    }

    private fun enqueueCover(mediaId: String, artwork: Uri) {
        if (instance?.currentMediaItem?.mediaId != mediaId) return
        val identity = "$mediaId:$artwork"
        // Re-applying is gated on the identity the bitmap actually *is*, not the one most recently
        // requested. loadedArtworkIdentity is set as soon as a load starts, so between that and
        // the load finishing it names the incoming track while appliedCoverBitmap still holds the
        // outgoing one - and a second call in that window repainted the player with the previous
        // song's cover.
        if (identity == appliedArtworkIdentity && appliedCoverBitmap != null) {
            // Already loaded, but the cover may have slid out for a playlist replacement that kept
            // the same track (returning from Finnect). Re-applying keeps the slide from settling
            // on the placeholder - nothing else will ever reload it, because the identity is
            // unchanged.
            //
            // Only while something is actually moving, though. media3 emits onMediaMetadataChanged
            // several times per track, and each one arrived here and repainted every surface - a
            // fresh BitmapDrawable, four setImageDrawable calls and an output-picker refresh, three
            // times over, for a cover already on screen. With nothing sliding there is no
            // placeholder to lose to, so the work has no result to show for itself.
            if (!coverSlideInFlight) return
            currentArtworkUri = artwork
            applyCover(
                android.graphics.drawable.BitmapDrawable(context.resources, appliedCoverBitmap),
                appliedCoverBitmap,
            )
            return
        }
        // Only skip a load already under way when one has actually landed. loadedArtworkIdentity is
        // set when a request *starts*, so on its own it also names requests that never delivered -
        // a success dropped by the mediaId guard below, for instance. Skipping unconditionally then
        // meant nothing ever re-requested that track's artwork and the cover kept the placeholder
        // for good, because every later retry matched the identity of the load that failed.
        if (identity == loadedArtworkIdentity && appliedCoverBitmap != null) return
        loadedArtworkIdentity = identity
        // Tell the prefetcher what size to warm, so its entries land under the same memory-cache
        // key this request will look for rather than beside it.
        QueuePrefetcher.artworkTargetPx = coverSimpleImageView.width
        lastDisposable = context.imageLoader.enqueue(
                ImageRequest.Builder(context).apply {
                    data(artwork)
                    size(coverSimpleImageView.width, coverSimpleImageView.height)
                    scale(Scale.FILL)
                    // The mediaId is re-checked on delivery, not only when the request is made.
                    // Two quick taps leave two requests in flight, and nothing stopped the slower
                    // one from finishing last and repainting the player with the previous song's
                    // cover - the artwork then belonged to a track that was no longer showing.
                    target(onSuccess = {
                        if (instance?.currentMediaItem?.mediaId == mediaId) {
                            appliedArtworkIdentity = identity
                            currentArtworkUri = artwork
                            applyCover(it.asDrawable(context.resources), it.toBitmap())
                        }
                    }, onError = {
                        if (instance?.currentMediaItem?.mediaId == mediaId) {
                            loadedArtworkIdentity = null
                            appliedArtworkIdentity = null
                            currentArtworkUri = artwork
                            applyCover(it?.asDrawable(context.resources), it?.toBitmap())
                        }
                    }) // do not react to onStart() which sets placeholder
                    // Deliberately software, overriding nothing: the loader is already built
                    // with allowHardware(false) and this request used to be the one exception.
                    // A Config#HARDWARE bitmap keeps its pixels on the GPU, and every consumer of
                    // this one reads pixels - the blur, the gradient palette, the sampler - so each
                    // track change paid for a full-size copy back to software on the main thread
                    // before any of them could start. Asking for software up front deletes that
                    // copy outright.
                    allowHardware(false)
                }.build()
            )
    }

    private suspend fun resolveCachedArtwork(mediaItem: MediaItem): Uri? {
        val songs = activity.reader.songListSnapshot()
        songs.firstOrNull { it.mediaId == mediaItem.mediaId }
            ?.mediaMetadata?.artworkUri?.let { return it }
        val extras = mediaItem.mediaMetadata.extras ?: return null
        if (!extras.containsKey("AlbumId")) return null
        val albumId = extras.getLong("AlbumId")
        return songs.firstOrNull { candidate ->
            val candidateExtras = candidate.mediaMetadata.extras
            candidateExtras?.containsKey("AlbumId") == true &&
                candidateExtras.getLong("AlbumId") == albumId &&
                candidate.mediaMetadata.artworkUri != null
        }?.mediaMetadata?.artworkUri
    }

    private fun showCachedLyricsOrEmpty(mediaItem: MediaItem?) {
        lyricsViewModel?.setLyrics(
            cachedLyrics.takeIf { cachedLyricsMediaId == mediaItem?.mediaId } ?: Lyrics.Empty
        )
    }

    /**
     * Extractors can discover embedded cover bytes after playback starts even when Jellyfin did
     * not advertise an image. Keep the confirmed bytes by media id so the next Activity (and the
     * collapsed player it owns) can paint them before the stream is parsed again.
     */
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val mediaId = instance?.currentMediaItem?.mediaId ?: return
        // Keep the file's own picture whenever the extractor surfaces one, even if Jellyfin also
        // offers a URL for it. This used to return early on the URL, which is why nothing was ever
        // written and every play went back to the server for a picture the file already contained.
        // Storing it now means the *next* play of this track resolves its cover from local storage
        // with no request at all.
        val bytes = mediaMetadata.artworkData
        if (bytes != null && !embeddedArtworkFile(mediaId).isFile) {
            embeddedArtworkJob?.cancel()
            embeddedArtworkJob = (findViewTreeLifecycleOwner()?.lifecycleScope
                ?: CoroutineScope(Dispatchers.Main)).launch {
                val file = withContext(Dispatchers.IO) { storeEmbeddedArtwork(mediaId, bytes) }
                // Only take over the display if nothing has managed to paint a cover yet. When the
                // server's copy already arrived it is the same picture, and swapping it out would
                // be a visible flicker bought for nothing.
                if (instance?.currentMediaItem?.mediaId == mediaId && appliedCoverBitmap == null) {
                    enqueueCover(mediaId, Uri.fromFile(file))
                }
            }
        }
        // Only worth asking the server when this track has no picture of its own. Without the
        // check every track loaded twice - once from the file, once over HTTP for the same image -
        // and the second one simply overwrote the first.
        mediaMetadata.artworkUri?.let {
            if (embeddedArtworkFile(mediaId).isFile) return
            enqueueCover(mediaId, it)
        }
    }

    // Both delegate to the shared store, because the playback service writes these files and this
    // reads them: two copies of the naming rule would only have to agree forever.
    private fun storeEmbeddedArtwork(mediaId: String, bytes: ByteArray): File =
        EmbeddedArtworkStore.store(context, mediaId, bytes)

    private fun embeddedArtworkFile(mediaId: String): File =
        EmbeddedArtworkStore.fileFor(context, mediaId)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updateLyricsKeepScreenOn()
        onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateRepeatButton(repeatMode)
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        queueShuffleButton.isChecked = shuffleModeEnabled
    }

    override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
        Log.d("FullPlayer", "onPlaybackStateChanged: $playbackState")
        // The controller follows whichever player owns the output, so this is simply "is music
        // playing" again. Asking the local player used to answer "no" for the whole of a cast -
        // it is deliberately silent then - and the button sat on Play over a playing speaker.
        val isPlaying = instance?.isPlaying == true
        // Cast reports not-playing while it buffers the next queue item. That is a loading state,
        // not a user pause: keep the artwork/ambient motion alive and move the feedback onto the
        // progress track. In-track Cast seeks are masked by CastQueuePlayer, so this is reserved
        // for genuine item loading. A paused receiver still has playWhenReady=false and behaves
        // like an ordinary pause.
        val isRemoteTrackBuffering = remoteOutputActive &&
            playbackState == Player.STATE_BUFFERING &&
            instance?.playWhenReady == true
        progressOverlaySlider.isLoading = isRemoteTrackBuffering
        val visuallyPlaying = isPlaying || isRemoteTrackBuffering
        // Both backdrop choices are ambient and remain animated while paused. Playback still
        // controls the cover's own pause treatment below, but never whether the background lives.
        meshGradientView.setPlaying(true)
        liquidGradientView.setPlaying(true)
        if (blendView.visibility == VISIBLE) blendView.startRotationAnimation()
        updateCoverPauseScale(
            isPlaying = visuallyPlaying,
            animate = !firstTime
        )
        if (isPlaying) {
            controllerButton.playAnimation(false)
        } else if (remoteOutputActive || playbackState != Player.STATE_BUFFERING) {
            controllerButton.playAnimation(true)
        }
        if (visuallyPlaying) {
            startPositionUpdates()
        } else {
            stopPositionUpdates()
            updateProgressDisplay()
        }
        /*
        if (instance?.isPlaying == true) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 1) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        wrappedContext ?: context,
                        R.drawable.play_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_play_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 1)
            }
            if (!isUserTracking) {
                progressDrawable.animate = true
            }
            if (!runnableRunning) {
                runnableRunning = true
                handler.postDelayed(positionRunnable, SLIDER_UPDATE_INTERVAL)
            }
            bottomSheetFullCover.startRotation()
        } else if (playbackState != Player.STATE_BUFFERING) {
            if (bottomSheetFullControllerButton.getTag(R.id.play_next) as Int? != 2) {
                bottomSheetFullControllerButton.icon =
                    AppCompatResources.getDrawable(
                        wrappedContext ?: context,
                        R.drawable.pause_anim
                    )
                bottomSheetFullControllerButton.background =
                    AppCompatResources.getDrawable(context, R.drawable.bg_pause_anim)
                bottomSheetFullControllerButton.icon.startAnimation()
                bottomSheetFullControllerButton.background.startAnimation()
                bottomSheetFullControllerButton.setTag(R.id.play_next, 2)
                bottomSheetFullCover.stopRotation()
            }
            if (!isUserTracking) {
                progressDrawable.animate = false
            }
        }

        */
    }

    private fun updateCoverPauseScale(isPlaying: Boolean, animate: Boolean) {
        val targetScale = if (isPlaying) 1F else PAUSED_COVER_SCALE
        if (coverPauseScale == targetScale) return
        coverPauseAnimator?.cancel()
        coverPauseAnimator = null
        if (!animate) {
            coverPauseScale = targetScale
            applyCoverScale()
            syncTransitionCoverScale()
            return
        }
        coverPauseAnimator = AnimationUtils.createValAnimator(
            coverPauseScale,
            targetScale,
            duration = LONG_DURATION,
            interpolator = AnimationUtils.easingStandardInterpolator
        ) {
            coverPauseScale = it
            applyCoverScale()
            syncTransitionCoverScale()
        }
    }

    private fun applyCoverScale() {
        // Only the queue neutralises the paused scale while it folds the portrait artwork into
        // its toolbar. Lyrics use the same content-transition fraction, but on a wide layout the
        // artwork remains beside them and must keep its normal pause-to-shrink/play-to-expand
        // behaviour.
        val queueBlend = if (contentType == ContentType.PLAYLIST) {
            transformationFraction.coerceIn(0F, 1F)
        } else {
            0F
        }
        val effectivePauseScale = lerp(coverPauseScale, 1F, queueBlend)
        // Treat an invalid intermediate value as the neutral transform. This is a final safety
        // boundary: view properties must never receive NaN/Infinity even if layout and playback
        // callbacks race during activity restoration.
        val safeBaseScale = coverBaseScale.takeIf { it.isFinite() } ?: 1F
        val safePauseScale = effectivePauseScale.takeIf { it.isFinite() } ?: 1F
        val scale = (safeBaseScale * safePauseScale).takeIf { it.isFinite() } ?: 1F
        coverSimpleImageView.pivotX = 0F
        coverSimpleImageView.pivotY = 0F
        coverSimpleImageView.scaleX = scale
        coverSimpleImageView.scaleY = scale
        val pauseOffsetX =
            coverSimpleImageView.width * safeBaseScale * (1f - safePauseScale) / 2f
        val pauseOffsetY =
            coverSimpleImageView.height * safeBaseScale * (1f - safePauseScale) / 2f
        // The resting position is always recorded, even mid-slide, because that is where the
        // incoming artwork has to come to a stop.
        coverRestingTranslationX = coverBaseTranslationX + pauseOffsetX
        applyCoverTranslation()
        coverSimpleImageView.translationY = coverBaseTranslationY + pauseOffsetY
    }

    /** The one place the cover's horizontal position is written. */
    private fun applyCoverTranslation() {
        coverSimpleImageView.translationX = coverRestingTranslationX + coverSlideOffsetX
    }

    /**
     * Animates the displacement to [target].
     *
     * A value animator rather than ViewPropertyAnimator: the offset has to be readable every
     * frame so the paused-state shrink can keep adjusting the resting position underneath it.
     */
    private fun animateCoverOffset(
        target: Float,
        duration: Long,
        interpolator: Interpolator,
        onEnd: (() -> Unit)? = null,
    ) {
        coverSlideAnimator?.cancel()
        coverSlideAnimator = ValueAnimator.ofFloat(coverSlideOffsetX, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener {
                coverSlideOffsetX = it.animatedValue as Float
                applyCoverTranslation()
            }
            addListener(object : AnimatorListenerAdapter() {
                // cancel() fires onAnimationEnd too. Without this guard, interrupting a slide ran
                // the interrupted one's completion callback: a new slide-out cancelled the previous
                // slide-in, whose `coverSlideInFlight = false` then wiped the flag startCoverSlideOut
                // had just set. The cover was left wherever the cancel stopped it - a resting offset
                // that leaked into every subsequent slide - and late artwork was written onto a view
                // parked off-centre with nothing pending to bring it back.
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (coverSlideAnimator === animation) coverSlideAnimator = null
                    if (!canceled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun syncTransitionCoverScale() {
        if (!coverSimpleImageView.isLaidOut) return
        updateTransitionTargetForContentType(contentType)
    }

    override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
        // Reported by whichever player owns the output, so this is already the receiver's level
        // during a cast and the remote session's on Finnect - no filtering needed. It also means
        // a change made on the speaker itself, or from Google Home, lands here.
        updateVolumeSlider(volume)
    }
    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
            updateProgressDisplay()
        }
        (queueRecyclerView.adapter as? QueuePreviewAdapter)?.updateItems(buildQueueItems(timeline))
    }

    /**
     * The queue, split into what was asked for and what follows on.
     *
     * Tracks queued by hand play before the album resumes, and saying so is the difference between
     * a queue you can read and a list of songs. Only what is still ahead gets a heading: labelling
     * something already played "Playing Next" would be a lie about the direction of travel.
     */
    private fun buildQueueItems(timeline: Timeline): List<QueueItem> {
        val window = Timeline.Window()
        val current = instance?.currentMediaItemIndex ?: 0
        val items = mutableListOf<QueueItem>()
        var labelledUserRun = false
        var labelledSource = false

        // A queue is the current track plus what will play after it. Keeping already-played tracks
        // above the current row made the list grow backwards forever and made its visual row
        // numbers diverge from the actions the user was taking on the upcoming queue.
        for (i in current.coerceAtLeast(0) until timeline.windowCount) {
            val w = timeline.getWindow(i, window)
            val queuedByHand = UserQueue.isUserQueued(w.mediaItem)
            var label: String? = null
            if (i > current) {
                if (queuedByHand && !labelledUserRun) {
                    label = context.getString(R.string.queue_section_next)
                    labelledUserRun = true
                } else if (!queuedByHand && !labelledSource) {
                    // Named after the record it is playing through, which is what the user chose;
                    // without a name there is nothing useful to say, so the heading is dropped.
                    val album = w.mediaItem.mediaMetadata.albumTitle?.toString()
                    label = album?.takeIf { it.isNotBlank() }?.let {
                        context.getString(R.string.queue_section_from, it)
                    }
                    labelledSource = true
                }
            }
            items.add(QueueItem(w.uid, w.mediaItem, label, isCurrent = i == current))
        }
        return items
    }

    /** Resolves an adapter row back into the current timeline after filtering played tracks. */
    private fun Timeline.indexOfWindow(uid: Any): Int {
        val window = Timeline.Window()
        for (index in 0 until windowCount) {
            if (getWindow(index, window).uid == uid) return index
        }
        return -1
    }

    /** Keeps artwork/title/queue selection aligned while audio continues on the remote phone. */
    private fun syncLocalPlayerToRemoteState(
        state: JellyfinRemoteTargets.RemotePlaybackState?,
    ) {
        state ?: return
        val itemId = state.itemId ?: return
        val index = state.queueIds.indexOf(itemId.replace("-", "").lowercase())
        val player = instance ?: return
        if (index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) return
        armCoverSlide(if (index > player.currentMediaItemIndex) SLIDE_NEXT else SLIDE_PREVIOUS)
        player.seekTo(index, 0L)
        player.pause()
    }

    override fun onDetachedFromWindow() {
        // The pause is only lifted by a slide back below the top, so a teardown while expanded
        // would leave the home feed's cards frozen for the rest of the process.
        ProceduralMotionTicker.setPaused(false)
        karaokeJob?.cancel()
        karaokeJob = null
        karaokeStatus.removeCallbacks(hideKaraokeStatusRunnable)
        remoteTargetJob?.cancel()
        remoteTargetJob = null
        remotePlaybackJob?.cancel()
        remotePlaybackJob = null
        remoteTargetsWarmupJob?.cancel()
        remoteTargetsWarmupJob = null
        stopPositionUpdates()
        removeCallbacks(hideControlsRunnable)
        lyricsRefreshJob?.cancel()
        lyricsRefreshJob = null
        keepScreenOn = false
        cancelSheetSliderAnimations()
        cancelCoverLoad()
        cancelQualityFlash()
        qualityAvailableHint.animate().cancel()
        qualityFlashing = false
        volumeUpdateAnimator?.cancel()
        coverPauseAnimator?.cancel()
        controlsAnimator?.cancel()
        controlsAnimator = null
        contentTypeAnimator?.cancel()
        contentTypeAnimator = null
        bottomButtonAnimators.values.toList().forEach { it.cancel() }
        bottomButtonAnimators.clear()
        airplayOverlayButton.removeCallbacks(resetAirplayButton)
        removeCallbacks(coverSlideBackstop)
        removeCallbacks(coverSlideInDeadline)
        removeCallbacks(coverIntegrityCheck)
        artworkLookupJob?.cancel()
        artworkLookupJob = null
        embeddedArtworkJob?.cancel()
        embeddedArtworkJob = null
        coverSlideAnimator?.cancel()
        coverSlideAnimator = null
        coverSwipeHaptics.reset()
        lyricsViewModel?.release()
        lyricsViewModel = null
        lyricsViewAttached = false
        if (isVolumeReceiverRegistered) {
            runCatching { context.unregisterReceiver(volumeChangeReceiver) }
            isVolumeReceiverRegistered = false
        }
        audioDeviceCallback?.let {
            runCatching { AudioOutput.unregister(context, it) }
            audioDeviceCallback = null
        }
        removeCallbacks(volumeResyncRunnable)
        if (outputRouteCallbackRegistered) {
            outputMediaRouter.removeCallback(outputRouteCallback)
            outputRouteCallbackRegistered = false
        }
        super.onDetachedFromWindow()
    }

    /**
     * The window coming back is the moment to check the pane and the chrome still agree; see
     * [syncContentTransformState] for what disagreeing looked like.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) post(::syncContentTransformState)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restoreCachedBackdrop()
        post(::syncContentTransformState)
        if (audioDeviceCallback == null) {
            audioDeviceCallback = AudioOutput.register(context) { refreshOutputDevice() }
        }
        if (!outputRouteCallbackRegistered) {
            outputMediaRouter.addCallback(outputRouteSelector, outputRouteCallback)
            outputRouteCallbackRegistered = true
        }
        // Not in the constructor: there is no lifecycle owner in the view tree until it is
        // attached, so the collector was never started and handing playback over silently left the
        // button showing the wrong thing. The null-safe call is what made it silent.
        if (remoteTargetJob == null) {
            remoteTargetJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                JellyfinRemoteTargets.active.collect { refreshOutputDevice() }
            }
        }
        if (castSessionJob == null && FincordCast.isAvailable) {
            castSessionJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                FincordCast.session.collect(::onCastSessionChanged)
            }
        }
        // The receiver's own status no longer needs a separate observer here. It reaches the
        // remote player, which is in front of the session while casting, so it arrives through the
        // ordinary Player.Listener callbacks this view already implements - the same ones a local
        // track change comes through.
        if (remotePlaybackJob == null) {
            remotePlaybackJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                JellyfinRemoteTargets.playbackState.collect { state ->
                    syncLocalPlayerToRemoteState(state)
                    onPlaybackStateChanged(instance?.playbackState ?: Player.STATE_IDLE)
                    state?.let {
                        queueShuffleButton.isChecked = it.playbackOrder == PlaybackOrder.SHUFFLE
                        updateRepeatButton(it.repeatMode.toPlayerRepeatMode())
                    }
                    updateProgressDisplay()
                    updateVolumeSlider()
                }
            }
        }
        // Warm the output picker while the player settles. Opening it then has its final device
        // count on the first frame; a very early tap still gets local routes immediately and the
        // same smooth resize used for a device appearing later.
        if (remoteTargetsWarmupJob == null && JellyfinRemoteTargets.isEnabled(context)) {
            remoteTargetsWarmupJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                JellyfinRemoteTargets.restoreActive()
                JellyfinRemoteTargets.available()
            }
        }
        refreshOutputDevice()
        if (!isVolumeReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_VOLUME_CHANGED_ACTION")
                addAction("android.media.MASTER_MUTE_CHANGED_ACTION")
                addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(volumeChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(volumeChangeReceiver, filter)
            }
            isVolumeReceiverRegistered = true
        }
    }

    /*
    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        val isHeart = (mediaMetadata.userRating as? HeartRating)?.isHeart == true
        if (bottomSheetFavoriteButton.isChecked != isHeart) {
            bottomSheetFavoriteButton.removeOnCheckedChangeListener(this)
            bottomSheetFavoriteButton.isChecked =
                (mediaMetadata.userRating as? HeartRating)?.isHeart == true
            bottomSheetFavoriteButton.addOnCheckedChangeListener(this)
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: @Player.TimelineChangeReason Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
            updateDuration()
        }
    }

    private fun updateDuration() {
        val duration = instance?.contentDuration?.let { if (it == C.TIME_UNSET) null else it }
            ?: instance?.currentMediaItem?.mediaMetadata?.durationMs
        if (duration != null && duration.toInt() != bottomSheetFullSeekBar.max) {
            bottomSheetFullDuration.setTextAnimation(
                CalculationUtils.convertDurationToTimeStamp(duration)
            )
            val position =
                CalculationUtils.convertDurationToTimeStamp(instance?.currentPosition ?: 0)
            if (!isUserTracking) {
                bottomSheetFullSeekBar.max = duration.toInt()
                bottomSheetFullSeekBar.progress = instance?.currentPosition?.toInt() ?: 0
                bottomSheetFullSlider.valueTo = duration.toFloat().coerceAtLeast(1f)
                bottomSheetFullSlider.value =
                    min(instance?.currentPosition?.toFloat() ?: 0f, bottomSheetFullSlider.valueTo)
                bottomSheetFullPosition.text = position
            }
            bottomSheetFullLyricView.updateLyricPositionFromPlaybackPos()
        }
    }
     */

    enum class ContentType {
        LYRICS, NORMAL, PLAYLIST
    }

    companion object {
        /** Measured on the window, so a phone-sized window on a tablet is treated as one. */
        private const val TABLET_WINDOW_DP = 600

        /**
         * Floors for the proportional rhythms, below which the fixed-size contents of a row no
         * longer fit the fraction it is given. The tablet column needs ~60dp between the title and
         * the scrubber out of the 8% it is allotted; the phone's 13.5% covers the same two lines.
         */
        private const val TABLET_RHYTHM_MIN_HEIGHT_DP = 720
        private const val PHONE_RHYTHM_MIN_HEIGHT_DP = 360

        private const val STATE_SUPER = "superState"
        private const val STATE_CONTENT_TYPE = "contentType"

        /** Last confirmed lyrics survive an Activity recreation for the unchanged media item. */
        private var cachedLyricsMediaId: String? = null
        private var cachedLyrics: Lyrics? = null
        const val TAG = "FullPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 500L


        /** Containers worth mentioning when a quality cap is hiding what they hold. */
        private val LOSSLESS_CONTAINERS = setOf("flac", "alac", "wav", "aiff", "ape", "wv")

        /** Long enough for the codec query to come back after the permission is granted. */
        private const val CODEC_PERMISSION_SETTLE_MS = 700L
        private const val OUTPUT_CODEC_BADGE = "output_codec_badge"
        private const val QUALITY_BADGE_CONTENT = "quality_badge_content"
        private const val QUALITY_BADGE_CONTENT_LABEL = "label"
        private const val QUALITY_BADGE_CONTENT_CODEC = "codec"
        private const val QUALITY_BADGE_FLASH = "quality_badge_flash"
        private const val QUALITY_BADGE_FLASH_HOLD_MS = 2_000L
        private const val QUALITY_HINT_FADE_MS = 220L
        private const val QUALITY_HINT_HOLD_MS = 3_000L
        private const val PAUSED_COVER_SCALE = 0.84F
        private const val OUTPUT_PICKER_RESIZE_MS = 240L
        /** How long the mixer takes to follow a route change before its level can be trusted. */
        private const val OUTPUT_VOLUME_SETTLE_MS = 250L

        private const val OUTPUT_PICKER_MAX_HEIGHT_DP = 540F
        private const val OUTPUT_PICKER_MIN_LIST_HEIGHT_DP = 96F
        private const val OUTPUT_PICKER_HEIGHT_FRACTION = 0.82F

        /** How far the cover follows the finger, and how far it has to go to count as a swipe. */
        private const val COVER_SWIPE_FOLLOW = 0.56F
        private const val COVER_SWIPE_THRESHOLD = 0.32F
        private const val COVER_SWIPE_MAX_TRAVEL =
            COVER_SWIPE_THRESHOLD * COVER_SWIPE_FOLLOW
        private const val COVER_FLING_VELOCITY_MULTIPLIER = 1.35F
        private const val COVER_MOMENTUM_PROJECTION_SECONDS = 0.07F
        private const val COVER_MOMENTUM_MS = 70L
        private const val COVER_SWIPE_SETTLE_MS = 190L

        /** Which way the artwork travels on a track change. */
        private const val SLIDE_NONE = 0
        private const val SLIDE_NEXT = 1
        private const val SLIDE_PREVIOUS = -1
        private const val COVER_SLIDE_OUT_MS = 180L
        private const val COVER_SLIDE_IN_MS = 260L
        private const val COVER_SLIDE_MIN_MS = 70L
        private const val COVER_SLIDE_BACKSTOP_MS = 400L

        /**
         * How long a requested skip has to produce a track change before the direction is dropped.
         *
         * Long enough for a Cast receiver to answer, since a skip while casting is a round trip
         * over the network rather than a local seek; the cover has not moved while this runs, so
         * waiting costs nothing visible.
         */
        private const val COVER_SLIDE_ARM_TIMEOUT_MS = 2_500L

        /** How far a group's member speakers sit inside the group row above them. */
        private const val OUTPUT_MEMBER_INDENT_DP = 30F

        /** The longest the cover stays off screen waiting for artwork to load. */
        /** Backdrops are blurred, so they never need more than this many pixels a side. */
        private const val BACKDROP_SOURCE_PX = 256

        /** Long enough that a burst of swipes has stopped before the cover is re-checked. */
        private const val COVER_INTEGRITY_DELAY_MS = 700L

        private const val COVER_ART_WAIT_MS = 90L

        /**
         * How long the backdrop takes to dissolve from one album's cover to the next.
         *
         * Deliberately far longer than the cover slide: the artwork is a hard, fast movement the
         * eye follows, and the field behind it should still be settling after the cover has
         * landed rather than cutting over with it.
         */
        private const val BACKDROP_CROSSFADE_MS = 1_000

        /**
         * The last backdrop artwork, held past any one [FullPlayer] so a rotation can restore it.
         *
         * A bare bitmap and a Uri - nothing here references a Context or a View, so this cannot
         * retain an Activity across the recreation it exists to survive.
         */
        private var cachedBackdrop: android.graphics.Bitmap? = null
        private var cachedBackdropArtwork: Uri? = null
        private const val CONTROLS_HIDE_DELAY_MS = 3_000L

        /**
         * Landscape only. The chrome and the lyrics want the same half of the screen there, so it
         * leaves as soon as the lyrics open and lingers only briefly after a tap has recalled it.
         */
        private const val CONTROLS_OPEN_HIDE_WIDE_MS = 180L
        private const val CONTROLS_HIDE_DELAY_WIDE_MS = 1_400L
        private const val LYRICS_TAP_TIMEOUT_MS = 300L
        private const val KARAOKE_POLL_INTERVAL_MS = 2_000L
        private const val KARAOKE_STATUS_HOLD_MS = 2_000L
        private const val KARAOKE_SWITCH_GUARD_MS = 1_000L

        private const val PREF_AUTOPLAY = "autoplay_similar"
        // The playback service reads this too, so the key itself lives in Automix rather than
    }

}
