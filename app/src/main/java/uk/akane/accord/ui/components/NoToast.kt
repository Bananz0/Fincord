package uk.akane.accord.ui.components

import android.content.Context
import androidx.annotation.StringRes

/**
 * Accord deliberately does not use Android Toasts.
 *
 * They obscure controls, queue confirmations can arrive after the queue has already changed, and
 * several OEMs keep them on screen long enough to look like part of the player. Existing call
 * sites route through this compatibility sink so no transient system overlay is created while the
 * underlying action and its visible state change remain untouched.
 */
object NoToast {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1

    fun makeText(context: Context?, text: CharSequence?, duration: Int): NoToast = this

    fun makeText(context: Context?, @StringRes text: Int, duration: Int): NoToast = this

    fun show() = Unit
}
