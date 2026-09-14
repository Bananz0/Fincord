package uk.akane.accord.ui.fragments.browse

/**
 * Where the disc headings go in an album's track list.
 *
 * Kept apart from the adapter, and expressed over disc numbers rather than tracks, because this is
 * where the arithmetic that can be wrong lives: a heading occupies a row without being a track, so
 * from the first heading onwards the adapter position and the track index diverge - and everything
 * that reads a row by position (tapping to play, swiping to queue) is wrong by the number of
 * headings above it if that mapping is not carried explicitly.
 */
internal object AlbumTrackPlan {

    sealed interface Entry {
        /** A "Disc N" heading. */
        data class Disc(val disc: Int) : Entry

        /**
         * @param index the track's position in the album, which is what playback is given.
         * @param endsDisc the last track of its disc, whose rule is dropped so a heading is not
         *   introduced by a hairline belonging to the disc above it.
         */
        data class Track(val index: Int, val endsDisc: Boolean) : Entry
    }

    /**
     * The rows for an album whose tracks are on [discs], in running order.
     *
     * Headings appear only when there is more than one disc. A single-disc album is the
     * overwhelming majority, and "Disc 1" standing alone above every record in the library would
     * be decoration standing in for information.
     */
    fun of(discs: List<Int>): List<Entry> {
        val multiDisc = discs.distinct().size > 1
        val entries = ArrayList<Entry>(discs.size + if (multiDisc) discs.distinct().size else 0)
        var current: Int? = null
        discs.forEachIndexed { index, disc ->
            if (multiDisc && disc != current) {
                entries.add(Entry.Disc(disc))
                current = disc
            }
            entries.add(
                Entry.Track(
                    index = index,
                    endsDisc = multiDisc && discs.getOrNull(index + 1) != disc,
                )
            )
        }
        return entries
    }
}
