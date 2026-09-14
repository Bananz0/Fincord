/*
 * Fincord - track analysis for Automix.
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file at the root of this
 * repository.
 *
 * Beat tracking is aubio's. What is written here is everything aubio does not do: a downbeat
 * phase, a chromagram, and a key estimate over it.
 */

#include "automix_analyze.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "aubio_subset.h"

/*
 * 1024/512 is aubio's own default for tempo, and the window its beat tracker was published and
 * evaluated at. There is no reason to differ.
 */
#define TEMPO_WIN 1024
#define TEMPO_HOP 512

/*
 * Chroma gets its own, longer window on the same hop. 1024 samples is ~23 ms at 44.1 kHz, whose
 * bins are ~43 Hz apart - wider than the semitones anywhere below the octave above middle C, so a
 * bass line would smear across two or three pitch classes. 4096 brings that to ~11 Hz, which
 * separates semitones down to about D2. A phase vocoder buffers internally, so running a second one
 * over the same hops costs a window of latency and nothing else.
 */
#define CHROMA_WIN 4096

/* Below this a bin is rumble; above it, harmonics outnumber fundamentals. */
#define CHROMA_MIN_HZ 55.0f
#define CHROMA_MAX_HZ 2000.0f

/* The bar Automix assumes. See the note on beats_per_bar in automix_analyze.h. */
#define BEATS_PER_BAR 4

/*
 * How near a quarter of a BPM a fitted tempo must be before it is snapped to one. Comfortably
 * inside the fit's own accuracy - measured within 0.04 BPM across the test matrix - so this only
 * ever removes residual error and never moves a tempo that was genuinely between the quarters.
 */
#define QUARTER_BPM_TOLERANCE 0.06f

/* Kick drums and bass live here, and they are what marks bar one. */
#define DOWNBEAT_LOW_HZ 150.0f

typedef struct {
    float *data;
    size_t count;
    size_t capacity;
} float_list_t;

struct automix_analyzer {
    uint_t samplerate;

    aubio_tempo_t *tempo;
    fvec_t *tempo_in;
    fvec_t *tempo_out;

    aubio_pvoc_t *chroma_pvoc;
    fvec_t *chroma_in;
    cvec_t *chroma_grain;

    /* Hop-sized staging for whatever the caller last handed us that did not fill a hop. */
    float *pending;
    size_t pending_count;

    /* Running totals over the whole analysed span. */
    double chroma[12];
    float_list_t beats_s;
    float_list_t hop_low_energy;
    float_list_t hop_flux;
    float *previous_norm;
    uint_t previous_norm_length;

    uint64_t frames_seen;
};

/*
 * Krumhansl and Kessler's probe-tone profiles, from Cognitive Foundations of Musical Pitch.
 * Correlating a chromagram against all 24 rotations of these is the Krumhansl-Schmuckler
 * key-finding algorithm: the oldest method that works and the easiest to explain, and on material
 * with a clear tonal centre - which is most of what gets beat-matched - close enough to the neural
 * state of the art not to be the weak link. Its known failure is the relative major/minor pair,
 * which shares six of seven notes; that surfaces as a low strength rather than as silent nonsense,
 * so a caller can decline to key-match instead of matching to the wrong one.
 */
static const float KK_MAJOR[12] = {
    6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
};

static const float KK_MINOR[12] = {
    6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
};

static int list_push(float_list_t *list, float value) {
    if (list->count == list->capacity) {
        size_t capacity = list->capacity ? list->capacity * 2 : 256;
        float *grown = (float *) realloc(list->data, capacity * sizeof(float));
        if (grown == NULL) return 0;
        list->data = grown;
        list->capacity = capacity;
    }
    list->data[list->count++] = value;
    return 1;
}

static void list_free(float_list_t *list) {
    free(list->data);
    list->data = NULL;
    list->count = 0;
    list->capacity = 0;
}

