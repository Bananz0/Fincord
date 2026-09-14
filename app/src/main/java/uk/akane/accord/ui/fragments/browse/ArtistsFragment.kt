package uk.akane.accord.ui.fragments.browse

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.adapters.browse.ArtistAdapter
import uk.akane.accord.ui.components.NavigationBar
import uk.akane.cupertino.navigation.SwitcherPostponeFragment
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.widget.doAfterTextChanged
import uk.akane.accord.ui.components.performPressHaptic
import uk.akane.cupertino.utils.AnimationUtils

class ArtistsFragment : SwitcherPostponeFragment() {

    private val activity get() = requireActivity() as MainActivity

    private lateinit var recyclerView: RecyclerView
    private lateinit var artistAdapter: ArtistAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var navigationBar: NavigationBar

    init {
        postponeSwitcherAnimation()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_browse_artists, container, false)

        navigationBar = rootView.findViewById(R.id.navigation_bar)

        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        recyclerView = rootView.findViewById(R.id.rv)
        layoutManager = LinearLayoutManager(requireContext())

        artistAdapter = ArtistAdapter(
            recyclerView = recyclerView,
            fragment = this
        ) {
            notifyContentLoaded()
        }

        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = artistAdapter

        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            // Side padding
            private val sidePx = dpToPx(24)

            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return

                outRect.left = sidePx
                outRect.right = 0
            }

            private fun dpToPx(dp: Int): Int =
                (dp * recyclerView.resources.displayMetrics.density).toInt()
        })

        // The layout has always carried a search box that nothing read.
        rootView.findViewById<EditText?>(R.id.search_input)?.doAfterTextChanged {
            artistAdapter.setFilter(it?.toString().orEmpty())
        }
        val modeContainer = rootView.findViewById<View>(R.id.artist_tab_container)
        val modeIndicator = rootView.findViewById<View>(R.id.artist_tab_indicator)
        val mainMode = rootView.findViewById<TextView>(R.id.artist_mode_main)
        val featuredMode = rootView.findViewById<TextView>(R.id.artist_mode_featured)
        var mainSelected = true

        fun updateModeIndicator(animate: Boolean) {
            val parent = modeIndicator.parent as View
            if (parent.width == 0) return
            val margins = modeIndicator.layoutParams as ViewGroup.MarginLayoutParams
            val horizontalMargins = margins.leftMargin + margins.rightMargin
            val segmentWidth = (parent.width - horizontalMargins) / 2
            if (margins.width != segmentWidth) {
                margins.width = segmentWidth
                modeIndicator.layoutParams = margins
            }
            val target = if (mainSelected) 0F
            else (parent.width - segmentWidth - horizontalMargins).toFloat()
            if (animate) {
                modeIndicator.animate()
                    .translationX(target)
                    .setDuration(AnimationUtils.MID_DURATION)
                    .setInterpolator(AnimationUtils.easingStandardInterpolator)
                    .start()
            } else {
                modeIndicator.translationX = target
            }
            val selected = requireContext().getColor(R.color.onSurfaceColor)
            val inactive = requireContext().getColor(R.color.onSurfaceColorInactive)
            mainMode.setTextColor(if (mainSelected) selected else inactive)
            featuredMode.setTextColor(if (mainSelected) inactive else selected)
        }

        fun selectMainArtists(selectMain: Boolean) {
            if (mainSelected == selectMain) return
            mainSelected = selectMain
            modeContainer.performPressHaptic()
            artistAdapter.setDisplayMode(
                if (selectMain) ArtistAdapter.ArtistKind.PRIMARY
                else ArtistAdapter.ArtistKind.FEATURED
            )
            recyclerView.scrollToPosition(0)
            updateModeIndicator(true)
        }

        mainMode.setOnClickListener { selectMainArtists(true) }
        featuredMode.setOnClickListener { selectMainArtists(false) }
        modeContainer.doOnLayout { updateModeIndicator(false) }
        navigationBar.attach(recyclerView)
        navigationBar.setOnReturnClickListener {
            activity.fragmentSwitcherView.popBackTopFragmentIfExists()
        }

        return rootView
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        navigationBar.onVisibilityChangedFromFragment(hidden)
    }
}
