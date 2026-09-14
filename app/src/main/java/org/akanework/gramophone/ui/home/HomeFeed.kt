package org.akanework.gramophone.ui.home

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import org.akanework.gramophone.logic.data.jellyfin.JellyfinLibraryLoader
import org.akanework.gramophone.logic.data.lastfm.LastFmClient
import org.akanework.gramophone.logic.data.lastfm.LastFmCredentialStore
import org.akanework.gramophone.logic.data.matching.MusicText
import uk.akane.accord.R
import uk.akane.accord.ui.adapters.BannerItem
import java.util.Calendar
import kotlin.math.roundToInt

enum class HomeSectionStyle { ROW, STATION }

enum class HomeCardTarget { MIX, ALBUM }

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val cards: List<HomeCard>,
    val style: HomeSectionStyle = HomeSectionStyle.ROW,
)

data class HomeCard(
    val title: String,
    val subtitle: String?,
    val cover: Uri?,
    val collageCovers: List<Uri> = emptyList(),
    val songs: List<MediaItem>,
    val startIndex: Int = 0,
    val cachedMediaIds: List<String> = emptyList(),
    val target: HomeCardTarget = HomeCardTarget.MIX,
) {
    val mediaIds: List<String>
        get() = songs.map { it.mediaId }.ifEmpty { cachedMediaIds }
}

/** Builds a deterministic home feed from the listening signals already stored by Jellyfin. */
object HomeFeed {

    private const val ROW_SIZE = 12
    private const val MIX_SIZE = 50
    private const val MIN_MIX_SIZE = 5

    /**
     * How much more than a mix needs is shuffled before the running order is worked out.
     *
     * [spread] can only separate two tracks by the same artist if there is a third to put between
     * them, so it is given a few times the material it will keep and discards the rest.
     */
    private const val POOL_OVERSAMPLE = 3

    /** Banners are the full-bleed cards at the top of the feed; they hold a mix like any other. */
    private const val BANNER_SIZE = 50
    private const val BANNER_ARTIST_MIXES = 4

    /** Artist banners rotate daily through this many of the best-ranked artists. */
    private const val BANNER_ARTIST_POOL = 12

    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    /** How long an artist has to go unplayed before they count as worth resurfacing. */
    private const val QUIET_DAYS = 45L

    /** Most distinct covers a collage will show, and the tile budget shared between them. */
    private const val COLLAGE_SLICES = 6
    private const val COLLAGE_BUDGET = 12

    data class ArtistInput(val title: String?, val songList: List<MediaItem>)

    fun build(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): List<HomeSection> {
        if (library.isEmpty()) return emptyList()

        return buildList {
            jumpBackIn(context, library)?.let(::add)
            madeForYou(context, library, artists)?.let(::add)
            becauseYouListenedTo(library, artists)?.let(::add)
            recentlyAddedAlbums(context, library)?.let(::add)
            topMixes(context, artists)?.let(::add)
            genreMixes(library)?.let(::add)
            finishYourAlbums(library)?.let(::add)
            backInRotation(artists)?.let(::add)
            neverPlayedAlbums(library)?.let(::add)
            decadeMixes(library)?.let(::add)
        }
    }

    /**
     * One mix per artist worth building around, each seeded by that artist but reaching past them.
     *
     * Distinct from [topMixes], which stays inside a single artist's catalogue. These lean outward:
     * roughly a third of the mix is the seed artist and the rest is drawn from the genres they sit
     * in, which is the part that makes the row worth opening rather than a second artist shelf.
     */
    private fun becauseYouListenedTo(
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val seeds = artists
            .filter { it.title?.isNotBlank() == true && it.songList.size >= 3 }
            .filter { artist -> artist.songList.any { it.playCount() > 0 } }
            .sortedByDescending { artist -> artist.songList.sumOf { it.playCount() } }
            .take(ROW_SIZE)
        if (seeds.isEmpty()) return null

        val cards = seeds.mapNotNull { seed ->
            val seedName = seed.title ?: return@mapNotNull null
            val seedKey = seedName.normaliseForMatch()
            val seedGenres = seed.songList.mapNotNull { it.genreKey() }.toSet()

            val core = seed.songList
                .sortedByDescending { it.playCount() }
                .take(MIX_SIZE / 3)
            val neighbours = library
                .filter { it.artistKey() != seedKey && it.genreKey() in seedGenres }
                .stableShuffle(weeklySeed("because_$seedKey"))
                .balancedByArtist()

            val songs = (core + neighbours).asOrderedMix()
            if (songs.size < MIN_MIX_SIZE) return@mapNotNull null

            HomeCard(
                title = phrase(
                    "because_card_$seedKey",
                    "Because you listened to $seedName",
                    "More like $seedName",
                    "Inspired by $seedName",
                    "In the world of $seedName",
                    "If you like $seedName",
                ),
                subtitle = songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                    .distinct().take(4).joinToString(", "),
                cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                collageCovers = songs.weightedCovers(),
                songs = songs,
            )
        }.withoutNearDuplicates()
        if (cards.isEmpty()) return null

        return HomeSection(
            id = "because_you_listened_v2",
            title = phrase(
                "because_section",
                "Because you listened",
                "Built from your favourites",
                "Started from what you play",
                "Following your taste",
            ),
            subtitle = phrase(
                "because_section_sub",
                "Each mix starts with an artist you play and travels outward",
                "Seeded by the artists you return to, then widened",
                "One artist as a starting point, not the whole mix",
            ),
            cards = cards,
        )
    }

