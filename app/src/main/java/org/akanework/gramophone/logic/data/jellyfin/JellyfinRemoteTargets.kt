package org.akanework.gramophone.logic.data.jellyfin

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SessionInfoDto
import org.jellyfin.sdk.model.api.SessionsMessage
import java.util.UUID

/** Sending and live-state half of Finnect. */
object JellyfinRemoteTargets {

    const val PREF_FINNECT_ENABLED = "finnect_enabled"

    private const val TAG = "JellyfinRemoteTargets"
    private const val MAX_IDLE_MINUTES = 10L
    private const val AVAILABLE_CACHE_MS = 30_000L
    private const val TICKS_PER_MILLISECOND = 10_000L
    private const val KEY_ACTIVE_SESSION_ID = "finnect_active_session_id"

    data class Target(
        val sessionId: String,
        val client: String,
        val deviceName: String,
        val nowPlaying: String?,
    )

    /** The authoritative state reported by the target session. */
    data class RemotePlaybackState(
        val target: Target,
        val itemId: String?,
        val itemName: String?,
        val queueIds: List<String>,
        val positionMs: Long,
        val durationMs: Long?,
        val isPaused: Boolean,
        val canSeek: Boolean,
        val volumePercent: Int?,
        val repeatMode: RepeatMode,
        val playbackOrder: PlaybackOrder,
        val observedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        fun projectedPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
            val elapsed = if (isPaused) 0L else (nowElapsedMs - observedAtElapsedMs).coerceAtLeast(0L)
            val projected = positionMs + elapsed
            return durationMs?.let { projected.coerceIn(0L, it) } ?: projected.coerceAtLeast(0L)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val _active = MutableStateFlow<Target?>(null)
    private val _playbackState = MutableStateFlow<RemotePlaybackState?>(null)

    @Volatile
    private var availableCache: List<Target> = emptyList()
    @Volatile
    private var availableCacheUpdatedAtMs = 0L

    val active: StateFlow<Target?> = _active.asStateFlow()
    val playbackState: StateFlow<RemotePlaybackState?> = _playbackState.asStateFlow()

    fun isEnabled(context: Context): Boolean = PreferenceManager
        .getDefaultSharedPreferences(context)
        .getBoolean(PREF_FINNECT_ENABLED, true)

    fun cachedAvailable(): List<Target> = availableCache.takeIf {
        SystemClock.elapsedRealtime() - availableCacheUpdatedAtMs <= AVAILABLE_CACHE_MS
    }.orEmpty()

    suspend fun available(): List<Target> = withContext(Dispatchers.IO) {
        try {
            val api = JellyfinClientHolder.api() ?: return@withContext emptyList()
            val ownDeviceId = api.deviceInfo.id
            val sessions = controllableSessions()
            sessions.firstOrNull { it.id == _active.value?.sessionId }?.let(::applySession)
            sessions
                .filter { it.supportsRemoteControl }
                .filter { it.deviceId != ownDeviceId }
                .filter { !it.client.isNullOrBlank() }
                .map(::toTarget)
                .also {
                    availableCache = it
                    availableCacheUpdatedAtMs = SystemClock.elapsedRealtime()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list sessions", e)
            emptyList()
        }
    }

    suspend fun playOn(
        target: Target,
        remoteIds: List<String>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
    ): Boolean = withContext(Dispatchers.IO) {
        val api = JellyfinClientHolder.api() ?: return@withContext false
        val ids = remoteIds.mapNotNull {
            runCatching { UUID.fromString(it.toDashedUuid()) }.getOrNull()
        }
        if (ids.isEmpty()) return@withContext false
        try {
            api.sessionApi.play(
                sessionId = target.sessionId,
                playCommand = PlayCommand.PLAY_NOW,
                itemIds = ids,
                startIndex = startIndex,
                startPositionTicks = startPositionMs * TICKS_PER_MILLISECOND,
            )
            _active.value = target
            rememberActive(target.sessionId)
            _playbackState.value = RemotePlaybackState(
                target = target,
                itemId = remoteIds.getOrNull(startIndex)?.normalizedId(),
                itemName = target.nowPlaying,
                queueIds = remoteIds.map { it.normalizedId() },
                positionMs = startPositionMs,
                durationMs = null,
                isPaused = false,
                canSeek = true,
                volumePercent = null,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            )
            startMonitor()
            Log.d(TAG, "Handed ${ids.size} items to ${target.client} on ${target.deviceName}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play on ${target.client}", e)
            false
        }
    }

    /**
     * Claims an already-playing target without replacing its queue.
     *
     * This is what another Accord client for the same Jellyfin user needs: the server has already
     * scoped [controllableSessions] to that user, and the live session remains authoritative.
     */
    suspend fun claim(target: Target): Boolean = withContext(Dispatchers.IO) {
        val session = runCatching {
            controllableSessions().firstOrNull {
                it.id == target.sessionId && it.supportsRemoteControl && it.nowPlayingItem != null
            }
        }.onFailure { Log.w(TAG, "Could not claim Finnect session", it) }.getOrNull()
            ?: return@withContext false
        val liveTarget = toTarget(session)
        _active.value = liveTarget
        rememberActive(liveTarget.sessionId)
        applySession(session)
        startMonitor()
        true
    }

    /** Reclaims the target this controller owned before its process was restarted. */
    suspend fun restoreActive(): Boolean = withContext(Dispatchers.IO) {
        if (_active.value != null) return@withContext true
        val sessionId = PreferenceManager.getDefaultSharedPreferences(JellyfinClientHolder.context())
            .getString(KEY_ACTIVE_SESSION_ID, null)
            ?: return@withContext false
        val session = runCatching {
            controllableSessions().firstOrNull {
                it.id == sessionId && it.supportsRemoteControl && it.nowPlayingItem != null
            }
        }.getOrNull()
        if (session == null) {
            forgetActive()
            return@withContext false
        }
        val target = toTarget(session)
        _active.value = target
        applySession(session)
        startMonitor()
        true
    }

    /** Fetch immediately before pulling playback back; the socket remains the normal update path. */
    suspend fun refreshActiveState(): RemotePlaybackState? = withContext(Dispatchers.IO) {
        val target = _active.value ?: return@withContext null
        runCatching {
            controllableSessions().firstOrNull { it.id == target.sessionId }?.also(::applySession)
        }.onFailure { Log.w(TAG, "Could not refresh active Finnect session", it) }
        _playbackState.value
    }

    suspend fun sendTransport(command: PlaystateCommand, seekPositionMs: Long? = null): Boolean {
        val target = _active.value ?: return false
        val api = JellyfinClientHolder.api() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                api.sessionApi.sendPlaystateCommand(
                    sessionId = target.sessionId,
                    command = command,
                    seekPositionTicks = seekPositionMs?.times(TICKS_PER_MILLISECOND),
                )
                applyOptimisticTransport(command, seekPositionMs)
                true
            }.onFailure { Log.w(TAG, "Transport $command failed", it) }.getOrDefault(false)
        }
    }

