package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import uk.akane.accord.R
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingFragment


class BehaviorSettingsFragment : BaseSettingFragment(R.string.settings_category_behavior,
    { BehaviorSettingsTopFragment() })

/**
 * Switches read elsewhere, so there is nothing to wire up here.
 *
 * This screen used to also carry a MediaStore length filter and an album-cover compatibility
 * toggle. Both described a local library this app no longer has, and the cover toggle routed to
 * the system permission page for READ_MEDIA_IMAGES - a permission no longer declared, so it opened
 * a page with nothing to grant.
 *
 * Play on launch is read by [MainActivity][uk.akane.accord.ui.MainActivity] when the media
 * controller connects, not from here: the queue it starts is restored by the playback service,
 * which this fragment has no handle on.
 */
class BehaviorSettingsTopFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
    }
}
