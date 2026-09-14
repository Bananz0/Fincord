package uk.akane.accord.logic.player

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader.Companion.EXTRA_JELLYFIN_ITEM_ID
import org.akanework.gramophone.logic.data.jellyfin.JellyfinRemoteTargets
import org.akanework.gramophone.logic.data.jellyfin.JellyfinRemoteTargets.RemotePlaybackState
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.RepeatMode

/**
 * Presents a Jellyfin session on another device ("Finnect") as an ordinary Media3 [Player].
 *
 * This exists for the same reason [androidx.media3.cast.RemoteCastPlayer] does. Playback that
 * happens somewhere else is still playback, and everything Android shows the user - the
 * notification, the lock screen, Android Auto, the mini player, any other app holding a
 * MediaController - is fed from whatever [Player] the MediaSession is holding. Driving a remote
 * session from a UI object instead leaves that session describing a paused local player, which is
 * exactly what the phone then reports to the rest of the system.
 *
 * The remote session is authoritative. Nothing here predicts what it will do: every command is
 * sent onward, and [JellyfinRemoteTargets.playbackState] pushing a new observation is what moves
 * this player's state. [RemotePlaybackState.projectedPositionMs] extrapolates between observations,
 * which is what makes a scrubber advance smoothly against a session that only reports periodically.
 */
