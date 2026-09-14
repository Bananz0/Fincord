package org.akanework.gramophone.ui.home


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import uk.akane.accord.R
import uk.akane.accord.ui.components.CollageArtView
import uk.akane.accord.ui.components.StationArtView
import uk.akane.accord.ui.components.performPressHaptic
import org.akanework.gramophone.logic.ui.coolCrossfade
import androidx.media3.session.MediaController

/**
 * The vertical list of home rows.
 *
 * Each row keeps its own horizontal adapter, and rows share a [RecyclerView.RecycledViewPool] so
 * scrolling past several of them does not inflate a fresh set of cards for each.
 */
class HomeSectionAdapter(
    /**
     * The controller to play a tapped card through. Passed as a lookup rather than an activity so
     * the same feed serves the old screens and the Accord ones, whose activities share no type.
     */
    private val player: () -> MediaController?,
    /**
     * What a tapped card should do. Null plays it straight away, which is what the old screens did;
     * the Accord home passes a handler that opens the station instead, so there is somewhere to see
     * the tracks and pick a starting point.
     */
    private val onCardClick: ((HomeSection, HomeCard) -> Unit)? = null
) : RecyclerView.Adapter<HomeSectionAdapter.ViewHolder>() {

    private val sections = mutableListOf<HomeSection>()
    private val cardPool = RecyclerView.RecycledViewPool()

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.section_title)
        val subtitle: TextView = view.findViewById(R.id.section_subtitle)
        val items: RecyclerView = view.findViewById(R.id.section_items)
        val cardAdapter = CardAdapter()

        init {
            items.layoutManager =
                LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            items.setRecycledViewPool(cardPool)
            items.isNestedScrollingEnabled = false
            items.adapter = cardAdapter
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context)
            .inflate(R.layout.homepage_section, parent, false)
    )

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val section = sections[position]
        holder.title.text = section.title
        holder.subtitle.text = section.subtitle
        holder.subtitle.visibility =
            if (section.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
        val sectionChanged = holder.cardAdapter.sectionId != section.id
        holder.cardAdapter.submit(section)
        if (sectionChanged) holder.items.scrollToPosition(0)
    }

    /** What is currently on screen, for callers that need to re-submit with an extra row folded in. */
    fun currentSections(): List<HomeSection> = sections.toList()

    fun submit(newSections: List<HomeSection>) {
        val diff = DiffUtil.calculateDiff(SectionDiff(sections.toList(), newSections))
        sections.clear()
        sections.addAll(newSections)
        diff.dispatchUpdatesTo(this)
    }

    inner class CardAdapter : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

        private var section: HomeSection? = null
        private val cards: List<HomeCard>
            get() = section?.cards.orEmpty()
        val sectionId: String?
            get() = section?.id

        fun submit(newSection: HomeSection) {
            section = newSection
            notifyDataSetChanged()
        }

        inner class CardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView? = view.findViewById(R.id.cover)
            val title: TextView =
                view.findViewById<TextView?>(R.id.station_title)
                    ?: view.findViewById(R.id.title)
            val subtitle: TextView? = view.findViewById(R.id.subtitle)
            val art: StationArtView? = view.findViewById(R.id.station_art)
            val collageArt: CollageArtView? = view.findViewById(R.id.collage_art)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CardViewHolder(
            LayoutInflater.from(parent.context).inflate(
                if (viewType == VIEW_TYPE_STATION) R.layout.layout_station_card
                else R.layout.homepage_recommend_card,
                parent,
                false
            )
        )

        override fun getItemCount(): Int = cards.size

        override fun getItemViewType(position: Int): Int =
            if (section?.style == HomeSectionStyle.STATION) VIEW_TYPE_STATION else VIEW_TYPE_CARD

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            val card = cards[position]
            holder.title.text = card.title
            holder.subtitle?.text = card.subtitle
            holder.subtitle?.visibility =
                if (card.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

            if (section?.style == HomeSectionStyle.STATION) {
                holder.art?.bind(card.title)
            } else {
                if (card.collageCovers.isNotEmpty()) {
                    holder.cover?.visibility = View.GONE
                    holder.art?.visibility = View.GONE
                    holder.collageArt?.visibility = View.VISIBLE
                    holder.collageArt?.setCovers(card.collageCovers)
                } else if (card.cover != null) {
                    holder.cover?.visibility = View.VISIBLE
                    holder.art?.visibility = View.GONE
                    holder.collageArt?.visibility = View.GONE
                    holder.cover?.load(card.cover) { coolCrossfade(true) }
                } else {
                    holder.cover?.visibility = View.GONE
                    holder.art?.visibility = View.VISIBLE
                    holder.collageArt?.visibility = View.GONE
                    holder.art?.bind(card.title)
                }
            }
            val clickListener = View.OnClickListener {
                it.performPressHaptic()
                val handler = onCardClick
                if (handler != null) {
                    section?.let { handler(it, card) }
                } else if (card.songs.isNotEmpty()) {
                    player()?.apply {
                        setMediaItems(card.songs, card.startIndex, C.TIME_UNSET)
                        prepare()
                        play()
                    }
                }
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.collageArt?.setOnClickListener(clickListener)
            holder.cover?.setOnClickListener(clickListener)
            holder.art?.setOnClickListener(clickListener)
        }
    }

    /**
     * Sections are identified by a stable id, so a rebuild that only changes a row's contents
     * rebinds that row instead of dropping the whole feed and scrolling back to the top.
     */
    private companion object {
        const val VIEW_TYPE_CARD = 0
        const val VIEW_TYPE_STATION = 1
    }

    private class SectionDiff(
        private val old: List<HomeSection>,
        private val new: List<HomeSection>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].id == new[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
    }
}
