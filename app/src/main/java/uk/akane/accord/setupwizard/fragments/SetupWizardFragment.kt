package uk.akane.accord.setupwizard.fragments

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import uk.akane.accord.R
import uk.akane.accord.logic.setCurrentItemInterpolated
import uk.akane.accord.setupwizard.adapters.SetupWizardViewPagerAdapter
import uk.akane.accord.ui.MainActivity
import uk.akane.cupertino.utils.AnimationUtils

/**
 * Upstream takes the success callback as a constructor argument, which leaves the fragment without
 * the no-argument constructor FragmentManager needs to restore it. Any recreation while the wizard
 * is on screen - a rotation, a theme switch, the process coming back - then dies with
 * "could not find Fragment constructor" before the activity has finished starting.
 *
 * The callback is resolved from the host activity instead, so it survives being recreated.
 */
class SetupWizardFragment : Fragment() {

    val onPermissionSuccessCallback: () -> Unit
        get() = { (activity as? MainActivity)?.updateLibrary() }

    private lateinit var viewPager2: ViewPager2
    private lateinit var viewPagerAdapter: SetupWizardViewPagerAdapter
    private lateinit var continueButton: MaterialButton

    private var inactiveBtnColor = 0
    private var activeBtnColor = 0

    private var onInactiveBtnColor = 0
    private var onActiveBtnColor = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_setup_wizard, container, false)

        inactiveBtnColor = requireContext().getColor(R.color.accentColorFainted)
        activeBtnColor = requireContext().getColor(R.color.accentColor)

        onInactiveBtnColor = requireContext().getColor(R.color.onAccentColorFainted)
        onActiveBtnColor = requireContext().getColor(R.color.onAccentColor)

        viewPager2 = rootView.findViewById(R.id.sw_viewpager)
        continueButton = rootView.findViewById(R.id.continue_btn)
        viewPagerAdapter = SetupWizardViewPagerAdapter(childFragmentManager, lifecycle)

        viewPager2.adapter = viewPagerAdapter
        viewPager2.isUserInputEnabled = false
        viewPager2.offscreenPageLimit = 9999

        continueButton.setOnClickListener {
            if (viewPager2.currentItem + 1 < viewPagerAdapter.itemCount) {
                // Landing on the Jellyfin page with no server signed in greys Continue out until
                // one is. Upstream gated on media permissions here for the same reason.
                if (viewPager2.currentItem + 1 == 1 &&
                    !JellyfinCredentialStore.hasStoredSession(requireContext())
                ) {
                    continueButton.isEnabled = false
                    AnimationUtils.createValAnimator(
                        activeBtnColor,
                        inactiveBtnColor,
                        isArgb = true
                    ) {
                        continueButton.backgroundTintList = ColorStateList.valueOf(
                            it
                        )
                    }
                    AnimationUtils.createValAnimator(
                        onActiveBtnColor,
                        onInactiveBtnColor,
                        isArgb = true
                    ) {
                        continueButton.setTextColor(
                            it
                        )
                    }
                }
                viewPager2.setCurrentItemInterpolated(viewPager2.currentItem + 1)
            } else {
                (requireActivity() as MainActivity).removeContainer()
            }
        }

        return rootView
    }

    fun releaseContinueButton() {
        AnimationUtils.createValAnimator(
            inactiveBtnColor,
            activeBtnColor,
            isArgb = true,
            doOnEnd = {
                continueButton.isEnabled = true
            }
        ) {
            continueButton.backgroundTintList = ColorStateList.valueOf(
                it
            )
        }
        AnimationUtils.createValAnimator(
            onInactiveBtnColor,
            onActiveBtnColor,
            isArgb = true
        ) {
            continueButton.setTextColor(
                it
            )
        }
    }
}