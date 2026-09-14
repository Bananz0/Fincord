package org.akanework.gramophone.logic.utils.exoplayer


import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import uk.akane.accord.BuildConfig
import org.akanework.gramophone.logic.utils.CircularShuffleOrder


/**
 * If player in STATE_ENDED is resumed, state will be STATE_READY, on play button press it will
 * update to STATE_ENDED and only then media3 will wrap around playlist for us. This is a workaround
 * to restore STATE_ENDED as well and fake it for media3 until it indeed wraps around playlist.
 */
@UnstableApi
class EndedWorkaroundPlayer(player: ExoPlayer)
	: ForwardingPlayer(player), Player.Listener {

	companion object {
		private const val TAG = "EndedWorkaroundPlayer"
	}

	val exoPlayer
		get() = wrappedPlayer as ExoPlayer

	/**
	 * Supplies the platform AudioTrack so the position can run on the audio hardware's own clock.
	 *
	 * Set by the playback service once AfFormatTracker has one. Null - before a track exists, or on
	 * a media3 whose internals moved again - simply means the correction is skipped and media3's
	 * own position is reported, which is the behaviour this class had before.
	 */
	var audioTrackProvider: (() -> android.media.AudioTrack?)? = null

	private val hardwareClock = uk.akane.accord.logic.player.HardwareClockPosition()

	/**
	 * The position everything downstream sees: the session, the notification, the seek bar, the
	 * scrobbler, the Jellyfin progress report and the lyrics.
	 *
	 * Overridden because media3's own answer is measurably the worst clock in the stack - several
	 * hundred milliseconds out and closing at a couple of percent a second, while the hardware
	 * timestamp beside it holds a constant offset with no drift. See [HardwareClockPosition] for
	 * the measurements and for every case in which it declines to correct anything.
	 */
	override fun getCurrentPosition(): Long {
		val raw = super.getCurrentPosition()
		val provider = audioTrackProvider ?: return raw
		return hardwareClock.correct(raw, provider(), super.isPlaying())
	}

	override fun seekTo(positionMs: Long) {
		hardwareClock.reset()
		super.seekTo(positionMs)
	}

	override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
		hardwareClock.reset()
		super.seekTo(mediaItemIndex, positionMs)
	}
	var isEnded = false
		set(value) {
			if (BuildConfig.DEBUG) {
				Log.d(TAG, "isEnded set to $value (was $field)")
			}
			field = value
			if (field) {
				wrappedPlayer.addListener(this)
			} else {
				wrappedPlayer.removeListener(this)
			}
		}
	val shufflePersistent: CircularShuffleOrder.Persistent?
		get() = if (shuffleModeEnabled) shuffleOrder?.let { CircularShuffleOrder.Persistent(it) } else null
	private var shuffleOrder: CircularShuffleOrder? = null

	override fun onPositionDiscontinuity(
		oldPosition: Player.PositionInfo,
		newPosition: Player.PositionInfo,
		reason: Int
	) {
		if (reason == DISCONTINUITY_REASON_SEEK) {
			isEnded = false
		}
		// Any discontinuity voids the anchor: the frame counter and the content position have just
		// stopped agreeing about what zero means.
		hardwareClock.reset()
		super.onPositionDiscontinuity(oldPosition, newPosition, reason)
	}

	override fun getPlaybackState(): Int {
		if (isEnded) return STATE_ENDED
		return super.getPlaybackState()
	}

	fun setShuffleOrder(
		shuffleOrderFactory: ((CircularShuffleOrder) -> Unit) -> CircularShuffleOrder) {
		val shuffleOrder = shuffleOrderFactory { shuffleOrder = it }
		exoPlayer.setShuffleOrder(shuffleOrder)
		this.shuffleOrder = shuffleOrder
	}

	override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) {
		super.moveMediaItems(fromIndex, toIndex, newIndex)
		try {
			shuffleOrder?.let {
				exoPlayer.setShuffleOrder(
					it.cloneAndRemove(fromIndex, toIndex)
						.cloneAndInsert(newIndex, toIndex - fromIndex)
				)
			}
		} catch (e: Exception) {
			Log.e(TAG, Log.getStackTraceString(e))
			throw e
		}
	}

	override fun moveMediaItem(currentIndex: Int, newIndex: Int) {
		if (currentIndex != newIndex) {
			moveMediaItems(currentIndex, currentIndex + 1, newIndex)
		}
	}
}