@UnstableApi
class JellyfinRemotePlayer(
    looper: Looper,
    private val scope: CoroutineScope,
) : SimpleBasePlayer(looper) {

    /**
     * The queue as this phone handed it over.
     *
     * Jellyfin reports its queue as bare item ids, with no titles, artwork or durations, so the
     * items sent in [handleSetMediaItems] are what the timeline is built from. An adopted session -
     * one already playing when this phone claimed it - has no such list, and falls back to the
     * single item the server names.
     */
    private var playlist: List<MediaItem> = emptyList()

    private var observeJob: Job? = null

    init {
        observeJob = scope.launch {
            JellyfinRemoteTargets.playbackState.collect { invalidateState() }
        }
    }

    override fun getState(): State {
        val remote = JellyfinRemoteTargets.playbackState.value
        val items = resolvePlaylist(remote)
        val index = resolveIndex(remote, items)
        return State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlayWhenReady(
                remote?.isPaused?.not() ?: false,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setPlaybackState(if (remote == null) Player.STATE_IDLE else Player.STATE_READY)
            .setPlaylist(items.mapIndexed { position, item -> item.toItemData(position, remote) })
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs { remote?.projectedPositionMs() ?: 0L }
            .setRepeatMode(remote?.repeatMode.toPlayerRepeatMode())
            .setShuffleModeEnabled(remote?.playbackOrder == PlaybackOrder.SHUFFLE)
            .setDeviceInfo(DEVICE_INFO)
            .setDeviceVolume(remote?.volumePercent ?: 0)
            .setIsDeviceMuted(remote?.volumePercent == 0)
            .build()
    }

    /**
     * The queue handed over, trimmed or padded to whatever the session actually reports holding.
     *
     * The two disagree whenever the remote session's own queue was changed elsewhere; the session
     * wins, because it is the thing playing.
     */
    private fun resolvePlaylist(remote: RemotePlaybackState?): List<MediaItem> {
        if (remote == null) return emptyList()
        if (playlist.isNotEmpty() && playlist.size == remote.queueIds.size) return playlist
        if (playlist.isNotEmpty() && remote.queueIds.isEmpty()) return playlist
        if (remote.queueIds.isNotEmpty()) {
            val byId = playlist.associateBy { it.remoteIdentity() }
            return remote.queueIds.map { id ->
                byId[id] ?: MediaItem.Builder()
                    .setMediaId(id)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(remote.itemName)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .build()
                    )
                    .build()
            }
        }
        // Claimed a session that was already playing: one item is all the server has named.
        return listOf(
            MediaItem.Builder()
                .setMediaId(remote.itemId ?: PLACEHOLDER_ID)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(remote.itemName)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        )
    }

    private fun resolveIndex(remote: RemotePlaybackState?, items: List<MediaItem>): Int {
        if (remote == null || items.isEmpty()) return 0
        val itemId = remote.itemId ?: return 0
        return items.indexOfFirst { it.remoteIdentity() == itemId }.coerceAtLeast(0)
    }

    private fun MediaItem.toItemData(position: Int, remote: RemotePlaybackState?): MediaItemData {
        val isCurrent = remote?.itemId != null && remote.itemId == remoteIdentity()
        val durationMs = (if (isCurrent) remote?.durationMs else null)
            ?: mediaMetadata.durationMs
        return MediaItemData.Builder("$position:${remoteIdentity()}")
            .setMediaItem(this)
            .setMediaMetadata(mediaMetadata)
            .setIsSeekable(remote?.canSeek ?: false)
            .setIsDynamic(false)
            .setDurationUs(
                durationMs?.takeIf { it > 0 }?.times(1_000L) ?: androidx.media3.common.C.TIME_UNSET
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendTransport(
            if (playWhenReady) PlaystateCommand.UNPAUSE else PlaystateCommand.PAUSE
        ) }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendTransport(PlaystateCommand.STOP) }

    override fun handleRelease(): ListenableFuture<*> {
        observeJob?.cancel()
        observeJob = null
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
            send { JellyfinRemoteTargets.sendTransport(PlaystateCommand.NEXT_TRACK) }

        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
            send { JellyfinRemoteTargets.sendTransport(PlaystateCommand.PREVIOUS_TRACK) }

        else -> {
            val current = JellyfinRemoteTargets.playbackState.value
            val currentIndex = resolveIndex(current, resolvePlaylist(current))
            if (mediaItemIndex != C_INDEX_UNSET && mediaItemIndex != currentIndex) {
                // Jumping to a different queue entry, which Jellyfin expresses as a fresh play
                // command at that index rather than as a seek.
                send { JellyfinRemoteTargets.playQueueIndex(mediaItemIndex) }
            } else {
                send {
                    JellyfinRemoteTargets.sendTransport(
                        PlaystateCommand.SEEK,
                        seekPositionMs = positionMs.coerceAtLeast(0L),
                    )
                }
            }
        }
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendRepeat(repeatMode.toRemoteRepeatMode()) }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendShuffle(shuffleModeEnabled) }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendVolume(deviceVolume) }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        send {
            val current = JellyfinRemoteTargets.playbackState.value?.volumePercent ?: 0
            JellyfinRemoteTargets.sendVolume(current + VOLUME_STEP_PERCENT)
        }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        send {
            val current = JellyfinRemoteTargets.playbackState.value?.volumePercent ?: 0
            JellyfinRemoteTargets.sendVolume(current - VOLUME_STEP_PERCENT)
        }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> =
        send { JellyfinRemoteTargets.sendVolume(if (muted) 0 else MUTE_RESTORE_PERCENT) }

    /**
     * Hands this phone's queue to the remote session.
     *
     * Only the Jellyfin ids travel - the server resolves them against its own library - so items
     * it cannot identify are dropped rather than sent as unusable entries.
     */
    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        playlist = mediaItems
        val target = JellyfinRemoteTargets.active.value ?: return Futures.immediateVoidFuture()
        val ids = mediaItems.map { it.remoteIdentity() }
        val resolvedIndex = if (startIndex == C_INDEX_UNSET) 0 else startIndex
        return send {
            JellyfinRemoteTargets.playOn(
                target = target,
                remoteIds = ids,
                startIndex = resolvedIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0)),
                startPositionMs = startPositionMs.coerceAtLeast(0L),
            )
        }
    }

    /**
     * Fire-and-forget onto the coroutine scope, resolving immediately.
     *
     * Waiting for the HTTP call would stall the caller's transport button; the session's own next
     * observation is what confirms the command, and [JellyfinRemoteTargets] already applies an
     * optimistic update so the UI does not sit still in the meantime.
     */
    private fun send(block: suspend () -> Unit): ListenableFuture<*> {
        scope.launch {
            block()
            invalidateState()
        }
        return Futures.immediateVoidFuture()
    }

    /** Jellyfin knows this track by its library id, which is not always the Media3 media id. */
    private fun MediaItem.remoteIdentity(): String =
        mediaMetadata.extras?.getString(EXTRA_JELLYFIN_ITEM_ID) ?: mediaId

    private fun RepeatMode?.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.REPEAT_ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    private fun Int.toRemoteRepeatMode(): RepeatMode = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatMode.REPEAT_ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.REPEAT_ONE
        else -> RepeatMode.REPEAT_NONE
    }

    private companion object {
        const val C_INDEX_UNSET = androidx.media3.common.C.INDEX_UNSET
        const val PLACEHOLDER_ID = "finnect-unknown"

        /** Jellyfin volume is a percentage, so a twentieth matches Android's own key step. */
        const val VOLUME_STEP_PERCENT = 5

        /** Jellyfin has no mute, only a level; unmuting restores a sane audible one. */
        const val MUTE_RESTORE_PERCENT = 30

        val DEVICE_INFO: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMaxVolume(100)
            .build()

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
