package org.akanework.gramophone.logic.data.automix

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Executes an analysed transition with one stable queue player and one outgoing ghost player.
 *
 * The session player remains the only queue owner and the only player visible outside this class.
 * Shortly before the planned handover, [ghost] opens the current item through the same cached media
 * path and synchronises silently with it. A 90 ms lap transfers the outgoing track to that ghost;
 * the queue player can then seek directly to the next item's analysed cue while the old song keeps
 * sounding independently. Once the incoming decoder is ready, the two players run the plan's gain
 * curve in opposite directions.
 *
 * The property worth having, and the reason for the shape: queue index, lyrics, artwork,
 * notification and scrobbling all move with the stable player at the moment the incoming song is
 * introduced. The ghost owns one tail only, never receives user commands, never requests audio
 * focus, and can be discarded without repairing queue state.
 *
 * **On the BitChord attribution this comment used to carry.** kushagrasinghx/BitChord (GPL-3.0,
 * verified against the GitHub API on 2026-09-02) does share the property above, and its
 * `CrossfadeController` is where the idea of getting it from two players came from. It does not
 * share this structure. BitChord puts the *incoming* track on its second player and swaps which
 * player backs the session; it describes putting the **outgoing** track there - what happens
 * below - as an earlier design it abandoned, because a lap makes two ExoPlayers render the same
 * audio at once and they cannot be started sample-accurately against each other. It measured that
 * misalignment at 9 to 41 ms and heard it as the last instant of the outgoing track playing twice
 * at the head of every transition.
 *
 * Which means [SYNC_TOLERANCE_MS], [learnedSeekLeadMs] and the whole arming loop are this design
 * fighting a problem the other one does not have. They may well be enough - nothing here has been
 * heard yet - but if the first listen finds a flam or a comb filter at the start of every mix, that
 * is what it is, and the answer is the peer-player swap rather than a tighter tolerance. See the
 * Automix notes in TODO.md.
 */
