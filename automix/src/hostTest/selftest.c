/*
 * Host self-test for the Automix analyser. Not part of the app build.
 *
 * Synthesises audio whose tempo, bar phase and key are known by construction, then checks the
 * analyser recovers them.
 */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "automix_analyze.h"

#define SR 44100
#define TWO_PI 6.283185307179586f

static const char *NAMES[12] = {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
};

static int camelot_number(int pc, int major) {
    return major ? ((pc * 7 + 7) % 12) + 1 : ((pc * 7 + 4) % 12) + 1;
}

/* One decaying sine burst: a stand-in for a drum hit. */
static void hit(float *buf, long n, long at, float freq, float amp, float decay) {
    for (long i = 0; i < (long) (SR * 0.35f); i++) {
        long k = at + i;
        if (k < 0 || k >= n) break;
        float t = (float) i / SR;
        buf[k] += amp * expf(-decay * t) * sinf(TWO_PI * freq * t);
    }
}

/* Equal temperament from C3 = MIDI 48. */
static float pitch(int semitones_above_c3) {
    return 440.0f * powf(2.0f, (48 + semitones_above_c3 - 69) / 12.0f);
}

int main(int argc, char **argv) {
    const float bpm = (argc > 1) ? (float) atof(argv[1]) : 120.0f;
    const int tonic = (argc > 2) ? atoi(argv[2]) : 0;
    const int want_major = (argc > 3) ? atoi(argv[3]) : 1;
    /*
     * Seconds of silence before the first beat. Real tracks have one; every case here used to
     * start its first beat at exactly t=0, which is the one case where the grid backfill has
     * nothing to do, so the backfill went untested.
     */
    const double lead_in = (argc > 4) ? atof(argv[4]) : 0.0;
    const int seconds = 60;
    const long n = (long) SR * seconds;
    float *buf = (float *) calloc((size_t) n, sizeof(float));
    if (!buf) return 1;

    /*
     * A held triad plus the root an octave down. Major is root/major-third/fifth, minor flattens
     * the third - the one interval the two profiles are told apart by.
     */
    const int third = want_major ? 4 : 3;
    const float chord[4] = {
        pitch(tonic), pitch(tonic + 12), pitch(tonic + 12 + third), pitch(tonic + 12 + 7)
    };
    for (long i = 0; i < n; i++) {
        float t = (float) i / SR;
        float s = 0.0f;
        for (int c = 0; c < 4; c++) s += sinf(TWO_PI * chord[c] * t);
        buf[i] = 0.06f * s;
    }

    /* Beats. Every fourth is a louder, lower kick, so beat 0 of each bar is the downbeat. */
    const double period = 60.0 / bpm;
    int beat = 0;
    for (double t = lead_in; t < seconds; t += period, beat++) {
        long at = (long) (t * SR);
        /*
         * A kick on every beat, louder on the one. Four-to-the-floor rather than a kick only on
         * bar one: with low-frequency energy on every beat, a tracker that halves the tempo has
         * full-strength kicks sitting on the midpoints of the grid it reports, which is the
         * evidence the octave check looks for. A signal with a kick every fourth beat cannot
         * exercise that at all - there is genuinely nothing at the midpoints to find.
         */
        hit(buf, n, at, 55.0f, (beat % 4 == 0) ? 0.95f : 0.55f, 22.0f);
        if (beat % 2 == 1) hit(buf, n, at, 220.0f, 0.25f, 45.0f); /* a snare-ish backbeat */
    }

    automix_analyzer_t *a = automix_analyzer_new(SR);
    if (!a) { printf("FAIL: analyzer_new returned NULL\n"); return 1; }

    /* Feed in odd-sized chunks so the hop buffering is exercised, not just the happy path. */
    long offset = 0;
    while (offset < n) {
        long take = (n - offset < 777) ? (n - offset) : 777;
        automix_analyzer_feed(a, buf + offset, (size_t) take);
        offset += take;
    }

    automix_result_t r;
    if (!automix_analyzer_finish(a, &r)) { printf("FAIL: finish returned 0\n"); return 1; }

    printf("expected bpm   : %.2f\n", bpm);
    printf("reported bpm   : %.2f (tempo confidence %.3f)\n", r.bpm, r.tempo_confidence);
    printf("beats found    : %d over %.1fs (expected ~%d)\n",
           r.beat_count, r.analysed_seconds, (int) (seconds / period));
    printf("downbeat       : index %d of %d, confidence %.3f\n",
           r.downbeat_index, r.beats_per_bar, r.downbeat_confidence);
    if (r.bpm_before_doubling > 0.0f) {
        printf("half-time fix  : doubled from %.2f\n", r.bpm_before_doubling);
    }
    if (r.key_pitch_class >= 0) {
        printf("key            : %s %s (%d%s), strength %.3f\n",
               NAMES[r.key_pitch_class], r.key_is_major ? "major" : "minor",
               camelot_number(r.key_pitch_class, r.key_is_major),
               r.key_is_major ? "B" : "A", r.key_strength);
    } else {
        printf("key            : none\n");
    }
    if (r.beat_count >= 12) {
        printf("first beats (s): ");
        for (int i = 0; i < 12; i++) printf("%.3f ", r.beats_s[i]);
        printf("\n");
    }

    int ok = 1;
    if (fabsf(r.bpm - bpm) > 1.5f) { printf("FAIL: bpm off by more than 1.5\n"); ok = 0; }
    /*
     * The half-time correction is the one step that can be confidently wrong, so check it reports
     * itself honestly rather than only checking the tempo it produced. aubio genuinely halves the
     * fast case, so this is also the only place the corrector is exercised at all.
     */
    if (r.bpm_before_doubling > 0.0f &&
        fabsf(r.bpm - 2.0f * r.bpm_before_doubling) > 0.01f * r.bpm) {
        printf("FAIL: claims doubled from %.2f but reports %.2f\n", r.bpm_before_doubling, r.bpm);
        ok = 0;
    }
    if (r.key_pitch_class != tonic || r.key_is_major != want_major) {
        printf("FAIL: expected %s %s\n", NAMES[tonic], want_major ? "major" : "minor");
        ok = 0;
    }
    /*
     * Check the bar phase by where it actually points, not by expecting index 0. The index is a
     * position in the beat list, and the list starts wherever the backfill reached, so with a
     * lead-in the correct answer is not 0 and hard-coding one would only test the no-lead-in case.
     * The contract is what a caller does with it: beats at list positions congruent to
     * downbeat_index are downbeats, and the synthesised downbeats are at lead_in + k * 4 * period.
     */
    if (r.downbeat_index < 0 || r.downbeat_index >= r.beats_per_bar) {
        printf("FAIL: downbeat index %d outside 0..%d\n", r.downbeat_index, r.beats_per_bar - 1);
        ok = 0;
    } else {
        const double bar = r.beats_per_bar * period;
        int checked = 0;
        int wrong = 0;
        for (int i = r.downbeat_index; i < r.beat_count; i += r.beats_per_bar) {
            if (r.beats_s[i] < lead_in - 0.001) continue; /* fabricated grid in the lead-in */
            double off = fmod(r.beats_s[i] - lead_in, bar);
            if (off > bar / 2) off -= bar;
            if (fabs(off) > 0.25 * period) wrong++;
            checked++;
        }
        if (checked == 0) {
            printf("FAIL: downbeat index %d selects no beat after the lead-in\n", r.downbeat_index);
            ok = 0;
        } else if (wrong > 0) {
            printf("FAIL: %d of %d claimed downbeats are not on a bar line\n", wrong, checked);
            ok = 0;
        }
    }
    /*
     * The grid, not just the tempo. A count that drifts from one beat per period means spurious or
     * dropped detections survived, and those are what a transition actually lands on. Measured over
     * the whole span rather than the played part, because the backfill deliberately extends the
     * grid through the lead-in and those beats are expected to be there.
     */
    const int want_beats = (int) (seconds / period);
    if (abs(r.beat_count - want_beats) > 2) {
        printf("FAIL: %d beats, expected %d +/- 2\n", r.beat_count, want_beats);
        ok = 0;
    }
    /* And that they are evenly spaced, since the right count can still hide a pair out of place. */
    for (int i = 1; i < r.beat_count; i++) {
        float gap = r.beats_s[i] - r.beats_s[i - 1];
        if (fabsf(gap - (float) period) > 0.25f * (float) period) {
            printf("FAIL: gap of %.3fs at beat %d, expected %.3fs\n", gap, i, period);
            ok = 0;
            break;
        }
    }
    printf(ok ? "PASS\n" : "FAILED\n");

    automix_analyzer_free(a);
    free(buf);
    return ok ? 0 : 1;
}
