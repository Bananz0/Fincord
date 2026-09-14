package org.akanework.gramophone.logic.data.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a span of a track to interleaved float PCM.
 *
 * Reads through [JellyfinMediaCache] like everything else here, so a track the player has already
 * streamed costs nothing to decode again and anything missing is written back for whoever wants it
 * next. Uses media3's [MediaExtractorCompat] rather than the platform `MediaExtractor` for the same
 * reason [TrackAnalyzer] does: it accepts a `DataSource.Factory`, and the platform class only knows
 * about URIs it can open itself, which here would mean downloading a track this device already has.
 *
 * Float rather than the source's own encoding because everything downstream - time stretching,
 * gain, summing - wants float anyway, and doing the conversion once at the source is cheaper than
 * threading five integer formats through the rest of it.
 *
 * Blocking, and slow enough to matter. Never call it from the audio thread.
 */
@OptIn(UnstableApi::class)
object PcmDecoder {

    /** How long to wait on the codec at each step. Generous; a cache miss goes to the network. */
    private const val TIMEOUT_US = 10_000L

    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_FLOAT = 4
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    private const val SHORT_FULL_SCALE = 32768f
    private const val INT24_FULL_SCALE = 8388608f
    private const val INT_FULL_SCALE = 2147483648f

    /**
     * What came out, alongside the samples themselves.
     *
     * [startMs] is where the audio *actually* begins, which is rarely where it was asked to. Seeking
     * lands on the nearest sync sample at or before the request, so a decode usually starts a little
     * early. Anything aligning this against a beat grid has to use this rather than what it asked
     * for: tens of milliseconds is a fraction of a beat, and a transition placed with that error is
     * a transition that does not sit on the pulse.
     */
    data class Pcm(
        val samples: FloatArray,
        val sampleRate: Int,
        val channelCount: Int,
        val startMs: Long,
    ) {
        val frameCount: Int get() = if (channelCount == 0) 0 else samples.size / channelCount

        // Arrays compare by identity, so the generated equals would be wrong in the one way that
        // matters. Contents, like the entity does.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Pcm && sampleRate == other.sampleRate &&
                channelCount == other.channelCount && startMs == other.startMs &&
                samples.contentEquals(other.samples))

