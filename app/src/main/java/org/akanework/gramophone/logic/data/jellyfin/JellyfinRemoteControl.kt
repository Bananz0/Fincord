package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommands
import org.jellyfin.sdk.api.sockets.subscribePlayStateCommands
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateCommand

/**
 * Lets other Jellyfin clients drive this one.
 *
 * The same idea as Spotify Connect, except the protocol already exists: Jellyfin brokers remote
 * control between sessions, so a client that advertises the right capabilities and listens on the
 * server's socket becomes a target in every other client's "Play on" menu. Nothing here is
 * Accord-specific and nothing has to be agreed with anyone - the web client, Findroid and Jellyfin
 * Media Player all speak this already, which is the part Spotify needs a licensing programme for.
 *
 * Two halves, and this is the receiving one: say what this client can do, then obey what arrives.
 *
 * Lives with the playback service because that is what owns the player. The honest consequence is
 * that Accord is a target while it is running, not while it is swiped away - Android does not hand
 * out permanently resident processes, and pretending otherwise would mean a foreground service
 * whose only job is to wait.
 */
class JellyfinRemoteControl(
    private val context: Context,
    private val player: () -> Player?,
    private val resolve: suspend (List<String>) -> List<MediaItem>,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var socketJob: Job? = null
    private var advertiseJob: Job? = null

    /**
     * Announces this client and starts listening.
     *
     * Capabilities are re-posted on every start rather than once ever: the server keys them to the
     * session, and a session is created fresh each time this device connects.
     */
    fun start() {
        if (socketJob?.isActive == true) return
        advertiseJob?.cancel()
        advertiseJob = scope.launch(Dispatchers.IO) { advertise(enabled = true) }
        socketJob = scope.launch {
            val api = withContext(Dispatchers.IO) { JellyfinClientHolder.api() } ?: return@launch

            // Three independent streams rather than one switch. They arrive on different message
            // types and a failure to parse one should not take the others down with it.
            launch { collectPlay(api) }
            launch { collectPlaystate(api) }
            launch { collectGeneral(api) }
        }
    }

    fun stop() {
        socketJob?.cancel()
        socketJob = null
        advertiseJob?.cancel()
        advertiseJob = scope.launch(Dispatchers.IO) { advertise(enabled = false) }
    }

    /**
     * Tells the server what this client will accept.
     *
     * [supportsMediaControl] is the flag that actually puts Accord in other clients' device lists;
     * the command list is what they grey out. Only audio is claimed, because that is all this app
     * can play - offering video would make it a target for something it would then refuse.
     */
    private suspend fun advertise(enabled: Boolean) {
        val api = JellyfinClientHolder.api() ?: return
        try {
            api.sessionApi.postFullCapabilities(
                data = ClientCapabilitiesDto(
                    playableMediaTypes = listOf(MediaType.AUDIO),
                    supportedCommands = if (enabled) SUPPORTED_COMMANDS else emptyList(),
                    supportsMediaControl = enabled,
                    supportsPersistentIdentifier = true,
                )
            )
            Log.d(TAG, "Advertised remote-control capabilities: enabled=$enabled")
        } catch (e: Exception) {
            // Not fatal: the app still plays, it simply will not appear as a target.
            Log.w(TAG, "Could not advertise capabilities", e)
        }
    }

    /** "Play this here" - the message behind another client's Play on / Add to queue. */
    private suspend fun collectPlay(api: org.jellyfin.sdk.api.client.ApiClient) {
        api.webSocket.subscribe(PlayMessage::class).collectLatest { message ->
            val request = message.data ?: return@collectLatest
            val ids = request.itemIds.orEmpty().map { it.toString() }
            if (ids.isEmpty()) return@collectLatest
            val items = withContext(Dispatchers.IO) { resolve(ids) }
            if (items.isEmpty()) {
                Log.w(TAG, "Asked to play ${ids.size} items none of which are in the library")
                return@collectLatest
            }
            val target = player() ?: return@collectLatest
            when (request.playCommand) {
                PlayCommand.PLAY_NOW -> {
                    val start = request.startIndex ?: 0
                    target.setMediaItems(items, start, request.startPositionTicks.toMs())
                    target.prepare()
                    target.play()
                }
                // Both land relative to what is playing rather than at the ends of the timeline,
                // which is what the names mean to the client that sent them.
                PlayCommand.PLAY_NEXT ->
                    target.addMediaItems(target.currentMediaItemIndex + 1, items)

                PlayCommand.PLAY_LAST -> target.addMediaItems(items)
                else -> Log.d(TAG, "Ignoring play command ${request.playCommand}")
            }
            Log.d(TAG, "Remote ${request.playCommand} of ${items.size} items")
        }
    }

    /** Transport: the buttons another client's now-playing screen shows for this session. */
    private suspend fun collectPlaystate(api: org.jellyfin.sdk.api.client.ApiClient) {
        api.webSocket.subscribePlayStateCommands().collectLatest { message ->
            val target = player() ?: return@collectLatest
            when (message.data?.command) {
                PlaystateCommand.PLAY_PAUSE -> if (target.isPlaying) target.pause() else target.play()
                PlaystateCommand.PAUSE -> target.pause()
                PlaystateCommand.UNPAUSE -> target.play()
                PlaystateCommand.STOP -> target.stop()
                PlaystateCommand.NEXT_TRACK -> target.seekToNextMediaItem()
                PlaystateCommand.PREVIOUS_TRACK -> target.seekToPreviousMediaItem()
                PlaystateCommand.SEEK ->
                    target.seekTo(message.data?.seekPositionTicks.toMs())
                else -> Log.d(TAG, "Ignoring playstate ${message.data?.command}")
            }
        }
    }

    /** Everything that is not transport: volume, shuffle, repeat. */
    private suspend fun collectGeneral(api: org.jellyfin.sdk.api.client.ApiClient) {
        api.webSocket.subscribeGeneralCommands().collectLatest { message ->
            val target = player() ?: return@collectLatest
            when (message.data?.name) {
                GeneralCommandType.SET_VOLUME ->
                    message.data?.arguments?.get("Volume")?.toFloatOrNull()?.let {
                        target.volume = (it / 100F).coerceIn(0F, 1F)
                    }

                GeneralCommandType.VOLUME_UP -> target.volume =
                    (target.volume + VOLUME_STEP).coerceAtMost(1F)

                GeneralCommandType.VOLUME_DOWN -> target.volume =
                    (target.volume - VOLUME_STEP).coerceAtLeast(0F)

                GeneralCommandType.MUTE -> target.volume = 0F
                GeneralCommandType.UNMUTE -> target.volume = 1F
                GeneralCommandType.TOGGLE_MUTE ->
                    target.volume = if (target.volume > 0F) 0F else 1F

                GeneralCommandType.SET_SHUFFLE_QUEUE ->
                    target.shuffleModeEnabled =
                        message.data?.arguments?.get("ShuffleMode") != "Sorted"

                GeneralCommandType.SET_REPEAT_MODE -> target.repeatMode =
                    when (message.data?.arguments?.get("RepeatMode")) {
                        "RepeatAll" -> Player.REPEAT_MODE_ALL
                        "RepeatOne" -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }

                else -> Log.d(TAG, "Ignoring general command ${message.data?.name}")
            }
        }
    }

    /** Jellyfin counts in 100-nanosecond ticks throughout its API. */
    private fun Long?.toMs(): Long = (this ?: 0L) / TICKS_PER_MILLISECOND

    companion object {
        private const val TAG = "JellyfinRemoteControl"
        private const val TICKS_PER_MILLISECOND = 10_000L
        private const val VOLUME_STEP = 0.1F

        /**
         * What other clients may ask for.
         *
         * Deliberately only what is actually wired below. Advertising a command and then ignoring
         * it leaves a live-looking button on somebody else's screen that silently does nothing,
         * which is worse than the button being absent.
         */
        private val SUPPORTED_COMMANDS = listOf(
            GeneralCommandType.SET_VOLUME,
            GeneralCommandType.VOLUME_UP,
            GeneralCommandType.VOLUME_DOWN,
            GeneralCommandType.MUTE,
            GeneralCommandType.UNMUTE,
            GeneralCommandType.TOGGLE_MUTE,
            GeneralCommandType.SET_SHUFFLE_QUEUE,
            GeneralCommandType.SET_REPEAT_MODE,
        )
    }
}
