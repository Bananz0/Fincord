package uk.akane.accord.ui.components.lyrics

@JvmInline
value class Lyrics(val lyrics: List<LyricsLine>) {
    companion object {
        val Empty = Lyrics(emptyList())
    }

    /**
     * Inserts a counting-dots line into every stretch of the song with nothing to sing.
     *
     * A lyric sheet that opens on its first line and holds it through a twenty-second intro says
     * nothing about whether the song has started, whether the timings are right, or how long the
     * wait is - and the same is true of the gap between verses. The dots answer all three by
     * filling across the gap: one, two, three, and the words arrive.
     *
     * The intro gets a shorter threshold than a mid-song gap. An intro is the case where the user
     * has just opened the sheet and has no other evidence that anything is working, while a gap
     * between two verses is surrounded by lines that already proved it.
     */
    fun withInterludes(): Lyrics {
        if (lyrics.isEmpty()) return this
        val built = ArrayList<LyricsLine>(lyrics.size + 4)
        lyrics.forEachIndexed { index, line ->
            val gapStart = if (index == 0) 0L else lyrics[index - 1].endOfLine()
            val threshold = if (index == 0) INTRO_INTERLUDE_MS else GAP_INTERLUDE_MS
            if (line.timestamp - gapStart >= threshold) {
                built += LyricsLine(
                    timestamp = gapStart,
                    agent = null,
                    text = "",
                    background = null,
                    interludeEndMs = line.timestamp,
                )
            }
            built += line
        }
        return if (built.size == lyrics.size) this else Lyrics(built)
    }
}

/**
 * When a line stops being sung.
 *
 * Word timings give a real end. Without them the only honest answer is the line's own timestamp,
 * which makes a gap look longer than it is - so plain LRC needs the longer threshold it gets.
 */
private fun LyricsLine.endOfLine(): Long =
    wordTimings.lastOrNull()?.endTimestamp ?: timestamp

/** An intro worth counting down. Shorter than a mid-song gap; see [Lyrics.withInterludes]. */
private const val INTRO_INTERLUDE_MS = 5_000L

/** Between two verses, long enough that the sheet would otherwise look stuck. */
private const val GAP_INTERLUDE_MS = 8_000L

data class LyricsLine(
    val timestamp: Long,
    val agent: String?,
    val text: String,
    val background: String?,
    /**
     * Progressive ranges from Enhanced LRC. Each range owns the text after the preceding range
     * and through [LyricsWordTiming.endOffset], and fills between its two timestamps.
     */
    val wordTimings: List<LyricsWordTiming> = emptyList(),
    /**
     * When set, this is not a lyric but the wait before the next one, ending at this timestamp.
     * It is drawn as three filling dots rather than as text. See [Lyrics.withInterludes].
     */
    val interludeEndMs: Long? = null,
) {
    val isInterlude: Boolean
        get() = interludeEndMs != null

    /** How far through the wait [positionMs] is, 0 at its start and 1 as the next line begins. */
    fun interludeProgressAt(positionMs: Long): Float {
        val end = interludeEndMs ?: return 0f
        val span = (end - timestamp).coerceAtLeast(1L)
        return ((positionMs - timestamp).toFloat() / span).coerceIn(0f, 1f)
    }

    val isMain: Boolean
        get() = agent == null ||
                agent == "v1" ||
                agent == "1" ||
                agent == "M"

    /** Character offset to which the sung colour should be painted at [positionMs]. */
    fun highlightOffsetAt(positionMs: Long): Float {
        if (wordTimings.isEmpty()) return if (positionMs >= timestamp) text.length.toFloat() else 0f

        var startOffset = 0
        wordTimings.forEach { timing ->
            val endOffset = timing.endOffset.coerceIn(startOffset, text.length)
            if (positionMs < timing.startTimestamp) return startOffset.toFloat()

            val duration = timing.endTimestamp - timing.startTimestamp
            if (positionMs <= timing.endTimestamp && duration > 0L) {
                val fill = wordFillDurationMs(duration, endOffset - startOffset)
                val fraction = ((positionMs - timing.startTimestamp).toFloat() / fill)
                    .coerceIn(0f, 1f)
                return startOffset + (endOffset - startOffset) * fraction
            }
            startOffset = endOffset
        }
        return text.length.toFloat()
    }
}

data class LyricsWordTiming(
    val endOffset: Int,
    val startTimestamp: Long,
    val endTimestamp: Long,
)

/**
 * How long the sung colour takes to cross one word.
 *
 * Every comparable player sweeps a word across the whole of its own duration, and that is what
 * this does whenever the duration is believable. The reason it cannot simply always do that is a
 * property of Enhanced LRC: the format marks only where each segment *starts*, so a segment's end
 * is taken to be the next segment's start. Where the singer pauses, or the line ends and the band
 * plays on, that turns a word sung in a third of a second into a "word" three seconds long, and
 * the colour crawls through it - which is the stall this used to be about.
 *
 * A flat 300 ms cap was the previous answer, and it traded that stall for a different one: any
 * word genuinely held longer than 300 ms lit in a rush and then froze, which is what reads as a
 * stutter on a slow song. Scaling the cap by how much text there is to cross separates the two
 * cases without needing an end time the file never had. Roughly a syllable's worth of time per
 * character is the most any singer spends, so anything past that is a gap rather than a note.
 */
internal fun wordFillDurationMs(durationMs: Long, chars: Int): Long =
    durationMs.coerceAtMost(chars.coerceAtLeast(1) * MAX_MS_PER_CHAR)

/** Slower than any sung syllable; past this the timing is describing a pause, not a note. */
private const val MAX_MS_PER_CHAR = 140L
