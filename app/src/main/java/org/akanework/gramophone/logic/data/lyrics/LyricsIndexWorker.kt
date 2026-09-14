package org.akanework.gramophone.logic.data.lyrics

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Indexes lyrics in the background, when doing so costs the user nothing.
 *
 * Ten thousand small requests is not much data - the whole library's words are around twenty
 * megabytes - but it is a long tail of radio wake-ups, and doing it while somebody is listening on
 * mobile data would be a poor trade for a feature they may never use. So it waits for a charger and
 * an unmetered network, which on a phone means overnight, and gives up its slot the moment either
 * stops being true.
 *
 * Rescheduled rather than looped. Each pass takes a bounded bite and enqueues the next only if work
 * remains, so a partial index is the normal state rather than a failure, and the constraints are
 * re-evaluated between slices instead of being checked once and held for an hour.
 */
class LyricsIndexWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val indexer = LyricsIndexer(applicationContext)
        return try {
            val progress = indexer.indexSome()
            if (progress.remaining > 0 && progress.done > 0) {
                // More to do and the last pass made headway, so come back for another slice.
                enqueue(applicationContext, replace = true)
            }
            Log.d(TAG, "Pass complete: ${progress.done} done, ${progress.remaining} left")
            Result.success()
        } catch (e: Exception) {
            // Retried with WorkManager's backoff. Nothing is lost either way: what was written is
            // recorded, and the next pass asks only for what is still unknown.
            Log.w(TAG, "Lyric indexing pass failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LyricsIndexWorker"
        private const val WORK_NAME = "lyrics-index"

        /**
         * Queues a pass for the next time the phone is charging on an unmetered network.
         *
         * Idle is deliberately not required. It is the strictest constraint Android has and on many
         * devices it is satisfied rarely enough that the work would simply never run; charging plus
         * unmetered already means "plugged in at home", which is the intent.
         */
        fun enqueue(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<LyricsIndexWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Runs a pass now, whatever the phone is plugged into.
         *
         * For the button on the settings screen. Somebody who has just asked for this is not
         * interested in being told to come back when they find a charger.
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<LyricsIndexWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
