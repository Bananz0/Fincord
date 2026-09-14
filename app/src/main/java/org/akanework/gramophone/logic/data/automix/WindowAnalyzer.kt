package org.akanework.gramophone.logic.data.automix

import android.util.Log
import org.akanework.gramophone.logic.data.db.entity.AnalysedTrack
import uk.akane.accord.automix.NativeAnalyzer

/**
 * Analyses one decoded span of a track as though it were a track of its own.
 *
 * [TrackAnalyzer] measures a fixed two minutes from the start and stores the answer. That is the
 * right thing to keep in a database - it is the track's tempo, in general - and it is the wrong
 * thing to mix on, at *both* ends of a transition:
 *
 * - The track being mixed **out of** hands over near its end, minutes past the stored window. The
 *   grid is a fitted constant tempo, so carrying it out there means extrapolating, and
 *   extrapolation inherits the tempo estimate's error multiplied by the distance carried. At 128
 *   BPM, 0.04 BPM out - the best accuracy the test matrix achieves - is about 75 ms of drift across
 *   four minutes, a fifth of a beat, and audible as a mix that is not tight.
 * - The track being mixed **into** is crossed over its first fifteen seconds or so. The stored
 *   window is eight times that, so a track whose intro runs at a different pace hands the mixer a
 *   tempo drawn mostly from audio the listener does not reach until after the transition is over.
 *
 * Both are the same mistake one window apart, and both have the same answer: measure where the mix
 * is. The test track that made this concrete reported 100.25 BPM over its first 120 s and 106.0 BPM
 * over its last 90 s, at high confidence both times, on a track only 2:12 long - so the windows
 * overlap across most of the same audio and still disagree by 5.7%, which is the whole stretch
 * budget. The difference lives entirely in the parts they do not share.
 *
 * Nothing here is written to the database. These analyses describe a window rather than a track,
 * and storing one under the track's key would replace the general answer with a local one.
 */
object WindowAnalyzer {

    private const val TAG = "WindowAnalyzer"

    /**
     * How much of the end to measure for the track being mixed out of.
     *
     * Long enough for the beat tracker to settle and for the fit to have a span worth regressing
     * over - the tempo's precision comes from the length of that span - and short enough that
     * decoding it stays inside the transition's preparation lead.
     */
    const val TAIL_SECONDS = 90

    /**
     * How much of the beginning to measure for the track being mixed into.
     *
     * Shorter than [TAIL_SECONDS], and deliberately so. The two windows are not symmetric: the
     * outgoing track's mix sits at the *end* of its window with the rest of the span there to
     * anchor the fit, while the incoming track's mix sits at the very *start* of its window, so
     * every second beyond the blend is audio the mixer is being asked about but the listener will
     * not hear until it is over. Forty-five seconds is the compromise - three times the longest
     * blend, which is enough span for the fit to mean something, and a third of what the stored
     * analysis averages over.
     *
     * It cannot go much below this. A tempo fitted over the blend alone would be drawn from perhaps
     * thirty beats, and the fit's precision is what places the downbeat the incoming track is cued
     * to.
     */
    const val HEAD_SECONDS = 45

    /**
     * Analyses [pcm], whose first sample is at [startMs] in the track, and returns it as a row
     * whose beat times are absolute track positions.
     *
     * The offset is the whole point of doing this here rather than in the caller. The analyser
     * knows nothing about where its input came from and reports beats from zero, so a tail analysis
     * used without shifting would put every beat somewhere in the track's first ninety seconds -
     * which would look like a working analysis and place every transition catastrophically wrong.
     */
    fun analyse(pcm: PcmDecoder.Pcm, startMs: Long, jellyfinId: String): AnalysedTrack? {
        if (!NativeAnalyzer.isAvailable || pcm.channelCount <= 0) return null

        val frames = pcm.frameCount
        if (frames <= 0) return null
        val mono = FloatArray(frames)
        val channels = pcm.channelCount
        for (frame in 0 until frames) {
            var sum = 0f
            val base = frame * channels
            for (channel in 0 until channels) sum += pcm.samples[base + channel]
            mono[frame] = sum / channels
        }

        val analysis = runCatching {
            NativeAnalyzer.analyse(pcm.sampleRate) { it.feed(mono, frames) }
        }.onFailure { Log.d(TAG, "Window analysis failed: $it") }.getOrNull() ?: return null

        // Range tests rather than their negations, so a non-finite figure fails rather than
        // passing every comparison silently. A NaN tempo confidence has been seen coming out of
        // aubio on real audio, and a row carrying one defeats the very gate meant to catch it.
        if (
            analysis.beatsMs.size < TrackAnalyzer.MINIMUM_USEFUL_BEATS ||
            !(analysis.bpm > 0f) ||
            !(analysis.tempoConfidence >= 0f)
        ) {
            Log.d(
                TAG,
                "Window of $jellyfinId from ${startMs}ms gave ${analysis.beatsMs.size} beats " +
                    "at ${analysis.bpm} BPM, confidence ${analysis.tempoConfidence}",
            )
            return null
        }

        val absolute = IntArray(analysis.beatsMs.size) { index ->
            (analysis.beatsMs[index] + startMs).toInt()
        }

        Log.d(
            TAG,
            "Window of $jellyfinId from ${startMs}ms: ${analysis.bpm} BPM, " +
                "${absolute.size} beats, downbeat ${analysis.downbeatIndex}, " +
                "confidence ${analysis.tempoConfidence}",
        )

        return AnalysedTrack(
            jellyfinId = jellyfinId,
            bpm = analysis.bpm,
            tempoConfidence = analysis.tempoConfidence,
            beatsMs = AnalysedTrack.packBeats(absolute),
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
    }
}

/**
 * Which analysis of the incoming track the plan should use: [head] when it read the blend, [stored]
 * when it did not.
 *
 * Pure, and separated out, because the fallback is the part that can go quietly wrong. Measuring
 * the mix window is only an improvement while the measurement holds; a window that produced a grid
 * too short to fit, or a tempo outside anything anyone counts, is a tracker that found nothing
 * rather than a track that is genuinely slower at its start. Preferring that over a stored analysis
 * which *did* settle would replace a slightly wrong number with a meaningless one, and it would do
 * it silently, on exactly the material - quiet intros - that the head window exists to handle.
 *
 * Confidence is deliberately **not** part of this. A blend window the beat tracker was unsure of is
 * a real answer about the audio the mix crosses, and [TransitionPlan] is where it gets weighed
 * against every other reason to decline. Substituting the stored row's higher confidence here would
 * launder a number measured somewhere else into the decision.
 */
internal fun preferredIncomingAnalysis(
    stored: AnalysedTrack,
    head: AnalysedTrack?,
): AnalysedTrack {
    if (head == null) return stored
    if (head.beatTimesMs().size < TrackAnalyzer.MINIMUM_USEFUL_BEATS) return stored
    if (head.bpm !in TrackAnalyzer.USEFUL_BPM) return stored
    return head
}
