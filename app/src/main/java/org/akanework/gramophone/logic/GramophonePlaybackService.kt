/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic


import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Parcelable
import android.util.Log
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.IllegalSeekPositionException
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.common.util.Util.isBitmapFactorySupportedMimeType
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.SessionState
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.preference.PreferenceManager
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.logic.cast.FincordCast
import uk.akane.accord.logic.cast.AccordMediaItemConverter
import uk.akane.accord.logic.PlaybackShortcuts
import uk.akane.accord.logic.player.CastQueuePlayer
import uk.akane.accord.logic.player.OutputRouting
import uk.akane.accord.logic.player.JellyfinRemotePlayer
import kotlinx.coroutines.sync.Semaphore
import uk.akane.accord.BuildConfig
import uk.akane.accord.R
import org.akanework.gramophone.logic.utils.CircularShuffleOrder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinRemoteControl
import org.akanework.gramophone.logic.data.jellyfin.JellyfinRemoteTargets
import org.akanework.gramophone.logic.data.jellyfin.JellyfinItemResolver
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import org.akanework.gramophone.logic.data.EmbeddedArtworkStore
import org.akanework.gramophone.logic.data.library.songListSnapshot
import uk.akane.accord.Accord
import org.akanework.gramophone.logic.utils.LastPlayedManager
import org.akanework.gramophone.logic.utils.LrcUtils.extractAndParseLyrics
import org.akanework.gramophone.logic.utils.LrcUtils.loadAndParseLyricsFile
import org.akanework.gramophone.logic.utils.MediaStoreUtils
import org.akanework.gramophone.logic.utils.ReplayGainAudioProcessor
import org.akanework.gramophone.logic.utils.ReplayGainUtil
import org.akanework.gramophone.logic.utils.exoplayer.EndedWorkaroundPlayer
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLyricsSource
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import org.akanework.gramophone.logic.data.jellyfin.JellyfinReporter
import org.akanework.gramophone.logic.data.lastfm.LastFmScrobbler
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneExtractorsFactory
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneMediaSourceFactory
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneRenderFactory
// The notification and the media session must reopen the app the user is actually
// using. Pointing at the old shell dropped them into the retired UI, where nothing
// they tapped belonged to the screen they had left.
import uk.akane.accord.ui.MainActivity
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import org.akanework.gramophone.logic.data.automix.AutomixAnalysisScheduler
import org.akanework.gramophone.logic.data.automix.Automix
import org.akanework.gramophone.logic.data.automix.AutomixTransitions
import org.akanework.gramophone.logic.data.automix.BassSwapAudioProcessor
import org.akanework.gramophone.logic.data.jellyfin.QueuePrefetcher
import uk.akane.accord.logic.player.AfFormatTracker
import uk.akane.accord.logic.player.PlaybackClockProbe
import uk.akane.accord.logic.player.BtCodecInfo
import uk.akane.accord.logic.player.UsbHiFiManager
import uk.akane.libphonograph.items.albumId


