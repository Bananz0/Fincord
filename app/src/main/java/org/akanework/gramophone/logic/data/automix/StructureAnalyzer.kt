package org.akanework.gramophone.logic.data.automix

import kotlin.math.sqrt

/**
 * Finds a track's structure, however it can.
 *
 * An interface because there are two ways to do this and they are not alternatives so much as
 * stages. [EnergyStructureAnalyzer] below is classical DSP that works today and answers the coarse
 * questions - is the energy rising, has it dropped and stayed down. A model answers the questions
 * it cannot: which bar of the phrase this is, where the eight-bar sections actually begin, whether
 * that quiet passage is a breakdown or the end.
 *
 * The seam exists now so that the second one is a substitution rather than a rewrite, and so that
 * whatever replaces this can be measured against something.
 */
interface StructureAnalyzer {

    /**
     * Describes [pcm], or null if it cannot.
     *
     * Null is a real answer and callers must treat it as one: no structure means the transition
     * falls back to the phrase arithmetic it uses today, which is worse but not wrong.
     */
    fun analyse(pcm: PcmDecoder.Pcm): TrackStructure?
}

/**
 * Structure from the loudness curve alone.
 *
 * Root-mean-square energy in two-second windows, smoothed, then read for the two features a
 * transition cares about: the last sharp rise, and the point after it where things stay quiet.
 * The approach - RMS windows, smoothing, first difference, sustained runs of increase as a buildup
 * and a sharp fall as a drop - is from kckDeepak's AI DJ Mixing System
 * (github.com/kckDeepak/AI-DJ-Mixing-System, MIT), implemented here from that description.
 *
 * It cannot tell a breakdown from an outro except by what follows, and on music without dynamics -
 * a wall-of-sound master, most older records - it will find nothing at all and say so. That is the
 * honest limit of loudness as a proxy for structure, and it is the reason the interface above
 * exists rather than this being the whole answer.
 */
class EnergyStructureAnalyzer : StructureAnalyzer {

    override fun analyse(pcm: PcmDecoder.Pcm): TrackStructure? {
        if (pcm.channelCount <= 0 || pcm.sampleRate <= 0) return null
        val framesPerWindow = (TrackStructure.ENERGY_WINDOW_MS * pcm.sampleRate / 1000L).toInt()
        if (framesPerWindow <= 0) return null
        val windows = pcm.frameCount / framesPerWindow
        // Fewer than this and there is no shape to read, only noise.
        if (windows < 8) return null

        val energy = FloatArray(windows)
        for (window in 0 until windows) {
            var sum = 0.0
            val base = window * framesPerWindow * pcm.channelCount
            val count = framesPerWindow * pcm.channelCount
            for (i in 0 until count) {
                val sample = pcm.samples[base + i]
                sum += sample.toDouble() * sample
            }
            energy[window] = sqrt(sum / count).toFloat()
        }

        val peak = energy.max()
        if (peak <= 0f) return null
        for (i in energy.indices) energy[i] /= peak

        // A three-window mean, which is enough to stop one loud bar reading as a section change
        // without blurring a real one - a drop is a step, and a step survives this.
        val smoothed = FloatArray(windows)
        for (i in 0 until windows) {
            var sum = 0f
            var count = 0
            for (j in maxOf(0, i - 1)..minOf(windows - 1, i + 1)) {
                sum += energy[j]
                count++
            }
            smoothed[i] = sum / count
        }

        val dropWindow = lastDropWindow(smoothed)
        return TrackStructure(
            energy = smoothed,
            lastDropMs = dropWindow?.let { pcm.startMs + it * TrackStructure.ENERGY_WINDOW_MS },
            outroStartMs = outroStart(smoothed, pcm.startMs, dropWindow),
        )
    }

    /**
     * The last window where energy rises by more than [RISE] over the one before it.
     *
     * Searched from the end backwards because the *last* drop is the one a transition has to get
     * past - mixing out before it cuts the track off at its climax.
     */
    private fun lastDropWindow(energy: FloatArray): Int? {
        for (i in energy.indices.reversed()) {
            if (i == 0) break
            if (energy[i] - energy[i - 1] > RISE) return i
        }
        return null
    }

    /**
     * The first window in the last third of the track after which energy never returns to [BUSY].
     *
     * "Never returns" is what separates an outro from a breakdown, and it is why this cannot be
     * decided while streaming forwards: a quiet passage is only an outro in hindsight.
     */
    private fun outroStart(energy: FloatArray, startMs: Long, lastDropWindow: Int?): Long? {
        /*
         * Never before the last drop. A quiet bar ahead of a final chorus looks exactly like an
         * outro to a loudness curve, and on device one was found eight seconds *before* the drop
         * that followed it - which would have mixed the track out immediately before its climax,
         * the one mistake this analysis exists to prevent. A track winds down once, and only after
         * the last time it lifts.
         */
        val earliest = maxOf(energy.size * 2 / 3, (lastDropWindow ?: 0) + 1)
        for (i in earliest until energy.size) {
            if (energy[i] >= BUSY) continue
            var staysQuiet = true
            for (j in i until energy.size) {
                if (energy[j] >= BUSY) {
                    staysQuiet = false
                    break
                }
            }
            if (staysQuiet) return startMs + i * TrackStructure.ENERGY_WINDOW_MS
        }
        return null
    }

    private companion object {
        /** Rise between adjacent windows that counts as a drop, against a peak-normalised curve. */
        const val RISE = 0.12f

        /** Above this a section is still going, rather than winding down. */
        const val BUSY = 0.65f
    }
}

/**
 * Structure from a neural model. Not implemented.
 *
 * This is where milestone 3 goes, and the case for it is specific rather than general: the numbers
 * that are weak are the ones classical DSP is worst at. Downbeat confidence measured 0.00 to 0.39
 * across real tracks against roughly 0.45 on synthetic signals, and aubio's tempo confidence is so
 * poorly calibrated that the gate built on it had to be loosened to 0.12 to let anything through at
 * all. A DBN-style beat tracker gives a properly calibrated confidence and a downbeat worth
 * trusting, and those two are what decide whether a transition should happen and where.
 *
 * Notes for whoever builds it:
 *
 * - **Benchmark plain CPU first.** The model is small and runs once per track inside a 20 s
 *   preparation lead, so the accelerator saves battery rather than time, and it may well save
 *   neither. The existing analysis costs about 4.5 s for two minutes of audio; anything in that
 *   region is fine.
 * - **NNAPI is not the door.** It is deprecated as of Android 15 and this app targets 36, so ONNX
 *   Runtime's NNAPI execution provider is the wrong path. The current options are LiteRT with a
 *   GPU delegate, or a vendor execution provider - QNN on Qualcomm, which this device has.
 * - **Licensing.** The obvious reference implementations are madmom (BSD-derived but with parts
 *   under a non-commercial clause, so read it carefully) and Essentia's TensorFlow models (AGPL-3.0
 *   for the library - permitted alongside GPL-3.0 by GPLv3 section 13, and see the note in
 *   CLAUDE.md about where that does and does not lead). A model's *weights* and its *code* are
 *   separately licensed and both matter.
 * - **The interface above is deliberately narrow.** If the model can also report metre, that is the
 *   moment `beatsPerBar` stops being hard-coded to 4 - see the note on it in `automix_analyze.h`.
 */
class NeuralStructureAnalyzer : StructureAnalyzer {
    override fun analyse(pcm: PcmDecoder.Pcm): TrackStructure? = null
}
