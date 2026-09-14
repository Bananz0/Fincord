package org.akanework.gramophone.logic.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

const val ANALYSED_TRACK_TABLE_NAME = "analysed_track"

/**
 * What Automix's beat and key analysis found in one track.
 *
 * Cached because the analysis is worth keeping but not worth repeating: it costs a decode of the
 * front of the track, and a track heard twice has not changed in between. Keyed by the same media
 * id the queue and the play reporter use, so a row can be looked up from a queue item with nothing
 * else in hand.
 *
 * Rows are cheap - a few hundred bytes each, dominated by [beatsMs] - and only accumulate for
 * tracks that were actually approached with Automix on, so there is no sweep over this table.
 */
@Entity(tableName = ANALYSED_TRACK_TABLE_NAME)
data class AnalysedTrack(
    /** Jellyfin's track GUID, undashed - the same string as `MediaItem.mediaId`. */
    @PrimaryKey val jellyfinId: String,

    /** Beats per minute, or `0` when no steady beat was found. */
    val bpm: Float,
    /** How settled the beat tracker was. Low means the tempo below is a guess. */
    val tempoConfidence: Float,

    /**
     * Beat positions in milliseconds from the start of the track, ascending, packed
     * little-endian.
     *
     * A blob rather than a joined string. Both are about the same size, but a blob is the shape the
     * data already has on both sides of the boundary, so reading it back is a buffer view instead
     * of splitting and parsing a few hundred substrings on the way to a transition that is already
     * being timed to the millisecond.
     */
    val beatsMs: ByteArray,

    val beatsPerBar: Int,
    /** Which of the first [beatsPerBar] beats is a downbeat; the rest follow every [beatsPerBar]. */
    val downbeatIndex: Int,
    val downbeatConfidence: Float,

    /** `0` = C through `11` = B, or `-1` when the track could not be placed. */
    val keyPitchClass: Int,
    val keyIsMajor: Boolean,
    val keyStrength: Float,

    /**
     * How many seconds of audio the analysis actually saw.
     *
     * Recorded because it is almost never the whole track, and because it is the first thing to
     * look at when a result is wrong: a tempo drawn from twenty seconds of an intro deserves less
     * trust than one drawn from two minutes, and neither the BPM nor the confidence says which
     * happened.
     */
    val analysedSeconds: Float,

    /** Unix millis. Only ever read by a human working out when a bad row was written. */
    val analysedAt: Long,

    /**
     * Which version of the analyser wrote this row.
     *
     * Any change to the DSP that would move the numbers must raise it, and rows below the current
     * value are treated as absent rather than migrated - there is nothing to migrate, because the
     * only way to get the new answer is to run the new code over the audio again.
     */
    val analyserVersion: Int,
) {

    /** The packed [beatsMs] as the array everything downstream wants. */
    fun beatTimesMs(): IntArray {
        val buffer = ByteBuffer.wrap(beatsMs).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        return IntArray(buffer.remaining()).also(buffer::get)
    }

    // Room's data class gives value equality to every field, which for a ByteArray is identity -
    // so two rows read back from the same query would compare unequal. Nothing relies on comparing
    // these, but leaving the trap armed for whoever later writes a distinctBy over them is worse
    // than the few lines it costs to close.
    override fun equals(other: Any?): Boolean =
        other is AnalysedTrack && jellyfinId == other.jellyfinId &&
            bpm == other.bpm && tempoConfidence == other.tempoConfidence &&
            beatsMs.contentEquals(other.beatsMs) &&
            beatsPerBar == other.beatsPerBar && downbeatIndex == other.downbeatIndex &&
            downbeatConfidence == other.downbeatConfidence &&
            keyPitchClass == other.keyPitchClass && keyIsMajor == other.keyIsMajor &&
            keyStrength == other.keyStrength && analysedSeconds == other.analysedSeconds &&
            analysedAt == other.analysedAt && analyserVersion == other.analyserVersion

    override fun hashCode(): Int = 31 * jellyfinId.hashCode() + beatsMs.contentHashCode()

    companion object {
        /**
         * Raise this whenever the analysis would produce different numbers for the same audio.
         *
         * 1: aubio 0.4.9 beat tracking, four-four downbeat from low-band energy and spectral flux,
         *    Krumhansl-Schmuckler key over a 4096-point chromagram.
         * 2: extend aubio's settled grid back through its lock-on delay, correct detectable
         *    half-time octave errors, and assign downbeat phase by grid time so false detections do
         *    not shift every later bar.
         * 3: drop duplicate detections and interpolate dropped ones so the grid is one beat per
         *    pulse, and stop the half-time correction above 90, where doubling produces a tempo no
         *    genre is counted at and was observed firing wrongly on real audio.
         * 4: replace the detections with a constant-tempo grid regressed onto them, rather than
         *    repairing them in place. Tempo is an order of magnitude more precise - within 0.04 BPM
         *    across the test matrix, where averaging intervals left it 0.5% out, which is over a
         *    beat of drift across two minutes.
         * 5: snap a fitted tempo to the nearest 0.25 BPM when it is already within 0.06 of one, and
         *    drop non-finite samples from float decodes. The snap takes the residual tempo error to
         *    exactly zero on every test case, which matters because the tempo is extrapolated to a
         *    transition point minutes past the analysed window.
         * 6: clamp a non-finite tempo confidence to zero. aubio was observed on device returning
         *    NaN, and this is a version bump rather than a quiet fix because rows carrying one are
         *    already written. A stored row is permanent and suppresses re-analysis, so a track that
         *    got a NaN would decline forever and never be measured again; treating those rows as
         *    absent is the only way to get the track looked at a second time.
         * 7: clamp a *negative* tempo confidence to zero as well. Surveying 150 tracks of a real
         *    library put aubio's confidence between -13.77 and 8.47 - it is an unbounded score and
         *    not the 0..1 correlation every comment here assumed. Same reasoning as 6: a row
         *    written by version 6 can carry a negative figure, and a track holding one would
         *    decline for good.
         * 8: stop asking the platform decoder for float output. This phone accepts the request,
         *    reports ENCODING_PCM_FLOAT back, and does not deliver float - every decode that took
         *    that path came back with a peak of infinity and an analysis of nothing, while the
         *    same file through ffmpeg gives 124.84 BPM at confidence 0.259. Sixteen bits is ninety
         *    decibels of range for a job that is looking for where the drums are. Rows written by
         *    any earlier version may have come from a poisoned decode and cannot be told apart
         *    from a genuinely unreadable track, so they go.
         */
        const val ANALYSER_VERSION = 8

        /** Packs beat times for [beatsMs]. */
        fun packBeats(beatsMs: IntArray): ByteArray =
            ByteBuffer.allocate(beatsMs.size * Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply { asIntBuffer().put(beatsMs) }
                .array()
    }
}
