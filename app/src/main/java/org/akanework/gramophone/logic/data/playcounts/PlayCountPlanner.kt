package org.akanework.gramophone.logic.data.playcounts

import android.content.Context
import android.util.Log
import org.akanework.gramophone.logic.data.db.AppDatabase
import org.akanework.gramophone.logic.data.db.entity.CachedSong

/**
 * Works out what an import would change, without changing anything.
 *
 * The arithmetic that makes repeated imports safe lives here, and it is worth stating plainly:
 *
 *     baseline    = what Jellyfin holds now - what every source contributed last time
 *     newTotal    = baseline + every source's contribution, this one's revised
 *
 * Deriving the baseline afresh on each run is the whole trick. Plays racked up through Jellyfin
 * since the last import are, by construction, not attributable to any source, so they land in the
 * baseline and survive. Re-running a source revises only its own share, and a source that has
 * nothing new to say revises it to the same number and changes nothing at all.
 *
 * The one way this could still inflate is a play this app itself reported to Jellyfin and scrobbled
 * to Last.fm, which would come back as history and be counted a second time on top of the organic
 * increment. Those are recognised by timestamp and dropped before they are ever tallied.
 */
class PlayCountPlanner(private val context: Context) {

    /**
     * Turns a source's raw scrobble stream into a plan.
     *
     * [plays] is consumed once and may be large, so it is a sequence: a decade of Last.fm history
     * is hundreds of thousands of rows and only the per-track tally needs to survive the walk.
     */
    fun plan(
        source: PlayCountSource,
        plays: Sequence<TimedPlay>,
        library: List<CachedSong>,
    ): ImportPlan {
        val ownPlays = ownPlayIndex()
        var deduped = 0
        var read = 0
        var newest = 0L

        // Tallied by the source's own naming, before any matching. Two spellings of the same track
        // will be merged later by resolving to the same library item.
        val tally = HashMap<String, MutableTally>()
        plays.forEach { play ->
            read++
            if (play.timestampSeconds > newest) newest = play.timestampSeconds
            if (ownPlays.claims(play)) {
                deduped++
                return@forEach
            }
            val key = TrackKey.exact(play.artist, play.title)
            val entry = tally.getOrPut(key) { MutableTally(play.artist, play.title, play.album) }
            entry.plays++
            if (play.timestampSeconds > entry.lastPlayed) entry.lastPlayed = play.timestampSeconds
            if (entry.album == null) entry.album = play.album
        }

        return build(source, tally.values, library, deduped, read, newest)
    }

    /** For archive sources, which arrive already aggregated per track. */
    fun planAggregated(
        source: PlayCountSource,
        tracks: List<ImportedTrack>,
        library: List<CachedSong>,
    ): ImportPlan {
        val tally = tracks.map {
            MutableTally(it.artist, it.title, it.album).apply {
                plays = it.plays
                lastPlayed = it.lastPlayedSeconds
            }
        }
        return build(
            source = source,
            tallies = tally,
            library = library,
            deduped = 0,
            read = tracks.sumOf { it.plays },
            newest = tracks.maxOfOrNull { it.lastPlayedSeconds } ?: 0L,
        )
    }

    private fun build(
        source: PlayCountSource,
        tallies: Collection<MutableTally>,
        library: List<CachedSong>,
        deduped: Int,
        read: Int,
        newest: Long,
    ): ImportPlan {
        val matcher = PlayCountMatcher.from(library)
        val currentById = library.associateBy({ it.jellyfinId }, { it })

        // Several source spellings can resolve to one library track, so the per-track totals are
        // summed after matching rather than before.
        val resolved = HashMap<String, ResolvedTally>()
        val unmatched = mutableListOf<UnmatchedTrack>()

        tallies.forEach { entry ->
            val match = matcher.match(entry.artist, entry.title, entry.album)
            if (match == null) {
                unmatched += UnmatchedTrack(entry.artist, entry.title, entry.plays)
                return@forEach
            }
            val existing = resolved[match.jellyfinId]
            if (existing == null) {
                resolved[match.jellyfinId] = ResolvedTally(
                    plays = entry.plays,
                    lastPlayed = entry.lastPlayed,
                    confidence = match.confidence,
                )
            } else {
                existing.plays += entry.plays
                if (entry.lastPlayed > existing.lastPlayed) existing.lastPlayed = entry.lastPlayed
                // The weakest evidence for any part of the total is what the whole is worth.
                if (match.confidence > existing.confidence) existing.confidence = match.confidence
            }
        }

        val previous = previousContributions(resolved.keys.toList())
        val changes = resolved.mapNotNull { (jellyfinId, tally) ->
            val song = currentById[jellyfinId] ?: return@mapNotNull null
            val contributions = previous[jellyfinId].orEmpty()
            val alreadyCounted = contributions.values.sum()
            // Cannot go below zero: a user who cleared their Jellyfin counts by hand would
            // otherwise give us a negative baseline that eats the new import.
            val baseline = (song.playCount - alreadyCounted).coerceAtLeast(0)

            // This source's share is replaced, not added to. On an incremental run the fetched
            // window only holds what is new, so the previous share is carried and topped up; on a
            // full re-read the window holds everything and the tally already is the whole share.
            val ours = if (isIncremental(source)) {
                (contributions[source.id] ?: 0) + tally.plays
            } else {
                tally.plays
            }
            val others = contributions.filterKeys { it != source.id }.values.sum()
            val newTotal = baseline + others + ours

            PlannedChange(
                jellyfinId = jellyfinId,
                title = song.title.orEmpty(),
                artist = song.artist ?: song.albumArtist.orEmpty(),
                currentTotal = song.playCount,
                newTotal = newTotal,
                sourceContribution = ours,
                lastPlayedSeconds = maxOf(tally.lastPlayed, song.lastPlayed ?: 0L),
                confidence = tally.confidence,
            )
        }

        Log.d(
            TAG,
            "Planned ${source.id}: read $read, deduped $deduped, " +
                "matched ${changes.size}, unmatched ${unmatched.size}"
        )

        return ImportPlan(
            source = source,
            changes = changes.sortedByDescending { it.newTotal - it.currentTotal },
            deduped = deduped,
            unmatched = unmatched.sortedByDescending { it.plays }.take(MAX_UNMATCHED_SHOWN),
            throughSeconds = newest,
            scrobblesRead = read,
        )
    }

