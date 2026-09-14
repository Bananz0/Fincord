package org.akanework.gramophone.logic.data.playcounts

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.CachedSong
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore

/**
 * Runs an import end to end: fetch, plan, and - once the user has agreed to the plan - write.
 *
 * Fetching and writing are separate calls on purpose. An import edits numbers on a server the user
 * cannot easily inspect and cannot undo, so nothing is written until they have seen what it would
 * do.
 */
class PlayCountImporter(private val context: Context) {

    sealed interface Progress {
        data object Reading : Progress
        data class Fetching(val fetched: Int, val total: Int) : Progress
        data object Matching : Progress
        data class Writing(val written: Int, val total: Int) : Progress
    }

    sealed interface Failure {
        /** Last.fm needs a username before any history can be read. */
        data object NotLinked : Failure
        data class Network(val message: String) : Failure
        data object EmptyLibrary : Failure
        /** The chosen file held nothing this source's parsers recognised. */
        data object UnrecognisedArchive : Failure
        data class UnreadableArchive(val message: String) : Failure
    }

    sealed interface PlanResult {
        data class Ready(val plan: ImportPlan) : PlanResult
        data class Failed(val reason: Failure) : PlanResult
    }

    /**
     * Reads a source and works out what it would change. Writes nothing.
     *
     * Network sources are asked only for scrobbles newer than the watermark the last run recorded,
     * so a repeat costs one page rather than the whole history. Passing [full] re-reads everything,
     * which is what to do after the ledger has been cleared or when a match rate was poor enough
     * that the user has retagged their library and wants another pass.
     */
    suspend fun plan(
        source: PlayCountSource,
        full: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): PlanResult = withContext(Dispatchers.IO) {
        val library = AppDatabase.getInstance(context).cachedSongDao().getAll()
        if (library.isEmpty()) return@withContext PlanResult.Failed(Failure.EmptyLibrary)

        when (source) {
            PlayCountSource.LAST_FM -> planLastFm(library, full, onProgress)
            // Archive sources cannot be read without being handed a file; the screen asks for one
            // and calls planArchive instead.
            else -> PlanResult.Failed(Failure.UnrecognisedArchive)
        }
    }

    /**
     * Reads an export the user has picked and works out what it would change. Writes nothing.
     *
     * An archive is always the service's whole history to date, so its tally replaces this source's
     * previous share rather than adding to it - importing a newer export must revise what the older
     * one said, not stack on top of it. [PlayCountPlanner] handles that from the source's kind.
     */
    suspend fun planArchive(
        source: PlayCountSource,
        uri: Uri,
        onProgress: (Progress) -> Unit = {},
    ): PlanResult = withContext(Dispatchers.IO) {
        val library = AppDatabase.getInstance(context).cachedSongDao().getAll()
        if (library.isEmpty()) return@withContext PlanResult.Failed(Failure.EmptyLibrary)

        onProgress(Progress.Reading)
        val outcome = PlayHistoryArchive.read(context, uri, source)
        val parsed = when (outcome) {
            is PlayHistoryArchive.Outcome.Unrecognised ->
                return@withContext PlanResult.Failed(Failure.UnrecognisedArchive)
            is PlayHistoryArchive.Outcome.Unreadable ->
                return@withContext PlanResult.Failed(Failure.UnreadableArchive(outcome.message))
            is PlayHistoryArchive.Outcome.Success -> outcome.parsed
        }

        onProgress(Progress.Matching)
        val plan = PlayCountPlanner(context).planAggregated(
            source = source,
            tracks = parsed.tracks,
            library = library,
        )
        PlanResult.Ready(plan)
    }

    private suspend fun planLastFm(
        library: List<CachedSong>,
        full: Boolean,
        onProgress: (Progress) -> Unit,
    ): PlanResult {
        val store = LastFmCredentialStore(context)
        val username = store.username?.takeIf { it.isNotBlank() }
            ?: return PlanResult.Failed(Failure.NotLinked)
        val client = store.buildClient() ?: return PlanResult.Failed(Failure.NotLinked)

        val dao = AppDatabase.getInstance(context).importContributionDao()
        val from = if (full) null else dao.watermarkFor(PlayCountSource.LAST_FM.id)

        // Held in memory rather than streamed straight into the tally because the planner needs to
        // walk them once and a plan has to exist before anything is written. A scrobble is a few
        // dozen bytes; even a very large history stays well inside what a phone can hold, and the
        // page callback keeps the JSON itself from accumulating.
        val plays = mutableListOf<TimedPlay>()
        try {
            client.getRecentTracks(
                username = username,
                fromSeconds = from,
                onProgress = { fetched, total -> onProgress(Progress.Fetching(fetched, total)) },
            ) { page ->
                page.forEach { timed ->
                    plays += TimedPlay(
                        artist = timed.track.artist,
                        title = timed.track.title,
                        album = timed.track.album,
                        timestampSeconds = timed.timestampSeconds,
                    )
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Last.fm history", e)
            return PlanResult.Failed(Failure.Network(e.message ?: "Last.fm request failed"))
        }

        onProgress(Progress.Matching)
        val plan = PlayCountPlanner(context).plan(
            source = PlayCountSource.LAST_FM,
            plays = plays.asSequence(),
            library = library,
        )
        return PlanResult.Ready(plan)
    }

    /** Applies a plan the user has approved. */
    suspend fun apply(
        plan: ImportPlan,
        onProgress: (Progress) -> Unit = {},
    ): PlayCountWriter.Result = withContext(Dispatchers.IO) {
        PlayCountWriter(context).apply(plan) { written, total ->
            onProgress(Progress.Writing(written, total))
        }
    }

    /**
     * What a source has contributed so far, for the import screen.
     *
     * Suspending because it reads the ledger, and a caller that got this wrong would not find out:
     * Room throws off the main thread, the failure reads as "never imported", and the screen would
     * invite the user to run an import that has already been run.
     */
    suspend fun summaryOf(source: PlayCountSource): Summary = withContext(Dispatchers.IO) {
        try {
            val dao = AppDatabase.getInstance(context).importContributionDao()
            Summary(
                tracks = dao.trackCountFor(source.id),
                plays = dao.playCountFor(source.id) ?: 0,
                lastImportedAt = dao.lastImportFor(source.id),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not summarise ${source.id}", e)
            Summary(0, 0, null)
        }
    }

    data class Summary(val tracks: Int, val plays: Int, val lastImportedAt: Long?) {
        val hasRun: Boolean get() = lastImportedAt != null
    }

    /**
     * Forgets a source's ledger without touching Jellyfin.
     *
     * The counts already written stay written - they become part of the baseline, which is the
     * honest outcome: they are real plays and this app has no record of what the number was before.
     * Re-importing the source afterwards would count that history a second time, so the screen says
     * so before offering it.
     */
    suspend fun forget(source: PlayCountSource) = withContext(Dispatchers.IO) {
        AppDatabase.getInstance(context).importContributionDao().deleteSource(source.id)
    }

    /**
     * Reading history needs an application key but no session - it is a public, unsigned call - so
     * this works for a profile the user has merely named, without linking an account. The broker is
     * passed through for installs whose key lives on a proxy rather than on the phone.
     */
    private fun LastFmCredentialStore.buildClient(): LastFmClient? {
        if (!hasApplicationCredentials()) return null
        return LastFmClient(apiKey, apiSecret, brokerUrl)
    }

    companion object {
        private const val TAG = "PlayCountImporter"
    }
}
