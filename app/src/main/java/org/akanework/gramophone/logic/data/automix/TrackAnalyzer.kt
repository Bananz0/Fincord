package org.akanework.gramophone.logic.data.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.dao.AnalysedTrackDao
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import org.akanework.gramophone.logic.data.jellyfin.JellyfinMediaCache
import uk.akane.accord.automix.NativeAnalyzer
import uk.akane.accord.automix.TrackAnalysis
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Works out a track's tempo, beat grid and key by decoding the front of it.
 *
 * Reads through [JellyfinMediaCache], the same cache playback reads from, so the stretch
 * `QueuePrefetcher` has already warmed costs nothing to analyse and anything beyond it is written
 * back for the playback that is about to want it anyway. Nothing here is a separate download.
 *
 * Everything it does is blocking and none of it is quick, so call it from a background thread.
 */
@OptIn(UnstableApi::class)
object TrackAnalyzer {

    private const val TAG = "TrackAnalyzer"

    /**
     * How much of a track to analyse.
     *
     * Long enough that the tempo comes from the body of the track rather than from an intro that
     * might be a pad, a spoken word or a drum machine counting in; short enough to stay well inside
     * the lead time a transition gives us, and to be a couple of minutes of one track rather than a
     * download of it. Anything past the second chorus tells the beat tracker nothing it does not
     * already know.
     */
    private const val ANALYSIS_SECONDS = 120

    /**
     * Below this the result is not worth storing.
     *
     * A tempo drawn from ten seconds is an average over perhaps twenty beats, most of an intro, and
     * writing it caches a bad answer under the same key a good one would use. Better to have no
     * analysis - the transition falls back to a plain change - than a confident wrong one.
     */
    private const val MINIMUM_USEFUL_SECONDS = 20f

    /**
     * The fewest beats a stored grid may have.
     *
     * Two bars. A track that decoded to silence - or to something the tempo tracker found no pulse
     * in at all - comes back with an empty grid and a tempo the tracker fell back on, and on device
     * that appeared as a stored row reading 40.69 BPM, no beats and a key strength of zero. Nothing
     * downstream can use that, and stored under the same key a real analysis would use, it is worse
     * than nothing: it looks like an answer and it suppresses the retry.
     */
    const val MINIMUM_USEFUL_BEATS = 8

    /**
     * The tempo range a stored analysis may claim.
     *
     * Wider than any tempo anyone counts, because this is a sanity bound and not a musical opinion
     * - it is here to reject a tracker that found nothing and reported a fallback, not to second
     * guess a genuine reading near the edges.
     */
    val USEFUL_BPM = 50f..220f

    /** How long to wait on the codec at each step. Generous; a cache miss goes to the network. */
    private const val CODEC_TIMEOUT_US = 10_000L

    /**
     * The loudest a decode may peak before it is treated as not being audio at all.
     *
     * Generous on purpose. Full scale is 1.0 and a legitimate decode can exceed it - intersample
     * peaks and a hot master both do, and the same file measured through ffmpeg on the server came
     * back at 1.41 - so this is not a level check and must not become one. It is here to catch a
     * buffer that is not PCM: a decoder that reported one encoding and wrote another produces
     * arbitrary bit patterns read as floats, which land in the 1e38 range and at infinity, and this
     * phone's does exactly that. Eight is far above any music and far below any garbage.
     */
    private const val MAX_PLAUSIBLE_PEAK = 8f

    /**
     * One analysis at a time, whoever asks.
     *
     * Two callers want this: [AutomixAnalysisScheduler] on the playback heartbeat, and
     * [AutomixTransitions] when it prepares a transition and finds no stored row. They are aimed at
     * the same track by design, so without a lock the common case is two threads decoding the same
     * two minutes of audio at once - twice the network, twice the CPU, and one of the two results
     * thrown away at the upsert.
     *
     * Deliberately one lock rather than one per track. Analyses of *different* tracks are no more
     * welcome concurrently: this is a phone, the decode is the expensive part, and running two of
     * them at once finishes neither sooner.
     */
    private val analysisLock = Any()

