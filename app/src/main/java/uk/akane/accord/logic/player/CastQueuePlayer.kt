package uk.akane.accord.logic.player

import android.os.Looper
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.MediaQueue
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.abs
import uk.akane.accord.logic.cast.CastGrants

/**
 * Presents the phone's complete, named queue while a Cast receiver does the playing.
 *
 * The local player remains the queue of record for MediaSession and the app UI. Cast transport is
 * driven directly through [RemoteMediaClient], because that is the API which owns the receiver's
 * persistent queue and its receiver-assigned item IDs. [RemoteCastPlayer] is retained only as a
 * convenient observer for playback/device state; its sparse timeline is never used as queue state.
 *
 * A handoff is one atomic `queueLoad`: item zero is exactly the local item which was playing and
 * the captured position is part of the same request. More items are added with `queueInsertItems`
 * as the receiver approaches the end of the loaded stretch. Nothing slides or reloads underneath
 * a playing item. Jumps, removals and reorders address the IDs in [MediaQueue], as required by the
 * Cast queue API.
 */
@UnstableApi
class CastQueuePlayer(
    looper: Looper,
    private val local: Player,
    private val remote: RemoteCastPlayer,
    private val converter: MediaItemConverter,
) : SimpleBasePlayer(looper) {

    private val handler = Handler(looper)

    private var client: RemoteMediaClient? = null
    private var mediaQueue: MediaQueue? = null

    /** Local timeline index represented by each receiver queue position. */
    private val receiverLocalIndices = mutableListOf<Int>()

    /** First local index not yet considered for an incremental receiver append. */
    private var nextLocalToEnqueue = 0
    private var appendInFlight = false

    /** Pins MediaSession to the requested item while status still describes the replaced queue. */
    private var pendingLoad: PendingLoad? = null

    /** A handoff waiting for a receiver-safe URL, before anything has been sent to Cast. */
    private var pendingGrantLoad: PendingGrantLoad? = null

    /** Keeps an in-queue jump on its target until the receiver reports that item as current. */
    private var pendingJump: PendingJump? = null
    private var pendingSeek: PendingSeek? = null
    private var lastReceiverLocalIndex = 0
    private var lastReceiverPlayWhenReady = false
    private var progressMs = 0L

    /**
     * Work parked while Cast grants are fetched checks one of these on return and stands down if
     * something newer has happened meanwhile. Two, because they supersede different things: a newer
     * load replaces an older one, but an edit - autoplay appending tracks, say - must not cancel a
     * handoff that is waiting for its grants. It only invalidates an insert computed against the
     * indices the edit has just moved.
     */
    private var loadGeneration = 0
    private var sessionGeneration = 0
    /** Cast reports BUFFERING for an in-track seek even though audio never becomes paused. */
    private var maskSeekBufferingUntilMs = 0L

    private val remoteListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            invalidateState()
        }
    }

    private val localListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // A restored local queue and a resumed Cast session race each other on a cold start.
            // Whichever arrives second must get another chance to establish the ID mapping.
            if (receiverLocalIndices.isEmpty()) adoptExistingQueueIfPossible()
            if (client != null) prefetchGrants()
            invalidateState()
        }
    }

    private val statusCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = receiverStateChanged()
        override fun onQueueStatusUpdated() = receiverStateChanged()
        override fun onMetadataUpdated() = receiverStateChanged()
    }

    private val queueCallback = object : MediaQueue.Callback() {
        override fun mediaQueueChanged() = receiverStateChanged()
        override fun itemsReloaded() = receiverStateChanged()
        override fun itemsInsertedInRange(insertIndex: Int, insertCount: Int) = receiverStateChanged()
        override fun itemsUpdatedAtIndexes(indexes: IntArray) = receiverStateChanged()
        override fun itemsRemovedAtIndexes(indexes: IntArray) = receiverStateChanged()
        override fun itemsReorderedAtIndexes(indexes: List<Int>, insertBeforeIndex: Int) =
            receiverStateChanged()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { position, _ ->
        // A disappearing session emits a final zero after its active item has been cleared. Keep
        // the last real receiver clock for the remote-to-local hand-back instead of accepting it.
        if (activeReceiverLocalIndex() != null) acceptReceiverPosition(position)
        // Position is exposed through State's value supplier, so rebuilding the complete player
        // state here only makes MediaSession/UI consumers rebind and remeasure every 500 ms.
        maybeAppendQueue()
    }

    init {
        remote.addListener(remoteListener)
        local.addListener(localListener)
    }

    /** Attaches the Cast SDK queue/status models for the lifetime of one receiver session. */
    fun attach(remoteMediaClient: RemoteMediaClient) {
        if (client === remoteMediaClient) return
        detachClient()
        sessionGeneration++
        loadGeneration++
        receiverLocalIndices.clear()
        nextLocalToEnqueue = 0
        appendInFlight = false
        client = remoteMediaClient
        mediaQueue = remoteMediaClient.mediaQueue.also {
            it.setCacheCapacity(CAST_QUEUE_BATCH_SIZE * 2)
            it.registerCallback(queueCallback)
        }
        remoteMediaClient.registerCallback(statusCallback)
        remoteMediaClient.addProgressListener(progressListener, PROGRESS_INTERVAL_MS)
        progressMs = remoteMediaClient.approximateStreamPosition.coerceAtLeast(0L)
        if (!adoptExistingQueueIfPossible()) remoteMediaClient.requestStatus()
        invalidateState()
    }

    /** Stops using SDK objects whose documented lifetime ends with their Cast session. */
    fun detach() {
        detachClient()
        sessionGeneration++
        loadGeneration++
        pendingGrantLoad = null
        pendingLoad = null
        pendingJump = null
        pendingSeek = null
        appendInFlight = false
        invalidateState()
    }

    private fun detachClient() {
        val oldClient = client
        mediaQueue?.unregisterCallback(queueCallback)
        oldClient?.unregisterCallback(statusCallback)
        oldClient?.removeProgressListener(progressListener)
        client = null
        mediaQueue = null
    }

    override fun getState(): State {
        val queue = localQueue()
        val index = currentLocalIndex(queue)
        val load = pendingLoad
        val grantLoad = pendingGrantLoad
        return State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlayWhenReady(
                grantLoad?.playWhenReady ?: load?.playWhenReady ?: remote.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setPlaybackState(
                when {
                    queue.isEmpty() -> Player.STATE_IDLE
                    grantLoad != null || load != null -> Player.STATE_BUFFERING
                    remote.playWhenReady &&
                        remote.playbackState == Player.STATE_BUFFERING &&
                        SystemClock.elapsedRealtime() < maskSeekBufferingUntilMs ->
                        Player.STATE_READY
                    else -> remote.playbackState
                },
            )
            .setPlaylist(queue.mapIndexed { position, item ->
                item.toItemData(position, position == index)
            })
            .setCurrentMediaItemIndex(
                grantLoad?.let { resolveTarget(queue, it.target) }?.takeIf { it >= 0 } ?: index,
            )
            .setContentPositionMs {
                pendingGrantLoad?.positionMs ?: pendingLoad?.positionMs
                ?: pendingJump?.positionMs ?: pendingSeek?.positionMs
                ?: progressMs
            }
            .setContentBufferedPositionMs { remote.bufferedPosition.coerceAtLeast(progressMs) }
            .setRepeatMode(remote.repeatMode)
            .setShuffleModeEnabled(local.shuffleModeEnabled)
            .setDeviceInfo(remote.deviceInfo.takeIf { it.maxVolume > 0 } ?: DEVICE_INFO)
            .setDeviceVolume(remote.deviceVolume)
            .setIsDeviceMuted(remote.isDeviceMuted)
            .build()
    }

    private fun localQueue(): List<MediaItem> {
        if (!local.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) return emptyList()
        val timeline = local.currentTimeline
        val window = Timeline.Window()
        return (0 until timeline.windowCount).map { timeline.getWindow(it, window).mediaItem }
    }

    private fun resolveTarget(queue: List<MediaItem>, target: QueueTarget): Int {
        queue.indexOfFirst { it === target.item }.takeIf { it >= 0 }?.let { return it }
        return queue.indices.filter { queue[it].mediaId == target.mediaId }
            .getOrNull(target.occurrence) ?: -1
    }

    /** Resolves the receiver's persistent queue item ID into the phone's authoritative timeline. */
    private fun currentLocalIndex(queue: List<MediaItem>): Int {
        if (queue.isEmpty()) return 0
        pendingLoad?.let { load ->
            if (!receiverConfirms(load)) return load.localIndex.coerceIn(queue.indices)
            Log.d(TAG, "receiver confirmed ${load.mediaId}/${load.title} at ${load.localIndex}")
            pendingLoad = null
        }
        pendingJump?.let { jump ->
            if (client?.mediaStatus?.currentItemId != jump.receiverItemId) {
                return jump.localIndex.coerceIn(queue.indices)
            }
            Log.d(TAG, "receiver confirmed jump to local ${jump.localIndex}")
            pendingJump = null
        }
        val status = client?.mediaStatus
        val receiverIndex = status?.currentItemId
            ?.let { mediaQueue?.indexOfItemWithId(it) }
            ?: -1
        receiverLocalIndices.getOrNull(receiverIndex)?.let {
            lastReceiverLocalIndex = it.coerceIn(queue.indices)
            return lastReceiverLocalIndex
        }
        return lastReceiverLocalIndex.coerceIn(queue.indices)
    }

    private fun activeReceiverLocalIndex(): Int? {
        val currentItemId = client?.mediaStatus?.currentItemId ?: return null
        if (currentItemId == MediaQueueItem.INVALID_ITEM_ID) return null
        val receiverIndex = mediaQueue?.indexOfItemWithId(currentItemId) ?: return null
        return receiverLocalIndices.getOrNull(receiverIndex)
    }

    private fun receiverConfirms(load: PendingLoad): Boolean {
        val info = client?.mediaInfo ?: return false
        val identityMatches = info.entity == load.entity || info.contentId == load.contentId
        val currentId = client?.mediaStatus?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID
        return identityMatches && (mediaQueue?.indexOfItemWithId(currentId) ?: -1) >= 0
    }

    private fun MediaItem.toItemData(position: Int, isCurrent: Boolean): MediaItemData =
        MediaItemData.Builder("$position:$mediaId")
            .setMediaItem(this)
            .setMediaMetadata(mediaMetadata)
            .setIsSeekable(true)
            .setIsDynamic(false)
            .setDurationUs(
                knownDurationUs(position, isCurrent)
            )
            .build()

    private fun MediaItem.knownDurationUs(position: Int, isCurrent: Boolean): Long {
        mediaMetadata.durationMs?.takeIf { it > 0L }?.let { return it * 1_000L }
        mediaMetadata.extras?.getLong("Duration")?.takeIf { it > 0L }
            ?.let { return it * 1_000L }
        val localDurationUs = runCatching {
            local.currentTimeline.getWindow(position, Timeline.Window()).durationUs
        }.getOrDefault(C.TIME_UNSET)
        if (localDurationUs != C.TIME_UNSET && localDurationUs > 0L) return localDurationUs
        if (isCurrent) {
            client?.mediaInfo?.streamDuration?.takeIf { it > 0L }
                ?.let { return it * 1_000L }
        }
        return C.TIME_UNSET
    }

    /** Starts or supersedes a load, parking it until receiver-safe URLs are available. */
    private fun loadQueueFrom(
        startIndex: Int,
        positionMs: Long,
        playWhenReady: Boolean,
    ) {
        val queue = localQueue()
        if (queue.isEmpty()) return
        val requested = startIndex.coerceIn(queue.indices)
        val item = queue[requested]
        val target = QueueTarget(
            item = item,
            mediaId = item.mediaId,
            occurrence = queue.take(requested + 1).count { it.mediaId == item.mediaId } - 1,
        )
        val pending = PendingGrantLoad(
            target = target,
            positionMs = positionMs.coerceAtLeast(0L),
            playWhenReady = playWhenReady,
            generation = ++loadGeneration,
            session = sessionGeneration,
        )
        pendingGrantLoad = pending
        resumeGrantLoad(pending)
        invalidateState()
    }

    private fun resumeGrantLoad(pending: PendingGrantLoad) {
        if (pendingGrantLoad !== pending || pending.generation != loadGeneration ||
            pending.session != sessionGeneration || client == null) return
        val queue = localQueue()
        val requested = resolveTarget(queue, pending.target)
        if (requested < 0) {
            Log.w(TAG, "${pending.target.mediaId} left the queue while fetching grants; not loading")
            pendingGrantLoad = null
            invalidateState()
            return
        }
        val upcoming = queue.subList(
            requested,
            (requested + CAST_QUEUE_BATCH_SIZE).coerceAtMost(queue.size),
        )
        if (CastGrants.needsGrants(upcoming)) {
            if (!CastGrants.mayRequest()) {
                handler.postDelayed({ resumeGrantLoad(pending) }, GRANT_RETRY_MS)
                return
            }
            Log.d(TAG, "fetching Cast grants before loading ${queue[requested].description(requested)}")
            CastGrants.fetch(upcoming) { resumeGrantLoad(pending) }
            return
        }
        pendingGrantLoad = null
        performLoad(queue, requested, pending)
    }

    /** Loads the current item and the next batch as one Cast continuation request. */
    private fun performLoad(queue: List<MediaItem>, requested: Int, request: PendingGrantLoad) {
        val castClient = client ?: return
        if (request.generation != loadGeneration || request.session != sessionGeneration) return
        val batch = castableBatch(queue, requested)
        if (batch.items.isEmpty() || batch.localIndices.firstOrNull() != requested) {
            val reason =
                if (CastGrants.receiverUrl(queue[requested]) == CastGrants.ReceiverUrl.AwaitingGrant) {
                    "the server did not grant a stream URL"
                } else {
                    "no receiver URL"
                }
            Log.e(TAG, "cannot cast ${queue[requested].description(requested)}: $reason")
            return
        }

        receiverLocalIndices.clear()
        receiverLocalIndices.addAll(batch.localIndices)
        nextLocalToEnqueue = batch.nextLocalIndex
        appendInFlight = false
        pendingJump = null
        pendingSeek = null
        lastReceiverLocalIndex = requested
        lastReceiverPlayWhenReady = request.playWhenReady
        progressMs = request.positionMs
        val first = batch.items.first()
        val firstInfo = first.media
        pendingLoad = PendingLoad(
            localIndex = requested,
            mediaId = queue[requested].mediaId,
            title = queue[requested].mediaMetadata.title?.toString().orEmpty(),
            entity = firstInfo?.entity,
            contentId = firstInfo?.contentId,
            positionMs = progressMs,
            playWhenReady = request.playWhenReady,
        )
        Log.d(
            TAG,
            "queueLoad ${batch.items.size} items; ${queue[requested].description(requested)} " +
                "at $progressMs ms, next=${queue.getOrNull(requested + 1)?.description(requested + 1)}",
        )
        castClient.queueLoad(
            batch.items.toTypedArray(),
            0,
            local.repeatMode.toCastRepeatMode(),
            progressMs,
            null,
        ).setResultCallback { result ->
            if (request.generation != loadGeneration || request.session != sessionGeneration ||
                client !== castClient) return@setResultCallback
            if (!result.status.isSuccess) {
                Log.e(TAG, "queueLoad failed: ${result.status.statusCode} ${result.status.statusMessage}")
                pendingLoad = null
                invalidateState()
                return@setResultCallback
            }
            Log.d(TAG, "queueLoad accepted; waiting for receiver item IDs")
            if (!request.playWhenReady) castClient.pause()
            castClient.requestStatus()
        }
        invalidateState()
    }

    private fun castableBatch(queue: List<MediaItem>, fromIndex: Int): CastBatch {
        val castItems = mutableListOf<MediaQueueItem>()
        val localIndices = mutableListOf<Int>()
        var index = fromIndex.coerceAtLeast(0)
        while (index < queue.size && castItems.size < CAST_QUEUE_BATCH_SIZE) {
            val item = queue[index]
            when (CastGrants.receiverUrl(item)) {
                is CastGrants.ReceiverUrl.Ready -> {
                    castItems += converter.toMediaQueueItem(item)
                    localIndices += index
                }
                // Stop rather than skip: a skipped item is never offered to the receiver again, and
                // this one only needs its grant. The batch resumes from here once it has one.
                CastGrants.ReceiverUrl.AwaitingGrant -> break
                CastGrants.ReceiverUrl.Uncastable ->
                    Log.w(TAG, "skipping uncastable ${item.description(index)}")
            }
            index++
        }
        return CastBatch(castItems, localIndices, index)
    }

    /** Adds the next batch without replacing or interrupting the active receiver queue. */
    private fun maybeAppendQueue() {
        if (pendingLoad != null || appendInFlight) return
        val castClient = client ?: return
        val castQueue = mediaQueue ?: return
        val queue = localQueue()
        if (nextLocalToEnqueue >= queue.size) return
        val receiverIndex = castClient.mediaStatus?.currentItemId
            ?.let(castQueue::indexOfItemWithId)
            ?: return
        if (receiverIndex < 0 || castQueue.itemCount - receiverIndex > QUEUE_LOW_WATER) return

        val upcoming = queue.subList(
            nextLocalToEnqueue,
            (nextLocalToEnqueue + CAST_QUEUE_BATCH_SIZE).coerceAtMost(queue.size),
        )
        if (CastGrants.needsGrants(upcoming)) {
            // Asked again on a later progress tick if this fails; mayRequest spaces those out.
            if (!CastGrants.mayRequest()) return
            appendInFlight = true
            val session = sessionGeneration
            CastGrants.fetch(upcoming) {
                if (session != sessionGeneration || client !== castClient) return@fetch
                // Recomputed from whatever the queue is by now, so an edit made meanwhile is safe.
                appendInFlight = false
                maybeAppendQueue()
            }
            return
        }

        val batch = castableBatch(queue, nextLocalToEnqueue)
        if (batch.items.isEmpty()) {
            nextLocalToEnqueue = batch.nextLocalIndex
            return
        }
        appendInFlight = true
        val session = sessionGeneration
        Log.d(
            TAG,
            "queueInsertItems ${batch.items.size} after ${receiverLocalIndices.lastOrNull()}, " +
                "through local ${batch.nextLocalIndex - 1}",
        )
        castClient.queueInsertItems(
            batch.items.toTypedArray(),
            MediaQueueItem.INVALID_ITEM_ID,
            null,
        ).setResultCallback { result ->
            if (session != sessionGeneration || client !== castClient) return@setResultCallback
            appendInFlight = false
            if (result.status.isSuccess) {
                receiverLocalIndices.addAll(batch.localIndices)
                nextLocalToEnqueue = batch.nextLocalIndex
                invalidateState()
                maybeAppendQueue()
            } else {
                Log.e(TAG, "queue append failed: ${result.status.statusCode}")
            }
        }
    }

    /** Reconstructs the local/receiver offset when rejoining a queue this app already started. */
    private fun adoptExistingQueueIfPossible(): Boolean {
        val castClient = client ?: return false
        val count = mediaQueue?.itemCount ?: 0
        if (count <= 0) return false
        val currentItemId = castClient.mediaStatus?.currentItemId
            ?.takeIf { it != MediaQueueItem.INVALID_ITEM_ID }
            ?: return false
        val currentRemoteIndex = mediaQueue?.indexOfItemWithId(currentItemId) ?: -1
        if (currentRemoteIndex < 0) return false
        val currentItem = castClient.currentItem
            ?: mediaQueue?.getItemAtIndex(currentRemoteIndex, true)
            ?: return false
        val adopted = runCatching { converter.toMediaItem(currentItem) }.getOrNull() ?: return false
        val queue = localQueue()
        val localIndex = queue.indexOfFirst { it.mediaId == adopted.mediaId }
        val firstLocal = localIndex - currentRemoteIndex
        if (localIndex < 0 || firstLocal < 0 || firstLocal + count > queue.size) return false
        receiverLocalIndices.clear()
        receiverLocalIndices.addAll(firstLocal until firstLocal + count)
        nextLocalToEnqueue = firstLocal + count
        lastReceiverLocalIndex = localIndex
        lastReceiverPlayWhenReady = remote.playWhenReady
        progressMs = castClient.approximateStreamPosition.coerceAtLeast(0L)
        Log.d(TAG, "adopted receiver IDs: $count items map to local $firstLocal..${nextLocalToEnqueue - 1}")
        return true
    }

    private fun receiverStateChanged() {
        if (receiverLocalIndices.isEmpty()) adoptExistingQueueIfPossible()
        pendingLoad?.let {
            if (receiverConfirms(it)) {
                Log.d(TAG, "receiver status now matches ${it.mediaId}/${it.title}")
                pendingLoad = null
            }
        }
        pendingJump?.let {
            if (client?.mediaStatus?.currentItemId == it.receiverItemId) {
                Log.d(TAG, "receiver status now matches jump to local ${it.localIndex}")
                pendingJump = null
            }
        }
        activeReceiverLocalIndex()?.let { activeLocalIndex ->
            lastReceiverLocalIndex = activeLocalIndex
            lastReceiverPlayWhenReady = remote.playWhenReady
            client?.approximateStreamPosition?.takeIf { it >= 0 }?.let(::acceptReceiverPosition)
        }
        invalidateState()
        maybeAppendQueue()
    }

    fun startCasting(startIndex: Int, positionMs: Long, playWhenReady: Boolean) {
        loadQueueFrom(startIndex, positionMs, playWhenReady)
    }

    /** Last receiver state that still had an active queue item; safe after teardown has started. */
    fun handoffSnapshot(): HandoffSnapshot = HandoffSnapshot(
        mediaItemIndex = pendingGrantLoad?.let { resolveTarget(localQueue(), it.target) }
            ?.takeIf { it >= 0 } ?: pendingLoad?.localIndex ?: pendingJump?.localIndex
            ?: lastReceiverLocalIndex,
        positionMs = pendingGrantLoad?.positionMs ?: pendingLoad?.positionMs
            ?: pendingJump?.positionMs ?: pendingSeek?.positionMs
            ?: progressMs,
        playWhenReady = pendingGrantLoad?.playWhenReady ?: pendingLoad?.playWhenReady
            ?: lastReceiverPlayWhenReady,
    )

    private fun acceptReceiverPosition(positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        pendingSeek?.let { seek ->
            val confirmed = abs(position - seek.positionMs) <= SEEK_CONFIRM_TOLERANCE_MS
            val timedOut = SystemClock.elapsedRealtime() - seek.requestedAtMs >= SEEK_CONFIRM_TIMEOUT_MS
            if (!confirmed && !timedOut) return
            if (confirmed) {
                Log.d(TAG, "receiver confirmed seek at $position ms")
            } else {
                Log.w(TAG, "seek confirmation timed out; receiver remained at $position ms")
            }
            pendingSeek = null
        }
        progressMs = position
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        pendingGrantLoad?.copy(playWhenReady = playWhenReady)?.let {
            pendingGrantLoad = it
            resumeGrantLoad(it)
        }
        pendingLoad = pendingLoad?.copy(playWhenReady = playWhenReady)
        lastReceiverPlayWhenReady = playWhenReady
        if (playWhenReady) client?.play() else client?.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        client?.requestStatus()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        loadGeneration++
        pendingGrantLoad = null
        pendingLoad = null
        lastReceiverPlayWhenReady = false
        client?.stop()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        sessionGeneration++
        loadGeneration++
        pendingGrantLoad = null
        detachClient()
        remote.removeListener(remoteListener)
        local.removeListener(localListener)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val castClient = client ?: return Futures.immediateVoidFuture()
        val queue = localQueue()
        if (queue.isEmpty()) return Futures.immediateVoidFuture()
        val current = currentLocalIndex(queue)
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> current + 1
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> current - 1
            else -> if (mediaItemIndex == C.INDEX_UNSET) current else mediaItemIndex
        }
        if (target !in queue.indices) return Futures.immediateVoidFuture()
        val requestedPosition = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        pendingGrantLoad?.let {
            loadQueueFrom(target, requestedPosition, it.playWhenReady)
            return Futures.immediateVoidFuture()
        }
        val seekWithinCurrent = target == current &&
            seekCommand != Player.COMMAND_SEEK_TO_NEXT &&
            seekCommand != Player.COMMAND_SEEK_TO_PREVIOUS
        if (seekWithinCurrent) {
            Log.d(TAG, "seek current item from $progressMs to $requestedPosition ms")
            pendingSeek = PendingSeek(requestedPosition, SystemClock.elapsedRealtime())
            // RemoteCastPlayer briefly flips READY -> BUFFERING -> READY for queue seek commands.
            // The receiver continues playing, so exposing that as isPlaying=false makes the cover
            // run its pause shrink/expand on every lyric tap or scrub. Keep the UI in READY only
            // for this short command acknowledgement window; a real sustained buffer still shows.
            maskSeekBufferingUntilMs =
                SystemClock.elapsedRealtime() + SEEK_BUFFERING_MASK_DURATION_MS
            progressMs = requestedPosition
            castClient.seek(
                MediaSeekOptions.Builder()
                    .setPosition(requestedPosition)
                    .setResumeState(MediaSeekOptions.RESUME_STATE_UNCHANGED)
                    .build(),
            ).setResultCallback { result ->
                if (!result.status.isSuccess && pendingSeek?.positionMs == requestedPosition) {
                    Log.e(
                        TAG,
                        "seek failed: ${result.status.statusCode} ${result.status.statusMessage}",
                    )
                    pendingSeek = null
                    maskSeekBufferingUntilMs = 0L
                    invalidateState()
                }
            }
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        val receiverIndex = receiverLocalIndices.indexOf(target)
        val itemId = receiverIndex.takeIf { it >= 0 }?.let { mediaQueue?.itemIdAtIndex(it) }
            ?: MediaQueueItem.INVALID_ITEM_ID
        if (itemId != MediaQueueItem.INVALID_ITEM_ID) {
            Log.d(TAG, "queueJumpToItem local=$target receiver=$receiverIndex itemId=$itemId")
            pendingJump = PendingJump(target, itemId, requestedPosition)
            pendingSeek = null
            lastReceiverLocalIndex = target
            progressMs = requestedPosition
            castClient.queueJumpToItem(itemId, requestedPosition, null).setResultCallback { result ->
                if (!result.status.isSuccess && pendingJump?.receiverItemId == itemId) {
                    Log.e(
                        TAG,
                        "queue jump failed: ${result.status.statusCode} " +
                            "${result.status.statusMessage}",
                    )
                    pendingJump = null
                    invalidateState()
                }
            }
        } else {
            loadQueueFrom(target, requestedPosition, playWhenReady = true)
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        val position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        local.setMediaItems(mediaItems, index, position)
        loadQueueFrom(index, position, playWhenReady = true)
        return Futures.immediateVoidFuture()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        local.addMediaItems(index, mediaItems)
        for (i in receiverLocalIndices.indices) {
            if (receiverLocalIndices[i] >= index) receiverLocalIndices[i] += mediaItems.size
        }
        if (index < nextLocalToEnqueue) nextLocalToEnqueue += mediaItems.size
        if (client != null && receiverLocalIndices.any { it >= index }) {
            insertWhenReady(mediaItems, sessionGeneration)
        }
        return Futures.immediateVoidFuture()
    }

    private fun insertWhenReady(mediaItems: List<MediaItem>, session: Int) {
        if (session != sessionGeneration || client == null) return
        val queue = localQueue()
        val present = resolveOccurrences(queue, mediaItems)
        if (present.isEmpty()) return
        val presentItems = present.map { it.second }
        if (CastGrants.needsGrants(presentItems)) {
            if (!CastGrants.mayRequest()) {
                handler.postDelayed({ insertWhenReady(mediaItems, session) }, GRANT_RETRY_MS)
            } else {
                CastGrants.fetch(presentItems) { insertWhenReady(mediaItems, session) }
            }
            return
        }
        insertOnReceiver(present)
    }

    /** Resolves the actual objects first, so equal duplicate songs retain their queue occurrence. */
    private fun resolveOccurrences(
        queue: List<MediaItem>,
        wanted: List<MediaItem>,
    ): List<Pair<Int, MediaItem>> {
        val used = BooleanArray(queue.size)
        return wanted.mapNotNull { item ->
            var index = queue.indices.firstOrNull { !used[it] && queue[it] === item } ?: -1
            if (index < 0) index = queue.indices.firstOrNull { !used[it] && queue[it] == item } ?: -1
            if (index < 0) null else {
                used[index] = true
                index to queue[index]
            }
        }.sortedBy { it.first }
    }

    private fun insertOnReceiver(resolved: List<Pair<Int, MediaItem>>) {
        val castClient = client ?: return
        val castQueue = mediaQueue ?: return
        // Insert from the end. Receiver IDs used as anchors stay valid, and each callback can update
        // the local mapping independently if later edits split the originally added block.
        resolved.asReversed().forEach { (localIndex, item) ->
            if (receiverLocalIndices.contains(localIndex)) return@forEach
            if (CastGrants.receiverUrl(item) !is CastGrants.ReceiverUrl.Ready) {
                Log.w(TAG, "added ${item.description(localIndex)} has no receiver URL")
                return@forEach
            }
            val receiverIndex = receiverLocalIndices.indexOfFirst { it >= localIndex }
                .let { if (it < 0) receiverLocalIndices.size else it }
            val beforeId = if (receiverIndex < castQueue.itemCount) {
                castQueue.itemIdAtIndex(receiverIndex)
            } else {
                MediaQueueItem.INVALID_ITEM_ID
            }
            val session = sessionGeneration
            castClient.queueInsertItems(
                arrayOf(converter.toMediaQueueItem(item)),
                beforeId,
                null,
            ).setResultCallback { result ->
                if (session != sessionGeneration || client !== castClient) return@setResultCallback
                if (result.status.isSuccess) {
                    val current = localQueue().indexOfFirst { it === item }
                    if (current >= 0 && !receiverLocalIndices.contains(current)) {
                        val at = receiverLocalIndices.indexOfFirst { it >= current }
                            .let { if (it < 0) receiverLocalIndices.size else it }
                        receiverLocalIndices.add(at, current)
                    }
                    invalidateState()
                } else {
                    Log.e(TAG, "queue insert failed: ${result.status.statusCode}")
                }
            }
        }
    }

    /** Warms grants for the stretch of the queue the receiver is about to be given. */
    private fun prefetchGrants() {
        val queue = localQueue()
        if (queue.isEmpty()) return
        val from = lastReceiverLocalIndex.coerceIn(queue.indices)
        CastGrants.prefetch(
            queue.subList(from, (from + CAST_QUEUE_BATCH_SIZE * 2).coerceAtMost(queue.size)),
        )
    }

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> {
        val oldSize = local.mediaItemCount
        val oldOrder = (0 until oldSize).toMutableList()
        val moved = oldOrder.subList(fromIndex, toIndex).toList()
        oldOrder.subList(fromIndex, toIndex).clear()
        oldOrder.addAll(newIndex.coerceIn(0, oldOrder.size), moved)
        val newPositionByOld = IntArray(oldSize)
        oldOrder.forEachIndexed { newPosition, oldPosition ->
            newPositionByOld[oldPosition] = newPosition
        }
        val ids = mediaQueue?.itemIds ?: IntArray(0)
        val remapped = receiverLocalIndices.mapIndexedNotNull { receiverIndex, oldLocalIndex ->
            ids.getOrNull(receiverIndex)?.let { it to newPositionByOld[oldLocalIndex] }
        }.sortedBy { it.second }

        local.moveMediaItems(fromIndex, toIndex, newIndex)
        if (remapped.isNotEmpty()) {
            client?.queueReorderItems(
                remapped.map { it.first }.toIntArray(),
                MediaQueueItem.INVALID_ITEM_ID,
                null,
            )
            receiverLocalIndices.clear()
            receiverLocalIndices.addAll(remapped.map { it.second })
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val ids = mediaQueue?.itemIds ?: IntArray(0)
        val idsToRemove = receiverLocalIndices.mapIndexedNotNull { receiverIndex, localIndex ->
            ids.getOrNull(receiverIndex)?.takeIf { localIndex in fromIndex until toIndex }
        }
        local.removeMediaItems(fromIndex, toIndex)
        if (idsToRemove.isNotEmpty()) client?.queueRemoveItems(idsToRemove.toIntArray(), null)
        val removedCount = toIndex - fromIndex
        receiverLocalIndices.removeAll { it in fromIndex until toIndex }
        for (i in receiverLocalIndices.indices) {
            if (receiverLocalIndices[i] >= toIndex) receiverLocalIndices[i] -= removedCount
        }
        nextLocalToEnqueue = when {
            nextLocalToEnqueue <= fromIndex -> nextLocalToEnqueue
            nextLocalToEnqueue >= toIndex -> nextLocalToEnqueue - removedCount
            else -> fromIndex
        }
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        client?.queueSetRepeatMode(repeatMode.toCastRepeatMode(), null)
        local.repeatMode = repeatMode
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        local.shuffleModeEnabled = shuffleModeEnabled
        // Cast's queueShuffle does not reveal its new ID order synchronously. Rebuild only for an
        // explicit shuffle action; passive playback and ordinary edits remain incremental.
        loadQueueFrom(currentLocalIndex(localQueue()), progressMs, remote.playWhenReady)
        return Futures.immediateVoidFuture()
    }

    /**
     * Volume on the receiver, and why it is stepped here rather than by the remote player.
     *
     * `RemoteCastPlayer.increaseDeviceVolume` is `setDeviceVolume(getDeviceVolume() + 1)` against
     * its own cached level, and `setDeviceVolume` returns silently when it has no `CastSession` and
     * swallows a throwing `CastSession.setVolume` behind a warning of its own. So a key press that
     * does nothing looks identical to one that worked, from here.
     *
     * Stepping from the level this player has already published removes the cache from the
     * question - it is the number the slider and the notification are drawing - and leaves exactly
     * one way to fail, which is logged.
     */
    private fun stepDeviceVolume(delta: Int, flags: Int): ListenableFuture<*> {
        val maxVolume = (remote.deviceInfo.takeIf { it.maxVolume > 0 } ?: DEVICE_INFO).maxVolume
        if (maxVolume <= 0) {
            Log.w(TAG, "receiver reports no volume range; ignoring volume step")
            return Futures.immediateVoidFuture()
        }
        val from = remote.deviceVolume
        val target = (from + delta).coerceIn(0, maxVolume)
        if (target == from) return Futures.immediateVoidFuture()
        remote.setDeviceVolume(target, flags)
        // Read back rather than assume. RemoteCastPlayer applies this to the CastSession and only
        // then updates its own field, so a level that has not moved by the next state read is the
        // signature of the silent drop above.
        Log.d(TAG, "device volume $from -> $target (max $maxVolume), remote now ${remote.deviceVolume}")
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        remote.setDeviceVolume(deviceVolume, flags)
        Log.d(TAG, "device volume set to $deviceVolume, remote now ${remote.deviceVolume}")
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        stepDeviceVolume(1, flags)

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        stepDeviceVolume(-1, flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        remote.setDeviceMuted(muted, flags)
        return Futures.immediateVoidFuture()
    }

    private fun Int.toCastRepeatMode(): Int = when (this) {
        Player.REPEAT_MODE_ALL -> MediaStatus.REPEAT_MODE_REPEAT_ALL
        Player.REPEAT_MODE_ONE -> MediaStatus.REPEAT_MODE_REPEAT_SINGLE
        else -> MediaStatus.REPEAT_MODE_REPEAT_OFF
    }

    private fun MediaItem.description(index: Int): String =
        "local[$index]=$mediaId/${mediaMetadata.title}"

    private data class PendingLoad(
        val localIndex: Int,
        val mediaId: String,
        val title: String,
        val entity: String?,
        val contentId: String?,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private data class QueueTarget(
        val item: MediaItem,
        val mediaId: String,
        val occurrence: Int,
    )

    private data class PendingGrantLoad(
        val target: QueueTarget,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val generation: Int,
        val session: Int,
    )

    private data class PendingJump(
        val localIndex: Int,
        val receiverItemId: Int,
        val positionMs: Long,
    )

    private data class PendingSeek(
        val positionMs: Long,
        val requestedAtMs: Long,
    )

    data class HandoffSnapshot(
        val mediaItemIndex: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
    )

    private data class CastBatch(
        val items: List<MediaQueueItem>,
        val localIndices: List<Int>,
        val nextLocalIndex: Int,
    )

    private companion object {
        const val TAG = "CastQueuePlayer"
        const val CAST_QUEUE_BATCH_SIZE = 20
        const val QUEUE_LOW_WATER = 8
        const val PROGRESS_INTERVAL_MS = 500L
        const val SEEK_CONFIRM_TOLERANCE_MS = 2_000L
        const val SEEK_CONFIRM_TIMEOUT_MS = 8_000L
        const val SEEK_BUFFERING_MASK_DURATION_MS = 2_500L
        const val GRANT_RETRY_MS = 15_000L

        val DEVICE_INFO: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMaxVolume(20)
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
