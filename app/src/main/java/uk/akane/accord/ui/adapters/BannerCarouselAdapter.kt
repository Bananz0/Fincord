package uk.akane.accord.ui.adapters

import android.net.Uri
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.logic.dp
import uk.akane.accord.ui.components.BannerView
import uk.akane.accord.ui.components.performPressHaptic

data class BannerItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artistsSummary: String? = null,
    val cover: Uri? = null,
    val songs: List<MediaItem> = emptyList(),
    val cachedMediaIds: List<String> = emptyList(),
    val onClick: (() -> Unit)? = null
) {
    val mediaIds: List<String>
        get() = songs.map { it.mediaId }.ifEmpty { cachedMediaIds }
}

class BannerCarouselAdapter(
    private var items: List<BannerItem> = emptyList(),
    private val onItemClick: (BannerItem) -> Unit
) : RecyclerView.Adapter<BannerCarouselAdapter.ViewHolder>() {

    fun submitList(newItems: List<BannerItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val bannerView = BannerView(parent.context).apply {
            val widthPx = 220.dp.px.toInt()
            val heightPx = 280.dp.px.toInt()
            val marginEndPx = 16.dp.px.toInt()

            layoutParams = ViewGroup.MarginLayoutParams(widthPx, heightPx).apply {
                marginEnd = marginEndPx
            }
        }
        return ViewHolder(bannerView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bannerView.bind(
            title = item.title,
            artistsSummary = item.artistsSummary ?: item.subtitle,
            seed = item.id
        )
        holder.bannerView.setOnClickListener { view ->
            view.performPressHaptic()
            item.onClick?.invoke() ?: onItemClick(item)
        }
    }

    class ViewHolder(val bannerView: BannerView) : RecyclerView.ViewHolder(bannerView)
}
