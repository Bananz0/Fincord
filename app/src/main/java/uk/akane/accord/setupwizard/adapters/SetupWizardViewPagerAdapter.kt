package uk.akane.accord.setupwizard.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import uk.akane.accord.setupwizard.fragments.JellyfinPageFragment
import uk.akane.accord.setupwizard.fragments.ServicesPageFragment
import uk.akane.accord.setupwizard.fragments.WelcomePageFragment

class SetupWizardViewPagerAdapter(
    fragmentManager: FragmentManager,
    lifecycle: Lifecycle
) : FragmentStateAdapter(fragmentManager, lifecycle) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment =
        when (position) {
            0 -> WelcomePageFragment()
            // Upstream asked for storage permission here. This app has no local library to read,
            // so the server takes that slot: it is the only thing setup actually needs.
            1 -> JellyfinPageFragment()
            // The services this fork adds. Optional, but asked about here rather than discovered
            // months later at the moment one of them is needed.
            2 -> ServicesPageFragment()
            else -> throw IllegalArgumentException("Didn't find desired fragment!")
        }
}