    /**
     * Returns the analysis for [item], running it if it is not already stored.
     *
     * `null` when the track could not be analysed - no audio track, an unsupported codec, the
     * native library missing on this ABI, or too little audio to be worth trusting. Every one of
     * those is a reason for Automix to leave this transition alone, not a reason to fail.
     *
     * Blocks while another caller is analysing anything at all. That wait is the point; see
     * [analysisLock].
     */
    fun analyse(context: Context, item: MediaItem): AnalysedTrack? {
        val mediaId = item.mediaId.takeIf { it.isNotEmpty() } ?: return null
        val dao = AppDatabase.getInstance(context).analysedTrackDao()
        // Read before taking the lock: the answer is usually already stored, and a stored answer
        // should never wait behind somebody else's decode.
        dao.get(mediaId, AnalysedTrack.ANALYSER_VERSION)?.let { return it }
        return synchronized(analysisLock) { analyseUnstored(context, item, mediaId, dao) }
    }

    private fun analyseUnstored(
        context: Context,
        item: MediaItem,
        mediaId: String,
        dao: AnalysedTrackDao,
    ): AnalysedTrack? {
        // Asked again inside the lock: whoever we queued behind may well have been analysing this
        // very track, which is the case the lock exists for.
        dao.get(mediaId, AnalysedTrack.ANALYSER_VERSION)?.let {
            Log.d(TAG, "Used $mediaId's analysis from the caller we waited for")
            return it
        }

        val uri = item.localConfiguration?.uri ?: return null
        val started = SystemClock.elapsedRealtime()
        val analysis = runCatching { decodeAndAnalyse(context, uri) }
            .onFailure { Log.d(TAG, "Analysis of $mediaId failed: $it") }
            .getOrNull()
            ?: return null

        if (analysis.analysedSeconds < MINIMUM_USEFUL_SECONDS) {
            Log.d(TAG, "Discarding $mediaId: only ${analysis.analysedSeconds}s decoded")
            return null
        }

        // The confidence is checked here as well as the tempo, and as a range rather than as its
        // negation, so that a non-finite figure is discarded instead of stored. A row is permanent
        // and is trusted by everything downstream; a NaN confidence written into one defeats the
        // gate that exists to catch a tracker that never settled, because every comparison against
        // NaN is false. aubio has been seen returning one on real audio.
        if (
            analysis.beatsMs.size < MINIMUM_USEFUL_BEATS ||
            analysis.bpm !in USEFUL_BPM ||
            !(analysis.tempoConfidence >= 0f)
        ) {
            Log.d(
                TAG,
                "Discarding $mediaId: ${analysis.beatsMs.size} beats at ${analysis.bpm} BPM, " +
                    "confidence ${analysis.tempoConfidence}, is not a usable grid",
            )
            return null
        }

        val row = AnalysedTrack(
            jellyfinId = mediaId,
            bpm = analysis.bpm,
            tempoConfidence = analysis.tempoConfidence,
            beatsMs = AnalysedTrack.packBeats(analysis.beatsMs),
            beatsPerBar = analysis.beatsPerBar,
            downbeatIndex = analysis.downbeatIndex,
            downbeatConfidence = analysis.downbeatConfidence,
            keyPitchClass = analysis.keyPitchClass,
            keyIsMajor = analysis.keyIsMajor,
            keyStrength = analysis.keyStrength,
            analysedSeconds = analysis.analysedSeconds,
            analysedAt = System.currentTimeMillis(),
            analyserVersion = AnalysedTrack.ANALYSER_VERSION,
        )
        dao.upsert(row)
        Log.d(
            TAG,
            "Analysed ${item.mediaMetadata.title} in ${SystemClock.elapsedRealtime() - started}ms: " +
                analysis,
        )
        return row
    }