    /**
     * Artists with real history that have gone quiet, ranked by how long they have been silent.
     *
     * Deliberately not [finishYourAlbums]: that is about unheard tracks, this is about music the
     * user demonstrably liked and simply stopped reaching for.
     */
    private fun backInRotation(artists: List<ArtistInput>): HomeSection? {
        val now = System.currentTimeMillis()
        val stale = artists
            .filter { it.title?.isNotBlank() == true && it.songList.size >= MIN_MIX_SIZE }
            .mapNotNull { artist ->
                val plays = artist.songList.sumOf { it.playCount() }
                if (plays < 3) return@mapNotNull null
                val lastPlayed = artist.songList.maxOf { it.lastPlayed() }
                if (lastPlayed <= 0L) return@mapNotNull null
                val silentDays = (now - lastPlayed) / MILLIS_PER_DAY
                if (silentDays < QUIET_DAYS) null else Triple(artist, lastPlayed, silentDays)
            }
            .sortedByDescending { it.third }
            .take(ROW_SIZE)
        if (stale.isEmpty()) return null

        return HomeSection(
            id = "back_in_rotation_v2",
            title = phrase(
                "back_in_rotation",
                "Back in rotation",
                "You used to play these",
                "Long time no listen",
                "Worth another spin",
            ),
            subtitle = phrase(
                "back_in_rotation_sub",
                "Artists you loved that have gone quiet",
                "Plenty of history here, and nothing recent",
                "These have not come up in a while",
            ),
            cards = stale.map { (artist, _, silentDays) ->
                val title = artist.title.orEmpty()
                val songs = artist.songList.asMix(weeklySeed("stale_$title"))
                HomeCard(
                    title = "$title Mix",
                    subtitle = "Last played ${silentDays.describeGap()}",
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.weightedCovers(),
                    songs = songs,
                )
            },
        )
    }

    /** Whole albums that have never been touched - the part of a library that stays invisible. */
    private fun neverPlayedAlbums(library: List<MediaItem>): HomeSection? {
        val albums = library.toAlbumGroups()
            .filter { (_, tracks) -> tracks.size >= 4 && tracks.all { it.playCount() == 0 } }
            .sortedBy { (key, _) -> stableHash(weeklySeed("unplayed_albums"), key) }
            .take(ROW_SIZE)
        if (albums.isEmpty()) return null

        return HomeSection(
            id = "never_played_albums_v2",
            title = phrase(
                "never_played",
                "Still unopened",
                "Never played",
                "Waiting for a first listen",
                "The unexplored shelf",
            ),
            subtitle = phrase(
                "never_played_sub",
                "Albums in your library you have not started",
                "Nothing here has ever been played",
                "Full records, still untouched",
            ),
            cards = albums.map { (_, tracks) -> tracks.toAlbumCard() },
        )
    }

    /**
     * Every tile is an independently compiled playlist. This deliberately avoids the old pattern
     * where a row showed thirty covers that all opened the same backing queue at a different index.
     */
    private fun madeForYou(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val played = library.filter { it.playCount() > 0 }
        val favourites = library.filter { it.isFavourite() }
        val topArtists = artists
            .filter { it.title?.isNotBlank() == true }
            .sortedByDescending { artist -> artist.songList.sumOf { it.playCount() } }
        val topArtistNames = topArtists.take(8).mapNotNull { it.title?.normaliseForMatch() }.toSet()
        val likedGenres = library
            .filter { it.playCount() > 0 || it.isFavourite() }
            .mapNotNull { item -> item.genreKey()?.let { it to (item.playCount() + if (item.isFavourite()) 5 else 0) } }
            .groupBy({ it.first }, { it.second })
            .entries
            .sortedByDescending { entry -> entry.value.sum() }
            .take(6)
            .map { it.key }
            .toSet()

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..10 -> "morning"
            in 11..16 -> "afternoon"
            in 17..21 -> "evening"
            else -> "late night"
        }

