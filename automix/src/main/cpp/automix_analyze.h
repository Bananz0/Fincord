/*
 * Fincord - track analysis for Automix.
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file at the root of this
 * repository.
 */

#ifndef AUTOMIX_ANALYZE_H
#define AUTOMIX_ANALYZE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct automix_analyzer automix_analyzer_t;

typedef struct {
    /* Beats per minute over the analysed span, or 0 when no steady beat was found. */
    float bpm;
    /* aubio's own confidence in the tempo it settled on. Higher is better; 0 means inconsistent. */
    float tempo_confidence;

    /*
     * Beat positions in seconds from the start of the track. Owned by the analyzer and valid until
     * it is freed, so a caller that needs them afterwards must copy them out.
     */
    const float *beats_s;
    int beat_count;

    /*
     * Which beat of the bar starts it: an index into the first `beats_per_bar` entries of
     * `beats_s`, from which every following downbeat is `beats_per_bar` apart.
     *
     * `beats_per_bar` is always 4. Guessing a metre from audio is a separate problem to guessing
     * where the bar starts, most beat-matched music is in four, and a wrong metre is worse than an
     * assumed one because it silently rescales every phrase boundary built on top of it. When the
     * neural downbeat model of milestone 3 arrives it can report the metre it actually found, and
     * this field is already the place to put it.
     */
    int beats_per_bar;
    int downbeat_index;
    /* How far clear of the other candidate phases the winner was, 0..1. */
    float downbeat_confidence;

    /* 0 = C through 11 = B, or -1 when the track was too quiet or atonal to place. */
    int key_pitch_class;
    int key_is_major;
    /* Correlation of the chromagram with the winning profile, 0..1. */
    float key_strength;

    /* How much audio actually reached the analyzer, which is rarely the whole track. */
    float analysed_seconds;

    /*
     * The tempo before the half-time correction, or 0 if it did not fire.
     *
     * Doubling is the one step here that can be confidently and badly wrong - it has already turned
     * an ordinary 99 BPM track into 199 once - and its result is indistinguishable afterwards from
     * a track that genuinely runs at the doubled tempo. Reporting what it started from is what lets
     * anyone reading a log or a stored row tell the two apart, and lets a caller that does not
     * trust the decision fall back to half.
     */
    float bpm_before_doubling;
} automix_result_t;

automix_analyzer_t *automix_analyzer_new(int samplerate);

/* Mono samples in [-1, 1]. Any count is accepted; the analyzer buffers to its own hop size. */
void automix_analyzer_feed(automix_analyzer_t *a, const float *samples, size_t count);

/* Fills `result` from everything fed so far. Returns non-zero on success. */
int automix_analyzer_finish(automix_analyzer_t *a, automix_result_t *result);

void automix_analyzer_free(automix_analyzer_t *a);

#ifdef __cplusplus
}
#endif

#endif /* AUTOMIX_ANALYZE_H */
