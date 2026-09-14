package org.akanework.gramophone.ui.fragments


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import uk.akane.accord.R
import uk.akane.accord.ui.components.NavigationBar

abstract class BaseSettingFragment(
    private val str: Int,
    private val fragmentCreator: () -> BasePreferenceFragment
) : BaseFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_top_settings, container, false)
        val navigationBar = rootView.findViewById<NavigationBar>(R.id.navigation_bar)
        navigationBar.setTitle(getString(str))
        ViewCompat.setOnApplyWindowInsetsListener(navigationBar) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            // Immersive mode hides systemBars, but it does not make the camera cutout safe to draw
            // through. Keep the same top rhythm as Scrobbling in both modes.
            view.setPadding(
                view.paddingLeft,
                maxOf(systemBars.top, cutout.top),
                view.paddingRight,
                view.paddingBottom,
            )
            insets
        }
        navigationBar.setOnReturnClickListener {
            // These screens are now reached from the Accord shell, whose back stack is the
            // fragment switcher's rather than the activity's. Fall back to the old behaviour for
            // whatever still hosts them the previous way.
            val activity = requireActivity()
            if (activity is uk.akane.accord.ui.MainActivity) {
                activity.fragmentSwitcherView.popBackTopFragmentIfExists()
            } else {
                activity.supportFragmentManager.popBackStack()
            }
        }

        // The child FragmentManager restores this page across activity recreation. Adding a new
        // preference fragment every time onCreateView runs leaves two full settings surfaces in
        // the same container (and two scroll listeners driving the title), which is the overlap
        // seen after returning from the background or changing theme.
        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            childFragmentManager
                .beginTransaction()
                .replace(R.id.settings, fragmentCreator())
                .commit()
        }

        return rootView
    }
}