automix_analyzer_t *automix_analyzer_new(int samplerate) {
    if (samplerate < 8000 || samplerate > 384000) return NULL;

    automix_analyzer_t *a = (automix_analyzer_t *) calloc(1, sizeof(automix_analyzer_t));
    if (a == NULL) return NULL;
    a->samplerate = (uint_t) samplerate;

    a->tempo = new_aubio_tempo("default", TEMPO_WIN, TEMPO_HOP, a->samplerate);
    a->tempo_in = new_fvec(TEMPO_HOP);
    a->tempo_out = new_fvec(2);
    a->chroma_pvoc = new_aubio_pvoc(CHROMA_WIN, TEMPO_HOP);
    a->chroma_in = new_fvec(TEMPO_HOP);
    a->chroma_grain = new_cvec(CHROMA_WIN);
    a->pending = (float *) calloc(TEMPO_HOP, sizeof(float));

    if (a->tempo == NULL || a->tempo_in == NULL || a->tempo_out == NULL ||
        a->chroma_pvoc == NULL || a->chroma_in == NULL || a->chroma_grain == NULL ||
        a->pending == NULL) {
        automix_analyzer_free(a);
        return NULL;
    }
    return a;
}

void automix_analyzer_free(automix_analyzer_t *a) {
    if (a == NULL) return;
    if (a->tempo) del_aubio_tempo(a->tempo);
    if (a->tempo_in) del_fvec(a->tempo_in);
    if (a->tempo_out) del_fvec(a->tempo_out);
    if (a->chroma_pvoc) del_aubio_pvoc(a->chroma_pvoc);
    if (a->chroma_in) del_fvec(a->chroma_in);
    if (a->chroma_grain) del_cvec(a->chroma_grain);
    free(a->pending);
    free(a->previous_norm);
    list_free(&a->beats_s);
    list_free(&a->hop_low_energy);
    list_free(&a->hop_flux);
    free(a);
}

/* One hop of mono samples through both analyses. */
static void process_hop(automix_analyzer_t *a, const float *samples) {
    memcpy(a->tempo_in->data, samples, TEMPO_HOP * sizeof(float));
    aubio_tempo_do(a->tempo, a->tempo_in, a->tempo_out);
    if (a->tempo_out->data[0] != 0.0f) {
        list_push(&a->beats_s, aubio_tempo_get_last_s(a->tempo));
    }

    memcpy(a->chroma_in->data, samples, TEMPO_HOP * sizeof(float));
    aubio_pvoc_do(a->chroma_pvoc, a->chroma_in, a->chroma_grain);

    const uint_t bins = a->chroma_grain->length;
    const float bin_hz = (float) a->samplerate / (float) CHROMA_WIN;

    if (a->previous_norm == NULL) {
        a->previous_norm = (float *) calloc(bins, sizeof(float));
        a->previous_norm_length = bins;
    }

    float low_energy = 0.0f;
    float flux = 0.0f;
    for (uint_t k = 1; k < bins; k++) {
        const float magnitude = a->chroma_grain->norm[k];
        const float hz = (float) k * bin_hz;

        if (hz <= DOWNBEAT_LOW_HZ) low_energy += magnitude * magnitude;

        /*
         * Half-wave rectified spectral flux: only bins that grew count. Energy dropping is a note
         * ending, and a transition wants to land where something starts.
         */
        if (a->previous_norm != NULL && k < a->previous_norm_length) {
            const float rise = magnitude - a->previous_norm[k];
            if (rise > 0.0f) flux += rise;
            a->previous_norm[k] = magnitude;
        }

        if (hz < CHROMA_MIN_HZ || hz > CHROMA_MAX_HZ) continue;
        /* 69 is A4 in MIDI numbering, where 440 Hz sits; twelve steps to the octave from there. */
        const float midi = 69.0f + 12.0f * log2f(hz / 440.0f);
        int pitch_class = ((int) lrintf(midi)) % 12;
        if (pitch_class < 0) pitch_class += 12;
        a->chroma[pitch_class] += magnitude;
    }

    list_push(&a->hop_low_energy, low_energy);
    list_push(&a->hop_flux, flux);
    a->frames_seen += TEMPO_HOP;
}

