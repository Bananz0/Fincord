package uk.akane.accord.ui.fragments.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import coil3.load
import coil3.request.crossfade
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredits
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment

/**
 * Who made the track that is playing, and what the file actually is.
 *
 * The build this UI came from fills this screen from a commercial catalogue. This fork asks the
 * Jellyfin server instead, so the answer is about the user's own file: the people tagged on it, the
 * label that released it, and the codec, bit depth and sample rate behind the player's badge.
 */
class ViewCreditsFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity

    private lateinit var navigationBar: NavigationBar
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var loadingView: View

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_view_credits, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }
        navigationBar.attach(
            rootView.findViewById<NestedScrollView>(R.id.credits_scroll), applyTopPadding = false
        )

        this.container = rootView.findViewById(R.id.credits_container)
        emptyView = rootView.findViewById(R.id.credits_empty)
        loadingView = rootView.findViewById(R.id.credits_loading)

        val arguments = requireArguments()
        rootView.findViewById<TextView>(R.id.credits_title).text =
            arguments.getString(ARG_TITLE).orEmpty()
        rootView.findViewById<TextView>(R.id.credits_subtitle).text = listOfNotNull(
            arguments.getString(ARG_ARTIST)?.takeIf { it.isNotBlank() },
            arguments.getString(ARG_ALBUM)?.takeIf { it.isNotBlank() },
        ).joinToString(" — ")
        arguments.getString(ARG_ARTWORK)?.takeIf { it.isNotBlank() }?.let { artwork ->
            rootView.findViewById<ImageView>(R.id.credits_cover).load(artwork.toUri()) {
                crossfade(true)
            }
        }

        load(arguments.getString(ARG_MEDIA_ID))

        return rootView
    }

    private fun load(mediaId: String?) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // The lookup is a network call keyed on a database row, so both halves are off the main
            // thread; the screen shows its spinner until it comes back.
            val result = withContext(Dispatchers.IO) { JellyfinCredits.load(context, mediaId) }
            loadingView.visibility = View.GONE
            when {
                result == null -> showEmpty(R.string.credits_unavailable)
                result.sections.isEmpty() -> showEmpty(R.string.credits_none)
                else -> render(result.sections)
            }
            // Releases the postponed switcher animation, so the screen slides in with its content
            // rather than sliding in blank and filling afterwards.
            notifyContentLoaded()
        }
    }

    private fun showEmpty(messageResId: Int) {
        container.removeAllViews()
        container.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        emptyView.setText(messageResId)
    }

    private fun render(sections: List<JellyfinCredits.Section>) {
        val inflater = LayoutInflater.from(requireContext())
        container.removeAllViews()
        container.visibility = View.VISIBLE
        emptyView.visibility = View.GONE

        sections.forEach { section ->
            val header = inflater.inflate(
                R.layout.layout_credits_section_header, container, false
            ) as TextView
            header.text = localisedTitle(section.title)
            container.addView(header)

            val card = inflater.inflate(R.layout.layout_credits_section_card, container, false)
            val rows = card.findViewById<LinearLayout>(R.id.credits_section_items)
            section.credits.forEachIndexed { index, credit ->
                val row = inflater.inflate(R.layout.layout_credit_item, rows, false)
                row.findViewById<TextView>(R.id.credit_name).text = credit.name
                row.findViewById<TextView>(R.id.credit_role).apply {
                    text = credit.role.orEmpty()
                    visibility = if (credit.role.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                row.findViewById<ShapeableImageView>(R.id.credit_thumbnail).apply {
                    if (credit.imageUrl == null) {
                        visibility = View.GONE
                    } else {
                        visibility = View.VISIBLE
                        // The server names an image for every person it knows, whether or not it
                        // actually has one, so a URL is not evidence a picture exists. Without this
                        // the row keeps an empty circle and the name sits indented behind nothing.
                        load(credit.imageUrl) {
                            crossfade(true)
                            listener(onError = { _, _ -> visibility = View.GONE })
                        }
                    }
                }
                // The last row in a card has nothing below it to separate from, and a divider there
                // reads as a line cutting the card off short.
                row.findViewById<View>(R.id.divider).visibility =
                    if (index == section.credits.lastIndex) View.GONE else View.VISIBLE
                rows.addView(row)
            }
            container.addView(card)
        }
    }

    /** The loader names its sections in English; the screen shows the translated string. */
    private fun localisedTitle(title: String): String = when (title) {
        JellyfinCredits.SECTION_PERFORMANCE -> getString(R.string.credits_section_performance)
        JellyfinCredits.SECTION_RELEASE -> getString(R.string.credits_section_release)
        JellyfinCredits.SECTION_AUDIO -> getString(R.string.credits_section_audio)
        else -> title
    }

    companion object {
        private const val ARG_MEDIA_ID = "credits_media_id"
        private const val ARG_TITLE = "credits_title"
        private const val ARG_ARTIST = "credits_artist"
        private const val ARG_ALBUM = "credits_album"
        private const val ARG_ARTWORK = "credits_artwork"

        fun newInstance(
            mediaId: String?,
            title: String?,
            artist: String?,
            album: String?,
            artworkUri: String?,
        ) = ViewCreditsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MEDIA_ID, mediaId)
                putString(ARG_TITLE, title)
                putString(ARG_ARTIST, artist)
                putString(ARG_ALBUM, album)
                putString(ARG_ARTWORK, artworkUri)
            }
        }
    }
}