    suspend fun playQueueIndex(index: Int): Boolean {
        val state = _playbackState.value ?: return false
        if (index !in state.queueIds.indices) return false
        return playOn(state.target, state.queueIds, startIndex = index, startPositionMs = 0L)
    }

    suspend fun sendVolume(percent: Int): Boolean {
        val target = _active.value ?: return false
        val api = JellyfinClientHolder.api() ?: return false
        val bounded = percent.coerceIn(0, 100)
        return withContext(Dispatchers.IO) {
            runCatching {
                api.sessionApi.sendFullGeneralCommand(
                    sessionId = target.sessionId,
                    data = org.jellyfin.sdk.model.api.GeneralCommand(
                        name = GeneralCommandType.SET_VOLUME,
                        controllingUserId = UUID(0, 0),
                        arguments = mapOf("Volume" to bounded.toString()),
                    ),
                )
                _playbackState.value = _playbackState.value?.copy(volumePercent = bounded)
                true
            }.onFailure { Log.w(TAG, "Volume failed", it) }.getOrDefault(false)
        }
    }

    suspend fun sendShuffle(enabled: Boolean): Boolean = sendGeneral(
        command = GeneralCommandType.SET_SHUFFLE_QUEUE,
        arguments = mapOf("ShuffleMode" to if (enabled) "Shuffle" else "Sorted"),
    ) {
        copy(playbackOrder = if (enabled) PlaybackOrder.SHUFFLE else PlaybackOrder.DEFAULT)
    }

    suspend fun sendRepeat(mode: RepeatMode): Boolean = sendGeneral(
        command = GeneralCommandType.SET_REPEAT_MODE,
        arguments = mapOf(
            "RepeatMode" to when (mode) {
                RepeatMode.REPEAT_ALL -> "RepeatAll"
                RepeatMode.REPEAT_ONE -> "RepeatOne"
                RepeatMode.REPEAT_NONE -> "RepeatNone"
            }
        ),
    ) { copy(repeatMode = mode) }

