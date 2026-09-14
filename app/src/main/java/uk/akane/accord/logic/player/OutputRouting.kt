package uk.akane.accord.logic.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a connected remote target is currently being *used*.
 *
 * Connecting to a speaker and hearing music on it were the same fact until now: a Cast session or
 * a Finnect target existing was what put its player in front of the session, so the only way to
 * hear a song on the phone was to give the remote one up. That is fine for "I am done casting" and
 * wrong for "not this song" - there was no way to keep a speaker picked and still play on the
 * handset, and re-picking a device every time is the friction that made the choice worth offering
 * in the first place.
 *
 * This is a preference, not a transport. It says which output the user wants next; deciding what
 * that means for the players stays in `GramophonePlaybackService.updateSessionPlayer`, which is
 * still the only place that chooses between the three. Nothing here talks to a receiver.
 *
 * It deliberately does not survive the target: a session that ends, or a Finnect target that is
 * cleared, [reset]s this, so a later cast starts by playing on the speaker the way anyone would
 * expect rather than silently inheriting a decision made about a device that is no longer there.
 */
object OutputRouting {

    private val _playLocally = MutableStateFlow(false)

    /** True while a remote target is connected but the user asked to hear it on this phone. */
    val playLocally: StateFlow<Boolean> = _playLocally.asStateFlow()

    /** Which target the prompt has already been shown for, so it is asked once and not per song. */
    @Volatile
    var promptedFor: String? = null
        private set

    fun setPlayLocally(local: Boolean) {
        _playLocally.value = local
    }

    /** Records that the choice has been offered for [targetId]; returns false if it already was. */
    fun markPrompted(targetId: String): Boolean {
        if (promptedFor == targetId) return false
        promptedFor = targetId
        return true
    }

    /** The remote target is gone, so the preference about it is meaningless. */
    fun reset() {
        _playLocally.value = false
        promptedFor = null
    }
}