class AutomixTransitions(
    private val context: Context,
    private val handler: Handler,
    /** Builds the single-item tail player lazily. It is retained warm between transitions. */
    private val newGhost: () -> ExoPlayer?,
    /** Refreshes settings that must describe the outgoing item before the ghost prepares it. */
    private val beforeGhostPrepare: () -> Unit = {},
    /** Mixing is deliberately disabled while Android grants a bit-perfect USB route. */
    private val bitPerfectOutput: () -> Boolean = { false },
    /**
     * Sweeps the low end out of the ghost's tail, in Hz and fade progress.
     *
     * The filter lives on the ghost's own audio chain because that is the only stream it may touch;
     * this class owns *when* it moves, which is a musical decision, and the processor owns how it
     * gets there without stepping. See [BassSwapAudioProcessor].
     */
    private val ghostBassCut: (cutoffHz: Float, progress: Float) -> Unit = { _, _ -> },
) {

    private enum class Phase { IDLE, ARMING, LAPPING, FADING, BAILING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val structureAnalyzer: StructureAnalyzer = EnergyStructureAnalyzer()
    private val prepareLeadMs = PREPARE_LEAD_MS

    /**
     * Tail analyses already worked out, newest use first.
     *
     * Replaying a track re-decodes ninety seconds of it to arrive at the same numbers, which is
     * about a second of work and a burst of network on a cold cache. Only the answers are kept -
     * the PCM they came from is tens of megabytes and is never worth holding on to.
     *
     * Keyed by duration as well as id because the duration is what places the window: the same
     * track transcoded to a different length is not the same ninety seconds.
     */
    private val tailCache = object : LinkedHashMap<String, Tail>(WINDOW_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tail>): Boolean =
            size > WINDOW_CACHE_ENTRIES
    }

    /**
     * Blend-window analyses of tracks mixed *into*, on the same terms as [tailCache].
     *
     * Separate rather than a second kind of entry in one map because the two windows answer to
     * different keys - see [headOf] - and a shared map would have to carry the more careful of the
     * two, keying a window that starts at zero by a duration that does not place it.
     */
    private val headCache =
        object : LinkedHashMap<String, AnalysedTrack>(WINDOW_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, AnalysedTrack>,
            ): Boolean = size > WINDOW_CACHE_ENTRIES
        }

    private var preparedFor: String? = null
    private var prepared: Prepared? = null
    private var scheduled: Runnable? = null
    private var tickScheduled = false
    private var released = false

    private var phase = Phase.IDLE
    private var transitionPlayer: Player? = null
    private var ghost: ExoPlayer? = null
    private var firing = false
    private var autoAdvance = false
    private var selfMoveUntil = 0L

    private var armDeadlineMs = 0L
    private var lastSyncAtMs = 0L
    private var learnedSeekLeadMs = DEFAULT_SEEK_LEAD_MS
    private var lapStartedAtMs = 0L
    private var bailStartedAtMs = 0L
    private var beganAtMs = 0L
    private var baseVolume = 1f
    private var basePlaybackParameters = PlaybackParameters.DEFAULT
    private var bailMainFrom = 0f
    private var bailGhostFrom = 0f

    private data class Prepared(
        val plan: TransitionPlan,
        val outgoingId: String,
        val incomingId: String,
    )

    /** Everything the end of a track has to say, from the one decode that reads it. */
    private data class Tail(val analysis: AnalysedTrack, val structure: TrackStructure?)

    private val ghostListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Outgoing ghost failed", error)
            handler.post { bail("the outgoing ghost failed") }
        }
    }

    /** True while the queue or ghost is involved in a handover. */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    /**
     * Whether the transition arriving at the service is our queue advance rather than a user skip.
     * Consumed once so reporting and scrobbling can treat it like a naturally completed track.
     */
    fun consumeAutoAdvance(): Boolean = autoAdvance.also { autoAdvance = false }

    /**
     * Considers preparing a transition. The service heartbeat is intentionally coarse; once a
     * plan exists, [schedule] and [tick] take over with millisecond-scale timing.
     */
    fun onProgress(
        player: Player,
        current: MediaItem?,
        next: MediaItem?,
        positionMs: Long,
        durationMs: Long,
        remoteOutput: Boolean,
    ) {
        if (released) return
        val outgoingId = current?.mediaId?.takeIf(String::isNotEmpty)
            ?: return skip { "no current track" }
        val incomingId = next?.mediaId?.takeIf(String::isNotEmpty)
            ?: return skip { "no next track" }
        if (remoteOutput) return cancel("a remote output owns playback")
        if (bitPerfectOutput()) return cancel("bit-perfect USB output is active")
        if (!Automix.isEnabled(context)) return cancel("Automix is switched off")
        if (durationMs <= 0L || durationMs == C.TIME_UNSET) {
            return cancel("the current track's duration is unknown")
        }

        if (preparedFor == outgoingId) {
            prepared?.let { schedule(player, it, positionMs) }
            return
        }
        if (phase != Phase.IDLE) return
        val remaining = durationMs - positionMs
        if (remaining > prepareLeadMs) {
            return skip { "${remaining}ms left, preparing under $prepareLeadMs" }
        }

        preparedFor = outgoingId
        val uri = current.localConfiguration?.uri ?: return skip { "current track has no uri" }
        scope.launch {
            val ready = prepare(outgoingId, next, uri, durationMs)
            handler.post {
                if (released || preparedFor != outgoingId) return@post
                prepared = ready
                if (ready != null) schedule(player, ready, player.currentPosition)
            }
        }
    }

    private fun prepare(
        outgoingId: String,
        incomingItem: MediaItem,
        uri: Uri,
        durationMs: Long,
    ): Prepared? {
        val incomingId = incomingItem.mediaId
        val stored = TrackAnalyzer.analyse(context, incomingItem)
        if (stored == null) {
            Log.d(TAG, "Not mixing: incoming $incomingId could not be analysed")
            return null
        }

        // The stored outgoing analysis describes the beginning of the song. Decode its tail once
        // so the tempo, grid and structure all describe the audio that will actually be mixed.
        val tail = tailOf(outgoingId, uri, durationMs) ?: return null
        val outgoing = tail.analysis
        val structure = tail.structure

        // And the same again at the other end. The stored incoming analysis averages two minutes;
        // the blend crosses the first fifteen seconds of them. Done after the tail on purpose - a
        // tail that cannot be read ends the preparation, and there is no sense decoding for a plan
        // that will not be made.
        val incoming = preferredIncomingAnalysis(stored, headOf(incomingId, incomingItem))
        if (incoming !== stored && abs(incoming.bpm - stored.bpm) > 0.25f) {
            // Worth a line of its own. A stretch this decides against is a transition that does not
            // happen, and the number it decided on came from a window nothing else in the log
            // mentions - so without this, a refusal reads as though the stored analysis produced it.
            Log.d(
                TAG,
                "Incoming $incomingId is %.2f BPM across the blend, %.2f BPM across the stored "
                    .format(incoming.bpm, stored.bpm) + "window",
            )
        }

        val style = Automix.style(context)
        val outcome = TransitionPlan.between(outgoing, incoming, durationMs, style, structure)
        val plan = when (outcome) {
            is TransitionPlan.Outcome.Mixable -> outcome.plan
            is TransitionPlan.Outcome.Declined -> {
                Log.d(TAG, "Not mixing $outgoingId into $incomingId: ${outcome.reason}")
                return null
            }
        }
        Log.d(
            TAG,
            "Prepared $style ghost transition: lap at ${plan.handoverMs}ms, " +
                "fade ${plan.overlapMs}ms, incoming cue ${plan.cueMs}ms, " +
                "fade end ${plan.resumeAtMs}ms, speed ${plan.speed}",
        )
        return Prepared(plan, outgoingId, incomingId)
    }

    /**
     * The tail analysis for [outgoingId], from the cache or from one decode.
     *
     * Every failure says which one it was at debug rather than at verbose. A preparation that runs
     * and produces nothing is the shape a broken transition has, and a silent return here reads
     * from a log exactly like a transition that was never attempted.
     */
    private fun tailOf(outgoingId: String, uri: Uri, durationMs: Long): Tail? {
        val key = "$outgoingId@$durationMs"
        synchronized(tailCache) { tailCache[key] }?.let {
            Log.d(TAG, "Reusing the cached tail analysis of $outgoingId")
            return it
        }

        val tailStartMs = (durationMs - WindowAnalyzer.TAIL_SECONDS * 1000L).coerceAtLeast(0L)
        val pcm = PcmDecoder.decode(context, uri, tailStartMs, durationMs - tailStartMs)
        if (pcm == null) {
            Log.d(TAG, "Not mixing: could not decode the tail of $outgoingId")
            return null
        }
        val analysis = WindowAnalyzer.analyse(pcm, pcm.startMs, outgoingId)
        if (analysis == null) {
            Log.d(TAG, "Not mixing: the tail of $outgoingId has no usable grid")
            return null
        }
        val structure = structureAnalyzer.analyse(pcm)
        Log.d(
            TAG,
            if (structure == null) "Tail of $outgoingId has no readable structure"
            else "Tail structure of $outgoingId: last drop ${structure.lastDropMs}ms, " +
                "outro ${structure.outroStartMs}ms",
        )
        return Tail(analysis, structure).also {
            synchronized(tailCache) { tailCache[key] = it }
        }
    }

    /**
     * The blend-window analysis for [incomingId], from the cache or from one decode.
     *
     * `null` is not a refusal here, unlike [tailOf]: the caller falls back to the stored analysis,
     * which is what every transition used before this window existed. Only the tail is load-bearing
     * enough that failing to read it should end the preparation.
     *
     * Keyed by media id alone, without the duration [tailOf] needs. The tail window is *placed* by
     * the duration, so the same track at a different length is a different ninety seconds; this
     * window starts at zero however long the track is.
     */
    private fun headOf(incomingId: String, item: MediaItem): AnalysedTrack? {
        synchronized(headCache) { headCache[incomingId] }?.let {
            Log.d(TAG, "Reusing the cached blend-window analysis of $incomingId")
            return it
        }

        val uri = item.localConfiguration?.uri ?: run {
            Log.d(TAG, "Incoming $incomingId has no uri; using its stored analysis")
            return null
        }
        val pcm = PcmDecoder.decode(context, uri, 0L, WindowAnalyzer.HEAD_SECONDS * 1000L) ?: run {
            Log.d(TAG, "Could not decode the head of $incomingId; using its stored analysis")
            return null
        }
        val analysis = WindowAnalyzer.analyse(pcm, pcm.startMs, incomingId) ?: run {
            Log.d(TAG, "The head of $incomingId has no usable grid; using its stored analysis")
            return null
        }
        return analysis.also { synchronized(headCache) { headCache[incomingId] = it } }
    }

    /** Arms the ghost two seconds before the planned lap, or immediately if preparation ran late. */
    private fun schedule(player: Player, ready: Prepared, positionMs: Long) {
        if (scheduled != null || phase != Phase.IDLE) return
        val untilHandover = ready.plan.handoverMs - positionMs
        if (untilHandover < 0L) {
            Log.d(TAG, "Missed the handover for ${ready.outgoingId}: it was ${-untilHandover}ms ago")
            clearPrepared()
            return
        }
        val delay = (untilHandover - ARM_LEAD_MS).coerceAtLeast(0L)
        val run = Runnable {
            scheduled = null
            begin(player, ready)
        }
        scheduled = run
        handler.postDelayed(run, delay)
    }

    private fun begin(player: Player, ready: Prepared) {
        if (released || phase != Phase.IDLE) return
        if (!stillMatches(player, ready)) return cancel("the queue changed before arming")
        if (!player.playWhenReady) return cancel("playback was paused before arming")
        if (!Automix.isEnabled(context) || bitPerfectOutput()) {
            return cancel("mixing is no longer allowed")
        }

        val tail = warmGhost() ?: return cancel("the outgoing ghost could not be built")
        transitionPlayer = player
        baseVolume = player.volume
        basePlaybackParameters = player.playbackParameters
        armDeadlineMs = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        lastSyncAtMs = 0L
        beganAtMs = SystemClock.elapsedRealtime()

        beforeGhostPrepare()
        // Target now, progress zero: the processor is configured as the ghost prepares, and a
        // cutoff arriving after the first buffers would be a step rather than the start of a sweep.
        ghostBassCut(ready.plan.style.bassCutHz, 0f)
        tail.skipSilenceEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("skip_silence", false)
        tail.playbackParameters = player.playbackParameters
        tail.setMediaItem(player.currentMediaItem ?: return cancel("the outgoing item disappeared"))
        tail.seekTo(player.currentPosition + learnedSeekLeadMs)
        tail.volume = 0f
        tail.playWhenReady = true
        tail.prepare()

        phase = Phase.ARMING
        Log.d(
            TAG,
            "Arming ghost for ${ready.outgoingId}->${ready.incomingId} at " +
                "${player.currentPosition}ms (lead ${learnedSeekLeadMs}ms)",
        )
        scheduleTick(0L)
    }

    private fun tick() {
        tickScheduled = false
        if (released || phase == Phase.IDLE) return
        val player = transitionPlayer
        if (player == null) {
            finish()
            return
        }

        if (phase == Phase.ARMING || phase == Phase.LAPPING || phase == Phase.FADING) {
            ghost?.playWhenReady = player.playWhenReady
        }
        when (phase) {
            Phase.IDLE -> return
            Phase.ARMING -> driveArming(player)
            Phase.LAPPING -> driveLap(player)
            Phase.FADING -> driveFade(player)
            Phase.BAILING -> driveBail(player)
        }
        if (phase != Phase.IDLE) scheduleTick(stepFor(phase))
    }

    private fun driveArming(player: Player) {
        val ready = prepared ?: return bail("the prepared transition disappeared")
        val tail = ghost ?: return bail("the outgoing ghost disappeared")
        if (!stillMatches(player, ready) || !Automix.isEnabled(context) || bitPerfectOutput()) {
            return bail("the transition is no longer valid")
        }

        val expired = SystemClock.elapsedRealtime() > armDeadlineMs
        val running = tail.isPlaying
        if (expired && !running) return bail("the outgoing ghost did not start")

        val driftMs = tail.currentPosition - player.currentPosition
        val aligned = running && abs(driftMs) <= SYNC_TOLERANCE_MS
        if (running && !aligned) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSyncAtMs >= SYNC_SETTLE_MS) {
                lastSyncAtMs = now
                learnedSeekLeadMs = (learnedSeekLeadMs - driftMs).coerceIn(0L, MAX_SEEK_LEAD_MS)
                tail.seekTo(player.currentPosition + learnedSeekLeadMs)
            }
        }

        if (player.currentPosition < ready.plan.handoverMs) return
        if (aligned || expired) {
            lapStartedAtMs = SystemClock.elapsedRealtime()
            phase = Phase.LAPPING
        }
    }

    /** Transfers the duplicate outgoing stream from the session player to the ghost. */
    private fun driveLap(player: Player) {
        val ready = prepared ?: return bail("the prepared transition disappeared during the lap")
        val tail = ghost ?: return bail("the outgoing ghost disappeared during the lap")
        if (!player.playWhenReady) return bail("playback paused during the lap")

        val progress = ((SystemClock.elapsedRealtime() - lapStartedAtMs).toFloat() / LAP_MS)
            .coerceIn(0f, 1f)
        player.volume = baseVolume * FadeCurve.LINEAR.gainAt(progress)
        tail.volume = baseVolume * FadeCurve.LINEAR.incomingGainAt(progress)
        if (progress < 1f) return

        tail.volume = baseVolume
        player.volume = 0f
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET || player.getMediaItemAt(nextIndex).mediaId != ready.incomingId) {
            return bail("the incoming item moved during the lap")
        }

        // From this line onward the stable session player owns the incoming song. The independent
        // ghost is what makes its decoder/AudioTrack setup time inaudible.
        firing = true
        autoAdvance = true
        selfMoveUntil = SystemClock.elapsedRealtime() + SELF_MOVE_WINDOW_MS
        player.seekTo(nextIndex, ready.plan.cueMs)
        player.playbackParameters = PlaybackParameters(
            basePlaybackParameters.speed * ready.plan.speed,
            basePlaybackParameters.pitch,
        )
        phase = Phase.FADING
        Log.d(TAG, "Queue handed to ${ready.incomingId} at cue ${ready.plan.cueMs}ms")
    }

    /** Fades the incoming queue player up against the independently playing outgoing tail. */
    private fun driveFade(player: Player) {
        val ready = prepared ?: return bail("the prepared transition disappeared during the fade")
        val tail = ghost ?: return bail("the outgoing ghost disappeared during the fade")
        if (!Automix.isEnabled(context) || bitPerfectOutput()) {
            return bail("mixing was disabled during the fade")
        }

        val progress = ghostFadeProgress(
            positionMs = player.currentPosition,
            cueMs = ready.plan.cueMs,
            fadeEndMs = ready.plan.resumeAtMs,
        )
        player.volume = baseVolume * ready.plan.style.fade.incomingGainAt(progress)
        tail.volume = baseVolume * ready.plan.style.fade.gainAt(progress)
        // The low end changes hands over the first half of the overlap, well before the levels do.
        // Waiting for the midpoint would leave both tracks with bass through the part of a mix
        // where they are most equally present, which is the part the swap exists for.
        ghostBassCut(ready.plan.style.bassCutHz, (progress * 2f).coerceAtMost(1f))

        if (
            progress >= 1f ||
            tail.playbackState == Player.STATE_ENDED ||
            tail.playbackState == Player.STATE_IDLE
        ) {
            finish()
        }
    }

    /** Smoothly removes a ghost if a skip, seek, route change or setting interrupts the blend. */
    private fun bail(reason: String) {
        if (phase == Phase.IDLE) {
            skip { reason }
            clearPrepared()
            return
        }
        // Past IDLE something is either about to be or already is audible, so an abandoned
        // transition is a thing that went wrong rather than a gate declining, and it is logged
        // where it can be seen without anyone having thought to turn verbose on first.
        Log.d(TAG, "Bailing out during $phase: $reason")
        if (phase == Phase.BAILING) return
        val player = transitionPlayer
        if (player == null) {
            finish()
            return
        }
        autoAdvance = false
        firing = false
        bailMainFrom = player.volume
        bailGhostFrom = ghost?.volume ?: 0f
        bailStartedAtMs = SystemClock.elapsedRealtime()
        phase = Phase.BAILING
        scheduleTick(0L)
    }

    private fun driveBail(player: Player) {
        val progress = ((SystemClock.elapsedRealtime() - bailStartedAtMs).toFloat() / BAIL_MS)
            .coerceIn(0f, 1f)
        player.volume = bailMainFrom + (baseVolume - bailMainFrom) * progress
        ghost?.volume = bailGhostFrom * FadeCurve.LINEAR.gainAt(progress)
        if (progress >= 1f) finish()
    }

    private fun finish() {
        if (beganAtMs != 0L) {
            val ready = prepared
            Log.d(
                TAG,
                "Transition ${ready?.outgoingId}->${ready?.incomingId} ended in $phase after " +
                    "${SystemClock.elapsedRealtime() - beganAtMs}ms",
            )
            beganAtMs = 0L
        }
        scheduled?.let(handler::removeCallbacks)
        scheduled = null
        handler.removeCallbacks(tickRunnable)
        tickScheduled = false
        transitionPlayer?.let { player ->
            player.volume = baseVolume
            player.playbackParameters = basePlaybackParameters
        }
        ghostBassCut(0f, 0f)
        ghost?.let { tail ->
            tail.volume = 0f
            tail.stop()
            tail.clearMediaItems()
        }
        transitionPlayer = null
        selfMoveUntil = 0L
        firing = false
        phase = Phase.IDLE
        clearPrepared()
    }

    private fun warmGhost(): ExoPlayer? {
        ghost?.let { return it }
        return runCatching { newGhost() }
            .onFailure { Log.w(TAG, "Could not build outgoing ghost", it) }
            .getOrNull()
            ?.also {
                it.addListener(ghostListener)
                ghost = it
            }
    }

    private fun stillMatches(player: Player, ready: Prepared): Boolean {
        if (player.currentMediaItem?.mediaId != ready.outgoingId) return false
        val nextIndex = player.nextMediaItemIndex
        return nextIndex != C.INDEX_UNSET &&
            player.getMediaItemAt(nextIndex).mediaId == ready.incomingId
    }

    private fun scheduleTick(delayMs: Long) {
        if (tickScheduled || released) return
        tickScheduled = true
        handler.postDelayed(tickRunnable, delayMs)
    }

    private val tickRunnable = Runnable(::tick)

    private fun stepFor(current: Phase): Long = when (current) {
        Phase.IDLE -> 0L
        Phase.ARMING -> ARM_STEP_MS
        Phase.LAPPING -> LAP_STEP_MS
        Phase.FADING -> FADE_STEP_MS
        Phase.BAILING -> BAIL_STEP_MS
    }

    /** Called for every real session-player transition. */
    fun onMediaItemTransition(reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (firing && SystemClock.elapsedRealtime() < selfMoveUntil) {
            firing = false
            return
        }
        if (phase != Phase.IDLE) bail("the queue moved independently") else clearPrepared()
    }

    /** A seek inside one item has no media-item callback, so it needs its own cancellation path. */
    fun onPositionDiscontinuity(reason: Int) {
        if (
            reason == Player.DISCONTINUITY_REASON_SEEK &&
            SystemClock.elapsedRealtime() >= selfMoveUntil &&
            phase != Phase.IDLE
        ) {
            bail("the playhead was moved")
        }
    }

    private inline fun skip(reason: () -> String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "Not transitioning: ${reason()}")
    }

    private fun cancel(reason: String) {
        skip { reason }
        cancel()
    }

    /** Drops preparation immediately and fades any audible ghost out safely. */
    fun cancel() {
        scheduled?.let(handler::removeCallbacks)
        scheduled = null
        if (phase == Phase.IDLE) clearPrepared() else bail("transition cancelled")
    }

    private fun clearPrepared() {
        preparedFor = null
        prepared = null
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        scheduled?.let(handler::removeCallbacks)
        scheduled = null
        handler.removeCallbacks(tickRunnable)
        tickScheduled = false
        transitionPlayer?.let {
            it.volume = baseVolume
            it.playbackParameters = basePlaybackParameters
        }
        ghostBassCut(0f, 0f)
        ghost?.removeListener(ghostListener)
        ghost?.release()
        ghost = null
        transitionPlayer = null
        clearPrepared()
        phase = Phase.IDLE
    }

    companion object {
        const val TAG = "AutomixTransition"

        /**
         * How long before the end of a track its transition is worked out.
         *
         * Preparation decodes and analyses the outgoing tail - [WindowAnalyzer.TAIL_SECONDS] of
         * audio that may not be cached yet - and, if the scheduler has not got there first, the
         * incoming track as well. The thirty seconds on top of the tail is that headroom.
         *
         * The incoming blend window costs nothing to add to that. [WindowAnalyzer.HEAD_SECONDS] is
         * a subset of the span the stored analysis already decoded through [JellyfinMediaCache], so
         * by the time preparation asks for it, it is the most reliably cache-warm audio in the app:
         * either the scheduler read it a couple of minutes ago, or the line above just did.
         *
         * [AutomixAnalysisScheduler] reads this to decide when the *incoming* analysis has to be
         * stored by, so the two leads cannot drift apart. They did: the analyser ran at 45 s while
         * preparation began at 120 s, which meant preparation analysed the incoming track itself
         * every time and the scheduler arrived afterwards to find the row already written.
         */
        const val PREPARE_LEAD_MS = WindowAnalyzer.TAIL_SECONDS * 1000L + 30_000L
        const val ARM_LEAD_MS = 2_000L
        const val ARM_TIMEOUT_MS = 4_000L
        const val SYNC_TOLERANCE_MS = 20L
        const val SYNC_SETTLE_MS = 150L
        const val DEFAULT_SEEK_LEAD_MS = 60L
        const val MAX_SEEK_LEAD_MS = 500L
        const val LAP_MS = 90L
        const val BAIL_MS = 120L
        const val SELF_MOVE_WINDOW_MS = 250L

        /**
         * How many window analyses of each kind to keep.
         *
         * Four covers going back and forth over the same couple of tracks, which is what a listener
         * skipping around actually does, and each entry is a beat grid rather than any audio.
         */
        const val WINDOW_CACHE_ENTRIES = 4
        const val ARM_STEP_MS = 40L
        const val LAP_STEP_MS = 10L
        const val FADE_STEP_MS = 30L
        const val BAIL_STEP_MS = 15L
    }
}

/** Progress in the incoming track's timeline, bounded for decoder startup and short files. */
internal fun ghostFadeProgress(positionMs: Long, cueMs: Long, fadeEndMs: Long): Float {
    val spanMs = (fadeEndMs - cueMs).coerceAtLeast(1L)
    val elapsedMs = (positionMs - cueMs).coerceAtLeast(0L)
    return (elapsedMs.toFloat() / spanMs).coerceIn(0f, 1f)
}
