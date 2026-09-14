/*
 * Fincord - JNI bridge for Automix track analysis.
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See the LICENSE file at the root of this
 * repository.
 */

#include <jni.h>
#include <math.h>
#include <stdlib.h>

#include "automix_analyze.h"

#define ANALYSIS_CLASS "uk/akane/accord/automix/TrackAnalysis"

static automix_analyzer_t *from_handle(jlong handle) {
    return (automix_analyzer_t *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_uk_akane_accord_automix_NativeAnalyzer_nativeCreate(JNIEnv *env, jclass clazz,
                                                         jint samplerate) {
    (void) env;
    (void) clazz;
    return (jlong) (intptr_t) automix_analyzer_new(samplerate);
}

JNIEXPORT void JNICALL
Java_uk_akane_accord_automix_NativeAnalyzer_nativeFeed(JNIEnv *env, jclass clazz, jlong handle,
                                                       jfloatArray samples, jint count) {
    (void) clazz;
    automix_analyzer_t *analyzer = from_handle(handle);
    if (analyzer == NULL || samples == NULL || count <= 0) return;
    if (count > (*env)->GetArrayLength(env, samples)) return;

    /*
     * Critical rather than Get/ReleaseFloatArrayElements: this runs once per decoded buffer for the
     * length of a track, and the copy the ordinary accessor is entitled to make would be the
     * largest single cost in the whole analysis. Nothing between the two calls touches the JNI
     * environment or allocates, which is what the critical region requires.
     */
    void *raw = (*env)->GetPrimitiveArrayCritical(env, samples, NULL);
    if (raw == NULL) return;
    automix_analyzer_feed(analyzer, (const float *) raw, (size_t) count);
    (*env)->ReleasePrimitiveArrayCritical(env, samples, raw, JNI_ABORT);
}

JNIEXPORT jobject JNICALL
Java_uk_akane_accord_automix_NativeAnalyzer_nativeFinish(JNIEnv *env, jclass clazz, jlong handle) {
    (void) clazz;
    automix_analyzer_t *analyzer = from_handle(handle);
    if (analyzer == NULL) return NULL;

    automix_result_t result;
    if (!automix_analyzer_finish(analyzer, &result)) return NULL;

    jclass analysis = (*env)->FindClass(env, ANALYSIS_CLASS);
    if (analysis == NULL) return NULL;
    jmethodID init = (*env)->GetMethodID(env, analysis, "<init>", "(FF[IIIFIZFFF)V");
    if (init == NULL) return NULL;

    /*
     * Beats cross to Kotlin as whole milliseconds. A millisecond is about a twentieth of a sample
     * period's worth of audible difference at these tempos and far below the accuracy of the beat
     * tracker itself, so the precision lost is imaginary, while the integers halve what the beat
     * grid costs in the database and are legible in a sqlite dump.
     */
    jintArray beats = (*env)->NewIntArray(env, result.beat_count);
    if (beats == NULL) return NULL;
    if (result.beat_count > 0) {
        jint *scratch = (jint *) malloc((size_t) result.beat_count * sizeof(jint));
        if (scratch == NULL) return NULL;
        for (int i = 0; i < result.beat_count; i++) {
            scratch[i] = (jint) lrintf(result.beats_s[i] * 1000.0f);
        }
        (*env)->SetIntArrayRegion(env, beats, 0, result.beat_count, scratch);
        free(scratch);
    }

    return (*env)->NewObject(env, analysis, init,
                             (jfloat) result.bpm,
                             (jfloat) result.tempo_confidence,
                             beats,
                             (jint) result.beats_per_bar,
                             (jint) result.downbeat_index,
                             (jfloat) result.downbeat_confidence,
                             (jint) result.key_pitch_class,
                             (jboolean) (result.key_is_major ? JNI_TRUE : JNI_FALSE),
                             (jfloat) result.key_strength,
                             (jfloat) result.analysed_seconds,
                             (jfloat) result.bpm_before_doubling);
}

JNIEXPORT void JNICALL
Java_uk_akane_accord_automix_NativeAnalyzer_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    automix_analyzer_free(from_handle(handle));
}
