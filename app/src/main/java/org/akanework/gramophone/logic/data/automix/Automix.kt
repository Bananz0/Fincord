package org.akanework.gramophone.logic.data.automix

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * The Automix setting, in one place.
 *
 * The pill in the full player writes it and the playback service reads it, and those two live in
 * different source trees, so the key had no home that both could see without one of them reaching
 * into the other.
 */
object Automix {

    const val PREF_KEY = "automix_transitions"

    /** Which of the [MixStyle]s to use, stored by enum name. */
    const val PREF_STYLE_KEY = "automix_style"

    /** Whether the user has asked for mixed transitions. */
    fun isEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false)

    /** How to mix, falling back to [MixStyle.DEFAULT] for anything unset or unrecognised. */
    fun style(context: Context): MixStyle =
        MixStyle.fromPreference(
            PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_STYLE_KEY, null)
        )
}