void automix_analyzer_feed(automix_analyzer_t *a, const float *samples, size_t count) {
    if (a == NULL || samples == NULL) return;
    size_t offset = 0;

    while (offset < count) {
        const size_t room = TEMPO_HOP - a->pending_count;
        const size_t take = (count - offset < room) ? (count - offset) : room;
        memcpy(a->pending + a->pending_count, samples + offset, take * sizeof(float));
        a->pending_count += take;
        offset += take;
        if (a->pending_count == TEMPO_HOP) {
            process_hop(a, a->pending);
            a->pending_count = 0;
        }
    }
}

static int compare_floats(const void *lhs, const void *rhs) {
    const float a = *(const float *) lhs;
    const float b = *(const float *) rhs;
    return (a > b) - (a < b);
}

/*
 * The tempo aubio reports is its estimate at the moment analysis stopped, which is one window's
 * view. Over a whole span the median gap between detected beats is steadier, because a handful of
 * missed or spurious beats move a median not at all.
 */
static float median_bpm(const float_list_t *beats) {
    if (beats->count < 4) return 0.0f;

    const size_t gaps = beats->count - 1;
    float *intervals = (float *) malloc(gaps * sizeof(float));
    if (intervals == NULL) return 0.0f;
    for (size_t i = 0; i < gaps; i++) intervals[i] = beats->data[i + 1] - beats->data[i];
    qsort(intervals, gaps, sizeof(float), compare_floats);
    const float median = intervals[gaps / 2];
    if (median <= 0.0f) {
        free(intervals);
        return 0.0f;
    }

    /*
     * Average only the intervals within a quarter of the median. A dropped beat leaves a gap of
     * roughly twice the period and a spurious one leaves half; excluding both before averaging
     * stops a track whose beat tracking wobbled from reporting a tempo somewhere in between.
     */
    float sum = 0.0f;
    size_t kept = 0;
    for (size_t i = 0; i < gaps; i++) {
        if (fabsf(intervals[i] - median) <= 0.25f * median) {
            sum += intervals[i];
            kept++;
        }
    }
    free(intervals);
    if (kept == 0 || sum <= 0.0f) return 60.0f / median;
    return 60.0f / (sum / (float) kept);
}

/* The strongest low-band hop within a beat's neighbourhood; see the note in estimate_downbeat. */
static float low_energy_near(const automix_analyzer_t *a, float when_s) {
    const float hop_s = (float) TEMPO_HOP / (float) a->samplerate;
    const long centre = lrintf(when_s / hop_s);
    float best = 0.0f;
    for (long hop = centre - 2; hop <= centre + 4; hop++) {
        if (hop < 0 || (size_t) hop >= a->hop_low_energy.count) continue;
        if (a->hop_low_energy.data[hop] > best) best = a->hop_low_energy.data[hop];
    }
    return best;
}

/*
 * Catches the tracker settling on half the real tempo.
 *
 * Beat trackers weight their period estimate towards a preferred tempo - aubio's sits near 120 -
 * so anything much faster tends to come back halved. A drum and bass track at 174 reported as 87 is
 * not a wrong answer musically, but for Automix it is: every other beat of the grid is missing, and
 * a transition aligned to it lands on air.
 *
 * The test is whether the half-period points carry as much low-frequency energy as the beats
 * themselves. If they do, the kick is playing there too and the real pulse is twice what was
 * reported. Deliberately low-band only: eighth-note hi-hats sit on exactly those points and would
 * fool a full-band or flux-based measure into doubling every straight-ahead track, while hats put
 * nothing below 150 Hz.
 *
 * Half a *period* from each beat, not the midpoint between successive detections. Those two are
 * only the same thing when the detections are evenly spaced, and the case this exists for is
 * precisely where they are not: a tracker sliding between full and half time reports a mixture of
 * one- and two-period gaps, whose midpoints land on the beat as often as between beats, which
 * averages the evidence away to nothing.
 *
 * This adjusts the tempo only. It deliberately does not touch the beat list - filling in the
 * missing half of the grid is fit_grid's job, and doing it here by interpolating every gap put a
 * spurious beat in the middle of every gap that was already one period long.
 */