    /**
     * Decodes the first [ANALYSIS_SECONDS] of [uri] to mono float PCM and runs the analyser over it.
     *
     * Uses media3's [MediaExtractorCompat] rather than the platform `MediaExtractor` for one
     * reason: it takes a `DataSource.Factory`, which is how the audio can come from the cache the
     * player already filled. The platform class only knows about URIs it can open itself, which
     * here would mean a second, parallel download of a track this device has already partly got.
     */
    private fun decodeAndAnalyse(context: Context, uri: Uri): TrackAnalysis? {
        if (!NativeAnalyzer.isAvailable) return null

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
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                /*
                 * Float output is deliberately **not** requested, and this is the second attempt
                 * at that decision.
                 *
                 * It used to be, on the argument that it saves a conversion and keeps all four of
                 * a 24-bit source's low bits. Measured on device on 2026-09-02, that argument buys
                 * nothing and costs everything: this phone's decoder accepts
                 * `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT`, *reports* encoding 4 in its output
                 * format, and does not deliver float. Every decode that took that path came back
                 * with `peak Infinity` - three for three - while the one recorded healthy decode
                 * in this tree's notes reports `encoding 2, peak 1.0000`.
                 *
                 * The same file decoded through ffmpeg on the server, and fed to this same
                 * analyser source, gives 124.84 BPM at confidence 0.259 with a key strength of
                 * 0.80 and not one non-finite sample in 5,292,000. Through this path it gave
                 * 122.36 BPM at confidence 0.00, key strength 0.00. The file is fine.
                 *
                 * And the trade was never worth making. This is a beat tracker and a chromagram:
                 * sixteen bits is some ninety decibels of range for a job that is looking for
                 * where the drums are. Not asking removes an entire class of device-specific
                 * decoder bugs from the analysis path in exchange for nothing measurable.
                 */
                codec.configure(format, null, null, 0)
                codec.start()
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                /*
                 * The peak is carried back out so the analysis can be thrown away when the decode
                 * was not audio.
                 *
                 * Not requesting float removes the one decoder lie actually observed, but it does
                 * not make this path trustworthy in general - the next device will lie differently,
                 * and the failure is silent by construction. A poisoned buffer produces an analysis
                 * that looks like a legitimately unreadable track: empty grid, zero key strength,
                 * no exception anywhere. Stored under the same key a good answer would use, it then
                 * suppresses the retry forever. The peak is the one number that tells the two
                 * apart, and it costs an absolute value per frame to keep.
                 */
                var peak = 0f
                val analysis = NativeAnalyzer.analyse(sampleRate) { analyzer ->
                    peak = pump(extractor, codec, analyzer, sampleRate, channels)
                }
                if (!(peak <= MAX_PLAUSIBLE_PEAK)) {
                    // Written as a range test, so a NaN or infinite peak fails rather than passing
                    // every comparison - the same trap the confidence gates carry a note about.
                    Log.d(TAG, "Discarding: the decode peaked at $peak, which is not audio")
                    return null
                }
                return analysis
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /**
     * Drives the decoder until [ANALYSIS_SECONDS] have been handed to [analyzer] or the input ends.
     *
     * The usual MediaCodec loop, with the one wrinkle that the codec is free to ignore the float
     * output we asked for, so the output format decides how each buffer is read rather than the
     * request that was made.
     */
    private fun pump(
        extractor: MediaExtractorCompat,
        codec: MediaCodec,
        analyzer: NativeAnalyzer,
        sampleRate: Int,
        inputChannels: Int,
    ): Float {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var channels = inputChannels
        var encoding = ENCODING_PCM_16BIT
        var framesFed = 0L
        val frameBudget = ANALYSIS_SECONDS.toLong() * sampleRate
        var mono = FloatArray(0)
        /*
         * The loudest sample seen. One track decoded a full two minutes that the tempo tracker
         * found no pulse in at all and whose chromagram was empty, which is what silence reaching
         * the analyser looks like from the far end - but "the decode produced silence" and "the
         * decode produced audio the tracker could not read" are indistinguishable from the result
         * alone, and the track could not be made to analyse again on demand. This separates them
         * the next time it happens.
         */
        var peak = 0f

        while (framesFed < frameBudget) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(
                            index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
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
                        val buffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (mono.size < frames) mono = FloatArray(frames)
                        downmix(buffer, encoding, channels, mono, frames)
                        for (frame in 0 until frames) {
                            val magnitude = abs(mono[frame])
                            if (magnitude > peak) peak = magnitude
                        }
                        analyzer.feed(mono, frames)
                        framesFed += frames
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        reportLevel(sampleRate, channels, encoding, peak, framesFed)
                        return peak
                    }
                }

                // Nothing ready and nothing left to feed it: the decoder has stalled on a read that
                // is not coming back, and waiting longer will not change that.
                index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone -> {
                    reportLevel(sampleRate, channels, encoding, peak, framesFed)
                    return peak
                }
            }
        }
        reportLevel(sampleRate, channels, encoding, peak, framesFed)
        return peak
    }

    /**
     * Says what the decoder actually produced, when anyone is listening.
     *
     * Silence is the interesting case and it is invisible in the analysis result, which reports the
     * same empty grid whether the audio was quiet or merely untrackable. Verbose only, since this
     * is one line per analysed track and useful only when something has gone wrong:
     *
     *     adb shell setprop log.tag.TrackAnalyzer VERBOSE
     */
    private fun reportLevel(
        sampleRate: Int,
        channels: Int,
        encoding: Int,
        peak: Float,
        framesFed: Long,
    ) {
        if (!Log.isLoggable(TAG, Log.VERBOSE)) return
        Log.v(
            TAG,
            "Decoded ${framesFed}f at ${sampleRate}Hz x$channels encoding $encoding, " +
                "peak %.4f%s".format(peak, if (peak < 1e-4f) " - SILENT" else ""),
        )
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

    /**
     * Averages the first [frames] frames of [buffer]'s channels into [out].
     *
     * Averaged rather than left-channel-only: a mix that pans the kick or the bass off centre would
     * otherwise lose most of what the beat tracker is looking for, and material that cancels when
     * summed is far rarer than an off-centre drum.
     */
    private fun downmix(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
        out: FloatArray,
        frames: Int,
    ) {
        buffer.order(ByteOrder.nativeOrder())

        when (encoding) {
            ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        // Float is the one encoding that can carry infinities and NaN, and a track
                        // on this device does: it decoded with a peak of Infinity, which poisoned
                        // every sum it entered and left the tracker with no pulse and an empty
                        // chromagram. Dropped to silence here, before it can propagate.
                        val sample = floats.get()
                        if (sample.isFinite()) sum += sample
                    }
                    out[frame] = sum / channels
                }
            }

            ENCODING_PCM_32BIT -> {
                val ints = buffer.asIntBuffer()
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += ints.get() / INT_FULL_SCALE
                    out[frame] = sum / channels
                }
            }

            ENCODING_PCM_8BIT -> {
                // Unsigned, centred on 128 - the one platform encoding that is not two's complement.
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        sum += ((buffer.get().toInt() and 0xFF) - 128) / 128f
                    }
                    out[frame] = sum / channels
                }
            }

            ENCODING_PCM_24BIT_PACKED -> {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) {
                        val low = buffer.get().toInt() and 0xFF
                        val mid = buffer.get().toInt() and 0xFF
                        val high = buffer.get().toInt()
                        sum += ((high shl 16) or (mid shl 8) or low) / INT24_FULL_SCALE
                    }
                    out[frame] = sum / channels
                }
            }

            else -> {
                val shorts = buffer.asShortBuffer()
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channels) sum += shorts.get() / SHORT_FULL_SCALE
                    out[frame] = sum / channels
                }
            }
        }
    }

    // AudioFormat's constants, spelled out rather than imported, because MediaFormat's
    // KEY_PCM_ENCODING carries AudioFormat values and reading this loop should not require knowing
    // that. ENCODING_PCM_24BIT_PACKED and ENCODING_PCM_32BIT are API 31, which is this app's floor.
    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_FLOAT = 4
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22

    private const val SHORT_FULL_SCALE = 32768f
    private const val INT24_FULL_SCALE = 8388608f
    private const val INT_FULL_SCALE = 2147483648f
}
