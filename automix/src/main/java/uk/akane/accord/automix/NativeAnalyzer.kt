/*
 * Copyright (C) 2026 the Fincord contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package uk.akane.accord.automix

import java.io.Closeable

/**
 * Streams mono PCM at a native beat and key analyser and collects the answer.
 *
 * Streaming rather than one call over the whole track on purpose: two minutes of 44.1 kHz audio is
 * a twenty-megabyte float array, and there is no reason to hold one when the analysis only ever
 * looks at 512 samples at a time. The caller feeds each decoded buffer straight through.
 *
 * Not thread-safe, and holds native memory: use it from one thread and close it, which
 * [analyse] does for you.
 */
class NativeAnalyzer private constructor(private var handle: Long) : Closeable {

    /**
     * Hands [samples] to the analyser. Values are expected in `[-1, 1]` and mono - one channel, not
     * one channel per frame - because everything downstream is about rhythm and pitch, neither of
     * which is a stereo property.
     */
    fun feed(samples: FloatArray, count: Int = samples.size) {
        check(handle != 0L) { "NativeAnalyzer used after close" }
        nativeFeed(handle, samples, count)
    }

    /** The result over everything fed so far, or `null` if the native side failed. */
    fun finish(): TrackAnalysis? {
        check(handle != 0L) { "NativeAnalyzer used after close" }
        return nativeFinish(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        /**
         * Whether the native library loaded.
         *
         * Analysis is an enhancement, so a device whose ABI this build does not carry should lose
         * Automix and keep its music, rather than taking the process down at the first transition.
         */
        val isAvailable: Boolean = runCatching { System.loadLibrary("automix") }.isSuccess

        /**
         * Opens an analyser for audio at [sampleRate], or `null` when the native side is
         * unavailable or the rate is not one it will accept.
         */
        fun open(sampleRate: Int): NativeAnalyzer? {
            if (!isAvailable) return null
            val handle = nativeCreate(sampleRate)
            return if (handle != 0L) NativeAnalyzer(handle) else null
        }

        /** Runs [feeder] against a fresh analyser and closes it, whatever [feeder] does. */
        inline fun analyse(sampleRate: Int, feeder: (NativeAnalyzer) -> Unit): TrackAnalysis? =
            open(sampleRate)?.use {
                feeder(it)
                it.finish()
            }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int): Long

        @JvmStatic
        private external fun nativeFeed(handle: Long, samples: FloatArray, count: Int)

        @JvmStatic
        private external fun nativeFinish(handle: Long): TrackAnalysis?

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
