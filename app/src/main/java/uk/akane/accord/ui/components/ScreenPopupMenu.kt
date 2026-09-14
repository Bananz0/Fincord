package uk.akane.accord.ui.components

import android.content.res.Resources
import uk.akane.accord.R
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.accord.ui.MainActivity

/**
 * The three-dot menu on an ordinary screen.
 *
 * Upstream shows the *player's* menu from every navigation bar in the app, so tapping the three dots
 * on the Albums screen offered to share the lyrics of whatever happened to be playing. That menu
 * belongs to the player and stays there; a screen gets one about the screen.
 *
 * Screens that want something more specific pass their own entries to
 * [NavigationBar.setMenuEntries]; this is what the rest get.
 */
object ScreenPopupMenu {

    enum class Action { SHUFFLE_ALL, REFRESH_LIBRARY, SETTINGS }

    fun build(resources: Resources): PopupHelper.PopupEntries =
        PopupHelper.PopupMenuBuilder()
            .addMenuEntry(
                resources, R.drawable.ic_shuffle, R.string.home_menu_shuffle, Action.SHUFFLE_ALL
            )
            .addMenuEntry(
                resources, R.drawable.ic_refresh, R.string.home_menu_refresh, Action.REFRESH_LIBRARY
            )
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_settings, R.string.home_menu_settings, Action.SETTINGS
            )
            .build()

    fun handle(activity: MainActivity, entry: PopupHelper.PopupEntry) {
        when ((entry as? PopupHelper.MenuEntry)?.payload as? Action) {
            Action.SHUFFLE_ALL -> activity.shuffleWholeLibrary()
            Action.REFRESH_LIBRARY -> activity.updateLibrary()
            Action.SETTINGS -> activity.openSettings()
            null -> Unit
        }
    }
}
