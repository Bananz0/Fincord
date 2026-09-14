/*
 * Fincord - runs the Automix analyser over real audio on a host.
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file at the root of this
 * repository.
 */

/*
 * Reads mono 32-bit float PCM from stdin and prints one line of analysis.
 *
 * The companion to selftest.c, which proves the DSP against synthetic signals it generates itself.
 * This one asks the other question - what the analyser says about *real records* - and it exists
 * because the answer that matters right now is a distribution rather than a value. The confidence
 * threshold that decides whether two tracks may be mixed was set from a handful of numbers, all of
 * them measured over the first two minutes of a track, and it is now refusing nearly everything on
 * the basis of tail windows it was never calibrated against. Settling that needs hundreds of
 * tracks, which on a phone means playing hundreds of tracks.
 *
 * Decoding is deliberately not done here. ffmpeg already knows every container in a music library
 * and can seek and window in one invocation, so the split is:
 *
 *   ffmpeg -v quiet -ss <start> -t <len> -i <file> -f f32le -ac 1 -ar 44100 - | analyse_stdin 44100
 *
 * which keeps this program to the part that is actually Fincord's, and keeps it byte-identical in
 * behaviour to the Android path - the same source file, fed the same mono float samples.
 *
 * Output is one tab-separated line so a shell loop can build a table without a parser:
 *
 *   seconds  bpm  tempo_confidence  beats  downbeat_index  downbeat_confidence  key  strength  doubled_from  first_downbeat_s
 *
 * The last column is where the *first downbeat* of this window falls, in seconds from its start.
 * It is there because it is the one number the transition needs that none of the others imply:
 * every phrase boundary is counted forward from it, and whether a mix can happen at all comes down
 * to whether one of those boundaries lands in the few seconds before the track ends.
 */

#include <stdio.h>
#include <stdlib.h>

#include "automix_analyze.h"

/* Read in blocks rather than one sample at a time; the analyser buffers to its own hop anyway. */
#define BLOCK 8192

int main(int argc, char **argv) {
    const int samplerate = (argc > 1) ? atoi(argv[1]) : 44100;
    if (samplerate <= 0) {
        fprintf(stderr, "usage: %s [samplerate] < mono-f32le\n", argv[0]);
        return 2;
    }

    automix_analyzer_t *a = automix_analyzer_new(samplerate);
    if (!a) {
        fprintf(stderr, "could not create analyzer\n");
        return 2;
    }

    static float block[BLOCK];
    size_t got;
    while ((got = fread(block, sizeof(float), BLOCK, stdin)) > 0) {
        automix_analyzer_feed(a, block, got);
    }

    automix_result_t r;
    if (!automix_analyzer_finish(a, &r)) {
        fprintf(stderr, "analysis failed\n");
        automix_analyzer_free(a);
        return 1;
    }

    /*
     * %g for the confidences, not %f. The whole reason this program exists is a value that printed
     * as "0.12" against a threshold of 0.12 and read as a broken comparison, and another that was
     * NaN and passed every test written the obvious way round. A format that shows 7.8e-06 and NaN
     * as themselves is the point.
     */
    const float first_downbeat_s =
        (r.beats_s && r.downbeat_index >= 0 && r.downbeat_index < r.beat_count)
            ? r.beats_s[r.downbeat_index]
            : -1.0f;

    printf("%.1f\t%.3f\t%g\t%d\t%d\t%g\t%d%s\t%g\t%g\t%.4f\n",
           r.analysed_seconds,
           r.bpm,
           r.tempo_confidence,
           r.beat_count,
           r.downbeat_index,
           r.downbeat_confidence,
           r.key_pitch_class,
           r.key_is_major ? "M" : "m",
           r.key_strength,
           r.bpm_before_doubling,
           first_downbeat_s);

    automix_analyzer_free(a);
    return 0;
}
