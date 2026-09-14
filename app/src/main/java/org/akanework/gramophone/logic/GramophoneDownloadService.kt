package org.akanework.gramophone.logic


import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
// The notification and the media session must reopen the app the user is actually
// using. Pointing at the old shell dropped them into the retired UI, where nothing
// they tapped belonged to the screen they had left.
import uk.akane.accord.ui.MainActivity

/**
 * Runs offline downloads in the foreground.
 *
 * Downloads have to survive the app being swept out of the recents list - a user who taps "download
 * album" and switches away expects to come back to a finished album - so they run in a foreground
 * service with a progress notification rather than on a coroutine tied to the UI.
 */
@OptIn(UnstableApi::class)
class GramophoneDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0,
) {

    override fun getDownloadManager(): DownloadManager =
        JellyfinDownloadManager.get(this).also { manager ->
            // The channel must exist before the first foreground notification is posted; the base
            // class only creates it lazily on some platform versions.
            NotificationManagerCompat.from(this).createNotificationChannel(
                NotificationChannelCompat.Builder(
                    CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.download_channel_name)).build()
            )
        }

    /**
     * Resumes interrupted downloads once the device meets the requirements again.
     *
     * [PlatformScheduler] uses JobScheduler, so a download cut short by the network dropping picks
     * itself back up without the app having to be reopened.
     */
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int
    ): Notification {
        val downloading = downloads.count { it.state == Download.STATE_DOWNLOADING }
        val queued = downloads.count { it.state == Download.STATE_QUEUED }
        val remaining = downloading + queued

        // percentDownloaded is per-download and is -1 (UNSET) before any bytes land, so an overall
        // figure is only meaningful once something is actually in flight.
        val progress = downloads
            .filter { it.state == Download.STATE_DOWNLOADING && it.percentDownloaded >= 0 }
            .map { it.percentDownloaded }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.download_notification_remaining, remaining, remaining
                )
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 2
        private const val JOB_ID = 1
    }
}
