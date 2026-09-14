package org.akanework.gramophone.logic.data.automix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack

/**
 * Decides when the track after this one gets analysed.
 *
 * Late on purpose. Analysing at the moment a track starts would mean analysing every track anybody
 * skips past, and doing it while the queue is most likely to change under it; analysing in the last
 * half-minute costs the same work for the transitions that actually happen. The lead time is
 * generous against the job, which is a decode of a couple of minutes of audio and well under a
 * second of DSP - what it is really covering is a cold cache on a slow connection.
 *
 * Driven by the playback service's existing ten-second heartbeat rather than by a timer of its own.
 * A second periodic wakeup for something that is allowed to be approximate is a battery cost with
 * nothing to show for it.
 */
object AutomixAnalysisScheduler {

    private const val TAG = "AutomixAnalysis"

    /**
     * Slack on top of [AutomixTransitions.PREPARE_LEAD_MS].
     *
     * Two heartbeats' worth. The heartbeat only looks every ten seconds, so a lead set exactly at
     * the preparation point would be caught anywhere in the ten seconds after it - which is to say,
     * reliably too late. One spare beat covers the granularity and the second covers a heartbeat
     * that arrives late because something else had the main thread.
     */
    private const val HEARTBEAT_SLACK_MS = 20_000L

    /**
     * How close to the end of the current track the analysis starts.
     *
     * Derived rather than chosen. What the analysis is *for* is the transition out of this track,
     * and [AutomixTransitions] starts working that out [AutomixTransitions.PREPARE_LEAD_MS] before
     * the end - so an analysis that begins after that point has missed the thing it exists to feed.
     *
     * It was 45 s against a preparation lead of 120 s, and the consequence was quiet rather than
     * loud: preparation found no stored row, analysed the incoming track itself on its own thread,
     * and the scheduler arrived a minute later to find the work already done. Everything worked and
     * the scheduler did nothing, which is the hardest kind of dead code to notice.
     *
     * The cost of the earlier lead is analysing tracks that get skipped past. That was the original
     * argument for being late, and it is worth less than it looks: the analysis is only ever of the
     * *next* track, it is skipped entirely on a metered connection, and a stored row is permanent -
     * a track analysed for a transition that never happened is analysed once, ever.
     */
    private const val LEAD_MS = AutomixTransitions.PREPARE_LEAD_MS + HEARTBEAT_SLACK_MS

    /**
     * How early to give up on a track that is nearly over.
     *
     * Starting an analysis with five seconds left means finishing it after the transition it was
     * for, having spent the decode for nothing.
     */
    private const val TOO_LATE_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running: Job? = null

    /** The media id [running] is working on, so the same track is not queued twice. */
    @Volatile
    private var runningId: String? = null

    /**
     * Considers analysing [next] given where the current track has got to.
     *
     * Safe to call as often as the caller likes: everything that would make this wasteful - Automix
     * off, a remote output, an analysis already stored, one already running, a metered connection -
     * is checked here and returns without doing anything.
     */
    fun onProgress(
        context: Context,
        next: MediaItem?,
        positionMs: Long,
        durationMs: Long,
        remoteOutput: Boolean,
    ) {
        if (next == null || durationMs <= 0L) return skip { "no next track, or its duration is unknown" }
        if (!Automix.isEnabled(context)) return skip { "Automix is switched off" }
        // A Cast receiver or another Jellyfin client decodes its own audio; this phone will not be
        // mixing anything, so there is nothing for an analysis to be for.
        if (remoteOutput) return skip { "a remote output owns playback" }

        val remaining = durationMs - positionMs
        if (remaining > LEAD_MS) return skip { "${remaining}ms left, waiting until $LEAD_MS" }
        // Nothing special is done for a track shorter than the lead: its first heartbeat is already
        // inside the window, so its successor is analysed from the start. That is correct - a short
        // track reaches its transition sooner, not later.

        if (remaining < TOO_LATE_MS) return skip { "only ${remaining}ms left, too late to finish" }

        val mediaId = next.mediaId.takeIf { it.isNotEmpty() } ?: return skip { "next track has no id" }
        if (runningId == mediaId) return skip { "$mediaId is already being analysed" }
        if (running?.isActive == true) return skip { "another analysis is still running" }

        // Prefetching audio is already refused on a metered connection, and analysis wants far more
        // of the track than a prefetch does. Spending someone's mobile data on a smoother
        // transition is not a trade worth making on their behalf.
        if (!isUnmetered(context)) return skip { "the connection is metered" }

        val appContext = context.applicationContext
        runningId = mediaId
        running = scope.launch {
            try {
                TrackAnalyzer.analyse(appContext, next)
            } catch (t: Throwable) {
                Log.d(TAG, "Analysis of $mediaId failed: $t")
            } finally {
                runningId = null
            }
        }
    }

    /**
     * Says why nothing happened, when anyone is listening.
     *
     * Every gate above declines silently, which means a correctly working Automix and a broken one
     * are indistinguishable from outside the process - a Cast session quietly suppressing every
     * analysis cost an afternoon to spot. This runs on the ten-second heartbeat, so it must stay
     * off by default; verbose is off unless asked for, and the message is not built until it is:
     *
     *     adb shell setprop log.tag.AutomixAnalysis VERBOSE
     */
    private inline fun skip(reason: () -> String) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "Not analysing: ${reason()}")
    }

    /**
     * Drops rows written by an analyser that is no longer this one.
     *
     * Called once at startup. Those rows can never be read - every query asks for the current
     * version - so this is housekeeping, not correctness, and it costs one statement.
     */
    fun pruneStaleAnalyses(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                AppDatabase.getInstance(appContext)
                    .analysedTrackDao()
                    .deleteOtherVersions(AnalysedTrack.ANALYSER_VERSION)
            }.onFailure { Log.d(TAG, "Could not prune old analyses: $it") }
        }
    }

    private fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
