package org.akanework.gramophone.logic.data.automix

import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * How the outgoing track's level falls across a transition.
 *
 * The shape is not cosmetic. Two tracks summed at half amplitude each are not as loud as either was
 * alone, because uncorrelated audio adds in power rather than in amplitude: halve both and the sum
 * sits about 3 dB below where it started, heard as the mix sagging in the middle and swelling back.
 * Which curve avoids that depends on how alike the two tracks are, and that is the whole reason
 * there is more than one here.
 */
enum class FadeCurve {

    /**
     * Constant power: gain follows a quarter cosine, so the two tracks' squares sum to one.
     *
     * The right default for a beat-matched mix of unrelated tracks, which is the case Automix is
     * built for. Two different records are uncorrelated, their powers add, and holding the total
     * power constant is what keeps the level steady through the overlap.
     */
    CONSTANT_POWER {
        override fun gainAt(progress: Float): Float =
            sqrt(0.5f * (1f + cos(PI * progress.coerceIn(0f, 1f)).toFloat()))
    },

    /**
     * Linear amplitude.
     *
     * For material that is genuinely correlated - the same track against itself, a loop against its
     * own continuation - where the two sum in amplitude rather than in power, and constant power
     * would push the middle of the transition about 3 dB *up* instead of holding it level.
     */
    LINEAR {
        override fun gainAt(progress: Float): Float = 1f - progress.coerceIn(0f, 1f)

        /** Amplitudes sum to one here, rather than powers, which is the point of this curve. */
        override fun incomingGainAt(progress: Float): Float = progress.coerceIn(0f, 1f)
    },

    /**
     * Falls away quickly and finishes early.
     *
     * For a transition that is a cut with the edge taken off rather than a blend: the outgoing
     * track is gone well before the crossfade window ends, which suits tracks whose endings are
     * busy, or whose keys clash badly enough that hearing them together is worse than not.
     */
    QUICK {
        override fun gainAt(progress: Float): Float {
            val p = progress.coerceIn(0f, 1f)
            val gain = 1f - p * 2f
            return if (gain <= 0f) 0f else gain * gain
        }
    };

    /** Gain from 1 down to 0 for [progress] running 0..1 across the transition. */
    abstract fun gainAt(progress: Float): Float

    /**
     * The gain the *incoming* track takes at the same moment, rising from 0 to 1.
     *
     * Without this there is no crossfade, only an overlay. The player starts the incoming track at
     * full volume the instant the handover happens, so fading the outgoing one alone means the
     * overlap runs at both tracks at once - which on device was described exactly that way, the old
     * track "audibly full volume overlain" on the new one. Both sides have to move.
     *
     * Complementary to [gainAt] in whichever sense the curve is defined: squares summing to one for
     * constant power, amplitudes summing to one for linear. That is what keeps the level steady
     * through the middle rather than dipping or swelling.
     */
    open fun incomingGainAt(progress: Float): Float {
        val outgoing = gainAt(progress)
        return sqrt((1f - outgoing * outgoing).coerceAtLeast(0f))
    }
}
