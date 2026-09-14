package org.akanework.gramophone.logic.data.automix

import java.nio.ByteBuffer

/**
 * The second stream in a mixed transition.
 *
 * "Side" rather than "second" because it is not a player. It has no timeline, no position anyone
 * can seek, and nothing downstream asks it what is playing - it is a finite run of PCM that
 * [MixingAudioSink] adds to the one the renderer is already producing, and then it is finished.
 *
 * Which track it carries is the design decision the whole transition turns on, and it is the
 * **incoming** one - arrived at the hard way, after the opposite was tried first.
 *
 * The appealing arrangement is the other one: move the player to the incoming track at the handover
 * so that the session, notification, queue and scrobbler all change over at the right moment, and
 * mix the outgoing tail on top. It does not work. Moving the player mid-track means
 * `seekToNextMediaItem`, a seek tears down and rebuilds the `AudioTrack`, and on device that was
 * about 394 ms of silence at exactly the point the mix was supposed to start - `audioTrackReleased`
 * then `audioTrackInit` then `audioPositionAdvancing`, straight after `mediaItem reason=SEEK`. A
 * natural end-of-track change logs `reason=AUTO` and none of those. Worse, a sink of this shape can
 * only *add to* buffers the renderer hands it, so while the renderer produces nothing the deck is
 * silent too: the mixer cannot cover the gap its own handover created.
 *
 * So the outgoing track is left to reach its own end, untouched, and the incoming one is mixed in
 * underneath it. The queue item for the incoming track is clipped to resume where the deck stops,
 * so the natural change picks up exactly where the mix left off. The session tells the truth a few
 * seconds later than it otherwise would, which is a far smaller price than a hole in the audio.
 *
 * Implementations are read from the audio thread and must not block: no I/O, no allocation, no
 * locks that anything slow can hold. Whatever needs decoding, stretching or resampling happens
 * before the deck is handed over.
 */
interface SideDeck {

    /**
     * Fills up to [byteCount] bytes at [destination]'s position with PCM in the sink's format,
     * advancing its position by what was written, and returns the number of bytes written.
     *
     * Returning less than asked - zero included - means the deck has run out, not that it stalled.
     * There is no "try again later": a transition cannot wait for the audio clock.
     */
    fun read(destination: ByteBuffer, byteCount: Int): Int

    /** True once [read] can never return anything again, so the mixer can drop this deck. */
    val isFinished: Boolean

    /**
     * The gain the renderer's own stream should take right now, falling from 1 to 0 across the deck.
     *
     * The deck carries the *incoming* track and its rising fade is already baked into its samples;
     * the renderer is still playing the outgoing one, and something has to take that down. A
     * crossfade has two sides and [MixingAudioSink] can only scale one of them, so the other lives
     * here - only the deck knows how far through the transition it is.
     */
    val hostGain: Float

    /**
     * Discards the next [byteCount] bytes, as though they had been read and thrown away.
     *
     * The handover is scheduled on a `Handler` and arrives whenever the main thread gets to it -
     * measured at 338 ms late once, which at 137 BPM is three quarters of a beat and plainly out of
     * time. The deck cannot be *started* earlier by then, but it can be started further in: dropping
     * exactly as much audio as the callback was late keeps it aligned with the incoming track
     * rather than trailing it.
     */
    fun skip(byteCount: Int)

    /** Releases anything held. Called off the audio thread once the deck is no longer installed. */
    fun release()
}
