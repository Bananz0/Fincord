/*
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package uk.akane.accord.automix

/**
 * What one pass of analysis found in a track.
 *
 * Constructed from native code, so the parameter list is a JNI signature as much as an API: see the
 * `GetMethodID` call in `automix_jni.c`, which has to be changed in the same commit as this.
 */
class TrackAnalysis(
    /** Beats per minute, or `0` when no steady beat was found. */
    @JvmField val bpm: Float,
    /** aubio's confidence in the tempo. Higher is better; `0` means it never settled. */
    @JvmField val tempoConfidence: Float,
    /** Beat positions in milliseconds from the start of the track, ascending. */
    @JvmField val beatsMs: IntArray,
    /**
     * Beats to the bar. Always 4 today - the analyser assumes it rather than detecting it, because
     * a wrong metre silently rescales every phrase boundary built on top of it and most of what
     * gets beat-matched is in four anyway.
     */
    @JvmField val beatsPerBar: Int,
    /**
     * Which of the first [beatsPerBar] entries of [beatsMs] is a downbeat. Every following downbeat
     * is [beatsPerBar] beats after it.
     */
    @JvmField val downbeatIndex: Int,
    /** How far clear of the other candidate phases the chosen downbeat was, `0..1`. */
    @JvmField val downbeatConfidence: Float,
    /** `0` = C through `11` = B, or `-1` when the track was too quiet or atonal to place. */
    @JvmField val keyPitchClass: Int,
    @JvmField val keyIsMajor: Boolean,
    /** Correlation with the winning key profile, `0..1`. Low means "do not key-match on this". */
    @JvmField val keyStrength: Float,
    /** How much audio actually reached the analyser, which is rarely the whole track. */
    @JvmField val analysedSeconds: Float,
    /**
     * What [bpm] was before the half-time correction doubled it, or `0` if it did not fire.
     *
     * Doubling is the one step in the analysis that can be confidently and badly wrong - it turned
     * an ordinary 99 BPM track into 199 once - and afterwards the result is indistinguishable from
     * a track that genuinely runs at the doubled tempo. This is what tells the two apart, and what
     * a caller that disagrees with the decision can fall back to.
     */
    @JvmField val bpmBeforeDoubling: Float,
) {

    /** Whether [bpm] is a doubling of what the tracker actually reported. */
    val tempoWasDoubled: Boolean
        get() = bpmBeforeDoubling > 0f

    /**
     * The key in the wheel DJs actually use: `1A`..`12A` for minor, `1B`..`12B` for major, where
     * neighbouring numbers are a fifth apart and A and B at the same number are relative keys.
     *
     * `null` when no key was found. The point of the notation is that two tracks whose codes are
     * equal or adjacent will not clash, which is the only question a transition needs to ask, and
     * it asks it with an integer comparison rather than a table of intervals.
     */
    val camelot: String?
        get() = Camelot.code(keyPitchClass, keyIsMajor)

    /** The key as musicians write it, for anything user-facing. `null` when no key was found. */
    val keyName: String?
        get() = if (keyPitchClass in 0..11) {
            "${PITCH_CLASS_NAMES[keyPitchClass]} ${if (keyIsMajor) "major" else "minor"}"
        } else {
            null
        }

    override fun toString(): String =
        ("TrackAnalysis(bpm=%.2f%s conf=%.2f beats=%d bar=%d/%d dbConf=%.2f key=%s/%s str=%.2f " +
            "%.1fs)")
            .format(
                bpm,
                if (tempoWasDoubled) " (doubled from %.2f)".format(bpmBeforeDoubling) else "",
                tempoConfidence, beatsMs.size, downbeatIndex, beatsPerBar,
                downbeatConfidence, keyName ?: "?", camelot ?: "?", keyStrength, analysedSeconds,
            )

    private companion object {
        // Sharps rather than flats throughout. Choosing per key would need the key spelling this
        // analysis does not produce, and a consistently wrong enharmonic reads better than a mix.
        val PITCH_CLASS_NAMES = arrayOf(
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
        )
    }
}
