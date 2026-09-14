/* Host-side regression checks for Automix's native analyser. */

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#include "automix_analyze.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define SAMPLE_RATE 44100
#define DURATION_S 32

static void add_tone(float *audio, size_t count, float hz, float amplitude) {
    const double step = 2.0 * M_PI * (double) hz / SAMPLE_RATE;
    for (size_t i = 0; i < count; i++) {
        audio[i] += amplitude * (float) sin(step * (double) i);
    }
}

static void add_pulse(float *audio, size_t count, float when_s, float amplitude) {
    const size_t start = (size_t) lrintf(when_s * SAMPLE_RATE);
    const size_t length = SAMPLE_RATE / 10;
    for (size_t i = 0; i < length && start + i < count; i++) {
        const float age = (float) i / SAMPLE_RATE;
        const float envelope = expf(-age * 45.0f);
        /* The kick sits below chroma; the short click gives the onset detector a sharp edge. */
        const float kick = sinf(2.0f * (float) M_PI * 45.0f * age);
        const float click = sinf(2.0f * (float) M_PI * 3000.0f * age);
        audio[start + i] += amplitude * envelope * (kick + 0.2f * click);
    }
}

static int nearest_downbeat_phase(const automix_result_t *result, float bpm, float offset_s) {
    const float bar = 4.0f * 60.0f / bpm;
    int best = 0;
    float best_distance = INFINITY;
    for (int phase = 0; phase < 4 && phase < result->beat_count; phase++) {
        const float beat = result->beats_s[phase];
        const float bar_number = roundf((beat - offset_s) / bar);
        const float distance = fabsf(beat - (offset_s + bar_number * bar));
        if (distance < best_distance) {
            best_distance = distance;
            best = phase;
        }
    }
    return best;
}

static int run_case(float expected_bpm) {
    const size_t count = (size_t) SAMPLE_RATE * DURATION_S;
    float *audio = (float *) calloc(count, sizeof(float));
    if (audio == NULL) return 0;

    /* A sustained C-major bed keeps the tonal answer independent of the rhythm test. */
    add_tone(audio, count, 261.6256f, 0.035f);
    add_tone(audio, count, 329.6276f, 0.030f);
    add_tone(audio, count, 391.9954f, 0.025f);

    const float offset_s = 0.25f;
    const float period_s = 60.0f / expected_bpm;
    for (int beat = 0; ; beat++) {
        const float when_s = offset_s + beat * period_s;
        if (when_s >= DURATION_S) break;
        /* Every pulse has a kick, including the midpoints of an aubio half-time grid. */
        add_pulse(audio, count, when_s, (beat % 4 == 0) ? 0.80f : 0.55f);
    }

    automix_analyzer_t *analyzer = automix_analyzer_new(SAMPLE_RATE);
    if (analyzer == NULL) {
        free(audio);
        return 0;
    }
    /* Odd chunks exercise the analyser's partial-hop staging as well as its DSP. */
    for (size_t offset = 0; offset < count; offset += 1379) {
        const size_t remaining = count - offset;
        const size_t chunk = remaining < 1379 ? remaining : 1379;
        automix_analyzer_feed(analyzer, audio + offset, chunk);
    }

    automix_result_t result;
    const int finished = automix_analyzer_finish(analyzer, &result);
    const int expected_phase = finished
        ? nearest_downbeat_phase(&result, expected_bpm, offset_s)
        : -1;
    const float first_beat = finished && result.beat_count > 0 ? result.beats_s[0] : INFINITY;
    const int ok = finished &&
        fabsf(result.bpm - expected_bpm) < 1.0f &&
        result.beat_count > 16 &&
        first_beat >= 0.0f && first_beat < period_s + offset_s &&
        result.downbeat_index == expected_phase &&
        result.downbeat_confidence > 0.10f &&
        result.key_pitch_class == 0 && result.key_is_major;

    printf(
        "%6.1f BPM -> %7.2f, beats=%3d first=%5.3f, downbeat=%d expected=%d "
        "confidence=%.3f, key=%d%s: %s\n",
        expected_bpm, result.bpm, result.beat_count, first_beat,
        result.downbeat_index, expected_phase, result.downbeat_confidence,
        result.key_pitch_class, result.key_is_major ? " major" : " minor",
        ok ? "PASS" : "FAIL"
    );

    automix_analyzer_free(analyzer);
    free(audio);
    return ok;
}

int main(void) {
    const float tempos[] = {96.0f, 128.0f, 174.0f};
    int passed = 0;
    for (size_t i = 0; i < sizeof(tempos) / sizeof(tempos[0]); i++) {
        passed += run_case(tempos[i]);
    }
    printf("%d/%zu native analysis cases passed\n", passed, sizeof(tempos) / sizeof(tempos[0]));
    return passed == (int) (sizeof(tempos) / sizeof(tempos[0])) ? EXIT_SUCCESS : EXIT_FAILURE;
}
