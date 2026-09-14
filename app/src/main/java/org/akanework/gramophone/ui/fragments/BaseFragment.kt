/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.ui.fragments


import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialSharedAxis
import uk.akane.accord.R

/**
 * BaseFragment:
 *   The base for the settings screens, which are the last fragments still hosted the old way.
 *   It creates material transitions easily and also makes overlapping colors more convenient.
 *
 * The `wantsPlayer` flag it used to carry is gone with the old shell: Accord's player is a floating
 * panel owned by its own activity, not a bottom sheet a fragment can ask to show or hide.
 *
 * @author AkaneTan, nift4
 */
abstract class BaseFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable material transitions.
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, /* forward= */ false)
    }

    // https://github.com/material-components/material-components-android/issues/1984#issuecomment-1089710991
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Overlap colors.
        view.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.contrast_colorBackground
            )
        )
    }

}