package org.akanework.gramophone.logic.data.playcounts

/** One track's worth of history from a source, already tallied. */
data class ImportedTrack(
    val artist: String,
    val title: String,
    val album: String?,
    val plays: Int,
    /** Newest play, epoch seconds, for Jellyfin's LastPlayedDate. */
    val lastPlayedSeconds: Long,
)

/**
 * Everything an import intends to do, computed before anything is written.
 *
 * An import changes numbers on a server, where the user cannot see the change happen and has no
 * undo. So it is worked out in full and shown first: the plan is the thing the user agrees to, and
 * [PlayCountWriter] does no thinking of its own.
 */
data class ImportPlan(
    val source: PlayCountSource,
    val changes: List<PlannedChange>,
    /** Scrobbles ignored because this app had already reported them to Jellyfin. */
    val deduped: Int,
    /** Plays whose track is not in the library, with the most frequent shown to the user. */
    val unmatched: List<UnmatchedTrack>,
    /** The newest play seen, which becomes the watermark once the write succeeds. */
    val throughSeconds: Long,
    val scrobblesRead: Int,
) {
    val tracksChanged: Int get() = changes.count { it.newTotal != it.currentTotal }
    val playsAdded: Int get() = changes.sumOf { (it.newTotal - it.currentTotal).coerceAtLeast(0) }
    val matched: Int get() = changes.size
    val unmatchedPlays: Int get() = unmatched.sumOf { it.plays }
}

data class PlannedChange(
    val jellyfinId: String,
    val title: String,
    val artist: String,
    /** What Jellyfin holds now. */
    val currentTotal: Int,
    /** baseline + every source's contribution, including this source's revised share. */
    val newTotal: Int,
    /** This source's share after the run, which is what gets written to the ledger. */
    val sourceContribution: Int,
    val lastPlayedSeconds: Long,
    val confidence: PlayCountMatcher.Confidence,
)

data class UnmatchedTrack(
    val artist: String,
    val title: String,
    val plays: Int,
)