        override fun hashCode(): Int =
            ((samples.contentHashCode() * 31 + sampleRate) * 31 + channelCount) * 31 +
                startMs.hashCode()
    }

    /**
     * Decodes at most [durationMs] of [uri] starting near [startMs].
     *
     * "Near" is not a hedge: seeking lands on the nearest sync sample at or before the request, so
     * the result usually begins a little early. The caller is told where it actually began through
     * the returned frame count rather than being promised an exactness the container cannot give.
     *
     * Returns null when there is no audio track, the codec will not start, or nothing decoded.
     */
    fun decode(context: Context, uri: Uri, startMs: Long, durationMs: Long): Pcm? {
        val extractor = MediaExtractorCompat(
            DefaultExtractorsFactory(),
            JellyfinMediaCache.dataSourceFactory(context),
        )
        try {
            extractor.setDataSource(uri, 0L)
            val track = (0 until extractor.getTrackCount()).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null

            extractor.selectTrack(track)
            if (startMs > 0L) {
                extractor.seekTo(startMs * 1000L, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            }

            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                // Float output is deliberately not requested; see the long note in [TrackAnalyzer],
                // which found a decoder that reports `ENCODING_PCM_FLOAT` and does not deliver it.
                // Everything this decodes is analysed rather than played, so sixteen bits is more
                // range than the question needs and the float path is pure risk.
                codec.configure(format, null, null, 0)
                codec.start()
                /*
                 * Where the seek actually landed, which is at or before what was asked for. The
                 * caller aligns this against a beat grid, so the difference matters: a sync sample
                 * tens of milliseconds early is a fraction of a beat, and silently treating the
                 * request as the truth puts the whole transition that far off the pulse.
                 */
                val actualStartMs = (extractor.sampleTime / 1000L).coerceAtLeast(0L)
                return pump(extractor, codec, sampleRate, sourceChannels, durationMs, actualStartMs)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } catch (t: Throwable) {
            return null
        } finally {
            extractor.release()
        }
    }

    private fun pump(
        extractor: MediaExtractorCompat,
        codec: MediaCodec,
        sampleRate: Int,
        sourceChannels: Int,
        durationMs: Long,
        startMs: Long,
    ): Pcm? {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var channels = sourceChannels
        var encoding = ENCODING_PCM_16BIT
        val frameBudget = durationMs * sampleRate / 1000L

        // Grown rather than sized up front: the frame budget is what was asked for, and a track
        // that ends early should not leave a couple of megabytes of silence behind it.
        var out = FloatArray(minOf(frameBudget * sourceChannels, 1 shl 20).toInt().coerceAtLeast(1))
        var written = 0
        var framesOut = 0L

        while (framesOut < frameBudget) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = codec.outputFormat
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        ENCODING_PCM_16BIT
                    }
                }

                index >= 0 -> {
                    val frames = frameCount(info.size, encoding, channels)
                    if (frames > 0) {
                        val needed = written + frames * channels
                        if (needed > out.size) out = out.copyOf(maxOf(needed, out.size * 2))
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        written += read(buffer, encoding, out, written, frames * channels)
                        framesOut += frames
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }

                // Nothing ready and nothing left to feed it: the decoder has stalled on a read that
                // is not coming back, and waiting longer will not change that.
                index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> break
            }
        }

        if (written == 0 || channels <= 0) return null
        return Pcm(out.copyOf(written), sampleRate, channels, startMs)
    }

    /** Reads [count] interleaved samples out of [buffer] into [out] at [offset], as floats. */
    private fun read(
        buffer: ByteBuffer,
        encoding: Int,
        out: FloatArray,
        offset: Int,
        count: Int,
    ): Int {
        buffer.order(ByteOrder.nativeOrder())
        var written = 0
        when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                /*
                 * Float is the one encoding that can carry values PCM cannot: infinities and NaN.
                 * A track decoding to float came back with a peak of Infinity on device, which is
                 * not a rounding problem - it propagates. Every sum it enters becomes infinite, so
                 * the whole analysis collapses, and worse, clamping an infinity to the -1..1 the
                 * mixer works in produces full scale. A tail rendered from that is a square wave at
                 * maximum level going straight to somebody's headphones.
                 *
                 * Non-finite samples are therefore replaced with silence at the point they enter,
                 * before anything can propagate them. The integer encodings cannot express these
                 * values at all and need no such check.
                 */
                while (written < count && floats.hasRemaining()) {
                    val sample = floats.get()
                    out[offset + written++] = if (sample.isFinite()) sample else 0f
                }
            }

            ENCODING_PCM_32BIT -> {
                val ints = buffer.asIntBuffer()
                while (written < count && ints.hasRemaining()) {
                    out[offset + written++] = ints.get() / INT_FULL_SCALE
                }
            }

            ENCODING_PCM_8BIT -> {
                // Unsigned, centred on 128 - the one platform encoding that is not two's complement.
                while (written < count && buffer.hasRemaining()) {
                    out[offset + written++] = ((buffer.get().toInt() and 0xFF) - 128) / 128f
                }
            }

            ENCODING_PCM_24BIT_PACKED -> {
                while (written < count && buffer.remaining() >= 3) {
                    val low = buffer.get().toInt() and 0xFF
                    val mid = buffer.get().toInt() and 0xFF
                    val high = buffer.get().toInt()
                    out[offset + written++] = ((high shl 16) or (mid shl 8) or low) / INT24_FULL_SCALE
                }
            }

            else -> {
                val shorts = buffer.asShortBuffer()
                while (written < count && shorts.hasRemaining()) {
                    out[offset + written++] = shorts.get() / SHORT_FULL_SCALE
                }
            }
        }
        return written
    }

    private fun frameCount(byteCount: Int, encoding: Int, channels: Int): Int =
        if (channels <= 0) 0 else byteCount / bytesPerSample(encoding) / channels

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENCODING_PCM_FLOAT -> 4
        ENCODING_PCM_24BIT_PACKED -> 3
        ENCODING_PCM_32BIT -> 4
        ENCODING_PCM_8BIT -> 1
        else -> 2
    }
}
