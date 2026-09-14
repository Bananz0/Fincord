package uk.akane.accord.ui.adapters

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.button.MaterialButton
import org.akanework.gramophone.logic.data.acquisition.AcquirableArtist
import org.akanework.gramophone.logic.data.acquisition.AcquirableRelease
import org.akanework.gramophone.logic.data.acquisition.ReleaseAvailability
import uk.akane.accord.R
import uk.akane.accord.logic.dp

/**
 * Releases a downloader is offering to fetch, and the artists behind them.
 *
 * Takes provider-neutral types rather than any one downloader's result classes, so the same rows
 * show whatever the user has set up. Artists and releases share one row layout and one list: they
 * are answers to the same question, and splitting them across two lists would only make the reader
 * decide which half to look at.
 */
class RequestableReleasesAdapter(
    private val onReleaseClick: (AcquirableRelease) -> Unit,
    /**
     * Whether this release is already in the user's own library, which the downloader cannot know:
     * it reports what it fetched, not what the media server has since imported and indexed.
     */
    private val isInLibrary: (AcquirableRelease) -> Boolean = { false },
    private val onArtistClick: (AcquirableArtist) -> Unit = {},
) : RecyclerView.Adapter<RequestableReleasesAdapter.ViewHolder>() {

    /** What has happened to a row since it was tapped. */
    enum class RequestState { IDLE, SENDING, REQUESTED, FAILED }

    /** One line of results. Both kinds render into the same layout. */
    sealed interface Row {
        val key: String

        data class Artist(val artist: AcquirableArtist) : Row {
            override val key get() = "artist:" + artist.id
        }

        data class Release(val release: AcquirableRelease) : Row {
            override val key get() = "release:" + release.id
        }
    }

    private val rows = mutableListOf<Row>()
    private val states = mutableMapOf<String, RequestState>()

    /** The release at [position], for the swipe gesture. Null where the row is an artist. */
    fun itemAt(position: Int): AcquirableRelease? =
        (rows.getOrNull(position) as? Row.Release)?.release

    fun submit(results: List<AcquirableRelease>) = submitRows(results.map(Row::Release))

    fun submitRows(newRows: List<Row>) {
        val diff = DiffUtil.calculateDiff(Diff(rows.toList(), newRows))
        rows.clear()
        rows.addAll(newRows)
        // A row keeps what the user did to it only while it is still on screen; a new search is a
        // new question and its answers have their own state.
        states.keys.retainAll(newRows.mapTo(HashSet(), Row::key))
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Moves one row through its request, in place.
     *
     * Re-running the whole search to reflect a single tap is what made requesting feel like the
     * page had been thrown away and rebuilt - seconds of blank list for a change to one line. Only
     * the subtitle of the tapped row is rebound, so the artwork does not reload and nothing moves.
     */
    fun setState(releaseId: String, state: RequestState) {
        val key = "release:$releaseId"
        states[key] = state
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) notifyItemChanged(index, STATE_PAYLOAD)
    }

    fun requestState(releaseId: String): RequestState =
        states["release:" + releaseId] ?: RequestState.IDLE

    private fun stateOf(release: AcquirableRelease): RequestState =
        requestState(release.id)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.layout_song_item, parent, false)
    )

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Artist -> {
                holder.stopSendingAnimation()
                holder.menu.visibility = View.GONE
                holder.title?.text = row.artist.name
                bindArtistSubtitle(holder, row.artist)
                holder.cover?.load(row.artist.artworkUrl) {
                    crossfade(true)
                    size(54.dp.px.toInt(), 54.dp.px.toInt())
                }
                holder.itemView.setOnClickListener { onArtistClick(row.artist) }
            }

            is Row.Release -> {
                holder.title?.text = row.release.title
                bindSubtitle(holder, row.release)
                bindAction(holder, row.release, animate = false)
                holder.cover?.load(row.release.artworkUrl) {
                    crossfade(true)
                    size(54.dp.px.toInt(), 54.dp.px.toInt())
                }
                holder.itemView.setOnClickListener { onReleaseClick(row.release) }
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val row = rows.getOrNull(position)
        if (payloads.contains(STATE_PAYLOAD) && row is Row.Release) {
            bindSubtitle(holder, row.release)
            bindAction(holder, row.release, animate = true)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /**
     * The one line that changes as a request progresses, so it is bound on its own.
     *
     * The label says what the row *is*, in the order the user cares about. "In your library" first,
     * because a record you already own is not something you might request - it is something you can
     * play, and saying "already requested" about it was simply wrong. Then what the downloader is
     * doing about it, which is the difference between waiting and nothing happening.
     */
    private fun bindSubtitle(holder: ViewHolder, release: AcquirableRelease) {
        val context = holder.itemView.context
        val status = when (stateOf(release)) {
            RequestState.SENDING -> context.getString(R.string.requests_row_sending)
            RequestState.REQUESTED -> context.getString(R.string.requests_row_requested)
            RequestState.FAILED -> context.getString(R.string.requests_row_failed)
            RequestState.IDLE -> when {
                isInLibrary(release) -> context.getString(R.string.requests_row_in_library)
                release.availability == ReleaseAvailability.DOWNLOADING ->
                    context.getString(R.string.requests_row_downloading)

                // Fetched, but the media server has not indexed it yet - a scan away from playable.
                release.availability == ReleaseAvailability.DOWNLOADED ->
                    context.getString(R.string.requests_row_downloaded)

                release.availability == ReleaseAvailability.REQUESTED ->
                    context.getString(R.string.requests_row_requested)

                else -> null
            }
        }
        holder.subtitle?.text = listOfNotNull(
            release.artist.takeIf(String::isNotBlank),
            release.year?.toString(),
            status,
        ).joinToString(" · ")
    }

    /**
     * The request control uses the same visual grammar as a Jellyfin local save: plus while an
     * action is available, motion while it is in flight, and a check once the server accepted it.
     * Keeping this on the row also means the result remains understandable when its subtitle is
     * truncated by a long artist and album name.
     */
    private fun bindAction(holder: ViewHolder, release: AcquirableRelease, animate: Boolean) {
        val button = holder.menu
        val state = stateOf(release)
        val alreadyHandled = isInLibrary(release) || release.alreadyPresent
        holder.stopSendingAnimation()
        button.visibility = View.VISIBLE
        button.isClickable = state != RequestState.SENDING && !alreadyHandled &&
            state != RequestState.REQUESTED
        button.isEnabled = state != RequestState.SENDING
        button.alpha = 1f
        button.rotation = 0f

        when {
            state == RequestState.SENDING -> {
                button.setIconResource(R.drawable.ic_progress_ring)
                button.contentDescription = button.context.getString(R.string.requests_row_sending)
                holder.startSendingAnimation()
            }

            state == RequestState.REQUESTED || alreadyHandled -> {
                button.setIconResource(R.drawable.ic_checkmark)
                button.contentDescription = button.context.getString(R.string.requests_row_requested)
            }

            state == RequestState.FAILED -> {
                button.setIconResource(R.drawable.ic_error)
                button.contentDescription = button.context.getString(R.string.requests_row_failed)
                button.isEnabled = true
                button.isClickable = true
            }

            else -> {
                button.setIconResource(R.drawable.ic_plus)
                button.contentDescription = button.context.getString(R.string.lidarr_request_missing)
            }
        }
        button.setOnClickListener {
            if (button.isClickable) onReleaseClick(release)
        }
        if (animate) {
            button.animate().cancel()
            button.scaleX = 0.72f
            button.scaleY = 0.72f
            button.animate().scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
    }

    private fun bindArtistSubtitle(holder: ViewHolder, artist: AcquirableArtist) {
        val context = holder.itemView.context
        holder.subtitle?.text = listOfNotNull(
            context.getString(R.string.requests_row_artist),
            // MusicBrainz's clarifier is the only thing separating three acts of the same name.
            artist.disambiguation?.takeIf(String::isNotBlank),
        ).joinToString(" · ")
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView? = view.findViewById(R.id.cover)
        val title: TextView? = view.findViewById(R.id.title)
        val subtitle: TextView? = view.findViewById(R.id.subtitle)
        val menu: MaterialButton = view.findViewById(R.id.menu_btn)
        private var sendingAnimator: ObjectAnimator? = null

        fun startSendingAnimation() {
            sendingAnimator = ObjectAnimator.ofFloat(menu, View.ROTATION, 0f, 360f).apply {
                duration = 800L
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        fun stopSendingAnimation() {
            sendingAnimator?.cancel()
            sendingAnimator = null
            menu.rotation = 0f
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.stopSendingAnimation()
        holder.menu.setOnClickListener(null)
        super.onViewRecycled(holder)
    }

    private class Diff(
        private val old: List<Row>,
        private val new: List<Row>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key == new[newPos].key
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }

    private companion object {
        /** Marks a rebind that only touches the status line, leaving the artwork alone. */
        private val STATE_PAYLOAD = Any()
    }
}
