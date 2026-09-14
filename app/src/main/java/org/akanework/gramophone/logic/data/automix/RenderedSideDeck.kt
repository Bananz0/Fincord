package org.akanework.gramophone.logic.data.automix

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A [SideDeck] holding PCM that was rendered before the transition started.
 *
 * Everything expensive - decoding, tempo stretching, resampling, the fade - happens in [render] on
 * a background thread, and what reaches the audio thread is a byte array and a cursor. That is the
 * point: the audio thread cannot wait for a decoder, and a transition that misses its buffer is
 * worse than one that never started.
 *
 * A few bars of audio is a few hundred kilobytes, so holding it outright is cheaper than the
 * machinery a streaming version would need.
 */
@OptIn(UnstableApi::class)
class RenderedSideDeck private constructor(
    private val pcm: ByteArray,
    private val fade: FadeCurve,
) : SideDeck {

    private var cursor = 0

    override val isFinished: Boolean
        get() = cursor >= pcm.size

    override val hostGain: Float
        get() = if (pcm.isEmpty()) 0f else fade.gainAt(cursor.toFloat() / pcm.size)

    override fun read(destination: ByteBuffer, byteCount: Int): Int {
        val available = minOf(byteCount, pcm.size - cursor)
        if (available <= 0) return 0
        destination.put(pcm, cursor, available)
        cursor += available
        return available
    }

    override fun skip(byteCount: Int) {
        if (byteCount <= 0) return
        // Whole frames only. Landing mid-frame would put the channels out of step with each other
        // for the whole transition, which is a far worse artefact than the lateness being corrected.
        cursor = (cursor + byteCount).coerceAtMost(pcm.size)
    }

    override fun release() = Unit

    companion object {

        private const val TAG = "SideDeck"

        /**
         * Decodes the tail of [uri] and prepares it to be mixed under a track running at a
         * different tempo.
         *
         * [speed] is the incoming tempo divided by the outgoing one: a 128 BPM track leaving under
         * a 140 BPM arrival plays at 140/128, so it comes out at the tempo of the track it is being
         * mixed under. Sonic changes duration without changing
         * pitch, which is what makes this a beat match rather than a tape edit - the alternative,
         * resampling to the new tempo, moves a 128 BPM track up nearly two semitones and is
         * instantly audible on anything with a vocal.
         *
         * The stretch is applied to the *outgoing* track deliberately. It is the one fading out, so
         * whatever Sonic does to transients is masked by its own fade, while the incoming track -
         * the one the listener is arriving at and will hear in full - is never touched.
         *
         * Returns null when the track cannot be decoded or the format cannot be matched, which is a
         * reason to fall back to a plain transition rather than an error.
         */
        fun render(
            context: Context,
            uri: Uri,
            fromMs: Long,
            durationMs: Long,
            speed: Float,
            format: MixingAudioSink.DeckFormat,
            style: MixStyle,
        ): RenderedSideDeck? {
            val decoded = PcmDecoder.decode(context, uri, fromMs, durationMs) ?: return null
            return fromPcm(decoded, 0L, durationMs, speed, format, style)
        }

        /**
         * Renders from PCM that has already been decoded, taking [durationMs] from [offsetMs].
         *
         * The transition decodes the outgoing track's tail once and uses it twice - the tail
         * analysis measures the tempo where the handover actually is, and the deck is the audio
         * from the handover onwards. Decoding it a second time would double the most expensive part
         * of preparing a transition to re-read audio already sitting in memory.
         */
        fun fromPcm(
            pcm: PcmDecoder.Pcm,
            offsetMs: Long,
            durationMs: Long,
            speed: Float,
            format: MixingAudioSink.DeckFormat,
            style: MixStyle,
        ): RenderedSideDeck? {
            val decoded = slice(pcm, offsetMs, durationMs) ?: return null
            val matched = matchFormat(decoded, speed, format) ?: return null
            /*
             * Filter before fading. The high-pass has memory - two samples of it - so running it
             * over audio that has already been faded to nothing leaves the filter state chasing a
             * signal that is no longer there, and the sweep ends up shaped by the fade rather than
             * by the music. Level is the last thing applied, as it is on a mixer.
             */
            BassSwap(format.sampleRate, style.bassCutHz).applyTo(matched, format.channelCount)
            applyFadeIn(matched, format.channelCount, style.fade)
            return RenderedSideDeck(encode(matched, format), style.fade)
        }

        /** [durationMs] of [pcm] starting [offsetMs] in, or null if that range holds no audio. */
        private fun slice(pcm: PcmDecoder.Pcm, offsetMs: Long, durationMs: Long): PcmDecoder.Pcm? {
            if (offsetMs <= 0L && durationMs * pcm.sampleRate / 1000L >= pcm.frameCount) return pcm
            val startFrame = (offsetMs * pcm.sampleRate / 1000L).toInt().coerceIn(0, pcm.frameCount)
            val wanted = (durationMs * pcm.sampleRate / 1000L).toInt()
            val frames = minOf(wanted, pcm.frameCount - startFrame)
            if (frames <= 0) return null
            val from = startFrame * pcm.channelCount
            return PcmDecoder.Pcm(
                samples = pcm.samples.copyOfRange(from, from + frames * pcm.channelCount),
                sampleRate = pcm.sampleRate,
                channelCount = pcm.channelCount,
                // The slice begins where it begins, in the track's own timeline: the frame this
                // started from, not the frame the decode did.
                startMs = pcm.startMs + startFrame * 1000L / pcm.sampleRate,
            )
        }

        /**
         * Brings decoded audio to the sink's sample rate, channel count and tempo.
         *
         * Sonic does the rate change as well as the stretch. Running one resampler rather than a
         * separate one for each is not only cheaper: two independent rate changes in series each
         * round the frame count, and over a few bars those roundings are what pull a beat grid out
         * of alignment.
         */
        private fun matchFormat(
            decoded: PcmDecoder.Pcm,
            speed: Float,
            target: MixingAudioSink.DeckFormat,
        ): FloatArray? {
            var samples = decoded.samples
            var channels = decoded.channelCount

            if (channels != target.channelCount) {
                samples = mixChannels(samples, channels, target.channelCount) ?: return null
                channels = target.channelCount
            }

            if (decoded.sampleRate == target.sampleRate && speed == 1f) return samples

            val sonic = SonicAudioProcessor()
            sonic.setSpeed(speed)
            sonic.setOutputSampleRateHz(target.sampleRate)
            /*
             * Sonic is driven in 16-bit even when the output is float. Its float path exists, but
             * `configure` rejects the format on some builds and the failure is an exception in the
             * middle of a transition rather than something visible in a test. The quantisation is
             * into a tail that is already fading out and is nowhere near bit-perfect territory -
             * Automix has stood down from that by this point regardless.
             */
            val input = AudioProcessor.AudioFormat(
                decoded.sampleRate, channels, C.ENCODING_PCM_16BIT
            )
            return runCatching {
                sonic.configure(input)
                /*
                 * The StreamMetadata overload, not the no-argument one. media3 1.11 made the bare
                 * `flush()` throw `IllegalStateException: AudioProcessor must implement at least
                 * one #flush() overload` unless a processor overrides it, and `SonicAudioProcessor`
                 * now implements only this one. It failed at exactly the moment a transition was
                 * otherwise ready to render, and the deprecation warning at build time was the only
                 * hint before that.
                 */
                sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
                drain(sonic, samples)
            }.onFailure { Log.d(TAG, "Could not stretch to $speed: $it") }.getOrNull()
        }

        /** Pushes every sample through [processor] and collects what comes out. */
        private fun drain(processor: SonicAudioProcessor, samples: FloatArray): FloatArray {
            val input = ByteBuffer
                .allocateDirect(samples.size * 2)
                .order(ByteOrder.nativeOrder())
            val shorts = input.asShortBuffer()
            for (sample in samples) {
                shorts.put((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
            }
            input.limit(samples.size * 2)

            var out = FloatArray(samples.size)
            var written = 0

            fun collect() {
                while (true) {
                    val output = processor.output
                    if (!output.hasRemaining()) break
                    val produced = output.remaining() / 2
                    if (written + produced > out.size) {
                        out = out.copyOf(maxOf(written + produced, out.size * 2))
                    }
                    val view = output.asShortBuffer()
                    while (view.hasRemaining()) out[written++] = view.get() / 32768f
                    output.position(output.limit())
                }
            }

            processor.queueInput(input)
            collect()
            processor.queueEndOfStream()
            while (!processor.isEnded) {
                val before = written
                collect()
                // A processor that reports neither output nor completion would spin here forever;
                // treating that as done loses a few milliseconds of tail instead of the audio
                // thread's next buffer.
                if (written == before) break
            }
            processor.reset()
            return out.copyOf(written)
        }

        /**
         * Folds or spreads channels to reach [targetChannels].
         *
         * Only where media3 has a matrix for it. An unusual layout is a reason to decline the
         * transition, not to invent a downmix: guessing which of six channels carries the music is
         * how a centre-channel vocal ends up missing from the mix.
         */
        private fun mixChannels(
            samples: FloatArray,
            from: Int,
            to: Int,
        ): FloatArray? {
            val matrix = runCatching {
                ChannelMixingMatrix.createForConstantGain(from, to)
            }.getOrNull() ?: return null

            val processor = ChannelMixingAudioProcessor()
            processor.putChannelMixingMatrix(matrix)
            val frames = samples.size / from
            val out = FloatArray(frames * to)
            for (frame in 0 until frames) {
                for (outChannel in 0 until to) {
                    var sum = 0f
                    for (inChannel in 0 until from) {
                        sum += samples[frame * from + inChannel] *
                            matrix.getMixingCoefficient(inChannel, outChannel)
                    }
                    out[frame * to + outChannel] = sum
                }
            }
            return out
        }

        /**
         * Applies the deck's own envelope in place, across frames rather than samples.
         *
         * Rising, because the deck carries the track being arrived at. The falling half of the
         * crossfade is applied to the renderer's stream by [MixingAudioSink], using [SideDeck.hostGain].
         */
        private fun applyFadeIn(samples: FloatArray, channels: Int, fade: FadeCurve) {
            if (channels <= 0) return
            val frames = samples.size / channels
            if (frames <= 0) return
            for (frame in 0 until frames) {
                val gain = fade.incomingGainAt(frame.toFloat() / frames)
                val base = frame * channels
                for (channel in 0 until channels) samples[base + channel] *= gain
            }
        }

        /** Writes float samples out in whatever the sink is expecting. */
        private fun encode(samples: FloatArray, format: MixingAudioSink.DeckFormat): ByteArray {
            val bytesPerSample = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val out = ByteBuffer
                .allocate(samples.size * bytesPerSample)
                .order(ByteOrder.nativeOrder())
            if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) {
                val view = out.asFloatBuffer()
                for (sample in samples) view.put(sample.coerceIn(-1f, 1f))
            } else {
                val view = out.asShortBuffer()
                for (sample in samples) {
                    view.put((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
            }
            return out.array()
        }
    }
}
