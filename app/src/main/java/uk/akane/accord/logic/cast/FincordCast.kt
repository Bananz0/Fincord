package uk.akane.accord.logic.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionTransferCallback
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the Google Cast *session*, and nothing else.
 *
 * Deliberately thin, in two separate senses. Discovery is not here: the Cast framework registers
 * itself as an AndroidX MediaRouter provider, so devices and speaker groups already arrive through
 * the router the output picker reads, one route stack rather than a second competing beside it.
 *
 * Neither is playback. Everything a receiver is told to do - load a queue, play, pause, seek, skip,
 * set volume, repeat - goes through the [RemoteCastPlayer][androidx.media3.cast.RemoteCastPlayer]
 * that the playback service keeps inside its [CastPlayer][androidx.media3.cast.CastPlayer], because
 * that player is what sits in front of the MediaSession while a cast is running. This object used
 * to carry a second, parallel transport API driven from the now-playing view, and having two owners
 * of the same receiver is precisely what left the session describing a paused phone: the expanded
 * player read the receiver, while the notification, the lock screen, the mini bar and every other
 * app read the session. Adding transport back here would recreate that split.
 *
 * The receiver fetches streams itself, which works because Jellyfin serves its static audio
 * endpoint unauthenticated and with range support (verified: HTTP 206, `audio/flac`). That is what
 * lets the original file be handed over untouched rather than transcoded for the cast.
 */
object FincordCast {

    private const val TAG = "FincordCast"

    /** Null whenever Cast is unusable - no Play Services, or the framework failed to start. */
    @Volatile
    private var context: CastContext? = null

    private val _castState = MutableStateFlow(CastState.NO_DEVICES_AVAILABLE)

    /** Mirrors [CastContext.getCastState]; drives whether the player offers a cast affordance. */
    val castState: StateFlow<Int> = _castState

    private val _session = MutableStateFlow<CastSession?>(null)

    /** The connected receiver, or null when playback is not on one. */
    val session: StateFlow<CastSession?> = _session

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            _session.value = session
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _session.value = session
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            _session.value = null
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            // Kept: a suspend is a transient network drop, and the framework resumes the same
            // session rather than starting a new one.
        }

        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "cast session failed to start: $error")
            _session.value = null
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            Log.w(TAG, "cast session failed to resume: $error")
            _session.value = null
        }
    }

    /**
     * Starts the Cast framework, if this device can run it at all.
     *
     * Returns false on a device without Play Services rather than throwing, because
     * [CastContext.getSharedInstance] does throw there - and an app that refuses to open on a
     * de-Googled phone because it wanted to offer casting is a worse outcome than no casting.
     */
    fun initialize(appContext: Context): Boolean {
        context?.let { return true }
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext)
        if (availability != ConnectionResult.SUCCESS) {
            Log.i(TAG, "cast unavailable: play services result $availability")
            return false
        }
        return runCatching {
            val castContext = CastContext.getSharedInstance(appContext)
            context = castContext
            castContext.sessionManager.addSessionManagerListener(
                sessionListener,
                CastSession::class.java,
            )
            // A process restart can restore the session before any listener callback reaches this
            // new process, so the current one is picked up directly as well.
            castContext.sessionManager.currentCastSession?.let { _session.value = it }
            _castState.value = castContext.castState
            castContext.addCastStateListener { state -> _castState.value = state }
            true
        }.onFailure { Log.w(TAG, "cast failed to initialise", it) }.getOrDefault(false)
    }

    /** True once [initialize] has succeeded, so callers can hide the affordance entirely. */
    val isAvailable: Boolean get() = context != null

    /** True while a receiver is loaded, as opposed to merely connected. */
    val isCasting: Boolean get() = _session.value?.remoteMediaClient?.hasMediaSession() == true

    /**
     * Lets a Cast remote-control notification join the receiver session it represents.
     *
     * The URI is registered against Fincord's receiver app in the Cast Developer Console. A copy
     * is passed to the framework because the Activity consumes its own intent after this call.
     */
    fun joinFrom(intent: Intent): Boolean {
        if (intent.data != Uri.parse(INTENT_TO_JOIN_URI)) return false
        val manager = context?.sessionManager ?: return false
        manager.startSession(Intent(intent))
        return true
    }

    /**
     * Detaches this sender, optionally terminating playback on the receiver.
     *
     * `false` is the Cast equivalent of "Play here too": the receiver and any other joined sender
     * remain untouched while this phone resumes its own copy. `true` is "Move here".
     */
    fun endSession(stopReceiver: Boolean): Boolean {
        val manager = context?.sessionManager ?: return false
        if (manager.currentCastSession == null) return false
        manager.endCurrentSession(stopReceiver)
        return true
    }

    fun addSessionTransferCallback(callback: SessionTransferCallback): Boolean {
        val castContext = context ?: return false
        castContext.addSessionTransferCallback(callback)
        return true
    }

    fun removeSessionTransferCallback(callback: SessionTransferCallback) {
        context?.removeSessionTransferCallback(callback)
    }

    /** Must also be registered for this package in the Cast Developer Console. */
    const val INTENT_TO_JOIN_URI = "fincord://cast/join"
}
