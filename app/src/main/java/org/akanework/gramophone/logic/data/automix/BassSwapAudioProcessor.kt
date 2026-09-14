package org.akanework.gramophone.logic.data.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The bass swap, on the ghost player's own audio chain.
 *
 * [BassSwap] filters a tail that was rendered ahead of time. The ghost renders its tail as it
 * plays, so the same filter has to run on the audio thread instead - which changes nothing about
 * the filter and everything about how it is driven. The cutoff cannot be a function of position in
 * a buffer here, because the buffer is a few milliseconds of a stream whose transition is seconds
 * long; it is set from the transition's own fade clock by [setSweep] and glided toward from the
 * audio thread, so a cutoff that arrives in 30 ms steps is heard as a sweep rather than as a
 * staircase.
 *
 * Only ever installed on the ghost. The session player must reach the listener exactly as it would
 * without Automix, and the ghost's chain is a separate sink built for one purpose, which is what
 * makes this safe to leave in place permanently: with no sweep set it passes audio through.
 */
class BassSwapAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetHz = 0f

    @Volatile
    private var progress = 0f

    private var filters: Array<HighPassBiquad> = emptyArray()
    private var currentCutoffHz = HighPassBiquad.MIN_CUTOFF_HZ

    /**
     * Sets where the sweep is going and how far along it is.
     *
     * Called from the transition's handler, read on the audio thread. Two independent floats rather
     * than one precomputed cutoff so the mapping - which is a musical decision about how early the
     * low end changes hands - stays in one place.
     */
    fun setSweep(cutoffHz: Float, progress: Float) {
        this.targetHz = cutoffHz
        this.progress = progress.coerceIn(0f, 1f)
    }

    /** Puts the filter back to doing nothing, for a ghost that is being retired or reused. */
    fun clear() {
        targetHz = 0f
        progress = 0f
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // Refusing an encoding here would fail the ghost's whole render, and a transition that
        // cannot filter is still a transition. Anything unrecognised leaves the processor inactive
        // and the audio untouched.
        if (inputAudioFormat.encoding !in SUPPORTED_ENCODINGS) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Captured before replaceOutputBuffer clears the window - which may be this window.
        val position = inputBuffer.position()
        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining)
        val target = targetHz
        val sweepTo = HighPassBiquad.cutoffFor(target, progress)

        // Off, and settled at the floor: this is the state the ghost spends most of its life in,
        // and passing the audio through untouched is what "no filter" has to mean.
        if (target <= 0f && currentCutoffHz <= HighPassBiquad.MIN_CUTOFF_HZ + SETTLED_HZ) {
            filters.forEach(HighPassBiquad::reset)
            currentCutoffHz = HighPassBiquad.MIN_CUTOFF_HZ
            // Never `output.put(inputBuffer)` unguarded: media3 hands a processor back the buffer
            // it last produced, and ByteBuffer.put refuses to copy a buffer onto itself. That is
            // exactly the fault that stopped playback dead in ReplayGainAudioProcessor, and this
            // is the same fast path in the same shape - it would have fired on the ghost every
            // time a transition ran with the bass swap off.
            if (output === inputBuffer) {
                output.position(position).limit(position + remaining)
            } else {
                output.put(inputBuffer)
                output.flip()
            }
            return
        }

        val channels = inputAudioFormat.channelCount
        if (channels != filters.size) {
            filters = Array(channels) { HighPassBiquad(inputAudioFormat.sampleRate) }
            currentCutoffHz = HighPassBiquad.MIN_CUTOFF_HZ
        }

        val byteOrder = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> ByteOrder.BIG_ENDIAN

            else -> ByteOrder.LITTLE_ENDIAN
        }
        inputBuffer.order(byteOrder)
        output.order(byteOrder)

        var framesUntilUpdate = 0
        var channel = 0
        while (inputBuffer.hasRemaining()) {
            if (channel == 0 && framesUntilUpdate-- <= 0) {
                framesUntilUpdate = UPDATE_FRAMES
                // A one-pole glide toward whatever the handler last asked for. The transition sets
                // the target every 30 ms; this closes most of the remaining distance in about ten,
                // so the filter is never behind the fade and never steps.
                currentCutoffHz += (sweepTo - currentCutoffHz) * GLIDE
                filters.forEach { it.setCutoff(currentCutoffHz) }
            }
            filterOneSample(inputBuffer, output, filters[channel])
            channel = if (channel + 1 == channels) 0 else channel + 1
        }
        output.flip()
    }

    /**
     * Reads one sample, filters it, and writes it back in the encoding it arrived in.
     *
     * Everything is normalised to ±1 before the biquad sees it. The filter is linear so the scale
     * makes no difference to the result, but it does mean the offline and streaming paths run
     * identical arithmetic - and it keeps 32-bit samples out of a float's 24-bit mantissa.
     */
    private fun filterOneSample(input: ByteBuffer, output: ByteBuffer, filter: HighPassBiquad) {
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_8BIT -> {
                val centered = ((input.get().toInt() and 0xff) - 128) / 128f
                val filtered = (filter.process(centered) * 128f).roundToInt().coerceIn(-128, 127)
                output.put((filtered + 128).toByte())
            }

            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                val filtered = (filter.process(input.short / 32768f) * 32768f).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(filtered.toShort())
            }

            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                val sample = if (output.order() == ByteOrder.LITTLE_ENDIAN) {
                    val b0 = input.get().toInt() and 0xff
                    val b1 = input.get().toInt() and 0xff
                    val b2 = input.get().toInt()
                    b0 or (b1 shl 8) or (b2 shl 16)
                } else {
                    val b0 = input.get().toInt()
                    val b1 = input.get().toInt() and 0xff
                    val b2 = input.get().toInt() and 0xff
                    (b0 shl 16) or (b1 shl 8) or b2
                }
                val filtered = (filter.process(sample / SCALE_24) * SCALE_24).roundToInt()
                    .coerceIn(MIN_PCM_24, MAX_PCM_24)
                if (output.order() == ByteOrder.LITTLE_ENDIAN) {
                    output.put(filtered.toByte())
                    output.put((filtered shr 8).toByte())
                    output.put((filtered shr 16).toByte())
                } else {
                    output.put((filtered shr 16).toByte())
                    output.put((filtered shr 8).toByte())
                    output.put(filtered.toByte())
                }
            }

            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                val filtered = (filter.process((input.int / SCALE_32).toFloat()) * SCALE_32)
                    .roundToLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                output.putInt(filtered.toInt())
            }

            C.ENCODING_PCM_FLOAT -> output.putFloat(filter.process(input.float).coerceIn(-1f, 1f))

            else -> {
                // onConfigure leaves the processor inactive for anything not listed above, so this
                // is unreachable - and copying rather than throwing keeps it unreachable *and*
                // harmless if a future encoding is added to one list and not the other.
                output.put(input.get())
            }
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = resetFilters()

    @Deprecated("Overridden alongside the StreamMetadata form so neither flush path is missed.")
    override fun onFlush() = resetFilters()

    override fun onReset() {
        clear()
        filters = emptyArray()
        currentCutoffHz = HighPassBiquad.MIN_CUTOFF_HZ
    }

    /** A delay line carrying samples from before a seek rings audibly across the discontinuity. */
    private fun resetFilters() {
        filters.forEach(HighPassBiquad::reset)
    }

    private companion object {
        const val MIN_PCM_24 = -8_388_608
        const val MAX_PCM_24 = 8_388_607
        const val SCALE_24 = 8_388_608f
        const val SCALE_32 = 2_147_483_648.0

        /** How often the cutoff is recomputed, in frames. Under a millisecond at any sample rate. */
        const val UPDATE_FRAMES = 32

        /** How much of the remaining distance to the target each update closes. */
        const val GLIDE = 0.08f

        /** Within this of the floor the filter is doing nothing, so it can be switched out. */
        const val SETTLED_HZ = 0.5f

        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT,
        )
    }
}