    private suspend fun sendGeneral(
        command: GeneralCommandType,
        arguments: Map<String, String>,
        optimisticUpdate: RemotePlaybackState.() -> RemotePlaybackState,
    ): Boolean {
        val target = _active.value ?: return false
        val api = JellyfinClientHolder.api() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                api.sessionApi.sendFullGeneralCommand(
                    sessionId = target.sessionId,
                    data = org.jellyfin.sdk.model.api.GeneralCommand(
                        name = command,
                        controllingUserId = UUID(0, 0),
                        arguments = arguments,
                    ),
                )
                _playbackState.value = _playbackState.value?.optimisticUpdate()
                true
            }.onFailure { Log.w(TAG, "$command failed", it) }.getOrDefault(false)
        }
    }

    fun playLocally() {
        monitorJob?.cancel()
        monitorJob = null
        _active.value = null
        _playbackState.value = null
        forgetActive()
    }

    /** Called when the global setting is switched off. */
    fun disable() {
        val target = _active.value
        availableCache = emptyList()
        availableCacheUpdatedAtMs = 0L
        playLocally()
        // Turning the feature off must not strand music on another phone with no controls left in
        // this UI. Clear local Finnect state immediately, then best-effort stop the old target.
        if (target != null) scope.launch {
            val api = JellyfinClientHolder.api() ?: return@launch
            runCatching {
                api.sessionApi.sendPlaystateCommand(
                    sessionId = target.sessionId,
                    command = PlaystateCommand.STOP,
                )
            }.onFailure { Log.w(TAG, "Could not stop Finnect target while disabling", it) }
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val api = JellyfinClientHolder.api() ?: return@launch
            // Gives the UI full state immediately; Sessions messages then arrive every second.
            refreshActiveState()
            api.webSocket.subscribe(SessionsMessage::class).collectLatest { message ->
                val sessionId = _active.value?.sessionId ?: return@collectLatest
                message.data?.firstOrNull { it.id == sessionId }?.let(::applySession)
            }
        }
    }

    private suspend fun controllableSessions(): List<SessionInfoDto> {
        val api = JellyfinClientHolder.api() ?: return emptyList()
        val userId = JellyfinClientHolder.credentials.userId
            ?.let { runCatching { UUID.fromString(it.toDashedUuid()) }.getOrNull() }
        return api.sessionApi.getSessions(
            controllableByUserId = userId,
            activeWithinSeconds = (MAX_IDLE_MINUTES * 60).toInt(),
        ).content
    }

    private fun applySession(session: SessionInfoDto) {
        val target = _active.value ?: return
        if (session.id != target.sessionId) return
        val previous = _playbackState.value
        val playState = session.playState
        val reportedQueue = session.nowPlayingQueue.orEmpty().map { it.id.toString().normalizedId() }
        _playbackState.value = RemotePlaybackState(
            target = target.copy(nowPlaying = session.nowPlayingItem?.name),
            itemId = session.nowPlayingItem?.id?.toString()?.normalizedId(),
            itemName = session.nowPlayingItem?.name,
            queueIds = reportedQueue.ifEmpty { previous?.queueIds.orEmpty() },
            positionMs = (playState?.positionTicks ?: 0L) / TICKS_PER_MILLISECOND,
            durationMs = session.nowPlayingItem?.runTimeTicks?.div(TICKS_PER_MILLISECOND),
            isPaused = playState?.isPaused ?: true,
            canSeek = playState?.canSeek ?: false,
            volumePercent = playState?.volumeLevel,
            repeatMode = playState?.repeatMode ?: previous?.repeatMode ?: RepeatMode.REPEAT_NONE,
            playbackOrder = playState?.playbackOrder
                ?: previous?.playbackOrder
                ?: PlaybackOrder.DEFAULT,
        )
    }

    private fun applyOptimisticTransport(command: PlaystateCommand, seekPositionMs: Long?) {
        val state = _playbackState.value ?: return
        val currentPosition = state.projectedPositionMs()
        _playbackState.value = when (command) {
            PlaystateCommand.PLAY_PAUSE -> state.copy(
                positionMs = currentPosition,
                isPaused = !state.isPaused,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            PlaystateCommand.PAUSE -> state.copy(
                positionMs = currentPosition,
                isPaused = true,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            PlaystateCommand.UNPAUSE -> state.copy(
                positionMs = currentPosition,
                isPaused = false,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            PlaystateCommand.SEEK -> state.copy(
                positionMs = seekPositionMs ?: currentPosition,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            PlaystateCommand.NEXT_TRACK,
            PlaystateCommand.PREVIOUS_TRACK -> {
                val current = state.queueIds.indexOf(state.itemId?.normalizedId())
                val next = if (command == PlaystateCommand.NEXT_TRACK) current + 1 else current - 1
                state.copy(
                    itemId = state.queueIds.getOrNull(next) ?: state.itemId,
                    itemName = null,
                    positionMs = 0L,
                    durationMs = null,
                    observedAtElapsedMs = SystemClock.elapsedRealtime(),
                )
            }
            PlaystateCommand.STOP -> state.copy(
                positionMs = currentPosition,
                isPaused = true,
                observedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            else -> state
        }
    }

    private fun toTarget(session: SessionInfoDto) = Target(
        sessionId = session.id.orEmpty(),
        client = session.client.orEmpty(),
        deviceName = session.deviceName.orEmpty(),
        nowPlaying = session.nowPlayingItem?.name,
    )

    private fun rememberActive(sessionId: String) {
        PreferenceManager.getDefaultSharedPreferences(JellyfinClientHolder.context())
            .edit().putString(KEY_ACTIVE_SESSION_ID, sessionId).apply()
    }

    private fun forgetActive() {
        PreferenceManager.getDefaultSharedPreferences(JellyfinClientHolder.context())
            .edit().remove(KEY_ACTIVE_SESSION_ID).apply()
    }

    private fun String.normalizedId(): String = replace("-", "").lowercase()

    private fun String.toDashedUuid(): String {
        if (length != 32) return this
        return buildString(36) {
            append(this@toDashedUuid, 0, 8).append('-')
            append(this@toDashedUuid, 8, 12).append('-')
            append(this@toDashedUuid, 12, 16).append('-')
            append(this@toDashedUuid, 16, 20).append('-')
            append(this@toDashedUuid, 20, 32)
        }
    }
}
