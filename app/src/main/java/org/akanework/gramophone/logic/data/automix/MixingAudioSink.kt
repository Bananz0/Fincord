package org.akanework.gramophone.logic.data.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The point where two streams become one.
 *
 * media3's [androidx.media3.common.audio.AudioProcessor] chain cannot do this. It lives *inside*
 * [androidx.media3.exoplayer.audio.DefaultAudioSink] and is driven by the single audio renderer, so
 * `queueInput` has no way to reach a second track's samples - there is only ever one stream in
 * front of it. One level up there is: `handleBuffer` is the renderer's PCM arriving with a
 * timestamp, and a [ForwardingAudioSink] sitting there can add something to it before passing it
 * down. That is the whole trick, and it is why Automix attaches here rather than as a processor.
 *
 * Inert until a deck is installed. With none, every call forwards untouched and this costs one null
 * check per buffer, which matters because this is on the audio thread for every second of playback
 * whether or not anyone ever turns Automix on.
 */
@OptIn(UnstableApi::class)
class MixingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    /**
     * The deck being mixed in, or null to pass through.
     *
     * Written by the transition controller and read by the audio thread, hence volatile. It is only
     * ever swapped whole - a deck is never mutated once installed - so a torn read is not possible
     * and no lock is needed on the hot path.
     */
    @Volatile
    private var deck: SideDeck? = null

    /**
     * The mixed copy of the buffer currently being handed down, or null when there is none in
     * flight.
     *
     * [androidx.media3.exoplayer.audio.AudioSink.handleBuffer] may take only part of what it is
     * offered and be re-presented with the same buffer, so the mixed version has to survive across
     * calls. Rebuilding it each time would pull fresh samples from the deck for audio that has
     * already been mixed once, and play the deck faster than the track it is under.
     */
    private var inFlight: ByteBuffer? = null

    /**
     * The deck actually being mixed, which lags [deck] until a safe moment.
     */
    private var active: SideDeck? = null

    /**
     * Whether the sink below still holds a buffer it has not finished with.
     *
     * The one piece of its state that has to be tracked here, because the mode switch is only safe
     * when it is false and there is no way to ask.
     */
    private var belowHoldsBuffer = false

    /** The sink's input format, which is what a deck has to produce. Null before configuration. */
    @Volatile
    private var pcmEncoding: Int = Format.NO_VALUE

    @Volatile
    private var sampleRate: Int = Format.NO_VALUE

    @Volatile
    private var channelCount: Int = Format.NO_VALUE

    /**
     * Whether a deck can be mixed into the current output at all.
     *
     * Only 16-bit and float PCM. The high-resolution integer formats are declined rather than
     * handled, and that is the same decision as the one in the Automix notes: mixing resamples,
     * stretches and sums, so it is incompatible with bit-perfect output by definition, and a route
     * negotiated for 24- or 32-bit is one where somebody has asked for exactly that. Declining
     * leaves them a plain transition instead of quietly rewriting their audio.
     */
    val canMix: Boolean
        get() = pcmEncoding == C.ENCODING_PCM_16BIT || pcmEncoding == C.ENCODING_PCM_FLOAT

    /** What the sink was last configured with, for logs that have to explain a refusal. */
    fun describeFormat(): String =
        "encoding=$pcmEncoding rate=$sampleRate channels=$channelCount"

    /** The format a deck must produce, or null if nothing is configured or the format cannot mix. */
    fun outputFormat(): DeckFormat? {
        if (!canMix || sampleRate == Format.NO_VALUE || channelCount == Format.NO_VALUE) return null
        return DeckFormat(pcmEncoding, sampleRate, channelCount)
    }

    /**
     * Starts mixing [next], replacing any deck already installed.
     *
     * The replaced deck is returned rather than released here, because this is called from the
     * controller and releasing may touch a decoder.
     */
    fun install(next: SideDeck?): SideDeck? {
        val previous = deck
        deck = next
        return previous
    }

    /**
     * media3 1.11 replaced the loose `(Format, Int, IntArray?)` signature with one config object
     * and made the old one final, deliberately, so that an override written against it fails to
     * compile rather than being silently bypassed. Same information, plus the timeline and media
     * period this configuration belongs to - which is the context a transition actually wants.
     */
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val inputFormat = audioSinkConfig.format
        pcmEncoding = inputFormat.pcmEncoding
        sampleRate = inputFormat.sampleRate
        channelCount = audioSinkConfig.outputChannelMapping?.length() ?: inputFormat.channelCount
        // A format change means the buffers in flight belong to a stream that no longer exists,
        // and nothing below is holding one any more either.
        inFlight = null
        belowHoldsBuffer = false
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        /*
         * Take up or drop a deck only when the sink below is not holding anything.
         *
         * [androidx.media3.exoplayer.audio.DefaultAudioSink] requires the *same ByteBuffer
         * instance* on every call until it has consumed it, and enforces that with an assertion.
         * Mixing swaps which object gets handed down - the renderer's own buffer while passing
         * through, a scratch copy while mixing - so changing modes with a partly-consumed buffer
         * outstanding hands it a different object and throws immediately.
         *
         * That is not theoretical: installing a deck mid-stream did exactly this on the first
         * transition that ever fired, and the whole player stopped with
         * `ERROR_CODE_FAILED_RUNTIME_CHECK` about fifty milliseconds after the handover. Deferring
         * the switch to a boundary costs at most one buffer of latency - a few milliseconds -
         * before a transition starts, and nothing else.
         */
        if (!belowHoldsBuffer) active = deck

        val current = active
        if (current == null || !canMix) {
            val passed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            belowHoldsBuffer = !passed
            return passed
        }

        var mixed = inFlight
        if (mixed == null) {
            mixed = mix(buffer, current)
            inFlight = mixed
        }

        val before = mixed.position()
        val accepted = super.handleBuffer(mixed, presentationTimeUs, encodedAccessUnitCount)
        val consumed = mixed.position() - before

        /*
         * Advance the renderer's buffer by exactly what the sink below took from the mixed copy.
         * The two run in lockstep by construction - the mixed copy is the same length as what was
         * offered - so a partial take leaves both with the same amount outstanding, and the next
         * call resumes rather than remixing.
         */
        buffer.position(buffer.position() + consumed)

        if (!mixed.hasRemaining()) inFlight = null
        belowHoldsBuffer = !accepted
        // Retiring the deck is itself a mode change, so it waits for a boundary like adopting one.
        if (current.isFinished && !belowHoldsBuffer) deck = null
        return accepted
    }

    /**
     * Copies [source] and adds the deck's samples to it, returning a buffer positioned to be read.
     *
     * Deliberately a copy. The buffer belongs to the renderer, which will present it again if the
     * sink below declines it, so summing into it would add the deck twice to any buffer that is not
     * accepted first time - audible as the transition briefly doubling in level under exactly the
     * conditions that cause it, which is a buffer running late.
     */
    private fun mix(source: ByteBuffer, active: SideDeck): ByteBuffer {
        val byteCount = source.remaining()
        val mixed = obtainScratch(byteCount)

        /*
         * Bulk copy through a duplicate, so the renderer's buffer keeps its own position for the
         * caller to advance afterwards. This was a byte-at-a-time loop, which is a per-sample cost
         * on the audio thread for no reason - and this runs inside the window where a missed buffer
         * is heard as a dropout.
         */
        mixed.clear()
        mixed.put(source.duplicate())
        mixed.flip()

        /*
         * Fade the renderer's stream out as the deck comes in. The renderer is still playing the
         * outgoing track and nothing else would take it down; without this the overlap is two
         * tracks at once rather than a crossfade, which is exactly what it sounded like on device.
         */
        val gain = active.hostGain
        val deckStart = deckScratch(byteCount)
        val deckBytes = active.read(deckStart, byteCount)
        deckStart.position(0).limit(maxOf(deckBytes, 0))

        when (pcmEncoding) {
            C.ENCODING_PCM_16BIT -> combineShorts(mixed, deckStart, gain, deckBytes / 2)
            C.ENCODING_PCM_FLOAT -> combineFloats(mixed, deckStart, gain, deckBytes / 4)
        }
        return mixed
    }

    /**
     * Scales the incoming stream by [gain] and adds the deck's first [deckSamples] samples to it.
     *
     * Both sides in one pass over the buffer. The incoming has to be scaled across its whole
     * length, including past where the deck's audio ran out, or the fade would stop halfway and
     * leave the level short for the rest of the buffer.
     */
    private fun combineShorts(into: ByteBuffer, from: ByteBuffer, gain: Float, deckSamples: Int) {
        val target = into.asShortBuffer()
        val other = from.asShortBuffer()
        val total = target.limit()
        for (i in 0 until total) {
            // Sum in int, then clamp. Adding two full-scale shorts overflows 16 bits, and letting
            // it wrap turns a loud moment into a burst of noise rather than into distortion.
            var sum = (target.get(i) * gain).toInt()
            if (i < deckSamples) sum += other.get(i)
            target.put(i, sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun combineFloats(into: ByteBuffer, from: ByteBuffer, gain: Float, deckSamples: Int) {
        val target = into.asFloatBuffer()
        val other = from.asFloatBuffer()
        val total = target.limit()
        for (i in 0 until total) {
            // Float PCM is nominally -1..1 and AudioTrack clips anything past it, so clamp here
            // where it is one comparison rather than leaving it to the hardware.
            var sum = target.get(i) * gain
            if (i < deckSamples) sum += other.get(i)
            target.put(i, sum.coerceIn(-1f, 1f))
        }
    }

    private var scratch: ByteBuffer? = null
    private var deckBuffer: ByteBuffer? = null

    private fun obtainScratch(byteCount: Int): ByteBuffer {
        var buffer = scratch
        if (buffer == null || buffer.capacity() < byteCount) {
            buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            scratch = buffer
        }
        return buffer.also { it.clear() }
    }

    private fun deckScratch(byteCount: Int): ByteBuffer {
        var buffer = deckBuffer
        if (buffer == null || buffer.capacity() < byteCount) {
            buffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
            deckBuffer = buffer
        }
        return buffer.also { it.clear() }
    }

    /**
     * Drops buffers in flight but keeps the deck.
     *
     * A flush means the renderer's stream is being discarded, which a transition *causes*: handing
     * over calls `seekToNextMediaItem`, and that flushes the sink a moment later. Clearing the deck
     * here would therefore silence every transition at the instant it began, and the bug would look
     * like the mixing never working rather than like a lifecycle mistake.
     *
     * A flush the user caused - a seek, a skip - has to cancel the transition, but the controller
     * does that by uninstalling the deck, because only it can tell the two apart.
     */
    override fun flush() {
        inFlight = null
        // A flush discards whatever the sink below was holding, so the next buffer starts clean.
        belowHoldsBuffer = false
        super.flush()
    }

    /** Unlike a flush, a reset is the sink going away, and nothing survives it. */
    override fun reset() {
        inFlight = null
        deck = null
        active = null
        belowHoldsBuffer = false
        super.reset()
    }

    /** The PCM a [SideDeck] has to produce for the output currently configured. */
    data class DeckFormat(val pcmEncoding: Int, val sampleRate: Int, val channelCount: Int) {
        val bytesPerFrame: Int
            get() = channelCount * if (pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
    }
}
