package org.akanework.gramophone.logic.data.playcounts

import androidx.annotation.StringRes
import uk.akane.accord.R

/**
 * A service play counts can be imported from.
 *
 * The [id] is written into the ledger and must never change: it is what ties a stored contribution
 * to the service that produced it, and renaming one would strand its rows, so a later import would
 * count the same history a second time.
 *
 * Only Last.fm can be read over the network. The rest publish nothing resembling a play count -
 * Spotify's API offers the last fifty plays and an unranked top-tracks list, Apple and YouTube
 * offer neither - so their history only exists inside the archive a user requests from them, and
 * those sources are fed a file instead.
 */
enum class PlayCountSource(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val kind: Kind,
) {
    LAST_FM(
        id = "lastfm",
        labelRes = R.string.import_source_lastfm,
        descriptionRes = R.string.import_source_lastfm_summary,
        kind = Kind.NETWORK,
    ),
    SPOTIFY(
        id = "spotify",
        labelRes = R.string.import_source_spotify,
        descriptionRes = R.string.import_source_spotify_summary,
        kind = Kind.ARCHIVE,
    ),
    APPLE_MUSIC(
        id = "applemusic",
        labelRes = R.string.import_source_apple,
        descriptionRes = R.string.import_source_apple_summary,
        kind = Kind.ARCHIVE,
    ),
    YOUTUBE_MUSIC(
        id = "ytmusic",
        labelRes = R.string.import_source_ytmusic,
        descriptionRes = R.string.import_source_ytmusic_summary,
        kind = Kind.ARCHIVE,
    ),
    ;

    enum class Kind {
        /** Read directly from the service. Can be re-run whenever, and picks up only what is new. */
        NETWORK,

        /** Read from an export the user downloads from the service and hands over. */
        ARCHIVE,
    }

    companion object {
        fun fromId(id: String): PlayCountSource? = entries.firstOrNull { it.id == id }
    }
}