static float correct_octave_error(automix_analyzer_t *a, float bpm) {
    /*
     * Only into the range where double-time is a real tempo rather than an arithmetic possibility.
     *
     * The music this exists for - drum and bass, footwork, happy hardcore - lives at 160 to 178,
     * which is reported back as 80 to 89. Above about 180 there is no genre that anyone counts at
     * the doubled rate, so a halved reading is not what is being looked at. Letting the ceiling sit
     * at 200 instead cost a real false positive on device: a track reported at 99 - an entirely
     * ordinary tempo - was doubled to 199, because a track with any off-beat bass at all passes the
     * energy test, and at 99 the check is far more likely to be wrong than right.
     */
    if (bpm <= 0.0f || bpm * 2.0f > 180.0f) return bpm;
    if (a->beats_s.count < 8) return bpm;

    const float half = 0.5f * (60.0f / bpm);
    double on_beat = 0.0;
    double between = 0.0;
    for (size_t i = 0; i < a->beats_s.count; i++) {
        const float current = a->beats_s.data[i];
        on_beat += low_energy_near(a, current);
        between += low_energy_near(a, current + half);
    }
    if (on_beat <= 0.0) return bpm;

    /*
     * 0.7 rather than something nearer 1. A real off-beat kick is rarely mixed as loud as the one
     * on the beat, so demanding equality would miss the case this exists for; demanding much less
     * would start doubling tracks whose only off-beat content is a bass note.
     */
    const double ratio = between / on_beat;
    if (ratio < 0.7) return bpm;
    return bpm * 2.0f;
}

/*
 * Replaces the detected beats with a constant-tempo grid fitted to them, and returns its tempo.
 *
 * A beat tracker's output is evidence about where the pulse is, not a grid. aubio reports nothing
 * for the first second or two while its autocorrelation commits to a period, emits well over one
 * detection per pulse in busy passages - a 174 BPM signal came back with 192 beats in a minute
 * where 174 exist - and emits none at all through a bar with no percussion in it. Repairing that
 * detection by detection takes several passes that each have to agree about the same period, and
 * still leaves the joins between them wrong: a lead-in produced a backfilled run and a detected
 * run that did not meet a whole period apart.
 *
 * A grid is a period and a phase, so fit both to every detection at once and lay the result across
 * the analysed span. That fills the lock-on delay and the quiet bars, drops the duplicates, and
 * cannot drift out of phase halfway through.
 *
 * Precision is the whole point, and it is why this regresses rather than averaging intervals. Beat
 * times are reported on hop boundaries, ~12 ms apart at 44.1 kHz, so a single interval carries a
 * couple of percent of error; the mean of many still left the tempo 0.4% out, which sounds
 * negligible and is not - across two minutes it is more than a beat of accumulated drift, and a
 * transition aligned on the first bar lands off the pulse by the last. Fitting a line to (index,
 * time) instead divides that ~12 ms over the *whole span* rather than over one beat.
 *
 * Beat indices come from counting successive intervals, not from dividing each time by an assumed
 * period. Cumulative counting only needs each interval to be right to within half a beat, which it
 * is; dividing needs the assumed period to be right to within half a beat over the entire track,
 * which is the very thing being solved for. Detections less than half a period after the previous
 * one are duplicates and are dropped rather than counted.
 *
 * This assumes the tempo is constant across the analysed window. For a live recording that drifts,
 * the fitted grid is worse than the detections it replaces - but a track whose tempo drifts cannot
 * be beat-matched to anyway, and `tempo_confidence` is what says so.
 */
