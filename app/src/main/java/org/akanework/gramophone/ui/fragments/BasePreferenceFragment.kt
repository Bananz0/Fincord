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


import android.content.SharedPreferences
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import uk.akane.accord.R
import org.akanework.gramophone.logic.allowDiskAccessInStrictMode
import org.akanework.gramophone.logic.dpToPx
import uk.akane.accord.ui.components.NavigationBar

/**
 * BasePreferenceFragment:
 *   A base fragment for all SettingsTopFragment. It
 * is used to make overlapping color easier.
 *
 * @author AkaneTan
 */
abstract class BasePreferenceFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * The preference layouts reach for Material3 attributes, which the activity theme used to
     * supply. The Accord shell that now hosts these screens is themed on MaterialComponents
     * instead, and inflating a preference row against it died with "Failed to resolve attribute".
     * Inflating against the old theme keeps these screens working wherever they are hosted.
     */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        super.onGetLayoutInflater(savedInstanceState)
            .cloneInContext(ContextThemeWrapper(requireContext(), R.style.Theme_Gramophone))

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                R.color.settings_background
            )
        )
        view.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view).apply {
            val side = 16.dpToPx(context)
            setPadding(side, paddingTop + 4.dpToPx(context), side, paddingBottom)
            addItemDecoration(SettingsGroupDecoration())
            // The original Accord screen lets its navigation bar own the scroll insets and title
            // collapse. Doing that here removes the last visible Material-settings seam.
            requireParentFragment().view
                ?.findViewById<NavigationBar>(R.id.navigation_bar)
                ?.let { navigationBar ->
                    scrollToPosition(0)
                    navigationBar.attach(this)
                    navigationBar.post {
                        scrollToPosition(0)
                        navigationBar.resetToExpandedState()
                    }
                }
        }
    }

    override fun setPreferencesFromResource(preferencesResId: Int, key: String?) {
        allowDiskAccessInStrictMode { super.setPreferencesFromResource(preferencesResId, key) }
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(Color.TRANSPARENT.toDrawable())
    }

    override fun setDividerHeight(height: Int) {
        super.setDividerHeight(0)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
    }

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        // Work around b/331383944: PreferenceFragmentCompat permanently mutates activity theme (enables vertical scrollbars)
        requireContext().theme.applyStyle(R.style.Theme_Gramophone, true)
        super.onDestroy()
    }

    /** Draws the same rounded groups and inset separators used by Accord's main Settings page. */
    private inner class SettingsGroupDecoration : RecyclerView.ItemDecoration() {
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), R.color.settings_card_background)
        }
        private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), R.color.bottomNavigationDividerColor)
            alpha = 128
            strokeWidth = resources.displayMetrics.density.coerceAtLeast(1f)
        }
        private val radius = 14.dpToPx(requireContext()).toFloat()
        private val dividerInset = 14.dpToPx(requireContext()).toFloat()

        @SuppressLint("RestrictedApi")
        override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            val adapter = parent.adapter as? PreferenceGroupAdapter ?: return
            val rows = (0 until parent.childCount).mapNotNull { index ->
                val child = parent.getChildAt(index)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION) null else Triple(child, position, adapter.getItem(position))
            }.sortedBy { it.second }

            var start: View? = null
            var end: View? = null
            val groups = mutableListOf<Pair<View, View>>()
            val separators = mutableListOf<View>()
            fun flush() {
                val first = start ?: return
                val last = end ?: first
                groups += first to last
                start = null
                end = null
            }

            rows.forEachIndexed { index, (child, _, preference) ->
                if (preference is PreferenceCategory) {
                    flush()
                    return@forEachIndexed
                }
                if (start == null) start = child
                end = child
                val next = rows.getOrNull(index + 1)?.third
                if (next != null && next !is PreferenceCategory) {
                    separators += child
                } else {
                    flush()
                }
            }
            flush()
            groups.forEach { (first, last) ->
                canvas.drawRoundRect(
                    RectF(first.left.toFloat(), first.top.toFloat(), last.right.toFloat(), last.bottom.toFloat()),
                    radius, radius, background,
                )
            }
            separators.forEach { child ->
                canvas.drawLine(
                    child.left + dividerInset,
                    child.bottom.toFloat(),
                    child.right.toFloat(),
                    child.bottom.toFloat(),
                    divider,
                )
            }
        }
    }

}
