package uk.akane.accord.ui.components.lyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.os.Bundle
import androidx.preference.PreferenceManager
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import uk.akane.accord.ui.components.FadingVerticalEdgeLayout
import uk.akane.accord.ui.components.scroll.ListenableNestedScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * @param positionProvider current playback position in milliseconds. Upstream ships this view as a
 *   demo - it walks a hardcoded verse on a five second timer with no idea what is playing - so real
 *   lyrics need both a source ([setLyrics]) and a clock to follow.
 */
class LyricsViewModel(
    private val context: Context,
    private val positionProvider: () -> Long = { 0L },
    /**
     * Whether playback is actually running.
     *
     * A position alone is not enough to run a local clock off: an interpolated clock advances
     * because wall time advances, and wall time does not stop when the music does. Without this
     * the lyrics kept walking forward while paused.
     */
    private val isPlayingProvider: () -> Boolean = { false },
    /**
     * The rate playback is running at.
     *
     * The player's own position already advances at this rate, so a local clock that fills in
     * between its updates has to as well or it drifts by exactly the speed error - obviously at
     * 1.5x, but also on the small pitch-preserving trims some users leave on permanently.
     */
    private val speedProvider: () -> Float = { 1f },
    /** Where a tapped line should take playback. */
    private val onSeek: (Long) -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var entranceAnimator: ValueAnimator? = null
    private var lyricsLayoutGeneration = 0

    private val lyrics = MutableStateFlow(Lyrics.Empty)

    private val sampleLyrics = Lyrics(
        listOf(
            LyricsLine(0, null, "For the puppets on TV", null),
            LyricsLine(5_000, null, "There is comfort in the strings", null),
            LyricsLine(10_000, null, "If you're gonna control me", null),
            LyricsLine(15_000, null, "At least make it interesting theatrically", null),
            LyricsLine(20_000, null, "How does it feel to be free?", null),
            LyricsLine(25_000, null, "Why don't you ask yourself?", null),
            LyricsLine(30_000, null, "The gate opened for me", null),
            LyricsLine(35_000, null, "So I leaped", null),
            LyricsLine(40_000, null, "Run and through this forest", null),
            LyricsLine(45_000, null, "With an arrow from the brother sun", null),
            LyricsLine(50_000, null, "Trees, weeds, leaves, and flowers", null),
            LyricsLine(55_000, null, "Feel we the breathe of the four season", null),
        )
    )

    fun onViewCreated(view: View, savedInstanceState: Bundle? = null) {
        val lifecycle = (context as? LifecycleOwner)?.lifecycle

        val fadingEdgeLayout = view as FadingVerticalEdgeLayout
        val scrollView = fadingEdgeLayout.getChildAt(0) as ListenableNestedScrollView
        val lyricsView = scrollView.getChildAt(0) as LyricsView
        lyricsView.onSeek = { timestamp ->
            // A seek is announced rather than discovered, so the clock lands on it at once
            // instead of being slewed towards it over the next few seconds.
            resync()
            onSeek(timestamp)
        }

        var isUserScrolling = false

        scope.launch {
            lifecycle?.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                scrollView.collectUserAction(
                    onActionStart = {
                        scrollView.isVerticalScrollBarEnabled = true
                        lyricsView.forEach { view ->
                            view as LyricsLineView
                            view.animations.cancelBlur()
                            view.visibility = View.VISIBLE
                        }
                        isUserScrolling = true
                    },
                    onActionEnd = {
                        isUserScrolling = false
                    }
                )
            }
        }

        fun updateCurrentIndex(index: Int) {
            if (!isUserScrolling) {
                val currentLineChild = lyricsView.getChildAt(index) as LyricsLineView? ?: return
                val currentOffset = scrollView.scrollY.toFloat()
                val maxScrollOffset =
                    (lyricsView.measuredHeight + lyricsView.contentPaddingTop - scrollView.measuredHeight).toFloat()
                val targetOffset =
                    currentLineChild.animations.getGlobalOffset().coerceAtMost(maxScrollOffset) -
                            lyricsView.contentPaddingTop
                val deltaOffset = targetOffset - currentOffset

                scrollView.isVerticalScrollBarEnabled = false
                scrollView.scrollTo(0, targetOffset.roundToInt())

                val scrollOffset = scrollView.scrollY.toFloat()
                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    val targetTransitionY = child.textOffset + deltaOffset
                    if (child.animations.checkIsInScreen(scrollOffset, targetTransitionY)) {
                        child.textOffset = targetTransitionY
                        child.animations.update(index)
                        if (child.visibility != View.VISIBLE) {
                            child.visibility = View.VISIBLE
                        }
                    } else {
                        if (child.visibility != View.GONE) {
                            child.visibility = View.GONE
                        }
                        child.animations.updateImmediately(index)
                    }
                }
            } else {
                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    child.textOffset = 0f
                    child.animations.update(index, true)
                }
            }
        }

        var isLayoutFinished = false
        var lastIndex = -1
        fun updateOnLayout() {
            entranceAnimator?.removeAllListeners()
            entranceAnimator?.cancel()
            entranceAnimator = null
            val layoutGeneration = ++lyricsLayoutGeneration

            lyricsView.doOnLayout {
                isLayoutFinished = false

                // Build the first visible frame around the lyric that is playing now. Give it a
                // little context above, then gently settle it into the normal followed position.
                // The current line is highlighted throughout; this is viewport motion, not a
                // replay of every lyric between line zero and the current timestamp.
                val position = playbackPosition()
                val index = getCurrentLyricsLineIndex(position)

                val currentLineChild = lyricsView.getChildAt(index) as? LyricsLineView ?: return@doOnLayout
                val contextIndex = (index - INITIAL_CONTEXT_LINES).coerceAtLeast(0)
                val contextLineChild = lyricsView.getChildAt(contextIndex) as? LyricsLineView ?: currentLineChild
                val maxScrollOffset =
                    (lyricsView.measuredHeight + lyricsView.contentPaddingTop - scrollView.measuredHeight)
                        .coerceAtLeast(0)
                        .toFloat()
                val startOffset =
                    (contextLineChild.animations.getGlobalOffset() - lyricsView.contentPaddingTop)
                        .coerceIn(0f, maxScrollOffset)
                val targetOffset =
                    (currentLineChild.animations.getGlobalOffset() - lyricsView.contentPaddingTop)
                        .coerceIn(0f, maxScrollOffset)

                scrollView.isVerticalScrollBarEnabled = false
                scrollView.scrollTo(0, startOffset.roundToInt())

                lyricsView.forEach { child: View ->
                    child as LyricsLineView
                    child.animations.updateImmediately(index)
                    child.visibility = View.VISIBLE
                }
                currentLineChild.updatePlaybackPosition(position)

                lastIndex = index

                // Waiting one frame ensures the contextual starting position is actually drawn.
                // A short eased settle is deliberate: opening lyrics should feel alive without
                // delaying the 200 ms playback follower below.
                scrollView.postOnAnimation {
                    if (layoutGeneration != lyricsLayoutGeneration || !scope.isActive) return@postOnAnimation
                    if (startOffset.roundToInt() == targetOffset.roundToInt()) {
                        isLayoutFinished = true
                        return@postOnAnimation
                    }

                    val animator = ValueAnimator.ofInt(
                        startOffset.roundToInt(),
                        targetOffset.roundToInt(),
                    ).apply {
                        duration = INITIAL_SETTLE_DURATION_MS
                        interpolator = INITIAL_SETTLE_INTERPOLATOR
                        addUpdateListener { valueAnimator ->
                            scrollView.scrollTo(0, valueAnimator.animatedValue as Int)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (entranceAnimator === animation &&
                                    layoutGeneration == lyricsLayoutGeneration
                                ) {
                                    entranceAnimator = null
                                    isLayoutFinished = true
                                }
                            }
                        })
                    }
                    entranceAnimator = animator
                    animator.start()
                }
            }
        }

        lyricsView.update(lyrics.value)
        updateOnLayout()

        // Follow the player rather than a timer. getCurrentLyricsLineIndex already maps a position
        // onto a line; upstream simply never called it with a real one.
        scope.launch {
            while (isActive) {
                LyricsSyncProbe.onLoopTick()
                var onFrameClock = false
                var position = 0L
                if (lyrics.value != Lyrics.Empty) {
                    position = playbackPosition()
                    val index = getCurrentLyricsLineIndex(position)
                    if (isLayoutFinished && index >= 0) {
                        if (index != lastIndex) {
                            lastIndex = index
                            updateCurrentIndex(index)
                        }
                        val currentLine = lyricsView.getChildAt(index) as? LyricsLineView
                        currentLine?.followErrorMs = followErrorMs
                        currentLine?.updatePlaybackPosition(position)
                        // Both a sung line and a counting-dots line move between line changes.
                        onFrameClock = currentLine?.needsFrameClock == true
                    }
                }
                // A sung line is redrawn on the display's own clock rather than on a 16 ms timer,
                // which on a 120 Hz panel sampled the position twice for some frames and not at
                // all for others; between the two, a word could light up a frame late.
                //
                // Everything else waits until the next line is actually due rather than polling
                // blindly. At a flat 200 ms a line could light up that far after it was sung -
                // which is the lag, and it is worst on the short lines where it is most obvious.
                // Sleeping to the boundary lands on time without sampling any more often: between
                // lines it now waits *longer* than the old poll, not less.
                // Nothing moves while paused, so nothing needs redrawing at the display's rate
                // either; a modest poll still lands a scrub on the right line.
                //
                // The sleep is measured against the same offset-adjusted position the highlight
                // uses. Measuring it against the raw one, as it used to, meant the loop woke when
                // the line was due at the *speaker* and then had to wait a whole frame more.
                if (onFrameClock && isPlayingProvider()) {
                    awaitFrame()
                } else if (!isPlayingProvider()) {
                    delay(PAUSED_POLL_MS)
                } else {
                    delay(waitForNextLineMs(position))
                }
            }
        }

        this.applyPending = { newLyrics ->
            lyricsView.update(newLyrics)
            updateOnLayout()
        }
        pendingLyrics?.let { applyPending?.invoke(it); pendingLyrics = null }
    }

    /** Hooked up once the view exists; before that, lyrics arriving are held in [pendingLyrics]. */
    private var applyPending: ((Lyrics) -> Unit)? = null
    private var pendingLyrics: Lyrics? = null

    /**
     * Replaces what is on screen. [Lyrics.Empty] hides the view's content.
     *
     * The counting dots are inserted here rather than by the view, because the followed line is
     * an index into this list: the two have to be looking at the same set of lines.
     */
    fun setLyrics(newLyrics: Lyrics) {
        LyricsSyncProbe.reset()
        // A new sheet means a new track, and the clock must land on it rather than glide there.
        resync()
        val withInterludes = newLyrics.withInterludes()
        lyrics.value = withInterludes
        val apply = applyPending
        if (apply == null) pendingLyrics = withInterludes else apply(withInterludes)
    }

    /**
     * Stops following the player and drops every reference to the views.
     *
     * The position poll is an endless loop of delays on the main handler, each one holding the
     * lyrics view - and through it the activity - until it runs. This existed already and nothing
     * ever called it, so leaving the screen left the whole thing pinned in memory.
     */
    fun release() {
        lyricsLayoutGeneration++
        entranceAnimator?.removeAllListeners()
        entranceAnimator?.cancel()
        entranceAnimator = null
        scope.cancel()
        applyPending = null
        pendingLyrics = null
    }

    private fun getCurrentLyricsLineIndex(position: Long): Int {
        val currentLyrics = lyrics.value
        val line = currentLyrics.lyrics.indexOfLast { it.timestamp <= position }
        return if (line != -1) line else 0
    }

    /** The song position the lyrics are drawn against, including the user's timing offset. */
    private fun playbackPosition(): Long = rawPlaybackPosition() + offsetMs

    /**
     * A smooth song clock, followed from a player position that is measurably not smooth.
     *
     * Measured on device with the lyric sheet open, by comparing the playback position the session
     * publishes against the session's own timestamps:
     *
     * ```
     * dPos=3123  dUpd=3005  err=+118ms  (+3.93%)
     * dPos=3140  dUpd=3014  err=+126ms  (+4.18%)
     * dPos=3147  dUpd=3012  err=+135ms  (+4.48%)
     * dPos=4189  dUpd=5115  err=-926ms  (-18.1%)
     * ```
     *
     * The position climbs about 4% faster than wall time for twenty-odd seconds, then loses most
     * of a second in a single step when media3 re-synchronises against the audio track's own
     * timestamp. Over a minute the average rate is right to within 0.2%, so nothing is wrong with
     * the *playback* - it is the reported position that sawtooths, and it does so identically with
     * this app's Automix, ReplayGain, silence-skipping and float output all disabled. Reading it
     * straight, which is what the previous version of this method did on purpose, draws that
     * sawtooth onto the words: a lurch forward every three seconds, and a jump back onto a word
     * already sung every twenty-five. Those are precisely the two complaints.
     *
     * Three earlier versions of this method tried to *filter* that, continuously, and all three
     * were wrong for the same reason. Worth recording, because the reason is the whole design.
     *
     * They assumed the position is a little wrong all the time. It is not. Traced on device, **84
     * of 88 words lit within about 20 ms of their timestamp** - the position is right almost
     * always, and then occasionally catastrophically wrong for a single reading. A continuous
     * filter is the wrong instrument for a rare outlier: it can only follow the outlier, which is
     * a jump, or absorb it, which is a stall. Resisting produced a standing lag that saturated at
     * its own rate limit (1012 ms of song per 1007 ms of wall time, for a minute). Absorbing
     * produced the opposite: a measured `song=+508ms wall=1015ms`, the highlight crawling at half
     * speed for over a second to pay a correction back. A stall reads worse than the jump it
     * replaced, because it lasts longer.
     *
     * So this one does not filter. It **rejects**, which is what an outlier deserves:
     *
     * - A reading within [AGREEMENT_MS] of the clock is ordinary jitter. Take it outright. No lag,
     *   nothing held back, nothing to pay off later. On a healthy device this is nearly every one.
     * - A reading further away is not jitter, it is an event, and it has to prove itself. Hold it,
     *   keep free-running, and draw nothing of it. If the *next* reading agrees with it, the change
     *   was real - a seek, or the audio clock genuinely re-synchronising - and it is taken at once,
     *   as a jump, because a real change should arrive rather than be eased in. If the next reading
     *   agrees with the clock instead, the odd one was a glitch and was never drawn at all.
     *
     * A real change therefore costs one extra sample - 8 ms at 120 Hz, 16 ms at 60 Hz - and a
     * glitch costs nothing. Critically this is independent of how *large* the error is, which is
     * why it covers both devices: one sees ~900 ms corrections, the other a 3.3 s discontinuity,
     * and neither reaches the screen.
     *
     * A seek or track change still calls [resync] rather than waiting to be noticed. Between
     * readings the clock free-runs at the playback rate, which is what carries the words for a
     * source like Cast that reports about once a second and holds the value in between.
     */
    private fun rawPlaybackPosition(): Long {
        val reported = positionProvider()
        val now = SystemClock.elapsedRealtime()

        if (!following || !isPlayingProvider()) {
            // While paused the position is the truth and is not moving, and taking it outright is
            // also what lands a scrub on the right line.
            anchorTo(reported, now)
            return reported
        }

        val wallMs = (now - followerRealtimeMs).coerceIn(0L, MAX_FREE_RUN_MS)
        followerRealtimeMs = now

        // What the position would be if it had simply kept playing. This is also what carries the
        // words between updates from a source that only reports about once a second, like Cast.
        followerMs += wallMs * speedProvider()

        if (reported != lastReportedPositionMs) {
            lastReportedPositionMs = reported
            val disagreement = reported - followerMs

            if (abs(disagreement) <= AGREEMENT_MS) {
                // The normal case, and on a good device it is 95% of readings. Take it outright:
                // no lag, no residual, nothing to pay back later.
                followerMs = reported.toDouble()
                pendingCorrectionMs = null
            } else {
                val pending = pendingCorrectionMs
                if (pending != null && abs(reported - pending) <= AGREEMENT_MS) {
                    // Two readings in a row agree on the new value, so the change is real - a seek,
                    // or the audio clock genuinely re-synchronising. Take it at once and as a jump:
                    // a real change should arrive, not be eased in over the next second.
                    followerMs = reported.toDouble()
                    pendingCorrectionMs = null
                } else {
                    // A single reading that disagrees with a clock that has been right until now.
                    // Hold it and keep free-running. If the next reading corroborates it we accept
                    // it above; if it does not, this one was a glitch and is never drawn.
                    pendingCorrectionMs = reported
                }
            }
        }

        followErrorMs = (lastReportedPositionMs - followerMs).toLong()
        return followerMs.toLong()
    }

    /** Puts the clock exactly where the player says it is. For a seek, or a track change. */
    fun resync() {
        following = false
    }

    private fun anchorTo(positionMs: Long, realtimeMs: Long) {
        followErrorMs = 0L
        pendingCorrectionMs = null
        followerMs = positionMs.toDouble()
        followerRealtimeMs = realtimeMs
        lastReportedPositionMs = positionMs
        following = true
    }

    /**
     * How far the smoothed clock currently sits from what the player reports, for [LyricsSyncProbe].
     *
     * This is the outstanding residual, so in the steady state it is zero and only moves while a
     * correction is being paid back. A number that sits large and one-signed means the smoothing
     * has become a standing lag, which is the failure both earlier versions had and the reason
     * this is reported per frame rather than inferred.
     */
    @Volatile
    var followErrorMs: Long = 0L
        private set

    /** Song time in flight, as a double so a per-frame trim of a millisecond is not lost to rounding. */
    private var followerMs = 0.0
    private var followerRealtimeMs = 0L
    private var following = false

    /**
     * A reading that disagreed with the clock and has not been corroborated yet.
     *
     * Nothing is drawn from it until a second reading agrees. See [rawPlaybackPosition].
     */
    private var pendingCorrectionMs: Long? = null

    /** The last value the player handed back, so a held reading is not mistaken for evidence. */
    private var lastReportedPositionMs = Long.MIN_VALUE

    /**
     * How far ahead of the clock the lyrics are drawn, in milliseconds.
     *
     * A player reports where playback *is*, not where it is being *heard*. media3's position
     * already accounts for what the audio track has rendered, but not for the mixer, the DSP or -
     * by far the largest of the three - a Bluetooth link, which alone runs from about 40 ms on
     * aptX Low Latency to well past 250 ms on SBC. There is no single right number, which is why
     * every player that gets this right makes it a setting rather than a constant, and why this
     * one does now.
     */
    private var offsetMs = readOffsetMs()

    /** Picks up a changed [PREF_LYRICS_OFFSET_MS] without needing the view rebuilt. */
    fun refreshOffset() {
        offsetMs = readOffsetMs()
    }

    private fun readOffsetMs(): Long =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_LYRICS_OFFSET_MS, DEFAULT_LYRICS_OFFSET_MS)
            .toLong()

    /**
     * How long to sleep before the next line is due, bounded by [POSITION_POLL_MS].
     *
     * Bounded in both directions on purpose: never longer than the old poll, so a seek or a track
     * change is still noticed promptly, and never zero, so a position that has stopped advancing
     * cannot spin this loop on the main thread.
     */
    private fun waitForNextLineMs(position: Long): Long {
        val lines = lyrics.value.lyrics
        val next = lines.firstOrNull { it.timestamp > position } ?: return POSITION_POLL_MS
        return (next.timestamp - position).coerceIn(MIN_POLL_MS, POSITION_POLL_MS)
    }

    /** Suspends until the next display frame, so the sung line is redrawn once per refresh. */
    private suspend fun awaitFrame(): Long = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { frameTimeNanos ->
            continuation.resumeWith(Result.success(frameTimeNanos))
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    companion object {
        private const val INITIAL_CONTEXT_LINES = 2
        private const val INITIAL_SETTLE_DURATION_MS = 320L
        private val INITIAL_SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** Fast enough that a line change is not visibly late, cheap enough to leave running. */
        private const val POSITION_POLL_MS = 200L

        /** Floor on the sleep above, so a stalled clock cannot busy-loop the main thread. */
        private const val MIN_POLL_MS = 8L

        /** How often a paused player is checked, so scrubbing still moves the lyrics. */
        private const val PAUSED_POLL_MS = 150L

        /**
         * The longest the clock runs on wall time alone in one step.
         *
         * The loop is normally woken every frame, so this only bites when it was not - the screen
         * went off, the app was backgrounded, the thread was starved. Whatever the gap was, the
         * reading that comes back after it is checked against [RESYNC_TOLERANCE_MS] and taken
         * outright if it has really moved on, so clamping here loses nothing.
         */
        private const val MAX_FREE_RUN_MS = 250L

        /**
         * How far a reading may sit from the clock and still count as ordinary agreement.
         *
         * Sized from what the position does when it is behaving: 84 of 88 traced words lit within
         * about 20 ms of their timestamp, so a disagreement of this size is jitter and deserves
         * nothing more than taking the new value. Anything larger is an event, and events have to
         * be corroborated before they reach the screen.
         */
        private const val AGREEMENT_MS = 60.0

        /**
         * Past this, a disagreement is a real jump rather than the sawtooth, and is taken at once.
         *
         * Comfortably above the ~900 ms correction measured on device and below any seek a person
         * makes on purpose - and seeks announce themselves through [resync] anyway, so this only
         * has to catch the ones nothing announced.
         */
        private const val RESYNC_TOLERANCE_MS = 1_200.0


        /** Reads and writes the same key as the Appearance settings slider. */
        const val PREF_LYRICS_OFFSET_MS = "lyrics_offset_ms"

        /**
         * Zero, because the clock it is added to is now honest.
         *
         * The 150 ms this shipped with was calibrated by ear while the position sawtoothed up to
         * 900 ms ahead of the audio, so it was correcting an average of a moving error rather than
         * a fixed one. [rawPlaybackPosition] now tracks the end of that sawtooth which media3
         * itself trusts - `AudioTrack.getTimestamp()`, the DAC's own presentation time - so what
         * is left to compensate is only whatever output latency sits past the timestamp, which is
         * tens of milliseconds on a wired route and a couple of hundred over Bluetooth.
         *
         * Positive moves the words *earlier* relative to the sound. Raise it if the highlight
         * lands late, lower it if it lands early.
         */
        const val DEFAULT_LYRICS_OFFSET_MS = 0
    }
}