        val cards = listOfNotNull(
            mixCard(
                title = phrase("card_anthems", "Personal anthems", "Your greatest hits", "On repeat"),
                subtitle = "Your biggest songs, balanced across the artists you love",
                songs = played.sortedByDescending { it.playCount() }.balancedByArtist(),
            ),
            mixCard(
                title = phrase("card_rotation", "Current rotation", "Lately", "This era"),
                subtitle = "The music defining your recent listening",
                songs = played.filter { it.lastPlayed() > 0L }
                    .sortedByDescending { it.lastPlayed() }
                    .take(MIX_SIZE * 3)
                    .balancedByArtist(),
            ),
            mixCard(
                title = "Loved essentials",
                subtitle = "A fresh sequence of songs you have favourited",
                songs = favourites.stableShuffle(weeklySeed("loved_essentials")),
            ),
            mixCard(
                title = "Forgotten favourites",
                subtitle = "Old favourites that have been waiting for another play",
                songs = favourites.filter { it.lastPlayed() > 0L }
                    .sortedBy { it.lastPlayed() }
                    .balancedByArtist(),
            ),
            mixCard(
                title = phrase("card_deep_cuts", "Deep cuts", "Hidden gems", "The B-sides"),
                subtitle = "Less-played tracks from the artists already in your orbit",
                songs = library.filter { item ->
                    item.artistKey() in topArtistNames && item.playCount() <= 1
                }.stableShuffle(weeklySeed("deep_cuts")),
            ),
            mixCard(
                title = phrase("card_new_to_you", "New to you", "Unheard territory", "Worth a try"),
                subtitle = "Unplayed tracks close to the genres you return to",
                songs = library.filter { item ->
                    item.playCount() == 0 &&
                        item.genreKey() in likedGenres &&
                        item.artistKey() !in topArtistNames.take(3)
                }.stableShuffle(weeklySeed("new_to_you")),
            ),
            mixCard(
                title = "Soundtrack your $partOfDay",
                subtitle = "A different corner of your library for right now",
                songs = library.stableShuffle(dailySeed("daylist_${hour / 4}")),
            ),
            mixCard(
                title = "Fresh arrivals",
                subtitle = "Recently added music, mixed across artists",
                songs = library.sortedByDescending { it.addDate() }
                    .take(MIX_SIZE * 3)
                    .balancedByArtist(),
            ),
            mixCard(
                title = "Album sampler",
                subtitle = "One doorway into every record in your collection",
                songs = library
                    .distinctBy { it.albumKey() ?: it.mediaId }
                    .stableShuffle(weeklySeed("album_sampler")),
            ),
        ).withoutNearDuplicates()

