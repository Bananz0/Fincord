package org.akanework.gramophone.logic.data.playcounts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.ImportContribution
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter.Companion.toDashedUuid
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Applies an [ImportPlan] to Jellyfin, then records what each track owes to the source.
 *
 * Counts go to the server rather than into a local table because that is where they already live:
 * the library sync reads `UserData.PlayCount` and it is what drives the whole home feed. Writing
 * locally would give this app a private opinion about play counts that no other client shared and
 * the next sync would overwrite.
 *
 * `POST /UserItems/{id}/UserData` sets the count outright, so one request per track imports any
 * number of plays. The alternative - marking an item played once per play - would be tens of
 * thousands of requests to say the same thing.
 */
class PlayCountWriter(private val context: Context) {

    data class Result(
        val written: Int,
        val failed: Int,
        val cancelled: Boolean,
    )

    /**
     * Writes every change in [plan], reporting progress as it goes.
     *
     * The ledger is updated per batch rather than at the end. An import of ten thousand tracks over
     * a home connection takes minutes, and a process death half way through must leave the ledger
     * agreeing with the server about what was already written - otherwise the next run would
     * recompute a baseline from contributions that were never applied and undo them.
     */
    suspend fun apply(
        plan: ImportPlan,
        onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val api = JellyfinClientHolder.api()
        if (api == null) {
            Log.w(TAG, "Not signed in; nothing written")
            return Result(written = 0, failed = plan.changes.size, cancelled = false)
        }

        val dao = AppDatabase.getInstance(context).importContributionDao()
        val now = System.currentTimeMillis()
        val pending = mutableListOf<ImportContribution>()
        var written = 0
        var failed = 0

        for ((index, change) in plan.changes.withIndex()) {
            if (!currentCoroutineContext().isActive) {
                dao.record(pending)
                return Result(written, failed, cancelled = true)
            }
            // A track whose total is unchanged is left alone. On a repeat import with nothing new
            // that is every track, which turns a re-run into a handful of reads.
            if (change.newTotal == change.currentTotal) {
                pending += change.toContribution(plan, now)
                continue
            }

            val ok = write(api, change)
            if (ok) {
                written++
                pending += change.toContribution(plan, now)
            } else {
                failed++
            }

            if (pending.size >= LEDGER_BATCH) {
                dao.record(pending)
                pending.clear()
            }
            onProgress(index + 1, plan.changes.size)
            // Paced so an import cannot look like an attack on a self-hosted server, or starve
            // playback happening at the same time.
            if (ok) delay(REQUEST_SPACING_MS)
        }

        dao.record(pending)
        Log.d(TAG, "Import of ${plan.source.id}: wrote $written, failed $failed")
        return Result(written, failed, cancelled = false)
    }

    private suspend fun write(
        api: org.jellyfin.sdk.api.client.ApiClient,
        change: PlannedChange,
    ): Boolean = try {
        api.userDataApi.updateItemUserData(
            itemId = UUID.fromString(change.jellyfinId.toDashedUuid()),
            data = UpdateUserItemDataDto(
                playCount = change.newTotal,
                lastPlayedDate = change.lastPlayedSeconds.takeIf { it > 0 }?.toJellyfinDateTime(),
                // Anything with plays has been played. Left unset the server can decide a track
                // with a count is still unplayed, which hides it from "recently played".
                played = change.newTotal > 0,
            ),
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not write play count for ${change.jellyfinId}", e)
        false
    }

    private fun PlannedChange.toContribution(plan: ImportPlan, now: Long) = ImportContribution(
        jellyfinId = jellyfinId,
        source = plan.source.id,
        count = sourceContribution,
        throughSeconds = plan.throughSeconds,
        importedAt = now,
    )

    private fun Long.toJellyfinDateTime(): DateTime =
        Instant.ofEpochSecond(this).atZone(ZoneOffset.UTC).toLocalDateTime()

    companion object {
        private const val TAG = "PlayCountWriter"
        private const val REQUEST_SPACING_MS = 25L
        private const val LEDGER_BATCH = 100
    }
}