    /**
     * Whether this source is read as a window since the watermark, or in full every time.
     *
     * Network sources are asked only for what is new, so their tally is a top-up. An archive is
     * always the user's whole history to date, so its tally replaces the previous share rather
     * than adding to it - re-importing a later export must not double what the earlier one said.
     */
    private fun isIncremental(source: PlayCountSource): Boolean =
        source.kind == PlayCountSource.Kind.NETWORK

    private fun previousContributions(jellyfinIds: List<String>): Map<String, Map<String, Int>> {
        if (jellyfinIds.isEmpty()) return emptyMap()
        val dao = AppDatabase.getInstance(context).importContributionDao()
        return jellyfinIds.chunked(SQL_VARIABLE_LIMIT)
            .flatMap { dao.getForTracks(it) }
            .groupBy { it.jellyfinId }
            .mapValues { (_, rows) -> rows.associate { it.source to it.count } }
    }

    /**
     * Our own reported plays, indexed by track and time.
     *
     * Bucketed to the minute rather than compared exactly. The scrobble carries the timestamp this
     * app generated, so the two should be identical - but a queued scrobble that failed and was
     * retried, or a clock corrected between the play and the submission, shifts it slightly, and
     * a near miss here silently double-counts.
     */
    private fun ownPlayIndex(): OwnPlayIndex {
        val dao = AppDatabase.getInstance(context).ownPlayDao()
        val rows = try {
            dao.between(0, Long.MAX_VALUE)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read own plays; import will not dedupe", e)
            emptyList()
        }
        val buckets = HashSet<Long>(rows.size * 3)
        rows.forEach { row ->
            val bucket = row.playedAtSeconds / DEDUPE_BUCKET_SECONDS
            val hash = row.trackKey.hashCode().toLong()
            // The play is registered in its own bucket and both neighbours, so a scrobble landing
            // just the other side of a boundary still finds it.
            buckets += hash * 31 + bucket
            buckets += hash * 31 + bucket - 1
            buckets += hash * 31 + bucket + 1
        }
        return OwnPlayIndex(buckets)
    }

    private class OwnPlayIndex(private val buckets: Set<Long>) {
        fun claims(play: TimedPlay): Boolean {
            if (buckets.isEmpty()) return false
            val hash = TrackKey.exact(play.artist, play.title).hashCode().toLong()
            return buckets.contains(hash * 31 + play.timestampSeconds / DEDUPE_BUCKET_SECONDS)
        }
    }

    private class MutableTally(
        val artist: String,
        val title: String,
        var album: String?,
    ) {
        var plays: Int = 0
        var lastPlayed: Long = 0
    }

    private class ResolvedTally(
        var plays: Int,
        var lastPlayed: Long,
        var confidence: PlayCountMatcher.Confidence,
    )

    companion object {
        private const val TAG = "PlayCountPlanner"

        /** SQLite refuses a statement with more than 999 bound variables. */
        private const val SQL_VARIABLE_LIMIT = 900

        private const val DEDUPE_BUCKET_SECONDS = 60L

        /** Enough for the user to see what kind of thing is missing, not a full report. */
        private const val MAX_UNMATCHED_SHOWN = 200
    }
}

/** One play of one track, as an external service reports it. */
data class TimedPlay(
    val artist: String,
    val title: String,
    val album: String?,
    val timestampSeconds: Long,
)