/**
 * [GramophonePlaybackService] is a server service.
 * It's using exoplayer2 as its player backend.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class GramophonePlaybackService : MediaLibraryService(), MediaSessionService.Listener,
    MediaLibraryService.MediaLibrarySession.Callback, Player.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "GramoPlaybackService"



        /** Reading tags off the network must not outlast the track it belongs to. */
        private const val EMBEDDED_LYRICS_TIMEOUT_MS = 8_000L
        /** Give Media3's track callback first refusal before doing a metadata-only lookup. */
        private const val LOCAL_LYRICS_TRACKS_FALLBACK_MS = 250L
        /** Heartbeat for Jellyfin progress reporting, matching what the official clients send. */
        private const val WARM_UP_TIMEOUT_MS = 2_000L

        private const val PROGRESS_REPORT_INTERVAL_MS = 10_000L
        private const val NOTIFY_CHANNEL_ID = "serviceFgsError"
        private const val NOTIFY_ID = 1
        private const val PENDING_INTENT_SESSION_ID = 0
        private const val PENDING_INTENT_NOTIFY_ID = 1
        private const val PLAYBACK_SHUFFLE_ACTION_ON = "shuffle_on"
        private const val PLAYBACK_SHUFFLE_ACTION_OFF = "shuffle_off"
        private const val PLAYBACK_REPEAT_OFF = "repeat_off"
        private const val PLAYBACK_REPEAT_ALL = "repeat_all"
        private const val PLAYBACK_REPEAT_ONE = "repeat_one"
        const val SERVICE_SET_TIMER = "set_timer"
        const val SERVICE_QUERY_TIMER = "query_timer"
        const val SERVICE_GET_LYRICS = "get_lyrics"
        const val SERVICE_GET_AUDIO_FORMAT = "get_audio_format"

        /** How many tracks past the current one are warmed; see QueuePrefetcher. */
        private const val PREFETCH_LOOKAHEAD = 5
        /** More than enough for system media controls without a 64 MiB software decode. */
        private const val NOTIFICATION_ARTWORK_MAX_PX = 512
        const val SERVICE_GET_SESSION = "get_session"
        const val SERVICE_TIMER_CHANGED = "changed_timer"

        var instanceForWidgetAndLyricsOnly: GramophonePlaybackService? = null
    }

    private var lastSessionId = 0
    /** Owns Media3's internal playback loop so AudioFlinger inspection runs on the same thread. */
    private val internalPlaybackThread =
        HandlerThread("ExoPlayer:Playback", Process.THREAD_PRIORITY_AUDIO)
    private var mediaSession: MediaLibrarySession? = null

    /**
     * The three Players this service can put in front of the session, and the one holding it now.
     *
     * The session's player is no longer always the local one. [localPlayer] is kept as its own
     * reference because the things that genuinely mean *this device* - the audio session id, the
     * shuffle order, saved playback state, AudioFlinger format inspection - must keep addressing
     * it whichever output is currently in front.
     */
    private var localPlayer: EndedWorkaroundPlayer? = null
    private var castQueuePlayer: CastQueuePlayer? = null
    private var remoteCastPlayer: RemoteCastPlayer? = null
    private var finnectPlayer: JellyfinRemotePlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var finnectJob: Job? = null
    private var outputRoutingJob: Job? = null

    /** Whether this cast session has already been handed a queue; see populateReceiverIfEmpty. */
    private var receiverStatusCallback: RemoteMediaClient.Callback? = null
    private var receiverStatusClient: RemoteMediaClient? = null
    private var castHandbackApplied = false
    private val castSessionTransferCallback = object : SessionTransferCallback() {
        override fun onTransferring(transferType: Int) {
            Log.d(TAG, "cast session transferring: $transferType")
        }

        override fun onTransferred(transferType: Int, sessionState: SessionState) {
            Log.d(TAG, "cast session transferred: $transferType")
            if (transferType == TRANSFER_TYPE_FROM_REMOTE_TO_LOCAL) {
                handler.post(::onReceiverGone)
            }
        }

        override fun onTransferFailed(transferType: Int, transferFailedReason: Int) {
            Log.w(TAG, "cast session transfer failed: type=$transferType reason=$transferFailedReason")
        }
    }

    val endedWorkaroundPlayer
        get() = localPlayer
    private var controller: MediaController? = null
    private var remoteControl: JellyfinRemoteControl? = null
    private var lyrics: MutableList<MediaStoreUtils.Lyric>? = null
    private var shuffleFactory:
            ((Int) -> ((CircularShuffleOrder) -> Unit) -> CircularShuffleOrder)? = null
    private lateinit var customCommands: List<CommandButton>
    private lateinit var navigationCommands: List<CommandButton>
    private lateinit var handler: Handler
    private lateinit var playbackHandler: Handler
    private lateinit var afFormatTracker: AfFormatTracker
    private var btCodecInfo: BtCodecInfo? = null
    private lateinit var nm: NotificationManagerCompat
    private lateinit var lastPlayedManager: LastPlayedManager
    private val lyricsLock = Semaphore(1)
    private var pendingLocalLyricsFallback: Runnable? = null
    /** Last item for which the service processed a real transition, not a queue metadata edit. */
    private var transitionedMediaId: String? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var reporter: JellyfinReporter
    private lateinit var scrobbler: LastFmScrobbler
    private lateinit var usbHiFiManager: UsbHiFiManager
    private lateinit var replayGainAudioProcessor: ReplayGainAudioProcessor
    private var automixGhostReplayGainProcessor: ReplayGainAudioProcessor? = null
    /** The ghost's bass swap. Only ever in the ghost's chain; the session player never has one. */
    private var automixGhostBassSwapProcessor: BassSwapAudioProcessor? = null
    private var replayGainMode = 0
    private var audioSinkInputFormat: Format? = null
    private lateinit var mediaSourceFactory: GramophoneMediaSourceFactory

    /** The track currently reported to Jellyfin as playing, by media3 media id. */
    private var reportedMediaId: String? = null
    private var lastReportedPositionMs: Long = 0L

    private var automixTransitions: AutomixTransitions? = null

    /** A sink setting changed while a remote output held the session; rebuild when it comes back. */
    private var pendingLocalPlayerRebuild = false

    /** A replacement player buffering in the background, waiting to take over from [localPlayer]. */
    private var warmingPlayer: EndedWorkaroundPlayer? = null
    private var warmingTimeout: Runnable? = null

    /** Set while the player being replaced is released, so its parting nulls are ignored. */
    private var retiringPlayerSink = false

    private fun getRepeatCommand(player: Player = controller!!) =
        when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> customCommands[2]
            Player.REPEAT_MODE_ALL -> customCommands[3]
            Player.REPEAT_MODE_ONE -> customCommands[4]
            else -> throw IllegalArgumentException()
        }

    private fun getShufflingCommand(player: Player = controller!!) =
        if (player.shuffleModeEnabled)
            customCommands[1]
        else
            customCommands[0]

    private fun mediaButtonPreferences(player: Player = controller!!): ImmutableList<CommandButton> =
        ImmutableList.of(
            navigationCommands[0],
            getRepeatCommand(player),
            getShufflingCommand(player),
            navigationCommands[1],
        )

    private val timer: Runnable = Runnable {
        controller!!.pause()
        timerDuration = 0
    }

    private var timerDuration = 0
        set(value) {
            field = value
            handler.removeCallbacks(timer)
            if (value > 0) {
                handler.postDelayed(timer, value.toLong())
            } else {
                handler.removeCallbacks(timer)
            }
            mediaSession!!.broadcastCustomCommand(
                SessionCommand(SERVICE_TIMER_CHANGED, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }

    private val headSetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action.equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                controller?.pause()
            }
        }
    }

    private val btCodecReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED" ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            ) return
            val codecConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                IntentCompat.getParcelableExtra(
                    intent,
                    "android.bluetooth.extra.CODEC_STATUS",
                    BluetoothCodecStatus::class.java,
                )?.codecConfig
            } else {
                // BluetoothCodecStatus was hidden from the public SDK until API 33 even though
                // Samsung broadcasts it on Android 12. Avoid resolving the class on 31/32 and
                // read its stable getCodecConfig method reflectively there.
                @Suppress("DEPRECATION")
                val status = intent.getParcelableExtra<Parcelable>(
                    "android.bluetooth.extra.CODEC_STATUS"
                )
                runCatching {
                    status?.javaClass?.getMethod("getCodecConfig")?.invoke(status)
                        as? BluetoothCodecConfig
                }.getOrNull()
            }
            btCodecInfo = BtCodecInfo.fromCodecConfig(codecConfig)
            Log.d(TAG, "Bluetooth codec changed to $btCodecInfo")
            broadcastAudioFormat()
        }
    }

    override fun onCreate() {
        instanceForWidgetAndLyricsOnly = this
        handler = Handler(Looper.getMainLooper())
        reporter = JellyfinReporter(this)
        scrobbler = LastFmScrobbler(this)
        // A session that ended offline leaves plays queued; submit them as soon as the service is
        // alive again rather than waiting for the next track to finish.
        scrobbler.flushAsync()
        // Rows an older analyser wrote can never be read again, because every lookup asks for the
        // current version. Clearing them here means a change to the DSP needs nothing but the
        // version bump.
        AutomixAnalysisScheduler.pruneStaleAnalyses(this)
        createAutomixTransitions()
        super.onCreate()
        FincordCast.addSessionTransferCallback(castSessionTransferCallback)
        internalPlaybackThread.start()
        playbackHandler = Handler(internalPlaybackThread.looper)
        nm = NotificationManagerCompat.from(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        replayGainAudioProcessor = ReplayGainAudioProcessor()
        readReplayGainPreferences()
        usbHiFiManager = UsbHiFiManager(
            context = this,
            enabled = { prefs.getBoolean("usb_hifi", true) },
            onChanged = { status ->
                if (status?.bitPerfect == true) automixTransitions?.cancel()
                broadcastAudioFormat()
            },
        )
        afFormatTracker = AfFormatTracker(this, playbackHandler, handler).apply {
            // The tracker reports after AudioTrack is created and whenever its real route changes.
            // Marshal back to the session thread before notifying connected controllers.
            formatChangedCallback = { _, _ -> handler.post(::broadcastAudioFormat) }
        }
        setListener(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_gramophone_monochrome)
            }
        )
        if (mayThrowForegroundServiceStartNotAllowed()) {
            // we don't need notification permission because this only is run on S/S_V2
            nm.createNotificationChannel(NotificationChannelCompat.Builder(
                NOTIFY_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH
            ).apply {
                setName(getString(R.string.fgs_failed_channel))
                setVibrationEnabled(true)
                setVibrationPattern(longArrayOf(0L, 200L))
                setLightsEnabled(false)
                setShowBadge(false)
                setSound(null, null)
            }.build()
            )
        } else if (nm.getNotificationChannel(NOTIFY_CHANNEL_ID) != null) {
            // for people who upgraded from S/S_V2 to newer version
            nm.deleteNotificationChannel(NOTIFY_CHANNEL_ID)
        }

        customCommands =
            listOf(
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF) // shuffle currently disabled, click will enable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_ON, Bundle.EMPTY)
                    )
                    .setCustomIconResId(R.drawable.ic_shuffle_off)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON) // shuffle currently enabled, click will disable
                    .setDisplayName(getString(R.string.shuffle))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_SHUFFLE_ACTION_OFF, Bundle.EMPTY)
                    )
                    .setCustomIconResId(R.drawable.ic_shuffle)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_OFF) // repeat currently disabled, click will repeat all
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ALL, Bundle.EMPTY)
                    )
                    .setCustomIconResId(R.drawable.ic_repeat_off)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ALL) // repeat all currently enabled, click will repeat one
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_ONE, Bundle.EMPTY)
                    )
                    .setCustomIconResId(R.drawable.ic_repeat)
                    .build(),
                CommandButton.Builder(CommandButton.ICON_REPEAT_ONE) // repeat one currently enabled, click will disable
                    .setDisplayName(getString(R.string.repeat_mode))
                    .setSessionCommand(
                        SessionCommand(PLAYBACK_REPEAT_OFF, Bundle.EMPTY)
                    )
                    .setCustomIconResId(R.drawable.ic_repeat_one)
                    .build(),
            )
        navigationCommands = listOf(
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName(getString(R.string.playback_previous))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName(getString(R.string.playback_next))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build(),
        )

        mediaSourceFactory = GramophoneMediaSourceFactory(
            JellyfinMediaCache.dataSourceFactory(this),
            GramophoneExtractorsFactory(),
        )
        val player = buildLocalPlayer()
        player.exoPlayer.audioSessionId = Util.generateAudioSessionIdV21(this)
        lastSessionId = player.exoPlayer.audioSessionId
        broadcastAudioSession()
        lastPlayedManager = LastPlayedManager(this, player)
        lastPlayedManager.allowSavingState = false

        localPlayer = player
        startPlaybackClockProbe()
        finnectPlayer = JellyfinRemotePlayer(Looper.getMainLooper(), serviceScope)
        val sessionPlayer = buildCastAwarePlayer(player)

        mediaSession =
            MediaLibrarySession
                .Builder(this, sessionPlayer, this)
                .setBitmapLoader(object : BitmapLoader {
                    // Coil-based bitmap loader to reuse Coil's caching and to make sure we use
                    // the same cover art as the rest of the app, ie MediaStore's cover

                    // MediaSession's limit is sized for notification/controller artwork. Without
                    // it Coil decodes Jellyfin's original file: the captured failure requested a
                    // 4096x4096 software bitmap (64 MiB) in a process with a 256 MiB heap, and
                    // repeated controller requests exhausted that heap within seconds of launch.
                    private val limit by lazy {
                        minOf(
                            MediaSession.getBitmapDimensionLimit(this@GramophonePlaybackService),
                            NOTIFICATION_ARTWORK_MAX_PX,
                        )
                    }

                    override fun decodeBitmap(data: ByteArray) =
                        throw UnsupportedOperationException("decodeBitmap() not supported")

                    override fun loadBitmap(
                        uri: Uri
                    ): ListenableFuture<Bitmap> {
                        return CallbackToFutureAdapter.getFuture { completer ->
                            imageLoader.enqueue(
                                ImageRequest.Builder(this@GramophonePlaybackService)
                                    .data(uri)
                                    .size(limit, limit)
                                    .allowHardware(false)
                                    .target(
                                        onStart = { _ ->
                                            // We don't need or want a placeholder.
                                        },
                                        onSuccess = { result ->
                                            completer.set((result as BitmapImage).bitmap)
                                        },
                                        onError = { _ ->
                                            completer.setException(Exception("coil onError called"))
                                        }
                                    )
                                    .build())
                                .also {
                                    completer.addCancellationListener(
                                        { it.dispose() },
                                        ContextCompat.getMainExecutor(
                                            this@GramophonePlaybackService
                                        )
                                    )
                                }
                            "coil load for $uri"
                        }
                    }

                    override fun supportsMimeType(mimeType: String): Boolean {
                        return isBitmapFactorySupportedMimeType(mimeType)
                    }

                    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
                        return metadata.artworkUri?.let { loadBitmap(it) }
                    }
                })
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        PENDING_INTENT_SESSION_ID,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                // System UI, Google Home, Auto and Wear all resolve their controls from this
                // ordered list. Custom layout alone is legacy-oriented and left the modern
                // controls visibly present but disabled for controllers not on the old allowlist.
                .setMediaButtonPreferences(mediaButtonPreferences(sessionPlayer))
                .build()
        controller = MediaController.Builder(this, mediaSession!!.token).buildAsync().get()
        observeFinnectTarget()
        handler.post {
            if (mediaSession == null) return@post
            lastPlayedManager.restore { items, factory ->
                if (mediaSession == null) return@restore
                applyShuffleSeed(true, factory.toFactory(controller!!))
                if (items != null) {
                    try {
                        // The saved queue belongs to this phone. On a cold Cast rejoin the session
                        // may already front CastQueuePlayer; restoring through it would interpret
                        // persistence as a new user queue and overwrite the still-playing receiver.
                        player.setMediaItems(
                            items.mediaItems, items.startIndex, items.startPositionMs
                        )
                    } catch (e: IllegalSeekPositionException) {
                        Log.e(TAG, "failed to restore: " + Log.getStackTraceString(e))
                        // song was edited to be shorter and playback position doesn't exist anymore
                    }
                    // Prepare Player after UI thread is less busy (loads tracks, required for lyric)
                    handler.post {
                        controller?.prepare()
                    }
                }
                lastPlayedManager.allowSavingState = true
            }
        }
        // Makes this device a target in every other Jellyfin client's "Play on" menu. Started here
        // rather than in the Application because the player is what a remote command acts on, and
        // there is no point advertising a session with nothing behind it.
        remoteControl = JellyfinRemoteControl(
            context = this,
            player = { controller },
            resolve = ::resolveRemoteIds,
        ).also {
            if (JellyfinRemoteTargets.isEnabled(this)) it.start()
        }

        onShuffleModeEnabledChanged(controller!!.shuffleModeEnabled) // refresh custom commands
        controller!!.addListener(this)
        registerReceiver(
            headSetReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        val initialCodecIntent = ContextCompat.registerReceiver(
            this,
            btCodecReceiver,
            IntentFilter("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Some Android builds retain the latest codec broadcast. Use it when available; otherwise
        // the next route/codec change supplies the value. The direct BluetoothA2dp query is not a
        // third-party API on current Android and throws without a Companion Device association.
        initialCodecIntent?.let { btCodecReceiver.onReceive(this, it) }
    }

    /**
     * Builds the Automix executor. Recreated with the local player, because its warm ghost shares
     * that player's audio session and would otherwise outlive it.
     */
    private fun createAutomixTransitions() {
        automixTransitions = AutomixTransitions(
            context = this,
            handler = handler,
            newGhost = ::buildAutomixGhost,
            beforeGhostPrepare = ::syncAutomixGhostReplayGain,
            ghostBassCut = { cutoffHz, progress ->
                automixGhostBassSwapProcessor?.setSweep(cutoffHz, progress)
            },
            bitPerfectOutput = { usbHiFiManager.status?.bitPerfect == true },
        )
    }

    /**
     * Rebuilds the local player in place, so a setting baked into its sink takes effect now.
     *
     * media3 fixes the renderers factory and the `DefaultAudioSink` when the player is constructed,
     * so there is no way to change high-resolution passthrough or hardware playback parameters on a
     * running player - the player has to be replaced. Everything the listener can perceive is
     * carried across: the queue, which item, the position, whether it was playing, repeat and
     * shuffle. The audio session id is reused deliberately, so an equaliser or any other effect
     * bound to it stays bound.
     *
     * **Deferred while anything remote is in front of the session.** Cast and Finnect own playback
     * at those moments and neither is affected by a local sink setting, so the rebuild waits for
     * the route to come back rather than disturbing a stream on another device;
     * [updateSessionPlayer] picks it up. The Cast queue wrapper does have to be rebuilt, because it
     * holds the local player - `CastQueuePlayer.handleRelease` only detaches its listeners and
     * leaves the receiver player alone, so the remote survives it.
     */
    /**
     * Rebuilds the local player in place, so a setting baked into its sink takes effect now.
     *
     * media3 fixes the renderers factory and the `DefaultAudioSink` when the player is constructed,
     * so there is no way to change high-resolution passthrough or hardware playback parameters on a
     * running player - the player has to be replaced. Everything the listener can perceive is
     * carried across: the queue, which item, the position, whether it was playing, repeat and
     * shuffle. The audio session id is reused deliberately, so an equaliser or any other effect
     * bound to it stays bound.
     *
     * **The replacement is warmed before it takes over.** Swapping first and preparing afterwards
     * cost 185-320 ms of silence, and the logs said where it went: `audioTrackInit` landed 269 ms
     * in and `STATE_READY` six milliseconds after it, so nearly all of it was source preparation
     * and AudioTrack creation. Both can happen while the outgoing player is still sounding. The
     * replacement therefore buffers to `STATE_READY` with `playWhenReady` false, and only then is
     * it caught up to the playhead and put in front of the session.
     *
     * **Deferred while anything remote is in front of the session.** Cast and Finnect own playback
     * at those moments and neither is affected by a local sink setting, so the rebuild waits for
     * the route to come back rather than disturbing a stream on another device;
     * [updateSessionPlayer] picks it up. The Cast queue wrapper does have to be rebuilt, because it
     * holds the local player - `CastQueuePlayer.handleRelease` only detaches its listeners and
     * leaves the receiver player alone, so the remote survives it.
     */
    private fun rebuildLocalPlayer(reason: String) {
        val old = localPlayer ?: return
        val session = mediaSession
        if (session != null && session.player !== old) {
            Log.d(TAG, "deferring player rebuild ($reason): a remote output owns the session")
            pendingLocalPlayerRebuild = true
            return
        }
        pendingLocalPlayerRebuild = false
        // A second toggle while one is warming makes that one obsolete: it was built from settings
        // that have already changed.
        discardWarmingPlayer()

        // Released first, so the volume captured at promotion is the real one rather than whatever
        // point a fade had reached.
        automixTransitions?.release()
        automixTransitions = null

        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        val positionMs = old.currentPosition.coerceAtLeast(0L)
        Log.d(TAG, "rebuilding the local player ($reason), ${items.size} items at ${positionMs}ms")

        val warm = buildLocalPlayer()
        // 0 is "never set", which is the same test broadcastAudioSession uses.
        if (lastSessionId != 0) {
            runCatching { warm.exoPlayer.audioSessionId = lastSessionId }
                .onFailure { Log.w(TAG, "could not reuse audio session $lastSessionId", it) }
        }
        warm.volume = 0f
        warm.playWhenReady = false
        if (items.isNotEmpty()) {
            warm.setMediaItems(items, index, positionMs)
            warm.repeatMode = old.repeatMode
            warm.shuffleModeEnabled = old.shuffleModeEnabled
            warm.prepare()
        }
        warmingPlayer = warm

        // Nothing is sounding, so there is nothing to cover and no reason to wait.
        if (items.isEmpty() || !old.playWhenReady) {
            promoteWarmedPlayer("nothing was playing")
            return
        }

        val readyListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                warm.exoPlayer.removeListener(this)
                promoteWarmedPlayer("warmed")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "replacement player failed while warming; swapping anyway", error)
                warm.exoPlayer.removeListener(this)
                promoteWarmedPlayer("warm-up failed")
            }
        }
        warm.exoPlayer.addListener(readyListener)
        // A replacement that never reaches READY must not strand the setting unapplied. Swapping
        // late is worse than swapping warm, and better than not swapping at all.
        val timeout = Runnable {
            warmingTimeout = null
            warm.exoPlayer.removeListener(readyListener)
            promoteWarmedPlayer("warm-up timed out")
        }
        warmingTimeout = timeout
        handler.postDelayed(timeout, WARM_UP_TIMEOUT_MS)
    }

    /**
     * Puts the warmed replacement in front of the session and retires the old player.
     *
     * The playhead has moved while the replacement was buffering, so it is seeked to catch up. That
     * seek lands inside audio the replacement has already buffered, which is the whole reason the
     * warm-up is worth doing: an in-buffer seek is cheap where a cold prepare is not.
     */
    private fun promoteWarmedPlayer(how: String) {
        val player = warmingPlayer ?: return
        val old = localPlayer
        warmingPlayer = null
        warmingTimeout?.let(handler::removeCallbacks)
        warmingTimeout = null

        if (old == null) {
            releaseLocalPlayer(player)
            return
        }

        val wasPlaying = old.playWhenReady
        val volume = old.volume
        val positionMs = old.currentPosition.coerceAtLeast(0L)
        val index = old.currentMediaItemIndex.coerceAtLeast(0)
        if (player.mediaItemCount > index) {
            player.seekTo(index, positionMs)
        }
        player.volume = volume

        localPlayer = player
        // Saving is suspended across the swap: the old manager still points at a player that is
        // about to be released, and persisting from it would write the wrong queue.
        lastPlayedManager.release()
        lastPlayedManager = LastPlayedManager(this, player)
        lastPlayedManager.allowSavingState = false

        remoteCastPlayer?.let { remote ->
            castQueuePlayer?.release()
            castQueuePlayer = CastQueuePlayer(
                Looper.getMainLooper(), player, remote, AccordMediaItemConverter()
            )
        }

        // Point the session at the new player before releasing the old one, so it is never holding
        // a released player even momentarily.
        mediaSession?.player = player
        player.playWhenReady = wasPlaying
        retiringPlayerSink = true
        releaseLocalPlayer(old)
        retiringPlayerSink = false

        lastPlayedManager.allowSavingState = true
        createAutomixTransitions()
        broadcastAudioSession()
        broadcastAudioFormat()
        updateSessionPlayer()
        Log.d(TAG, "local player swapped in ($how) at ${positionMs}ms")
    }

    /** Throws away a replacement that will never be used. */
    private fun discardWarmingPlayer() {
        warmingTimeout?.let(handler::removeCallbacks)
        warmingTimeout = null
        warmingPlayer?.let(::releaseLocalPlayer)
        warmingPlayer = null
    }

    /**
     * Builds the local player, sink and all.
     *
     * Extracted so it can be called again. `floatoutput` and `ps_hardware_acc` are read here and
     * nowhere else, because media3 bakes them into the renderers factory and the `DefaultAudioSink`
     * at construction - which is why changing either of them used to do nothing at all until the
     * service was force-stopped, and why a switch that looked inert cost several rounds of chasing
     * an audio bug that was somewhere else entirely. [rebuildLocalPlayer] is the answer to that.
     */
    /**
     * Samples every clock in the playback stack once a second, for [PlaybackClockProbe].
     *
     * On the **application** thread, not the playback one. media3 permits `getCurrentPosition`
     * only from the thread the player was built on, and reading it anywhere else throws - which
     * the first version of this did, straight into a `runCatching` that swallowed it, so the probe
     * silently produced nothing at all. The `AudioTrack` is safe to sample from here because
     * `AfFormatTracker` publishes it as a volatile and reading a position off it is a thread-safe
     * native call; taking both halves on one thread is also the only way the comparison means
     * anything, since two clocks read a handler-hop apart cannot be subtracted.
     *
     * Inert unless the tag is enabled: one post a second and an immediate return.
     */
    private var playbackClockProbe: Runnable? = null

    private fun stopPlaybackClockProbe() {
        playbackClockProbe?.let(handler::removeCallbacks)
        playbackClockProbe = null
    }

    private fun startPlaybackClockProbe() {
        stopPlaybackClockProbe()
        val tick = object : Runnable {
            override fun run() {
                if (playbackClockProbe !== this) return
                val exoPlayer = localPlayer?.exoPlayer ?: return
                if (PlaybackClockProbe.isEnabled) {
                    if (exoPlayer.isPlaying) {
                        PlaybackClockProbe.sample(
                            exoPlayer.currentPosition,
                            afFormatTracker.probeAudioTrack,
                        )
                    } else {
                        PlaybackClockProbe.reset()
                    }
                }
                if (playbackClockProbe === this) handler.postDelayed(this, 1_000L)
            }
        }
        playbackClockProbe = tick
        handler.post(tick)
    }

    /** Both active and warming players own callbacks that must be detached when retired. */
    private val localRenderFactories = mutableMapOf<EndedWorkaroundPlayer, GramophoneRenderFactory>()

    private fun releaseLocalPlayer(player: EndedWorkaroundPlayer) {
        localRenderFactories.remove(player)?.detach()
        player.audioTrackProvider = null
        player.release()
    }

    private fun buildLocalPlayer(): EndedWorkaroundPlayer {
        /*
         * A processor instance belongs to one sink.
         *
         * `DefaultAudioSink` configures the processors it was built with and resets them when it is
         * released, and while a replacement player is warming there are two live sinks. Sharing one
         * processor meant the dying sink's reset cleared the PCM format out from under the sink
         * that had just taken over, and the survivor then threw
         * `IllegalStateException: Unsupported ReplayGain PCM encoding: -1` - `Format.NO_VALUE` -
         * from the middle of a buffer, milliseconds after the swap. The Automix ghost has always
         * had its own for exactly this reason.
         *
         * The new instance inherits the current settings and becomes the one settings changes are
         * applied to; the outgoing player keeps the instance it was built with until it is
         * released.
         */
        replayGainAudioProcessor = ReplayGainAudioProcessor().apply {
            setPreampDb(replayGainAudioProcessor.preampDb)
            setUntaggedGainDb(replayGainAudioProcessor.untaggedGainDb)
            setMode(replayGainAudioProcessor.mode)
        }
        // Held so onDestroy can drop its listeners. Both of the ones passed below are bound to this
        // service, and the factory outlives it inside media3's renderers - see
        // GramophoneRenderFactory.detach.
        val renderFactory = GramophoneRenderFactory(
            this,
            replayGainAudioProcessor = replayGainAudioProcessor,
            configurationListener = ::onAudioSinkInputFormatChanged,
            audioSinkListener = afFormatTracker::setAudioSink,
        )
        val player =
            EndedWorkaroundPlayer(
                ExoPlayer.Builder(
                    this,
                    renderFactory
                        .setEnableAudioFloatOutput(
                            // High-resolution passthrough, kept as an explicit advanced option. USB
                            // Hi-Fi used to force it on every route, including Samsung's built-in
                            // speaker path; that clock then jumped ~209 ms per buffer and media3
                            // emitted continuous UnexpectedDiscontinuity errors, heard as severe
                            // distortion. The USB manager still negotiates an exact mixer mode for the
                            // safe PCM format instead. The preference key is historical - see
                            // GramophoneRenderFactory.buildAudioSink for what the switch really does,
                            // which is nothing at all below 24-bit.
                            prefs.getBooleanStrict("floatoutput", false)
                        )
                        .setEnableDecoderFallback(true)
                        .setEnableAudioTrackPlaybackParams( // hardware/system-accelerated playback speed
                            // Default off. Measured on device: with hardware playback params on,
                            // ExoPlayer's reported position sat 235-497 ms from the audio
                            // hardware's own timestamp and closed that gap at about 2% a second -
                            // a sawtooth that reaches the lyrics, the seek bar and scrobbling.
                            // Off, the same measurement gave -49..+176 ms and left the hardware
                            // timestamp accurate to +-1 ms. It only ever bought a cheaper
                            // resampler for a speed control almost nobody moves.
                            prefs.getBooleanStrict("ps_hardware_acc", false)
                        )
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
                    mediaSourceFactory
                    /* .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING))
                TODO flag breaks playback of AcousticGuitar.mp3, report exo bug + add UI toggle*/
                )
                    // Tracks stream over the network now, so the wake lock has to keep the radio up
                    // as well as the CPU.
                    .setWakeMode(C.WAKE_MODE_NETWORK)
                    .setSkipSilenceEnabled(prefs.getBooleanStrict("skip_silence", false))
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(), true
                    )
                    .setPlaybackLooper(internalPlaybackThread.looper)
                    .build()
            )
        localRenderFactories[player] = renderFactory
        player.exoPlayer.addAnalyticsListener(afFormatTracker)
        // Lets the forwarding player run its position on the audio hardware's clock rather than
        // media3's. Measured, media3's is the worst clock in the stack; see HardwareClockPosition.
        player.audioTrackProvider = { afFormatTracker.probeAudioTrack }
        // Keep the cover the file itself carries.
        //
        // This hangs off the ExoPlayer rather than the MediaController the rest of the service
        // listens through: media3 does not carry MediaMetadata.artworkData across the MediaSession
        // boundary, because a cover is larger than a Binder transaction may be. The player UI
        // therefore never sees these bytes however it asks - it can only read what is written here,
        // and until then falls back to fetching Jellyfin's copy of the very same picture over HTTP.
        //
        // Read off the live player rather than through MetadataRetriever, which cannot do this at
        // all on media3 1.11: the vendored copy calls a `prepareSource` overload the current
        // MediaSource no longer implements and throws. The player has already parsed the header to
        // play the track, so the picture is sitting in Format.metadata for free.
        player.exoPlayer.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                keepEmbeddedArtwork(player.exoPlayer, mediaMetadata.artworkData)
            }

            // Usually a frame on the track's Format rather than folded into artworkData - which is
            // what the debug EventLogger prints as "Picture: mimeType=image/jpeg".
            override fun onTracksChanged(tracks: Tracks) {
                val id = runCatching { player.exoPlayer.currentMediaItem?.mediaId }
                    .getOrNull() ?: return
                if (EmbeddedArtworkStore.fileFor(applicationContext, id).isFile) return
                for (group in tracks.groups) {
                    for (i in 0 until group.length) {
                        val metadata = group.getTrackFormat(i).metadata ?: continue
                        for (j in 0 until metadata.length()) {
                            val bytes = when (val entry = metadata.get(j)) {
                                is PictureFrame -> entry.pictureData
                                is ApicFrame -> entry.pictureData
                                else -> null
                            } ?: continue
                            keepEmbeddedArtwork(player.exoPlayer, bytes)
                            return
                        }
                    }
                }
            }
        })
        if (BuildConfig.DEBUG) {
            player.exoPlayer.addAnalyticsListener(EventLogger())
        }
        return player
    }

    /**
     * Builds Automix's single-item outgoing player.
     *
     * It shares the main player's cache-aware media source and audio-session id, but deliberately
     * owns neither audio focus nor noisy-route handling. The session player remains responsible for
     * both; the ghost simply follows its play/pause state for the few seconds it carries the tail.
     */
    /** Writes a track's own cover to [EmbeddedArtworkStore] the first time it is seen. */
    private fun keepEmbeddedArtwork(source: ExoPlayer, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        val id = runCatching { source.currentMediaItem?.mediaId }.getOrNull() ?: return
        if (EmbeddedArtworkStore.fileFor(applicationContext, id).isFile) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching { EmbeddedArtworkStore.store(applicationContext, id, bytes) }
                .onSuccess { Log.d(TAG, "kept embedded artwork (${bytes.size} B) for $id") }
                .onFailure { Log.d(TAG, "Could not keep embedded artwork: $it") }
        }
    }

    private fun buildAutomixGhost(): ExoPlayer? {
        val main = localPlayer?.exoPlayer ?: return null
        val ghostReplayGain = ReplayGainAudioProcessor().apply {
            setMode(replayGainAudioProcessor.mode)
            setPreampDb(replayGainAudioProcessor.preampDb)
            setUntaggedGainDb(replayGainAudioProcessor.untaggedGainDb)
        }
        automixGhostReplayGainProcessor = ghostReplayGain
        val ghostBassSwap = BassSwapAudioProcessor()
        automixGhostBassSwapProcessor = ghostBassSwap
        return ExoPlayer.Builder(
            this,
            GramophoneRenderFactory(
                this,
                replayGainAudioProcessor = ghostReplayGain,
                extraAudioProcessors = arrayOf(ghostBassSwap),
            )
                .setEnableAudioFloatOutput(prefs.getBooleanStrict("floatoutput", false))
                .setEnableDecoderFallback(true)
                .setEnableAudioTrackPlaybackParams(
                    prefs.getBooleanStrict("ps_hardware_acc", false)
                )
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            mediaSourceFactory,
        )
            .setSkipSilenceEnabled(main.skipSilenceEnabled)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
            .also { it.audioSessionId = main.audioSessionId }
    }

    // When destroying, we should release server side player
    // alongside with the mediaSession.
    /**
     * Turns Jellyfin item ids into playable items, keeping the order the caller asked for.
     *
     * The library is keyed by a local id and the wire speaks Jellyfin GUIDs, so this walks the
     * snapshot once and indexes it rather than resolving each id separately - a remote "play this
     * album" arrives as a dozen ids at once.
     */
    private suspend fun resolveRemoteIds(remoteIds: List<String>): List<MediaItem> {
        val wanted = remoteIds.map { it.replace("-", "").lowercase() }.toSet()
        val songs = (application as? Accord)?.reader?.songListSnapshot().orEmpty()
        val byRemote = HashMap<String, MediaItem>(wanted.size)
        songs.forEach { item ->
            val remote = JellyfinItemResolver.remoteIdForMediaId(this, item.mediaId)
                ?.replace("-", "")?.lowercase() ?: return@forEach
            if (remote in wanted) byRemote[remote] = item
        }
        return remoteIds.mapNotNull { byRemote[it.replace("-", "").lowercase()] }
    }

    override fun onDestroy() {
        stopPlaybackClockProbe()
        handler.removeCallbacksAndMessages(null)
        // Before anything else tears down: media3's binder stub keeps the timeline, and through it
        // the renderers and this factory, alive well past this point. Whatever else happens below,
        // the factory must stop pointing back at this service.
        localRenderFactories.values.forEach { it.detach() }
        afFormatTracker.formatChangedCallback = null
        cancelPendingLocalLyricsFallback()
        automixTransitions?.release()
        automixTransitions = null
        automixGhostReplayGainProcessor = null
        automixGhostBassSwapProcessor = null
        remoteControl?.stop()
        remoteControl = null
        instanceForWidgetAndLyricsOnly = null
        // Tell the server we stopped before tearing anything down, otherwise the session is left
        // open and other clients keep showing this track as playing.
        stopProgressReporting()
        reportedMediaId?.let {
            reporter.reportStopped(it, controller?.currentPosition ?: lastReportedPositionMs)
            reportedMediaId = null
        }
        scrobbler.onTrackFinished(
            controller?.currentPosition ?: lastReportedPositionMs,
            playedToEnd = false
        )
        // Important: this must happen before sending stop() as that changes state ENDED -> IDLE.
        // Immediately, not debounced: nothing will be around to run a delayed save.
        lastPlayedManager.saveNow()
        lastPlayedManager.release()
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        usbHiFiManager.release()
        mediaSession!!.player.stop()
        broadcastAudioSessionClose()
        controller!!.release()
        controller = null
        mediaSession!!.release()
        // Every player is released by name. The session only ever held one of the three, so
        // releasing its player alone would leak whichever outputs were not in front at the time.
        // CastPlayer forwards its release to the local player as well; ExoPlayer tolerates the
        // second call, and doing it explicitly means a cast-less device still releases cleanly.
        finnectJob?.cancel()
        finnectJob = null
        receiverStatusCallback?.let { receiverStatusClient?.unregisterCallback(it) }
        receiverStatusClient = null
        receiverStatusCallback = null
        FincordCast.removeSessionTransferCallback(castSessionTransferCallback)
        serviceScope.cancel()
        discardWarmingPlayer()
        finnectPlayer?.release()
        finnectPlayer = null
        castQueuePlayer?.release()
        castQueuePlayer = null
        remoteCastPlayer?.release()
        remoteCastPlayer = null
        localPlayer?.let(::releaseLocalPlayer)
        localPlayer = null
        mediaSession = null
        internalPlaybackThread.quitSafely()
        lyrics = null
        unregisterReceiver(headSetReceiver)
        unregisterReceiver(btCodecReceiver)
        // Player teardown can synchronously enqueue final listener notifications and saves.
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            "usb_hifi" -> usbHiFiManager.refreshRoute()
            Automix.PREF_KEY -> if (!Automix.isEnabled(this)) automixTransitions?.cancel()
            // Baked into the sink and the renderers factory, so the player has to be replaced for
            // either of them to mean anything. See rebuildLocalPlayer.
            "floatoutput" -> rebuildLocalPlayer("high-resolution output changed")
            "ps_hardware_acc" -> rebuildLocalPlayer("hardware playback parameters changed")
            "skip_silence" -> {
                val enabled = prefs.getBooleanStrict("skip_silence", false)
                localPlayer?.exoPlayer?.skipSilenceEnabled = enabled
            }
            "replaygain_mode" -> {
                val wasBypassed = replayGainAudioProcessor.mode == ReplayGainUtil.Mode.None
                replayGainMode = prefs.getStringStrict("replaygain_mode", "0")
                    ?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                applyReplayGainMode()
                // Whether ReplayGain is on at all decides whether high-resolution passthrough is
                // allowed - the two cannot both have the samples. Crossing that line therefore
                // changes how the sink was built, and only matters when passthrough is switched on.
                val nowBypassed = replayGainAudioProcessor.mode == ReplayGainUtil.Mode.None
                if (wasBypassed != nowBypassed && prefs.getBooleanStrict("floatoutput", false)) {
                    rebuildLocalPlayer("ReplayGain changed which pipeline the sink can use")
                }
            }
            "replaygain_preamp_db" -> {
                val value = prefs.getIntStrict("replaygain_preamp_db", 0)
                replayGainAudioProcessor.setPreampDb(value)
                if (automixTransitions?.isTransitioning() != true) {
                    automixGhostReplayGainProcessor?.setPreampDb(value)
                }
            }
            "replaygain_untagged_attenuation_db" -> {
                val value = -prefs.getIntStrict("replaygain_untagged_attenuation_db", 0)
                replayGainAudioProcessor.setUntaggedGainDb(value)
                if (automixTransitions?.isTransitioning() != true) {
                    automixGhostReplayGainProcessor?.setUntaggedGainDb(value)
                }
            }
            JellyfinRemoteTargets.PREF_FINNECT_ENABLED -> {
                if (JellyfinRemoteTargets.isEnabled(this)) {
                    remoteControl?.start()
                } else {
                    remoteControl?.stop()
                    JellyfinRemoteTargets.disable()
                }
            }
        }
    }

    // This onGetSession is a necessary method override needed by
    // MediaSessionService.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        super.onAudioSessionIdChanged(audioSessionId)
        broadcastAudioSessionClose()
        lastSessionId = audioSessionId
        broadcastAudioSession()
    }

    private fun broadcastAudioSession() {
        if (lastSessionId != 0) {
            sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
                putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            })
        }
    }

    private fun broadcastAudioSessionClose() {
        if (lastSessionId != 0) {
            sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, lastSessionId)
            })
        }
    }

    /** Runs before DefaultAudioSink creates AudioTrack, so Android sees the USB preference in time. */
    private fun onAudioSinkInputFormatChanged(format: Format?) {
        // Both sinks report through here while a replacement is warming, and the outgoing one
        // reports null as it is released - after the incoming one has already reported the format
        // it is really playing. Taking that null would blank the format readout until the next
        // track. A real stop still clears it, because nothing is being retired then.
        if (format == null && retiringPlayerSink) return
        audioSinkInputFormat = format
        usbHiFiManager.configure(format)
        broadcastAudioFormat()
    }

    private fun broadcastAudioFormat() {
        mediaSession?.broadcastCustomCommand(
            SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    // Configure commands available to the controller in onConnect()
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo)
            : MediaSession.ConnectionResult {
        val availableSessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        // Repeat and shuffle are harmless playback controls, so every accepted controller gets
        // the commands backing the buttons it is shown. Restricting these to notifications/Auto
        // made Google Home render the session preferences as disabled actions.
        for (commandButton in customCommands) {
            commandButton.sessionCommand?.let { availableSessionCommands.add(it) }
        }
        availableSessionCommands.add(SessionCommand(SERVICE_SET_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_SESSION, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_QUERY_TIMER, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY))
        availableSessionCommands.add(SessionCommand(SERVICE_GET_AUDIO_FORMAT, Bundle.EMPTY))
        handler.post {
            session.sendCustomCommand(
                controller,
                SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY),
                Bundle.EMPTY
            )
        }
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(availableSessionCommands.build())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        return Futures.immediateFuture(when (customCommand.customAction) {
            PLAYBACK_SHUFFLE_ACTION_ON -> {
                this.controller!!.shuffleModeEnabled = true
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_SHUFFLE_ACTION_OFF -> {
                this.controller!!.shuffleModeEnabled = false
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            SERVICE_SET_TIMER -> {
                // 0 = clear timer
                timerDuration = customCommand.customExtras.getInt("duration")
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            SERVICE_QUERY_TIMER -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putInt("duration", timerDuration)
                }
            }

            SERVICE_GET_LYRICS -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putParcelableArray("lyrics", lyrics?.toTypedArray())
                }
            }

            SERVICE_GET_AUDIO_FORMAT -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putBundle("sink_format", audioSinkInputFormat?.toBundle())
                    it.extras.putParcelable("usb_hifi", usbHiFiManager.status)
                    // Unlike sink_format, this is what AudioFlinger and the HAL actually granted
                    // after routing, resampling, offload decisions and device selection.
                    it.extras.putParcelable("hal_format", afFormatTracker.format)
                    it.extras.putParcelable("bt_codec", btCodecInfo)
                }
            }

            SERVICE_GET_SESSION -> {
                SessionResult(SessionResult.RESULT_SUCCESS).also {
                    it.extras.putInt("session", lastSessionId)
                }
            }

            PLAYBACK_REPEAT_OFF -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_OFF
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_REPEAT_ONE -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_ONE
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            PLAYBACK_REPEAT_ALL -> {
                this.controller!!.repeatMode = Player.REPEAT_MODE_ALL
                SessionResult(SessionResult.RESULT_SUCCESS)
            }

            else -> {
                SessionResult(SessionError.ERROR_BAD_VALUE)
            }
        })
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo
    ): ListenableFuture<MediaItemsWithStartPosition> {
        // Resumption restores what *this device* was last playing, which is the wrong thing
        // entirely when a receiver already owns playback: the saved queue gets pushed over the
        // receiver's, reloading it from scratch. Every reload reports INTERRUPTED and empties the
        // timeline, which makes the player look idle, which invites another resumption - the loop
        // that wiped the queue mid-track and left the app blank over a speaker that kept playing.
        if (isRemoteOutputActive()) {
            Log.d(TAG, "declining playback resumption; a remote output already owns playback")
            return Futures.immediateFailedFuture(
                IllegalStateException("remote output already owns playback")
            )
        }
        val settable = SettableFuture.create<MediaItemsWithStartPosition>()
        lastPlayedManager.restore { items, factory ->
            applyShuffleSeed(true, factory.toFactory(this.controller!!))
            if (items == null) {
                settable.setException(
                    NullPointerException(
                        "null MediaItemsWithStartPosition, see former logs for root cause"
                    )
                )
            } else if (items.mediaItems.isNotEmpty()) {
                settable.set(items)
            } else {
                settable.setException(
                    IndexOutOfBoundsException(
                        "LastPlayedManager restored empty MediaItemsWithStartPosition"
                    )
                )
            }
        }
        return settable
    }

    override fun onTracksChanged(tracks: Tracks) {
        val sourceFormat = tracks.groups.firstNotNullOfOrNull { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@firstNotNullOfOrNull null
            (0 until group.length)
                .firstOrNull(group::isTrackSelected)
                ?.let(group::getTrackFormat)
                ?.takeIf { MimeTypes.isAudio(it.sampleMimeType) }
        }
        replayGainAudioProcessor.setRootFormat(sourceFormat)
        // This is the richer lookup: it can read the selected track's already-decoded metadata.
        // Suppress the metadata-only fallback scheduled by the item transition when this callback
        // arrives normally.
        cancelPendingLocalLyricsFallback()
        resolveLyrics(controller!!.currentMediaItem, tracks)
    }

    private fun cancelPendingLocalLyricsFallback() {
        pendingLocalLyricsFallback?.let(handler::removeCallbacks)
        pendingLocalLyricsFallback = null
    }

    /**
     * Local playback normally supplies [onTracksChanged], but an Automix handover can reuse an
     * equivalent, already-prepared track selection and Media3 then has no track change to report.
     * Wait briefly for the richer callback, then fall back to the sidecar/header/server path.
     */
    private fun scheduleLocalLyricsFallback(mediaItem: MediaItem?) {
        cancelPendingLocalLyricsFallback()
        val item = mediaItem ?: return
        val requestedMediaId = item.mediaId
        val fallback = Runnable {
            pendingLocalLyricsFallback = null
            if (controller?.currentMediaItem?.mediaId == requestedMediaId) {
                resolveLyrics(item, Tracks.EMPTY)
            }
        }
        pendingLocalLyricsFallback = fallback
        handler.postDelayed(fallback, LOCAL_LYRICS_TRACKS_FALLBACK_MS)
    }

    /**
     * Finds the words for a track: embedded tags first, then a sidecar file, then the server.
     *
     * The order matters and so does the trigger. Embedded eLRC lives in the file's own metadata,
     * which only exists here once something has decoded the file - so this normally runs from
     * [onTracksChanged]. Nothing decodes locally while a receiver or another Jellyfin session is
     * playing, that callback never arrives with track metadata, and lyrics simply never appeared
     * for the whole of a cast. The server branch covers that: Jellyfin reads the same embedded
     * eLRC out of the same file and returns it with its timestamps intact, which is what the
     * lyric view needs to follow the receiver's clock.
     */
    private fun resolveLyrics(mediaItem: MediaItem?, tracks: Tracks) {
        val item = mediaItem ?: return
        val requestedMediaId = item.mediaId
        lyricsLock.runInBg {
            // Default on. LRC files routinely carry a space after the timestamp, which is
            // formatting rather than part of the words - untrimmed it indented the first
            // line of every verse while the lines it wrapped onto stayed flush.
            val trim = prefs.getBoolean("trim_lyrics", true)
            var lrc = loadAndParseLyricsFile(item.getFile(), trim)
            if (lrc == null) {
                loop@ for (i in tracks.groups) {
                    for (j in 0 until i.length) {
                        if (!i.isTrackSelected(j)) continue
                        // note: wav files can have null metadata
                        val trackMetadata = i.getTrackFormat(j).metadata ?: continue
                        lrc = extractAndParseLyrics(trackMetadata, trim) ?: continue
                        // add empty element at the beginning
                        lrc.add(0, MediaStoreUtils.Lyric())
                        break@loop
                    }
                }
            } else {
                // add empty element at the beginning
                lrc.add(0, MediaStoreUtils.Lyric())
            }
            if (lrc == null) {
                // Still the embedded eLRC, just read without playing the file. Nothing decodes on
                // this device while a receiver has the music, so the tags never arrive as track
                // metadata above - but they are in the mp3/flac either way, and fetching only the
                // header is far cheaper than giving up on what the file actually carries.
                lrc = retrieveEmbeddedLyrics(item, trim)
                lrc?.add(0, MediaStoreUtils.Lyric())
            }
            if (lrc == null) {
                // Last resort: ask the server. This catches .lrc files sitting beside the track on
                // the server, which the client never sees because it only receives the audio
                // stream. Tried last because it is the only branch that costs a network round trip.
                lrc = JellyfinLyricsSource.load(this@GramophonePlaybackService, requestedMediaId, trim)
                lrc?.add(0, MediaStoreUtils.Lyric())
            }
            withContext(Dispatchers.Main) {
                // Network/tag reads are serialized, not cancelled. A slow result for the outgoing
                // song must never overwrite lyrics belonging to the item now in the session.
                if (controller?.currentMediaItem?.mediaId != requestedMediaId) {
                    Log.d(TAG, "Discarding stale lyrics for $requestedMediaId")
                    return@withContext
                }
                mediaSession?.let {
                    lyrics = lrc
                    it.broadcastCustomCommand(
                        SessionCommand(SERVICE_GET_LYRICS, Bundle.EMPTY),
                        Bundle.EMPTY
                    )
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val incomingMediaId = mediaItem?.mediaId
        if (
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
            incomingMediaId != null && incomingMediaId == transitionedMediaId
        ) {
            // Automix clips the *next* queue item with replaceMediaItem(). Media3 reports that as
            // PLAYLIST_CHANGED for the unchanged current item. Treating it as a song transition
            // clears lyrics and also double-reports/scrobbles the current track.
            automixTransitions?.onMediaItemTransition(reason)
            Log.v(TAG, "Ignoring same-item playlist transition for $incomingMediaId")
            return
        }
        transitionedMediaId = incomingMediaId
        // The ghost executor advances with an ordinary seek so Media3 updates every normal session
        // consumer. Remember that this particular seek stands in for the outgoing song ending.
        val automixAutoAdvance = automixTransitions?.consumeAutoAdvance() == true
        applyReplayGainMode()
        cancelPendingLocalLyricsFallback()
        lyrics = null
        lastPlayedManager.save()
        // A transition Automix did not cause - a skip, a queue jump, a track simply ending - means
        // the tail it was about to mix belongs to a track that is no longer the one leaving, so
        // whatever was prepared is now wrong. Its own handover is exempt, or it would cancel itself
        // in the moment it fired.
        automixTransitions?.onMediaItemTransition(reason)
        // The shortcut is both a launcher affordance and a semantic hint to the device's local
        // suggestion engine. Publishing here tracks actual queue transitions and works for local,
        // Jellyfin-remote and Cast playback without inventing a second MediaSession.
        mediaItem?.let { item ->
            serviceScope.launch(Dispatchers.Default) { PlaybackShortcuts.push(this@GramophonePlaybackService, item) }
        }
        // Which device is about to make the sound, and whether the Cast leg still believes it has
        // a session. "It played on the phone instead of the speaker" is exactly the disagreement
        // between these two that is otherwise invisible from the outside.
        val remote = isRemoteOutputActive()
        Log.d(
            TAG,
            "transition to ${mediaItem?.mediaId} on ${if (remote) "REMOTE" else "LOCAL"} output " +
                "(castSession=${remoteCastPlayer?.isCastSessionAvailable}, " +
                "remoteItems=${remoteCastPlayer?.mediaItemCount})",
        )
        // A remote output decodes nothing on this device, so no onTracksChanged will follow. Local
        // playback normally gets that callback, but Automix may retain an equivalent prepared
        // selection across its natural handover; schedule a fallback for that case.
        if (remote) resolveLyrics(mediaItem, Tracks.EMPTY)
        else scheduleLocalLyricsFallback(mediaItem)
        prefetchUpcoming()
        // Close out the previous track before opening the new one, so the server sees a clean
        // start/stop pair rather than two overlapping sessions.
        reportedMediaId?.let { previous ->
            reporter.reportStopped(previous, lastReportedPositionMs)
        }
        // AUTO means the player reached the end of the previous track by itself, so it was heard in
        // full. Any other reason - a skip, a seek to another item, a new queue - means the last
        // heartbeat's position is the best estimate of how much was actually listened to.
        scrobbler.onTrackFinished(
            lastReportedPositionMs,
            playedToEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || automixAutoAdvance
        )
        reportedMediaId = mediaItem?.mediaId
        lastReportedPositionMs = 0L
        reportedMediaId?.let {
            reporter.reportStart(it, 0L, controller?.isPlaying != true)
        }
        scrobbler.onTrackStarted(mediaItem, System.currentTimeMillis() / 1000)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        automixTransitions?.onPositionDiscontinuity(reason)
    }

    /**
     * Warms the next few tracks so the following skip does not wait on the network.
     *
     * Lives here rather than in the player UI: the queue advances whether or not anyone is looking
     * at it, and the point is for the track after this one to be ready before it is asked for.
     */
    private fun prefetchUpcoming() {
        val player = controller ?: return
        val upcoming = buildList {
            var index = player.currentMediaItemIndex + 1
            while (index < player.mediaItemCount && size < PREFETCH_LOOKAHEAD) {
                add(player.getMediaItemAt(index))
                index++
            }
        }
        QueuePrefetcher.prefetch(this, upcoming, imageLoader)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        lastPlayedManager.save()
        // Pausing and resuming both need to reach the server promptly, otherwise other clients keep
        // showing the track as still playing.
        reportedMediaId?.let {
            lastReportedPositionMs = controller?.currentPosition ?: 0L
            reporter.reportProgress(it, lastReportedPositionMs, !isPlaying)
        }
        if (isPlaying) {
            // Resuming a queue restored after the app was killed loads no new item, so no
            // transition fires and the scrobbler would never learn what is playing.
            scrobbler.ensureTracking(controller?.currentMediaItem, System.currentTimeMillis() / 1000)
        } else {
            // Pausing is the last chance to catch a track that crossed the scrobble threshold since
            // the previous heartbeat and is about to sit paused indefinitely.
            scrobbler.onProgress(controller?.currentPosition ?: lastReportedPositionMs)
        }
        if (isPlaying) startProgressReporting() else stopProgressReporting()
    }

    /**
     * Periodic progress reporting.
     *
     * Jellyfin expects a heartbeat while a track plays; without it the server's idea of the
     * position drifts and the session eventually looks stale. Ten seconds matches what the official
     * clients use - frequent enough to be useful, rare enough to be invisible on battery.
     */
    private fun startProgressReporting() {
        stopProgressReporting()
        handler.postDelayed(progressReportRunnable, PROGRESS_REPORT_INTERVAL_MS)
    }

    private fun stopProgressReporting() {
        handler.removeCallbacks(progressReportRunnable)
    }

    private val progressReportRunnable = object : Runnable {
        override fun run() {
            val id = reportedMediaId
            val player = controller
            if (id != null && player != null) {
                lastReportedPositionMs = player.currentPosition
                reporter.reportProgress(id, lastReportedPositionMs, !player.isPlaying)
                scrobbler.onProgress(lastReportedPositionMs)
                scheduleAutomixAnalysis()
            }
            handler.postDelayed(this, PROGRESS_REPORT_INTERVAL_MS)
        }
    }

    /**
     * Offers the upcoming track to Automix's analyser as the current one runs out.
     *
     * Rides the progress heartbeat rather than owning a timer: the analysis only has to start
     * somewhere in the last half-minute, which ten-second granularity covers comfortably, and a
     * second periodic wakeup would cost battery for precision nothing needs.
     */
    private fun scheduleAutomixAnalysis() {
        val remote = isRemoteOutputActive()
        // The ghost executor must automate the concrete local player, not the MediaController
        // facade whose active player may switch to Cast or Finnect underneath it.
        val transitionPlayer = localPlayer ?: return
        val nextIndex = transitionPlayer.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) {
            automixTransitions?.cancel()
            return
        }
        val next = transitionPlayer.getMediaItemAt(nextIndex)
        AutomixAnalysisScheduler.onProgress(
            context = this,
            next = next,
            positionMs = lastReportedPositionMs,
            durationMs = transitionPlayer.duration,
            remoteOutput = remote,
        )
        automixTransitions?.onProgress(
            player = transitionPlayer,
            current = transitionPlayer.currentMediaItem,
            next = next,
            positionMs = transitionPlayer.currentPosition,
            durationMs = transitionPlayer.duration,
            remoteOutput = remote,
        )
    }

    override fun onEvents(player: Player, events: Player.Events) {
        super.onEvents(player, events)
        // if timeline changed, handle shuffle update in onTimelineChanged() instead
        // (onTimelineChanged() runs before both this callback and onShuffleModeEnabledChanged(),
        // which means shuffleFactory != null is not a valid check)
        if (events.contains(EVENT_SHUFFLE_MODE_ENABLED_CHANGED) &&
            shuffleFactory == null && !events.contains(Player.EVENT_TIMELINE_CHANGED)
        ) {
            // when enabling shuffle, re-shuffle lists so that the first index is up to date
            applyShuffleSeed(false) { c -> { CircularShuffleOrder(
                it, c, controller!!.mediaItemCount, Random.nextLong()) } }
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        super.onShuffleModeEnabledChanged(shuffleModeEnabled)
        mediaSession!!.setMediaButtonPreferences(mediaButtonPreferences())
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        super.onTimelineChanged(timeline, reason)
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            applyReplayGainMode()
            // The one thing that makes the stored queue stale. Everything else - skipping, pausing,
            // seeking - only moves the position, and re-encoding thousands of tracks to record that
            // is what made a large queue crawl.
            lastPlayedManager.markQueueDirty()
            shuffleFactory?.let {
                // A shuffle order is ExoPlayer's own, and sized to the local queue. While a
                // receiver owns playback these timeline events describe *its* queue instead, and
                // an order sized for a different list is rejected outright - which is what took
                // the app down on a playlist change mid-cast. Held rather than discarded, so it
                // still applies when playback comes back to this device.
                if (!isRemoteOutputActive()) {
                    applyShuffleSeed(false, it)
                    shuffleFactory = null
                }
            }
        }
    }

    private fun readReplayGainPreferences() {
        replayGainMode = prefs.getStringStrict("replaygain_mode", "0")
            ?.toIntOrNull()?.coerceIn(0, 3) ?: 0
        replayGainAudioProcessor.setPreampDb(
            prefs.getIntStrict("replaygain_preamp_db", 0),
        )
        replayGainAudioProcessor.setUntaggedGainDb(
            -prefs.getIntStrict("replaygain_untagged_attenuation_db", 0),
        )
        applyReplayGainMode()
    }

    private fun applyReplayGainMode() {
        val mode = when (replayGainMode) {
            0 -> ReplayGainUtil.Mode.None
            1 -> ReplayGainUtil.Mode.Track
            2 -> ReplayGainUtil.Mode.Album
            3 -> {
                val player = controller
                val index = player?.currentMediaItemIndex ?: C.INDEX_UNSET
                val currentAlbumId = player?.currentMediaItem?.mediaMetadata?.albumId
                val previousAlbumId = if (player != null && index > 0) {
                    player.getMediaItemAt(index - 1).mediaMetadata.albumId
                } else null
                val nextAlbumId = if (player != null && index >= 0 && index + 1 < player.mediaItemCount) {
                    player.getMediaItemAt(index + 1).mediaMetadata.albumId
                } else null
                if (currentAlbumId != null &&
                    (currentAlbumId == previousAlbumId || currentAlbumId == nextAlbumId)
                ) ReplayGainUtil.Mode.Album else ReplayGainUtil.Mode.Track
            }
            else -> ReplayGainUtil.Mode.None
        }
        replayGainAudioProcessor.setMode(mode)
        // Once the ghost is carrying an outgoing tail its tags/mode belong to that song. The main
        // player's incoming transition must not switch the tail from album to track gain mid-fade.
        if (automixTransitions?.isTransitioning() != true) {
            automixGhostReplayGainProcessor?.setMode(mode)
        }
    }

    private fun syncAutomixGhostReplayGain() {
        applyReplayGainMode()
        automixGhostReplayGainProcessor?.let {
            it.setPreampDb(replayGainAudioProcessor.preampDb)
            it.setUntaggedGainDb(replayGainAudioProcessor.untaggedGainDb)
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        super.onRepeatModeChanged(repeatMode)
        mediaSession!!.setMediaButtonPreferences(mediaButtonPreferences())
        if (needsMissingOnDestroyCallWorkarounds()) {
            handler.post { lastPlayedManager.save() }
        }
    }

    @SuppressLint("MissingPermission", "NotificationPermission") // only used on S/S_V2
    override fun onForegroundServiceStartNotAllowedException() {
        Log.w(TAG, "Failed to resume playback :/")
        if (mayThrowForegroundServiceStartNotAllowed()) {
            nm.notify(NOTIFY_ID, NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID).apply {
                setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                setAutoCancel(true)
                setCategory(NotificationCompat.CATEGORY_ERROR)
                setSmallIcon(R.drawable.ic_warning)
                setContentTitle(this@GramophonePlaybackService.getString(R.string.fgs_failed_title))
                setContentText(this@GramophonePlaybackService.getString(R.string.fgs_failed_text))
                setContentIntent(
                    PendingIntent.getActivity(
                        this@GramophonePlaybackService,
                        PENDING_INTENT_NOTIFY_ID,
                        Intent(this@GramophonePlaybackService, MainActivity::class.java)
                            .putExtra(MainActivity.PLAYBACK_AUTO_START_FOR_FGS, true),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                setVibrate(longArrayOf(0L, 200L))
                setLights(0, 0, 0)
                setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                setSound(null)
            }.build())
        } else {
            handler.post {
                throw IllegalStateException("onForegroundServiceStartNotAllowedException shouldn't be called on T+")
            }
        }
    }

    /**
     * Wraps the local player in a [CastPlayer] whenever this device can cast at all.
     *
     * media3 1.9's CastPlayer is itself a forwarding player over a local and a remote one: it
     * chooses which is active, carries the queue and position across when a session starts or
     * ends, and reports the pair through the system Output Switcher. Handing *this* to the session
     * rather than the ExoPlayer is the whole point - a session pointed at the local player goes on
     * describing a paused phone for as long as a receiver is playing, and that description is what
     * the notification, the lock screen, Android Auto and every other app end up showing.
     *
     * Falls back to plain local playback on a device with no Play Services, where constructing any
     * of this throws.
     */
    private fun buildCastAwarePlayer(local: EndedWorkaroundPlayer): Player {
        if (!FincordCast.isAvailable) return local
        return runCatching {
            val converter = AccordMediaItemConverter()
            val remote = RemoteCastPlayer.Builder(this)
                .setMediaItemConverter(converter)
                .build()
                .also { remoteCastPlayer = it }
            castQueuePlayer = CastQueuePlayer(Looper.getMainLooper(), local, remote, converter)
            remote.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // A rejected Cast load is otherwise silent: the remote player simply reports an
                    // empty timeline, which is indistinguishable from "nothing was ever sent".
                    Log.e(TAG, "receiver rejected playback", error)
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    // How many items landed, and how many of them the Cast layer could actually
                    // name. An item it could not resolve still holds its place in the queue - the
                    // order stays right and the row is still clickable - but it carries no title,
                    // artist or stream URL, which is what the queue renders as "unknown track".
                    val window = Timeline.Window()
                    var named = 0
                    for (i in 0 until timeline.windowCount) {
                        if (timeline.getWindow(i, window).mediaItem.mediaMetadata.title != null) named++
                    }
                    Log.d(
                        TAG,
                        "receiver timeline now ${timeline.windowCount} items, " +
                            "$named named (reason $reason)",
                    )
                }
            })
            remote.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    observeReceiverStatus()
                    handler.post { onReceiverAvailable() }
                }

                override fun onCastSessionUnavailable() {
                    handler.post { onReceiverGone() }
                }
            })
            local
        }.onFailure { Log.w(TAG, "cast player unavailable, staying local", it) }
            .getOrDefault(local)
    }

    /**
     * A receiver became available: put the queue player in front of the session.
     *
     * The queue player keeps this phone's full queue and gives the receiver only the stretch it
     * needs, which is why the session keeps a complete, named queue while a cast is running.
     *
     * A receiver that already holds a queue is left strictly alone. Adopting it is the entire point
     * of joining one, and pushing this phone's queue over it would hijack playback that another
     * sender - or this app before its process was killed - had already started.
     */
    private fun onReceiverAvailable() {
        automixTransitions?.cancel()
        val queuePlayer = castQueuePlayer ?: return
        val local = localPlayer ?: return
        val remote = remoteCastPlayer ?: return
        val client = FincordCast.session.value?.remoteMediaClient ?: return
        castHandbackApplied = false
        queuePlayer.attach(client)
        val startIndex = local.currentMediaItemIndex.coerceAtLeast(0)
        val positionMs = local.currentPosition.coerceAtLeast(0L)
        // Choosing a speaker is a request to play there, so an explicitly paused phone is the only
        // thing that should arrive paused.
        val playWhenReady = local.playWhenReady || local.isPlaying
        local.pause()
        updateSessionPlayer()
        // MediaQueue is synchronized asynchronously. During a cold process start it can report
        // zero for a live receiver for a few hundred milliseconds. hasMediaSession/status are the
        // authority here: a resumed session must never be mistaken for a new empty receiver.
        val currentItemId = client.mediaStatus?.currentItemId
            ?: MediaQueueItem.INVALID_ITEM_ID
        if (client.hasMediaSession() ||
            currentItemId != MediaQueueItem.INVALID_ITEM_ID ||
            client.mediaQueue.itemCount > 0
        ) {
            Log.d(
                TAG,
                "receiver already has media (IDs=${client.mediaQueue.itemCount}, " +
                    "current=$currentItemId); adopting without reload",
            )
            client.requestStatus()
            return
        }
        if (local.mediaItemCount == 0) return
        queuePlayer.startCasting(startIndex, positionMs, playWhenReady)
    }

    /**
     * The receiver went away: hand playback back to this device.
     *
     * Only the position has to come back. The local player never gave up its queue, so restoring it
     * is a seek rather than a reload - which also means coming off a cast cannot shorten the queue
     * to whatever stretch the receiver happened to be holding.
     */
    private fun onReceiverGone() {
        if (castHandbackApplied) return
        val local = localPlayer ?: return
        val queuePlayer = castQueuePlayer
        // Session teardown clears Cast's current item and reports position zero before this
        // callback. Read the queue player's last status that still had an active receiver item.
        val snapshot = queuePlayer?.handoffSnapshot()
        val index = snapshot?.mediaItemIndex ?: local.currentMediaItemIndex
        val positionMs = snapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
        val playWhenReady = snapshot?.playWhenReady ?: false
        queuePlayer?.detach()
        // The session is over, so any "play here instead" said about it is over too.
        OutputRouting.reset()
        updateSessionPlayer()
        if (index in 0 until local.mediaItemCount) local.seekTo(index, positionMs)
        local.playWhenReady = playWhenReady
        local.prepare()
        castHandbackApplied = true
        Log.d(TAG, "receiver gone; local playback resumes at $index/$positionMs")
    }

    /**
     * Puts whichever player owns the current output in front of the session.
     *
     * One place decides, so the three outputs cannot disagree about who is playing. Finnect wins
     * over Cast when both are somehow live, because it is the one the user chose explicitly.
     */
    private fun updateSessionPlayer() {
        val session = mediaSession ?: return
        val next = when {
            // A target can be connected and deliberately unused; see [OutputRouting]. It outranks
            // both remotes because it is the only one of the three the user stated outright.
            OutputRouting.playLocally.value -> localPlayer
            JellyfinRemoteTargets.active.value != null -> finnectPlayer
            remoteCastPlayer?.isCastSessionAvailable == true -> castQueuePlayer
            else -> localPlayer
        } ?: return
        if (session.player === next) {
            // The route settled back on this phone and a sink setting is still waiting on it.
            if (next === localPlayer && pendingLocalPlayerRebuild) {
                rebuildLocalPlayer("a deferred audio setting")
            }
            return
        }
        if (next !== localPlayer) automixTransitions?.cancel()
        session.player = next
        Log.d(TAG, "session player is now ${next.javaClass.simpleName}")
        if (next === localPlayer && pendingLocalPlayerRebuild) {
            rebuildLocalPlayer("a deferred audio setting")
        }
    }

    /**
     * Reads a track's embedded lyrics out of its tags without playing it.
     *
     * The eLRC lives in the mp3/flac, so it is still there while a receiver is playing - what is
     * missing is the local decode that would otherwise surface it as track metadata.
     * [MetadataRetriever] pulls just enough of the stream to parse the tags, over the same data
     * source the player uses, so casting keeps the lyrics the file actually carries instead of
     * dropping to the server's copy of them.
     */
    private suspend fun retrieveEmbeddedLyrics(
        mediaItem: MediaItem?,
        trim: Boolean,
    ): MutableList<MediaStoreUtils.Lyric>? {
        if (mediaItem?.localConfiguration?.uri == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val groups = MetadataRetriever.retrieveMetadata(
                    GramophoneMediaSourceFactory(
                        JellyfinMediaCache.dataSourceFactory(this@GramophonePlaybackService),
                        GramophoneExtractorsFactory(),
                    ),
                    mediaItem,
                ).get(EMBEDDED_LYRICS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                (0 until groups.length).firstNotNullOfOrNull { groupIndex ->
                    val group = groups.get(groupIndex)
                    (0 until group.length).firstNotNullOfOrNull { trackIndex ->
                        group.getFormat(trackIndex).metadata
                            ?.let { extractAndParseLyrics(it, trim) }
                    }
                }
            }.onFailure {
                Log.w(TAG, "no embedded lyrics for ${mediaItem.mediaId}: ${it.message}")
            }.getOrNull()
        }
    }

    /**
     * Logs what the receiver says about its own playback.
     *
     * media3 surfaces a receiver that cannot play something as an ordinary end-of-item, so a queue
     * whose every track fails looks exactly like a queue that played very fast. `idleReason` is the
     * field that separates the two: `ERROR` (4) means the stream was refused, `FINISHED` (1) means
     * it genuinely reached the end.
     */
    private fun observeReceiverStatus() {
        val client = FincordCast.session.value?.remoteMediaClient ?: return
        // Per client, not once ever. A session that drops and comes back brings a new
        // RemoteMediaClient with it, and the old registration goes with the old one - which is why
        // receiver state simply stopped being reported partway through the last session.
        if (receiverStatusClient === client) return
        receiverStatusCallback?.let { receiverStatusClient?.unregisterCallback(it) }
        receiverStatusClient = client
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = client.mediaStatus ?: return
                Log.d(
                    TAG,
                    "receiver state=${status.playerState} idleReason=${status.idleReason} " +
                        "queue=${status.queueItemCount} current=${status.currentItemId} " +
                        "contentId=${client.mediaInfo?.contentId?.takeLast(60)}",
                )
            }
        }
        receiverStatusCallback = callback
        client.registerCallback(callback)
    }


    /** True while a receiver or another Jellyfin session, rather than this phone, is playing. */
    private fun isRemoteOutputActive(): Boolean =
        mediaSession?.player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE


    /**
     * Puts the Finnect player in front of the session while a Jellyfin target owns playback.
     *
     * Cast has its own trigger in the receiver-availability callbacks. Only the transition in and
     * out of this *third* output is watched here, and only when the target appears or disappears -
     * not on every state change the remote session reports.
     */
    private fun observeFinnectTarget() {
        finnectJob?.cancel()
        outputRoutingJob?.cancel()
        outputRoutingJob = serviceScope.launch {
            OutputRouting.playLocally
                .drop(1)
                .distinctUntilChanged()
                .collect(::applyOutputRouting)
        }
        finnectJob = serviceScope.launch {
            JellyfinRemoteTargets.active
                .distinctUntilChanged { old, new -> (old == null) == (new == null) }
                .collect {
                    // A target that goes away takes any "play here instead" with it, or the next
                    // one picked would silently inherit a decision about a device that is gone.
                    if (JellyfinRemoteTargets.active.value == null &&
                        remoteCastPlayer?.isCastSessionAvailable != true
                    ) {
                        OutputRouting.reset()
                    }
                    updateSessionPlayer()
                }
        }
    }

    /**
     * Moves playback between this phone and a connected remote target without disconnecting it.
     *
     * The two directions are the two hand-offs that already exist, with the session left alone:
     * coming home is [onReceiverGone]'s restore, going back out is [onReceiverAvailable]'s load.
     * Only the position travels either way - the local player never gives up the queue - so this
     * cannot shorten it to whatever stretch the receiver happened to be holding.
     */
    private fun applyOutputRouting(playLocally: Boolean) {
        val local = localPlayer ?: return
        val queuePlayer = castQueuePlayer
        val casting = remoteCastPlayer?.isCastSessionAvailable == true
        val finnect = finnectPlayer?.takeIf { JellyfinRemoteTargets.active.value != null }
        if (!casting && finnect == null) {
            // Nothing is connected, so there is nothing to move; the preference is stale.
            OutputRouting.reset()
            return
        }
        if (playLocally) {
            // Whichever remote holds playback knows where it had got to. Cast's status is read
            // through the queue player because a detaching session reports position zero; the
            // Finnect player is an ordinary Player and answers for itself.
            val snapshot = queuePlayer?.takeIf { casting }?.handoffSnapshot()
            val index = snapshot?.mediaItemIndex
                ?: finnect?.currentMediaItemIndex
                ?: local.currentMediaItemIndex
            val positionMs = (snapshot?.positionMs ?: finnect?.currentPosition ?: local.currentPosition)
                .coerceAtLeast(0L)
            val playWhenReady = snapshot?.playWhenReady ?: finnect?.playWhenReady ?: local.playWhenReady
            // Silence the target rather than give it up: it stays picked, and going back out is
            // what the picker's other row is for.
            if (casting) FincordCast.session.value?.remoteMediaClient?.pause()
            finnect?.pause()
            queuePlayer?.detach()
            updateSessionPlayer()
            if (index in 0 until local.mediaItemCount) local.seekTo(index, positionMs)
            local.playWhenReady = playWhenReady
            local.prepare()
            Log.d(TAG, "output moved to this device at $index/$positionMs")
            return
        }
        automixTransitions?.cancel()
        val startIndex = local.currentMediaItemIndex.coerceAtLeast(0)
        val positionMs = local.currentPosition.coerceAtLeast(0L)
        val playWhenReady = local.playWhenReady || local.isPlaying
        local.pause()
        updateSessionPlayer()
        if (casting) {
            val client = FincordCast.session.value?.remoteMediaClient ?: return
            queuePlayer?.attach(client)
            if (local.mediaItemCount == 0) return
            queuePlayer?.startCasting(startIndex, positionMs, playWhenReady)
        } else {
            finnect?.seekTo(startIndex, positionMs)
            finnect?.playWhenReady = playWhenReady
        }
        Log.d(TAG, "output moved back to the target at $startIndex/$positionMs")
    }

    private fun applyShuffleSeed(lazy: Boolean, factory:
        (Int) -> ((CircularShuffleOrder) -> Unit) -> CircularShuffleOrder) {
        if (lazy) {
            shuffleFactory = factory
        } else {
            localPlayer?.let {
                val data = try {
                    factory(it.currentMediaItemIndex)
                } catch (e: IllegalStateException) {
                    lastPlayedManager.eraseShuffleOrder()
                    throw e
                }
                try {
                    it.setShuffleOrder(data)
                } catch (e: IllegalArgumentException) {
                    // A saved order only fits the queue it was saved against. If that queue has
                    // since been replaced, the order is meaningless - which is a reason to drop it,
                    // never a reason to bring the app down.
                    Log.w(TAG, "saved shuffle order does not fit the current queue; discarding it")
                    lastPlayedManager.eraseShuffleOrder()
                }
            }
        }
    }
}