        if (cards.isEmpty()) return null
        return HomeSection(
            id = "made_for_you_v2",
            title = context.getString(R.string.home_made_for_you),
            subtitle = phrase(
                "made_for_you_sub",
                "Distinct mixes built from different parts of your listening",
                "Nine ways into your own library",
                "Each one drawn from a different signal",
            ),
            cards = cards,
            style = HomeSectionStyle.STATION,
        )
    }

    private fun mixCard(title: String, subtitle: String, songs: List<MediaItem>): HomeCard? {
        val selected = songs.asOrderedMix()
        if (selected.size < MIN_MIX_SIZE) return null
        return HomeCard(
            title = title,
            subtitle = subtitle,
            cover = null,
            collageCovers = selected.weightedCovers(),
            songs = selected,
        )
    }

    private fun jumpBackIn(context: Context, library: List<MediaItem>): HomeSection? {
        val played = library.filter { it.lastPlayed() > 0L }
        val source = if (played.isNotEmpty()) played.sortedByDescending { it.lastPlayed() }
        else library.sortedByDescending { it.addDate() }
        val albums = source.toAlbumGroups().take(ROW_SIZE)
        if (albums.isEmpty()) return null

        return HomeSection(
            id = "jump_back_in_v2",
            title = context.getString(R.string.home_jump_back_in),
            cards = albums.map { (_, tracks) -> tracks.toAlbumCard() },
        )
    }

    private fun recentlyAddedAlbums(context: Context, library: List<MediaItem>): HomeSection? {
        val albums = library
            .sortedByDescending { it.addDate() }
            .toAlbumGroups()
            .take(ROW_SIZE)
        if (albums.isEmpty()) return null

        return HomeSection(
            id = "recently_added_albums_v2",
            title = context.getString(R.string.mix_recently_added),
            subtitle = phrase(
                "recently_added_sub",
                "The newest records in your library",
                "Fresh off the server",
                "Just landed",
            ),
            cards = albums.map { (_, tracks) -> tracks.toAlbumCard() },
        )
    }

    private fun topMixes(context: Context, allArtists: List<ArtistInput>): HomeSection? {
        val artists = allArtists
            .filter { it.songList.size >= 3 && it.title?.isNotBlank() == true }
            .sortedByDescending { artist ->
                artist.songList.sumOf { it.playCount() }.takeIf { it > 0 } ?: artist.songList.size
            }
            .take(ROW_SIZE)
        if (artists.isEmpty()) return null

        return HomeSection(
            id = "top_mixes_v2",
            title = context.getString(R.string.home_top_mixes),
            subtitle = context.getString(R.string.home_top_mixes_subtitle),
            cards = artists.map { artist ->
                val title = artist.title ?: context.getString(R.string.unknown_artist)
                val songs = artist.songList.asMix(weeklySeed("artist_mix_$title"))
                HomeCard(
                    title = context.getString(R.string.home_artist_mix, title),
                    subtitle = songs.mapNotNull { it.mediaMetadata.albumTitle?.toString() }
                        .distinct().take(3).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.weightedCovers(),
                    songs = songs,
                )
            },
        )
    }

    private fun genreMixes(library: List<MediaItem>): HomeSection? {
        val genres = library
            .mapNotNull { item -> item.genreKey()?.let { key -> Triple(key, item.genreName(), item) } }
            .groupBy({ it.first }, { it })
            .values
            .filter { it.size >= MIN_MIX_SIZE }
            .sortedByDescending { genre ->
                genre.sumOf { it.third.playCount() } * 3 + genre.size
            }
            .take(ROW_SIZE)
        if (genres.isEmpty()) return null

        return HomeSection(
            id = "genre_mixes_v2",
            title = phrase(
                "genre_mixes",
                "Your genre mixes",
                "By the sound of it",
                "Pick a lane",
                "Sorted by sound",
            ),
            subtitle = phrase(
                "genre_mixes_sub",
                "Each mix stays inside a different sound in your library",
                "One mix per corner of your collection",
                "No genre-hopping - each stays put",
            ),
            cards = genres.map { entries ->
                val displayName = entries.first().second
                val songs = entries.map { it.third }
                    .asMix(weeklySeed("genre_${entries.first().first}"))
                HomeCard(
                    title = "$displayName Mix",
                    subtitle = songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                        .distinct().take(4).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.weightedCovers(),
                    songs = songs,
                )
            },
        )
    }

    private fun finishYourAlbums(library: List<MediaItem>): HomeSection? {
        val cards = library.toAlbumGroups()
            .mapNotNull { (_, tracks) ->
                val unheard = tracks.filter { it.playCount() == 0 }
                val playedCount = tracks.size - unheard.size
                if (tracks.size < 4 || playedCount == 0 || unheard.isEmpty()) return@mapNotNull null
                HomeCard(
                    title = tracks.albumTitle(),
                    subtitle = "${unheard.size} unheard · ${tracks.albumArtist()}",
                    cover = tracks.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    songs = unheard,
                )
            }
            .sortedByDescending { it.songs.size }
            .take(ROW_SIZE)
        if (cards.isEmpty()) return null

        return HomeSection(
            id = "finish_your_albums_v2",
            title = phrase(
                "finish_albums",
                "Finish what you started",
                "You are halfway through",
                "Pick up where you left off",
                "Unfinished business",
            ),
            subtitle = phrase(
                "finish_albums_sub",
                "Unheard tracks from albums you have already begun",
                "The parts of these records you have not reached",
                "Started but not finished",
            ),
            cards = cards,
        )
    }

    private fun decadeMixes(library: List<MediaItem>): HomeSection? {
        val decades = library.mapNotNull { item ->
            val year = item.mediaMetadata.releaseYear?.takeIf { it in 1950..2039 }
                ?: return@mapNotNull null
            (year / 10) * 10 to item
        }.groupBy({ it.first }, { it.second })
            .filterValues { it.size >= MIN_MIX_SIZE }
            .entries
            .sortedByDescending { it.key }
            .take(ROW_SIZE)
        if (decades.isEmpty()) return null

        return HomeSection(
            id = "decade_mixes_v2",
            title = phrase(
                "decade_mixes",
                "Time capsules",
                "By the decade",
                "Rewind",
                "Eras",
            ),
            subtitle = phrase(
                "decade_mixes_sub",
                "A separate playlist for every era in your collection",
                "Your library, split by when it was made",
                "One mix per decade you own",
            ),
            cards = decades.map { (decade, tracks) ->
                val songs = tracks.asMix(weeklySeed("decade_$decade"))
                HomeCard(
                    title = "${decade}s Mix",
                    subtitle = songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                        .distinct().take(4).joinToString(", "),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.weightedCovers(),
                    songs = songs,
                )
            },
        )
    }

    /**
     * The banner carousel at the top of the home screen.
     *
     * This used to be built separately from the shelves below it, and it showed. Two of its
     * banners fell back to `library.take(50)` whenever the signal behind them was missing - no
     * favourites yet, nothing played yet - which is not a mix but the first fifty rows of a sorted
     * library: fifty tracks whose titles all begin with the same letter, every alternate version
     * of the same song next to its twin, and the same fifty in two different banners. The artist
     * banners picked the four artists whose names sorted first. Everything here now goes through
     * [asMix], and a banner with nothing behind it is left out rather than filled with whatever
     * was at the top of the list.
     */
    fun banners(
        context: Context,
        library: List<MediaItem>,
        artists: List<ArtistInput>,
    ): List<BannerItem> {
        if (library.isEmpty()) return emptyList()

        val played = library.filter { it.playCount() > 0 }
        val favourites = library.filter { it.isFavourite() }
        val likedGenres = library
            .filter { it.playCount() > 0 || it.isFavourite() }
            .mapNotNull { it.genreKey() }
            .toSet()

        val banners = buildList {
            // The one banner that is always available: it needs no history, only a library.
            bannerItem(
                id = "daily_shuffle",
                title = context.getString(R.string.mix_daily_shuffle),
                fallbackSummary = context.getString(R.string.mix_daily_shuffle_subtitle),
                songs = library.asMix(dailySeed("daily_shuffle"), BANNER_SIZE),
            )?.let(::add)

            bannerItem(
                id = "heavy_rotation",
                title = context.getString(R.string.mix_most_played),
                fallbackSummary = null,
                songs = played.sortedByDescending { it.playCount() }.asOrderedMix(BANNER_SIZE),
            )?.let(::add)

            bannerItem(
                id = "favourites_mix",
                title = context.getString(R.string.mix_favourites),
                fallbackSummary = null,
                songs = favourites.asMix(weeklySeed("favourites_banner"), BANNER_SIZE),
            )?.let(::add)

            bannerItem(
                id = "recently_added",
                title = context.getString(R.string.mix_recently_added),
                fallbackSummary = null,
                songs = library.filter { it.addDate() > 0L }
                    .sortedByDescending { it.addDate() }
                    .asOrderedMix(BANNER_SIZE),
            )?.let(::add)

            // Discovery, which is the one thing none of the others do: everything above is drawn
            // from music the user has already reached for.
            bannerItem(
                id = "unheard_mix",
                title = phrase(
                    "banner_unheard",
                    "Yet to be heard",
                    "First listens",
                    "New to you",
                ),
                fallbackSummary = null,
                songs = library
                    .filter { it.playCount() == 0 && it.genreKey() in likedGenres }
                    .asMix(dailySeed("unheard_banner"), BANNER_SIZE),
            )?.let(::add)

            bannerArtists(artists).forEach { artist ->
                val title = artist.title ?: return@forEach
                bannerItem(
                    id = "artist_mix_$title",
                    title = context.getString(R.string.home_artist_mix, title),
                    fallbackSummary = title,
                    songs = artist.songList.asMix(weeklySeed("artist_banner_$title"), BANNER_SIZE),
                    summary = artist.songList.mapNotNull { it.mediaMetadata.albumTitle?.toString() }
                        .distinct().take(4).joinToString("、"),
                )?.let(::add)
            }
        }
        return banners.withoutNearDuplicateBanners()
    }

    /**
     * The artists worth a banner, rotated daily.
     *
     * Ranked by how much the user actually plays them, with catalogue size as a tie-break so a
     * library with no history still picks the artists it holds most of. The daily rotation through
     * the top of that ranking is the point: a fixed [BANNER_ARTIST_MIXES] taken straight off the
     * ranking would show the same four artists every day for as long as the ranking held.
     */
    private fun bannerArtists(artists: List<ArtistInput>): List<ArtistInput> = artists
        .filter { !it.title.isNullOrBlank() && it.songList.size >= MIN_MIX_SIZE }
        .sortedByDescending { artist -> artist.songList.sumOf { it.playCount() } * 4 + artist.songList.size }
        .take(BANNER_ARTIST_POOL)
        .sortedBy { stableHash(dailySeed("artist_banner"), it.title.orEmpty()) }
        .take(BANNER_ARTIST_MIXES)

    /** A banner, or null when there was not enough behind it to be worth showing one. */
    private fun bannerItem(
        id: String,
        title: String,
        fallbackSummary: String?,
        songs: List<MediaItem>,
        summary: String? = null,
    ): BannerItem? {
        if (songs.size < MIN_MIX_SIZE) return null
        val artistsSummary = summary?.takeIf(String::isNotBlank)
            ?: songs.mapNotNull { it.mediaMetadata.artist?.toString() }
                .distinct().take(5).joinToString("、").takeIf(String::isNotBlank)
            ?: fallbackSummary
        return BannerItem(
            id = id,
            title = title,
            artistsSummary = artistsSummary,
            cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
            songs = songs,
        )
    }

    /** Two banners holding the same music are one banner and one wasted slot. */
    private fun List<BannerItem>.withoutNearDuplicateBanners(): List<BannerItem> = buildList {
        this@withoutNearDuplicateBanners.forEach { candidate ->
            val candidateIds = candidate.mediaIds.toSet()
            val duplicate = any { accepted ->
                val acceptedIds = accepted.mediaIds.toSet()
                val smaller = minOf(candidateIds.size, acceptedIds.size)
                smaller > 0 && candidateIds.intersect(acceptedIds).size.toDouble() / smaller >= 0.90
            }
            if (!duplicate) add(candidate)
        }
    }

    /** Last.fm discovery, narrowed to artists that can actually be played from this library. */
    suspend fun similarArtistSection(
        context: Context,
        artists: List<ArtistInput>,
    ): HomeSection? {
        val store = LastFmCredentialStore(context)
        if (!store.hasApplicationCredentials() || artists.isEmpty()) return null

        val seed = artists
            .filter { artist -> artist.songList.any { it.playCount() > 0 } }
            .maxByOrNull { artist -> artist.songList.sumOf { it.playCount() } }
            ?: artists.firstOrNull()
            ?: return null
        val seedName = seed.title ?: return null
        val similar = try {
            LastFmClient(store.apiKey, store.apiSecret, store.brokerUrl).getSimilarArtists(seedName)
        } catch (_: Exception) {
            return null
        }

        val byName = artists.associateBy { it.title?.normaliseForMatch().orEmpty() }
        val matches = similar.mapNotNull { byName[it.normaliseForMatch()] }
            .filter { it.title != seed.title && it.songList.size >= 3 }
            .distinctBy { it.title }
            .take(ROW_SIZE)
        if (matches.isEmpty()) return null

        return HomeSection(
            id = "for_fans_of_v2",
            title = context.getString(R.string.home_for_fans_of, seedName),
            subtitle = "Playable artist mixes related to $seedName",
            cards = matches.map { artist ->
                val title = artist.title ?: context.getString(R.string.unknown_artist)
                val songs = artist.songList.asMix(weeklySeed("similar_artist_$title"))
                HomeCard(
                    title = "$title Mix",
                    subtitle = context.resources.getQuantityString(
                        R.plurals.songs, artist.songList.size, artist.songList.size,
                    ),
                    cover = songs.firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
                    collageCovers = songs.weightedCovers(),
                    songs = songs,
                )
            },
        )
    }

    private fun List<MediaItem>.toAlbumGroups(): List<Pair<String, List<MediaItem>>> {
        val groups = linkedMapOf<String, MutableList<MediaItem>>()
        forEach { item ->
            val key = item.albumKey() ?: return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.map { it.key to it.value }
    }

    private fun List<MediaItem>.toAlbumCard(): HomeCard = HomeCard(
        title = albumTitle(),
        subtitle = albumArtist(),
        cover = firstNotNullOfOrNull { it.mediaMetadata.artworkUri },
        songs = this,
        target = HomeCardTarget.ALBUM,
    )

    private fun List<MediaItem>.albumTitle(): String =
        firstNotNullOfOrNull { it.mediaMetadata.albumTitle?.toString()?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun List<MediaItem>.albumArtist(): String =
        firstNotNullOfOrNull {
            it.mediaMetadata.albumArtist?.toString()?.takeIf(String::isNotBlank)
                ?: it.mediaMetadata.artist?.toString()?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun MediaItem.albumKey(): String? {
        val title = mediaMetadata.albumTitle?.toString()?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val artist = mediaMetadata.albumArtist?.toString()?.trim()
            ?: mediaMetadata.artist?.toString()?.trim().orEmpty()
        return "${title.normaliseForMatch()}|${artist.normaliseForMatch()}"
    }

    private fun MediaItem.artistKey(): String =
        mediaMetadata.artist?.toString()?.normaliseForMatch().orEmpty()

    private fun MediaItem.genreKey(): String? =
        mediaMetadata.genre?.toString()?.trim()?.takeIf(String::isNotBlank)?.normaliseForMatch()

    private fun MediaItem.genreName(): String =
        mediaMetadata.genre?.toString()?.trim()?.takeIf(String::isNotBlank) ?: "Genre"

    private fun String.normaliseForMatch(): String = lowercase().filter { it.isLetterOrDigit() }

    private fun MediaItem.playCount(): Int =
        mediaMetadata.extras?.getInt(JellyfinLibraryLoader.EXTRA_PLAY_COUNT, 0) ?: 0

    private fun MediaItem.isFavourite(): Boolean =
        mediaMetadata.extras?.getBoolean(JellyfinLibraryLoader.EXTRA_IS_FAVOURITE, false) == true

    private fun MediaItem.lastPlayed(): Long =
        mediaMetadata.extras?.getLong(JellyfinLibraryLoader.EXTRA_LAST_PLAYED, 0L) ?: 0L

    private fun MediaItem.addDate(): Long = mediaMetadata.extras?.getLong("AddDate", 0L) ?: 0L

    /**
     * Covers for a card's collage, each repeated in proportion to how much of the mix it is.
     *
     * The repeats are the point: [uk.akane.accord.ui.components.CollageArtView] sizes each tile by
     * how often its cover appears, so a mix drawn mostly from one album shows that sleeve large
     * rather than as several identical squares. Kept short because these are written to the feed
     * cache, and a few tiles is all the artwork can show anyway.
     */
    private fun List<MediaItem>.weightedCovers(): List<Uri> {
        val ranked = mapNotNull { it.mediaMetadata.artworkUri }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(COLLAGE_SLICES)
        if (ranked.isEmpty()) return emptyList()

        val total = ranked.sumOf { it.value }
        return ranked.flatMap { (uri, count) ->
            val share = ((count.toDouble() / total) * COLLAGE_BUDGET)
                .roundToInt()
                .coerceAtLeast(1)
            List(share) { uri }
        }
    }

    /**
     * The one way a loose pool of tracks becomes a mix.
     *
     * Three things happen here, and the row is wrong without any of them. One copy of each
     * recording survives, because a library that holds a song on its single, its album and a
     * compilation otherwise puts all three in the same mix - which is what made a mix read as a
     * list of near-identical titles that had nothing to do with each other. The order is a
     * deterministic shuffle rather than whatever order the library arrived in, which is sorted:
     * taking the first fifty tracks of a sorted library is an alphabetical slice, not a mix. And
     * the survivors are then [spread] so the same artist and album are not stacked together.
     */
    private fun List<MediaItem>.asMix(seed: String, size: Int = MIX_SIZE): List<MediaItem> =
        distinctBy { it.mediaId }
            .dedupeRecordings()
            .stableShuffle(seed)
            .take(size * POOL_OVERSAMPLE)
            .spread()
            .take(size)

    /**
     * [asMix] for a pool whose order already means something - most played first, newest first -
     * so the sequence is kept and only the duplicates and the clumping are dealt with.
     */
    private fun List<MediaItem>.asOrderedMix(size: Int = MIX_SIZE): List<MediaItem> =
        distinctBy { it.mediaId }
            .dedupeRecordings()
            .take(size * POOL_OVERSAMPLE)
            .spread()
            .take(size)

    /**
     * One copy of each recording, preferring the copy the user has actually engaged with.
     *
     * Editions are the problem this solves: the same song filed under a single, a studio album, a
     * deluxe reissue and a greatest-hits compilation is four rows with the same title, and a mix
     * that draws from a whole library will happily take several of them. [MusicText.recordingKey]
     * is what decides they are the same recording - it folds featured credits and remaster years
     * away, and deliberately keeps live and remix markers, because those really are different
     * recordings rather than different spellings of one.
     */
    private fun List<MediaItem>.dedupeRecordings(): List<MediaItem> = dedupeBy(
        key = { it.recordingKey() },
        prefer = compareBy<MediaItem> { it.playCount() }
            .thenBy { if (it.isFavourite()) 1 else 0 }
            // Last resort, so which copy wins does not change between two identical runs.
            .thenByDescending { it.mediaId },
    )

    /**
     * One item per [key], keeping the position of the first of each group and the best of them.
     *
     * Position and choice are separated on purpose: which copy of a song is kept should follow the
     * user's history, while where it sits should follow whatever ordering the caller built.
     */
    internal fun <T> List<T>.dedupeBy(key: (T) -> String, prefer: Comparator<T>): List<T> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        forEach { item -> groups.getOrPut(key(item)) { mutableListOf() }.add(item) }
        return groups.values.map { group -> group.maxWithOrNull(prefer) ?: group.first() }
    }

    /**
     * Reorders so consecutive tracks come from different artists and different records.
     *
     * A shuffle alone still hands out runs - four tracks off one album in a row is an ordinary
     * outcome of shuffling a library where that album is a tenth of the pool - and a run like that
     * is what makes a mix feel like a directory listing. Each track is moved no further than the
     * first position where its neighbour differs, so the shuffle's ordering survives.
     */
    private fun List<MediaItem>.spread(): List<MediaItem> = spreadBy { item ->
        listOfNotNull(item.artistKey().takeIf(String::isNotBlank), item.albumKey())
    }

    /**
     * Reorders so no two consecutive items share any of the keys [neighbours] gives them.
     *
     * Greedy and order-preserving: each step takes the earliest remaining item that does not
     * collide with the one just placed, and falls back to the earliest remaining item when every
     * candidate collides - which is what happens once only one artist is left, and is the right
     * answer there.
     */
    internal fun <T> List<T>.spreadBy(neighbours: (T) -> List<String>): List<T> {
        if (size < 3) return this
        val remaining = toMutableList()
        val ordered = ArrayList<T>(remaining.size)
        var previous: List<String>? = null
        while (remaining.isNotEmpty()) {
            val index = previous?.let { last ->
                remaining.indexOfFirst { neighbours(it).none(last::contains) }.takeIf { it >= 0 }
            } ?: 0
            val chosen = remaining.removeAt(index)
            previous = neighbours(chosen)
            ordered.add(chosen)
        }
        return ordered
    }

    /** Identity of the recording rather than of the file, for [dedupeRecordings]. */
    private fun MediaItem.recordingKey(): String {
        val title = mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
            // Nothing to compare, so it is only ever equal to itself.
            ?: return "id:$mediaId"
        return artistKey() + " " + MusicText.recordingKey(title)
    }

    /** Round-robin artists so one prolific artist cannot consume an entire personal mix. */
    private fun List<MediaItem>.balancedByArtist(): List<MediaItem> {
        val groups = linkedMapOf<String, ArrayDeque<MediaItem>>()
        forEach { item ->
            val key = item.artistKey().ifBlank { "unknown:${item.mediaId}" }
            groups.getOrPut(key) { ArrayDeque() }.add(item)
        }
        return buildList {
            while (size < MIX_SIZE && groups.isNotEmpty()) {
                val iterator = groups.iterator()
                while (iterator.hasNext() && size < MIX_SIZE) {
                    val entry = iterator.next()
                    entry.value.removeFirstOrNull()?.let(::add)
                    if (entry.value.isEmpty()) iterator.remove()
                }
            }
        }
    }

    private fun List<HomeCard>.withoutNearDuplicates(): List<HomeCard> = buildList {
        this@withoutNearDuplicates.forEach { candidate ->
            val candidateIds = candidate.mediaIds.toSet()
            val isNearDuplicate = any { accepted ->
                val acceptedIds = accepted.mediaIds.toSet()
                val smaller = minOf(candidateIds.size, acceptedIds.size)
                smaller > 0 && candidateIds.intersect(acceptedIds).size.toDouble() / smaller >= 0.90
            }
            if (!isNearDuplicate) add(candidate)
        }
    }

    /**
     * Picks one of several ways of saying the same thing, fixed for the day.
     *
     * The wording changes between days so the feed does not read as the same printed page every
     * morning, but never changes within one - which keeps it consistent with the cached copy the
     * home screen renders on a cold start, and stops a title swapping under the user mid-scroll.
     */
    private fun phrase(key: String, vararg options: String): String {
        if (options.isEmpty()) return ""
        val index = (stableHash(dailySeed("phrase"), key).toULong() % options.size.toULong()).toInt()
        return options[index]
    }

    private fun Long.describeGap(): String = when {
        this >= 365 -> "over a year ago"
        this >= 60 -> "${this / 30} months ago"
        this >= 30 -> "a month ago"
        else -> "$this days ago"
    }

    private fun dailySeed(prefix: String): String {
        val calendar = Calendar.getInstance()
        return "$prefix:${calendar.get(Calendar.YEAR)}:${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun weeklySeed(prefix: String): String {
        val calendar = Calendar.getInstance()
        return "$prefix:${calendar.get(Calendar.YEAR)}:${calendar.get(Calendar.WEEK_OF_YEAR)}"
    }

    private fun List<MediaItem>.stableShuffle(seed: String): List<MediaItem> =
        sortedWith(compareBy<MediaItem> { stableHash(seed, it.mediaId) }.thenBy { it.mediaId })

    private fun stableHash(seed: String, value: String): Long {
        var hash = 0xcbf29ce484222325UL.toLong()
        "$seed\u0000$value".forEach { character ->
            hash = (hash xor character.code.toLong()) * 0x100000001b3L
        }
        return hash
    }
}
