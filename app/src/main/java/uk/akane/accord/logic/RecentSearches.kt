package uk.akane.accord.logic

import android.content.Context
import androidx.core.content.edit

/**
 * The last few things the user searched for.
 *
 * The search screen has always drawn a "Recently Searched" heading and a Clear button with nothing
 * behind either - no storage, no list, no listener. This is what they were describing.
 *
 * Deliberately not in the library database. These are a handful of short strings that are worthless
 * to anything else, and a query typed a second ago has to appear the moment the field is cleared;
 * going through Room for that would mean a thread hop to read six words.
 */
object RecentSearches {

    private const val PREFERENCES = "recent_searches"
    private const val KEY = "queries"

    /**
     * Kept short. This list sits between the user and the search field on an otherwise empty
     * screen, and a long history of near-identical typos is not a feature.
     */
    private const val MAX = 8

    /**
     * A newline, which a search query cannot contain. Not a space - queries are full of those.
     *
     * Stored as one delimited string rather than a string set: a set has no order, and order is
     * the whole meaning of a recents list.
     */
    private val SEPARATOR = Char(10)

    fun recent(context: Context): List<String> =
        prefs(context).getString(KEY, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    /**
     * Records a query the user acted on, newest first.
     *
     * Called when a result is opened rather than on every keystroke: a history of every prefix
     * typed on the way to a word is noise, and "kend", "kendr", "kendri" would fill the whole list
     * before the user reached what they were looking for.
     */
    fun remember(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val existing = recent(context).filterNot { it.equals(trimmed, ignoreCase = true) }
        val updated = (listOf(trimmed) + existing).take(MAX)
        prefs(context).edit { putString(KEY, updated.joinToString(SEPARATOR.toString())) }
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