static float fit_grid(automix_analyzer_t *a, float bpm) {
    if (bpm <= 0.0f || a->beats_s.count < 8) return bpm;

    double period = 60.0 / (double) bpm;
    double phase = a->beats_s.data[0];

    /*
     * Two rounds. The first fits every detection that is not a duplicate; the second refits with
     * the outliers of that fit removed, which is what stops one badly placed detection - a false
     * onset in a fill - from tilting the line across the whole track.
     */
    for (int round = 0; round < 2; round++) {
        const double reject = (round == 0) ? 0.0 : 0.25 * period;
        double sk = 0.0, st = 0.0, skk = 0.0, skt = 0.0;
        size_t n = 0;
        double index = 0.0;
        double previous = a->beats_s.data[0];

        for (size_t i = 0; i < a->beats_s.count; i++) {
            const double t = a->beats_s.data[i];
            if (i > 0) {
                const double gap = t - previous;
                /*
                 * Three quarters of a period, not a half. Rounding the gap alone puts a spurious
                 * detection sitting near the half-beat - which is where a fast track's extra
                 * onsets land - exactly on the boundary, and rounding it up counts a beat that is
                 * not there. At 174 BPM that inflated the index enough to fit 178. Nothing
                 * genuine arrives that early: hop quantisation moves a beat by a few percent of a
                 * period, nowhere near a quarter of one.
                 */
                if (gap < 0.75 * period) continue;
                const double step = floor(gap / period + 0.5);
                index += (step < 1.0) ? 1.0 : step;
                previous = t;
            }
            if (reject > 0.0 && fabs(t - (phase + index * period)) > reject) continue;
            sk += index;
            st += t;
            skk += index * index;
            skt += index * t;
            n++;
        }

        if (n < 4) break;
        const double denominator = (double) n * skk - sk * sk;
        if (denominator <= 0.0) break;
        const double fitted_period = ((double) n * skt - sk * st) / denominator;
        const double fitted_phase = (st - fitted_period * sk) / (double) n;
        /* A fit that moved the tempo this far did not converge on the pulse; keep what we had. */
        if (fitted_period <= 0.0 || fabs(fitted_period - period) > 0.2 * period) break;
        period = fitted_period;
        phase = fitted_phase;
    }

    const double last = a->beats_s.data[a->beats_s.count - 1];
    const long first_k = (long) ceil((0.0 - phase) / period);
    const long last_k = (long) floor((last - phase) / period);
    if (last_k < first_k + 1) return bpm;

    const size_t count = (size_t) (last_k - first_k + 1);
    float *grid = (float *) malloc(count * sizeof(float));
    if (grid == NULL) return bpm;
    for (size_t i = 0; i < count; i++) {
        grid[i] = (float) (phase + (double) ((long) i + first_k) * period);
    }

    free(a->beats_s.data);
    a->beats_s.data = grid;
    a->beats_s.count = count;
    a->beats_s.capacity = count;
    return (float) (60.0 / period);
}

/*
 * Snaps a fitted tempo to a quarter of a BPM when it is already nearly there.
 *
 * The idea is from walkywalker's Automix (github.com/walkywalker/automix), which rounds its grid
 * tempo to 0.25 BPM on the grounds that electronic music is authored at quantised tempos - a track
 * is written at 174, not at 173.97. Implemented here from that observation rather than from its
 * code.
 *
 * It matters far more than a hundredth of a BPM sounds like it should, because the tempo is not
 * only reported, it is *extrapolated*: the transition point sits minutes past the analysed window,
 * and an error of 0.04 BPM at 128 accumulates to about 75 ms of drift over four minutes, which is a
 * fifth of a beat. Landing exactly on the authored tempo makes that error zero at any distance.
 *
 * Only when the fit is already within a tolerance of the quarter, and the tolerance is deliberately
 * smaller than the fit's own accuracy. A track that genuinely runs at 121.13 - anything played by
 * people rather than programmed - is left alone, because moving it to 121.25 would be inventing a
 * tempo rather than recovering one.
 */
static float snap_to_quarter_bpm(float bpm) {
    if (bpm <= 0.0f) return bpm;
    const float quarters = bpm * 4.0f;
    const float nearest = roundf(quarters) / 4.0f;
    return (fabsf(bpm - nearest) <= QUARTER_BPM_TOLERANCE) ? nearest : bpm;
}

