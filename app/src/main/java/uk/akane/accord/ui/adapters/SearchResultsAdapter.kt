package uk.akane.accord.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import uk.akane.libphonograph.items.Album
import uk.akane.libphonograph.items.Artist
import uk.akane.accord.R
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.components.TrackRowMenu

/**
 * The results of a library search, as artists, albums and songs rather than songs alone.
 *
 * Searching only tracks meant an album could never be a result: typing its name returned its
 * fourteen songs and no way to reach the album itself, which is usually the thing being looked for.
 * One list with section headers rather than a shelf per type - the number of results of each kind
 * is unknowable in advance, and a sideways shelf silently caps what can be reached.
 */
class SearchResultsAdapter(
    private val player: () -> MediaController?,
    private val onAlbum: (Album) -> Unit = {},
    private val onArtist: (Artist) -> Unit = {},
    private val onRecent: (String) -> Unit = {},
    private val onTrackSelected: () -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** One line of the results list. */
    sealed interface Row {
        data class Header(val title: CharSequence) : Row
        data class ArtistRow(val artist: Artist) : Row
        data class AlbumRow(val album: Album) : Row
        data class SongRow(val item: MediaItem) : Row

        /** A song whose lyrics matched, with the words around the hit. */
        data class LyricRow(val item: MediaItem, val snippet: CharSequence) : Row

        /** A previous query, offered on the otherwise blank screen before anything is typed. */
        data class Recent(val query: String) : Row
    }

    private val rows = mutableListOf<Row>()

    /** Every playable result, in list order, so a lyric hit is a real track rather than decoration. */
    private val songs get() = rows.mapNotNull { it.mediaItemOrNull() }

    private fun Row.mediaItemOrNull(): MediaItem? = when (this) {
        is Row.SongRow -> item
        is Row.LyricRow -> item
        else -> null
    }

    fun submit(results: List<Row>) {
        val diff = DiffUtil.calculateDiff(Diff(rows.toList(), results))
        rows.clear()
        rows.addAll(results)
        diff.dispatchUpdatesTo(this)
    }

    /** Songs-only convenience, for callers that have not been taught about the other types. */
    fun submitSongs(results: List<MediaItem>) = submit(results.map(Row::SongRow))

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.ArtistRow -> TYPE_ARTIST
        is Row.AlbumRow -> TYPE_ALBUM
        is Row.SongRow -> TYPE_SONG
        is Row.LyricRow -> TYPE_SONG
        is Row.Recent -> TYPE_RECENT
    }

    /**
     * What row [position] is showing, for the swipe actions.
     *
     * Null for anything that is not a song: the swipe gestures queue and enqueue tracks, and there
     * is nothing sensible for them to do to a header.
     */
    fun itemAt(position: Int): MediaItem? = rows.getOrNull(position)?.mediaItemOrNull()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.layout_search_section_header, parent, false)
            )
            TYPE_RECENT -> RecentHolder(
                inflater.inflate(R.layout.layout_search_recent_item, parent, false)
            )
            else -> ItemHolder(inflater.inflate(R.layout.layout_song_item, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).title.text = row.title
            is Row.ArtistRow -> (holder as ItemHolder).bindArtist(row.artist)
            is Row.AlbumRow -> (holder as ItemHolder).bindAlbum(row.album)
            is Row.SongRow -> (holder as ItemHolder).bindSong(row.item)
            is Row.LyricRow -> (holder as ItemHolder).bindLyric(row.item, row.snippet)
            is Row.Recent -> (holder as RecentHolder).bind(row.query)
        }
    }

    private inner class ItemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView? = view.findViewById(R.id.cover)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val menu: View? = view.findViewById(R.id.menu_btn)

        fun bindArtist(artist: Artist) {
            title?.text = artist.title
            subtitle?.text = itemView.context.resources.getQuantityString(
                R.plurals.albums, artist.albumList.size, artist.albumList.size
            )
            // Round, which is how every music app distinguishes a person from a record at a glance -
            // and the only cue separating the two sections once you have scrolled past the header.
            cover?.load(artist.songList.firstOrNull()?.mediaMetadata?.artworkUri) {
                crossfade(true)
                transformations(CircleCropTransformation())
                size(COVER_PX, COVER_PX)
            }
            menu?.visibility = View.GONE
            itemView.setOnClickListener { onArtist(artist) }
        }

        fun bindAlbum(album: Album) {
            title?.text = album.title
            subtitle?.text = listOfNotNull(
                album.albumArtist?.takeIf { it.isNotBlank() },
                album.albumYear?.takeIf { it > 0 }?.toString(),
            ).joinToString(" · ")
            cover?.load(album.cover) {
                crossfade(true)
                size(COVER_PX, COVER_PX)
            }
            menu?.visibility = View.GONE
            itemView.setOnClickListener { onAlbum(album) }
        }

        /**
         * Same row as a song, but the subtitle carries the matched words instead of the artist.
         *
         * The words are the whole reason this result is here: a lyric hit with the artist underneath
         * looks identical to a title match and gives no clue why the song was returned.
         */
        fun bindLyric(item: MediaItem, snippet: CharSequence) {
            bindSong(item)
            subtitle?.text = snippet
        }

        fun bindSong(item: MediaItem) {
            title?.text = item.mediaMetadata.title
            subtitle?.text = item.mediaMetadata.artist
            cover?.load(item.mediaMetadata.artworkUri) {
                crossfade(true)
                size(COVER_PX, COVER_PX)
            }
            menu?.visibility = View.VISIBLE
            menu?.setOnClickListener { anchor -> TrackRowMenu.show(anchor, item) }
            itemView.setOnClickListener {
                // Plays every track section from here. Lyric rows used to be excluded from this
                // queue: a lyrics-only search therefore set an empty timeline, while a mixed
                // search silently played its first ordinary title match instead.
                val queue = songs
                val start = queue.indexOfFirst { it.mediaId == item.mediaId }
                if (start < 0) return@setOnClickListener
                onTrackSelected()
                player()?.apply {
                    setMediaItems(queue, start, C.TIME_UNSET)
                    prepare()
                    play()
                }
            }
        }
    }

    private class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
    }

    private inner class RecentHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.recent_query)

        fun bind(query: String) {
            label.text = query
            itemView.setOnClickListener { onRecent(query) }
        }
    }

    private class Diff(
        private val old: List<Row>,
        private val new: List<Row>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val a = old[oldPos]
            val b = new[newPos]
            return when {
                a is Row.Header && b is Row.Header -> a.title == b.title
                a is Row.ArtistRow && b is Row.ArtistRow -> a.artist.id == b.artist.id
                a is Row.AlbumRow && b is Row.AlbumRow -> a.album.id == b.album.id
                a is Row.SongRow && b is Row.SongRow -> a.item.mediaId == b.item.mediaId
                a is Row.LyricRow && b is Row.LyricRow -> a.item.mediaId == b.item.mediaId
                a is Row.Recent && b is Row.Recent -> a.query == b.query
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            areItemsTheSame(oldPos, newPos)
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ARTIST = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_SONG = 3
        private const val TYPE_RECENT = 4

        private val COVER_PX = 62.dp.px.toInt()
    }
}
