/*
 *     Copyright (C) 2025 nift4
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/*
 * Ported from FoedusProgramme/Gramophone on 2026-09-02. Gramophone can use a native dynamic-range
 * compressor from its hificore fork. Accord does not carry that compressor, so this version always
 * uses Gramophone's peak-aware attenuation path. That keeps the output safe from clipping without
 * adding a native dependency or changing PCM encoding merely because ReplayGain is enabled.
 */

package org.akanework.gramophone.logic.utils

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ReplayGainAudioProcessor : BaseAudioProcessor() {
    private val stateLock = Any()
    private var tags = ReplayGainUtil.ReplayGainInfo(null, null, null, null)
    private var gain = 1f

    var mode = ReplayGainUtil.Mode.None
        private set
    var preampDb = 0
        private set
    var untaggedGainDb = 0
        private set

    /** Reused source copy for the case where media3 hands back this processor's own buffer. */
    private var readScratch: ByteBuffer? = null

    /** So the aliasing notice is logged once per stream rather than once per buffer. */
    private var reportedAliasing = false

    fun setMode(value: ReplayGainUtil.Mode) = synchronized(stateLock) {
        mode = value
        updateGainLocked()
    }

    fun setPreampDb(value: Int) = synchronized(stateLock) {
        preampDb = value
        updateGainLocked()
    }

    fun setUntaggedGainDb(value: Int) = synchronized(stateLock) {
        untaggedGainDb = value
        updateGainLocked()
    }

    /** Supplies the compressed source format, whose metadata carries the ReplayGain tags. */
    fun setRootFormat(inputFormat: Format?) = synchronized(stateLock) {
        tags = ReplayGainUtil.parse(inputFormat)
        updateGainLocked()
    }

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        require(inputAudioFormat.encoding in SUPPORTED_ENCODINGS) {
            "Unsupported ReplayGain PCM encoding: ${inputAudioFormat.encoding}"
        }
        return inputAudioFormat
    }

    override fun queueInput(rawInput: ByteBuffer) {
        val currentGain = synchronized(stateLock) { gain }
        // Captured before replaceOutputBuffer, which clears the buffer - and may be clearing this
        // very window, see below.
        val position = rawInput.position()
        val remaining = rawInput.remaining()
        val outputBuffer = replaceOutputBuffer(remaining)
        /*
         * media3 can hand a processor back the buffer it last produced, so input and output are
         * sometimes the same object. Both paths below have to know, and both were wrong about it:
         * the copy threw outright, and the scaling loop below silently corrupted its own input.
         */
        val aliased = outputBuffer === rawInput
        if (aliased && !reportedAliasing) {
            reportedAliasing = true
            Log.i(TAG, "media3 is passing this processor its own output buffer; handling in place")
        }
        if (currentGain == 1f) {
            /*
             * Unity gain: the bytes leave exactly as they arrived.
             *
             * The obvious way to write that is `outputBuffer.put(inputBuffer)`, which is what it
             * was, and it is wrong in one case that turns out to be the common one. media3 can
             * hand this processor back the buffer it last produced, and `ByteBuffer.put` throws
             * `IllegalArgumentException: The source buffer is this buffer` rather than copying a
             * buffer onto itself. That surfaced as `ERROR_CODE_FAILED_RUNTIME_CHECK` from
             * `DefaultAudioSink.processBuffers` before a single frame was rendered.
             *
             * The reason it was the common case: gain is exactly 1 whenever ReplayGain is *off*,
             * so every playback attempt with the feature disabled took this path and the player
             * went straight to IDLE. With ReplayGain on, gain is never exactly 1 and the
             * per-sample path below runs instead - which is why turning normalisation *on* was
             * what made audio work, and why it looked like a bug in float output for a while.
             *
             * When the buffers are the same object there is nothing to copy: the bytes are
             * already in place. Only the window has to be put back, because replaceOutputBuffer
             * cleared it. When they differ, this is the copy it always was.
             */
            if (aliased) {
                outputBuffer.position(position).limit(position + remaining)
            } else {
                outputBuffer.put(rawInput)
                outputBuffer.flip()
            }
            return
        }

        val byteOrder = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN -> ByteOrder.BIG_ENDIAN
            else -> ByteOrder.LITTLE_ENDIAN
        }
        /*
         * Read through a private copy when the two are one buffer.
         *
         * The loop below reads a sample and writes it back scaled, and a ByteBuffer carries one
         * position for both. Aliased, that means every write lands on the bytes the next read was
         * going to take - the samples are progressively overwritten by their own scaled
         * predecessors. It does not fail; it comes out as gross distortion, which is a far harder
         * thing to trace back here than a crash would have been.
         *
         * The copy is only made on the aliased path and its buffer is reused, so the untouched
         * case allocates nothing and costs one reference comparison.
         */
        val inputBuffer = if (aliased) copyForReading(rawInput, position, remaining) else rawInput
        inputBuffer.order(byteOrder)
        outputBuffer.clear()
        outputBuffer.order(byteOrder)

        while (inputBuffer.hasRemaining()) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_8BIT -> {
                    val centered = (inputBuffer.get().toInt() and 0xff) - 128
                    val scaled = (centered * currentGain).roundToInt().coerceIn(-128, 127)
                    outputBuffer.put((scaled + 128).toByte())
                }

                C.ENCODING_PCM_16BIT,
                C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
                    val scaled = (inputBuffer.short * currentGain).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    outputBuffer.putShort(scaled.toShort())
                }

                C.ENCODING_PCM_24BIT,
                C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                    val sample = if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                        val b0 = inputBuffer.get().toInt() and 0xff
                        val b1 = inputBuffer.get().toInt() and 0xff
                        val b2 = inputBuffer.get().toInt()
                        b0 or (b1 shl 8) or (b2 shl 16)
                    } else {
                        val b0 = inputBuffer.get().toInt()
                        val b1 = inputBuffer.get().toInt() and 0xff
                        val b2 = inputBuffer.get().toInt() and 0xff
                        (b0 shl 16) or (b1 shl 8) or b2
                    }
                    val scaled = (sample * currentGain).roundToInt()
                        .coerceIn(MIN_PCM_24, MAX_PCM_24)
                    if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
                        outputBuffer.put(scaled.toByte())
                        outputBuffer.put((scaled shr 8).toByte())
                        outputBuffer.put((scaled shr 16).toByte())
                    } else {
                        outputBuffer.put((scaled shr 16).toByte())
                        outputBuffer.put((scaled shr 8).toByte())
                        outputBuffer.put(scaled.toByte())
                    }
                }

                C.ENCODING_PCM_32BIT,
                C.ENCODING_PCM_32BIT_BIG_ENDIAN -> {
                    val scaled = (inputBuffer.int.toDouble() * currentGain).roundToLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    outputBuffer.putInt(scaled.toInt())
                }

                C.ENCODING_PCM_FLOAT -> outputBuffer.putFloat(
                    (inputBuffer.float * currentGain).coerceIn(-1f, 1f),
                )

                C.ENCODING_PCM_DOUBLE -> outputBuffer.putDouble(
                    (inputBuffer.double * currentGain).coerceIn(-1.0, 1.0),
                )

                else -> error("Unsupported ReplayGain PCM encoding: ${inputAudioFormat.encoding}")
            }
        }
        outputBuffer.flip()
    }

    /**
     * Copies [remaining] bytes from [source] at [position] into a buffer this processor owns.
     *
     * Reused across calls and grown only when a larger buffer arrives, so the aliased path settles
     * on one allocation rather than one per buffer on the audio thread.
     */
    private fun copyForReading(source: ByteBuffer, position: Int, remaining: Int): ByteBuffer {
        val scratch = readScratch.let {
            if (it != null && it.capacity() >= remaining) it
            else ByteBuffer.allocate(remaining).also { fresh -> readScratch = fresh }
        }
        scratch.clear()
        val window = source.duplicate()
        window.position(position)
        window.limit(position + remaining)
        scratch.put(window)
        scratch.flip()
        return scratch
    }

    override fun onReset() {
        readScratch = null
        reportedAliasing = false
        synchronized(stateLock) {
            tags = ReplayGainUtil.ReplayGainInfo(null, null, null, null)
            updateGainLocked()
        }
    }

    private fun updateGainLocked() {
        gain = ReplayGainUtil.calculateGain(
            tags = tags,
            mode = mode,
            rgGain = preampDb,
            reduceGain = true,
            ratio = ReplayGainUtil.RATIO,
        )?.first ?: ReplayGainUtil.dbToAmpl(untaggedGainDb.toFloat())
    }

    private companion object {
        const val TAG = "ReplayGain"

        const val MIN_PCM_24 = -8_388_608
        const val MAX_PCM_24 = 8_388_607

        val SUPPORTED_ENCODINGS = setOf(
            C.ENCODING_PCM_8BIT,
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_24BIT_BIG_ENDIAN,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_32BIT_BIG_ENDIAN,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_DOUBLE,
        )
    }
}