/*
 * Which beat of the four starts the bar.
 *
 * Bar one lands on the kick, so score each candidate phase by the low-frequency energy and the
 * spectral flux at the beats it would claim, and take the strongest. Confidence is how far clear of
 * the other three the winner is, which is what separates "the kick is on the one" from "this track
 * has no drums and all four look alike".
 */
static void estimate_downbeat(automix_analyzer_t *a, automix_result_t *result, float bpm) {
    result->beats_per_bar = BEATS_PER_BAR;
    result->downbeat_index = 0;
    result->downbeat_confidence = 0.0f;
    if (a->beats_s.count < BEATS_PER_BAR * 2 || bpm <= 0.0f) return;

    const float hop_s = (float) TEMPO_HOP / (float) a->samplerate;
    const float period = 60.0f / bpm;
    const float origin = a->beats_s.data[0];
    double score[BEATS_PER_BAR] = {0.0};
    size_t counted[BEATS_PER_BAR] = {0};

    /* Flux and low-band energy are on unrelated scales, so normalise each before adding them. */
    float peak_low = 0.0f;
    float peak_flux = 0.0f;
    for (size_t h = 0; h < a->hop_low_energy.count; h++) {
        if (a->hop_low_energy.data[h] > peak_low) peak_low = a->hop_low_energy.data[h];
        if (a->hop_flux.data[h] > peak_flux) peak_flux = a->hop_flux.data[h];
    }
    if (peak_low <= 0.0f) peak_low = 1.0f;
    if (peak_flux <= 0.0f) peak_flux = 1.0f;

    for (size_t i = 0; i < a->beats_s.count; i++) {
        const long centre = lrintf(a->beats_s.data[i] / hop_s);
        /*
         * The strongest hop near the beat, not the hop the beat time lands in. A beat time is the
         * tracker's estimate of where the pulse is, which is a few milliseconds from where the kick
         * actually peaks - and a kick's energy keeps climbing for a hop or two after its onset. On
         * a single hop that mismatch reads as a quiet downbeat and the bar phase comes out wrong;
         * the window either side costs nothing and removes the whole class of error. Wider forwards
         * than backwards because energy follows a transient rather than preceding it.
         */
        float salience = 0.0f;
        for (long hop = centre - 2; hop <= centre + 4; hop++) {
            if (hop < 0 || (size_t) hop >= a->hop_low_energy.count) continue;
            const float here = a->hop_low_energy.data[hop] / peak_low +
                               a->hop_flux.data[hop] / peak_flux;
            if (here > salience) salience = here;
        }
        /*
         * Derive the phase from the settled grid, not from this beat's position in aubio's list.
         * A single false detection inserts one list entry and would otherwise move every genuine
         * beat after it into the next bar phase. Rounding the elapsed periods makes a false or
         * missing detection local: subsequent beats still land in the phase their time implies.
         */
        const long grid_position = lrintf((a->beats_s.data[i] - origin) / period);
        int phase = (int) (grid_position % BEATS_PER_BAR);
        if (phase < 0) phase += BEATS_PER_BAR;
        score[phase] += salience;
        counted[phase]++;
    }

    int best = 0;
    double best_score = -1.0;
    double total = 0.0;
    for (int p = 0; p < BEATS_PER_BAR; p++) {
        if (counted[p] > 0) score[p] /= (double) counted[p];
        total += score[p];
        if (score[p] > best_score) {
            best_score = score[p];
            best = p;
        }
    }

    result->downbeat_index = best;
    const double others = (total - best_score) / (double) (BEATS_PER_BAR - 1);
    if (best_score > 0.0) {
        float confidence = (float) ((best_score - others) / best_score);
        result->downbeat_confidence = (confidence > 0.0f) ? confidence : 0.0f;
    }
}

