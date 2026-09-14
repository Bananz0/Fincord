package org.akanework.gramophone.logic.data.library

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user has asked never to see again.
 *
 * The blacklist page only ever hid folders, which is close to useless against a Jellyfin library:
 * the server's tracks have no folder the app knows about, so the page listed the handful of local
 * directories and nothing else. Artists and individual songs are what people actually want gone -
 * the comedy album in the middle of a shuffle, the one artist a shared server keeps offering.
 *
 * Artists are stored by normalised name rather than id. The same artist arrives with a different id
 * from Jellyfin and from MediaStore, and blocking one of the two would leave the other playing.
 * Songs are stored by media id, which is stable per source and unambiguous.
 */
class BlacklistStore(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private val _artists = MutableStateFlow(read(KEY_ARTISTS))
    private val _songs = MutableStateFlow(read(KEY_SONGS))

    /** Normalised artist names. Compare with [normaliseArtist] before looking anything up. */
    val artists: StateFlow<Set<String>> = _artists.asStateFlow()

    /** Media ids. */
    val songs: StateFlow<Set<String>> = _songs.asStateFlow()

    fun isArtistBlocked(name: String?): Boolean =
        name != null && normaliseArtist(name) in _artists.value

    fun isSongBlocked(mediaId: String?): Boolean = mediaId != null && mediaId in _songs.value

    fun setArtistBlocked(name: String, blocked: Boolean) =
        update(KEY_ARTISTS, _artists, normaliseArtist(name), blocked)

    fun setSongBlocked(mediaId: String, blocked: Boolean) =
        update(KEY_SONGS, _songs, mediaId, blocked)

    private fun update(
        key: String,
        state: MutableStateFlow<Set<String>>,
        value: String,
        blocked: Boolean,
    ) {
        if (value.isBlank()) return
        val updated = state.value.toMutableSet().apply {
            if (blocked) add(value) else remove(value)
        }
        prefs.edit { putStringSet(key, updated) }
        // Emitted after the write so a reader reacting to this always finds the stored value.
        state.value = updated
    }

    private fun read(key: String): Set<String> =
        prefs.getStringSet(key, null)?.toSet().orEmpty()

    companion object {
        private const val KEY_ARTISTS = "artistBlacklist"
        private const val KEY_SONGS = "songBlacklist"

        /**
         * Case and punctuation are not a meaningful difference between two spellings of one artist,
         * and tags disagree about both constantly.
         */
        fun normaliseArtist(name: String): String =
            name.lowercase().filter { it.isLetterOrDigit() }
    }
}