/* Pearson correlation between the chromagram and one profile rotation. */
static float correlate(const float *chroma, const float *profile, int rotation) {
    float chroma_mean = 0.0f;
    float profile_mean = 0.0f;
    for (int i = 0; i < 12; i++) {
        chroma_mean += chroma[i];
        profile_mean += profile[i];
    }
    chroma_mean /= 12.0f;
    profile_mean /= 12.0f;

    float covariance = 0.0f;
    float chroma_var = 0.0f;
    float profile_var = 0.0f;
    for (int i = 0; i < 12; i++) {
        const float c = chroma[(i + rotation) % 12] - chroma_mean;
        const float p = profile[i] - profile_mean;
        covariance += c * p;
        chroma_var += c * c;
        profile_var += p * p;
    }
    const float denominator = sqrtf(chroma_var * profile_var);
    return (denominator > 0.0f) ? covariance / denominator : 0.0f;
}

static void estimate_key(automix_analyzer_t *a, automix_result_t *result) {
    result->key_pitch_class = -1;
    result->key_is_major = 0;
    result->key_strength = 0.0f;

    float chroma[12];
    double total = 0.0;
    for (int i = 0; i < 12; i++) total += a->chroma[i];
    if (total <= 0.0) return;
    for (int i = 0; i < 12; i++) chroma[i] = (float) (a->chroma[i] / total);

    float best = -2.0f;
    for (int tonic = 0; tonic < 12; tonic++) {
        const float major = correlate(chroma, KK_MAJOR, tonic);
        if (major > best) {
            best = major;
            result->key_pitch_class = tonic;
            result->key_is_major = 1;
        }
        const float minor = correlate(chroma, KK_MINOR, tonic);
        if (minor > best) {
            best = minor;
            result->key_pitch_class = tonic;
            result->key_is_major = 0;
        }
    }
    result->key_strength = (best > 0.0f) ? best : 0.0f;
}

int automix_analyzer_finish(automix_analyzer_t *a, automix_result_t *result) {
    if (a == NULL || result == NULL) return 0;
    memset(result, 0, sizeof(*result));

    /*
     * A last partial hop is dropped rather than zero-padded. Padding hands the onset detector a
     * step down into silence, which reads as a transient, and a fabricated beat at the very end of
     * the analysed span is exactly where it would do the most damage.
     */
    a->pending_count = 0;

    result->analysed_seconds = (float) ((double) a->frames_seen / (double) a->samplerate);
    /*
     * aubio's own figure, and it is neither bounded nor always a number.
     *
     * Measured over 150 tracks of a real library on 2026-09-02, it ran from **-13.77 to 8.47**, and
     * on synthetic signals the self-test sees 2.2 to 2.4. Everything written about this field
     * before that survey assumed a 0..1 correlation, which it is not - it is an unbounded score,
     * and the threshold built on it is a number on a scale nobody had actually looked at.
     *
     * Both ends are clamped here, for different reasons that arrive at the same value:
     *
     * - **NaN**, observed on device. It is the worst possible value to pass on, because every
     *   comparison against it is false: a threshold written the obvious way, `confidence < minimum`,
     *   does not reject NaN, it *admits* it.
     * - **Negative**, observed across the survey. Clamped rather than rejected by the caller, so
     *   the row is still stored and still declines at the gate. Discarding it instead would leave
     *   no row at all, and no row means the track is decoded and analysed again every single time
     *   it comes up - paying two minutes of decode to re-derive the same refusal.
     *
     * Zero is the honest translation of both: no confidence.
     */
    const float tempo_confidence = aubio_tempo_get_confidence(a->tempo);
    result->tempo_confidence =
        (isfinite(tempo_confidence) && tempo_confidence > 0.0f) ? tempo_confidence : 0.0f;

    result->bpm = median_bpm(&a->beats_s);
    if (result->bpm <= 0.0f) result->bpm = aubio_tempo_get_bpm(a->tempo);

    const float before_doubling = result->bpm;
    result->bpm = correct_octave_error(a, result->bpm);
    result->bpm_before_doubling = (result->bpm != before_doubling) ? before_doubling : 0.0f;
    result->bpm = snap_to_quarter_bpm(fit_grid(a, result->bpm));

    result->beat_count = (int) a->beats_s.count;
    result->beats_s = a->beats_s.data;

    estimate_downbeat(a, result, result->bpm);
    estimate_key(a, result);
    return 1;
}
