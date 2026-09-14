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

package org.akanework.gramophone.logic.utils


import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import uk.akane.accord.BuildConfig
import org.akanework.gramophone.logic.use
import org.akanework.gramophone.logic.utils.exoplayer.EndedWorkaroundPlayer
import java.nio.charset.StandardCharsets
import android.os.Handler
import android.os.Looper

@OptIn(UnstableApi::class)
class LastPlayedManager(context: Context,
                        private val controller: EndedWorkaroundPlayer) {

    companion object {
        private const val TAG = "LastPlayedManager"

        /**
         * Bump when the encoding of a saved queue entry changes. Saved state is only a cache of the
         * last queue, so a mismatch discards it rather than trying to migrate.
         *
         * 1: media id and path moved from raw to base64.
         */
        private const val LAST_PLAYED_FORMAT = 1

        /** Long enough to swallow a burst of skips, short enough to survive being killed. */
        private const val SAVE_DEBOUNCE_MS = 1200L
    }

    var allowSavingState = true
    private val prefs = context.applicationContext.getSharedPreferences("LastPlayedManager", 0)
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val saveHandler = Handler(Looper.getMainLooper())

    /**
     * Whether the queue itself has changed since it was last written out.
     *
     * Skipping to the next track changes the index and nothing else, but the queue was being
     * walked, re-encoded and rewritten in full every time - on a queue of several thousand
     * tracks that is seconds of work per press, most of it before the UI can respond. The
     * items are only re-encoded when something actually changed them; a skip writes the
     * position and stops.
     */
    private var queueDirty = true

    /** Called by the service when the playlist is replaced or reordered. */
    fun markQueueDirty() {
        queueDirty = true
    }

    private val saveRunnable = Runnable { performSave() }

    private fun dumpPlaylist(): MediaItemsWithStartPosition {
        val items = mutableListOf<MediaItem>()
        for (i in 0 until controller.mediaItemCount) {
            items.add(controller.getMediaItemAt(i))
        }
        return MediaItemsWithStartPosition(
            items, controller.currentMediaItemIndex, controller.currentPosition
        )
    }

    fun eraseShuffleOrder() {
        prefs.use(relax = true) {
            edit(commit = true) {
                putString("shuffle_persist", null)
            }
        }
    }

    /**
     * Schedules a save.
     *
     * Coalesced, because this is called on every track change and every play/pause: holding
     * down next through a queue otherwise starts one full save per press, and they queue up
     * behind each other. Anything that must not be lost calls [saveNow].
     */
    fun save() {
        if (!allowSavingState) {
            Log.i(TAG, "skipped save")
            return
        }
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, SAVE_DEBOUNCE_MS)
    }

    /** Writes immediately - for shutdown, where a debounced save would never run. */
    fun saveNow() {
        saveHandler.removeCallbacks(saveRunnable)
        if (!allowSavingState) return
        performSave()
    }

    /** Stops work that can access a retired player; already captured saves may finish. */
    fun release() {
        allowSavingState = false
        saveHandler.removeCallbacksAndMessages(null)
        restoreScope.cancel()
    }

    private fun performSave() {
        if (!allowSavingState) {
            Log.i(TAG, "skipped save")
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "dumping playlist...")
        }
        // Only walked when the queue actually changed; see [queueDirty].
        val items = if (queueDirty) dumpPlaylist().mediaItems else null
        queueDirty = false
        val startIndex = controller.currentMediaItemIndex
        val startPosition = controller.currentPosition
        val repeatMode = controller.repeatMode
        val shuffleModeEnabled = controller.shuffleModeEnabled
        val playbackParameters = controller.playbackParameters
        val persistent = controller.shufflePersistent
        val ended = controller.playbackState == Player.STATE_ENDED
        // The disk write may outlive the service, but must not retain this manager or its player.
        val prefs = this.prefs
        CoroutineScope(Dispatchers.Default).launch {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "saving playlist (${items?.size ?: -1} items, repeat $repeatMode, " +
                        "shuffle $shuffleModeEnabled, ended $ended)...")
            }
            val lastPlayed = items?.let { list -> PrefsListUtils.dump(
                list.map {
                    val b = SafeDelimitedStringConcat(":")
                    // add new entries at the bottom and remember they are null for upgrade path
                    // Base64, not raw. A media id is not guaranteed to avoid the ':' delimiter -
                    // anything URL-shaped contains one - and writing it raw threw
                    // IllegalArgumentException from a background coroutine, taking the process
                    // down every time a queue was saved.
                    b.writeStringSafe(it.mediaId)
                    b.writeUri(it.localConfiguration?.uri)
                    b.writeStringSafe(it.localConfiguration?.mimeType)
                    b.writeStringSafe(it.mediaMetadata.title)
                    b.writeStringSafe(it.mediaMetadata.artist)
                    b.writeStringSafe(it.mediaMetadata.albumTitle)
                    b.writeStringSafe(it.mediaMetadata.albumArtist)
                    b.writeUri(it.mediaMetadata.artworkUri)
                    b.writeInt(it.mediaMetadata.trackNumber)
                    b.writeInt(it.mediaMetadata.discNumber)
                    b.writeInt(it.mediaMetadata.recordingYear)
                    b.writeInt(it.mediaMetadata.releaseYear)
                    b.writeBool(it.mediaMetadata.isBrowsable)
                    b.writeBool(it.mediaMetadata.isPlayable)
                    b.writeLong(it.mediaMetadata.extras?.getLong("AddDate"))
                    b.writeStringSafe(it.mediaMetadata.writer)
                    b.writeStringSafe(it.mediaMetadata.compilation)
                    b.writeStringSafe(it.mediaMetadata.composer)
                    b.writeStringSafe(it.mediaMetadata.genre)
                    b.writeInt(it.mediaMetadata.recordingDay)
                    b.writeInt(it.mediaMetadata.recordingMonth)
                    b.writeLong(it.mediaMetadata.extras?.getLong("ArtistId"))
                    b.writeLong(it.mediaMetadata.extras?.getLong("AlbumId"))
                    b.writeLong(it.mediaMetadata.extras?.getLong("GenreId"))
                    b.writeStringSafe(it.mediaMetadata.extras?.getString("Author"))
                    b.writeInt(it.mediaMetadata.extras?.getInt("CdTrackNumber"))
                    b.writeLong(it.mediaMetadata.extras?.getLong("Duration"))
                    // Same hazard as the media id: a Jellyfin item's path is a URL.
                    b.writeStringSafe(it.mediaMetadata.extras?.getString("Path"))
                    b.writeLong(it.mediaMetadata.extras?.getLong("ModifiedDate"))
                    b.toString()
                })
            }
            prefs.edit {
                // Absent when the queue is unchanged - the stored one is still correct, and
                // rewriting several thousand entries to say so is the whole cost being avoided.
                lastPlayed?.let {
                    putStringSet("last_played_lst", it.first)
                    putInt("last_played_format", LAST_PLAYED_FORMAT)
                    putString("last_played_grp", it.second)
                }
                putInt("last_played_idx", startIndex)
                putLong("last_played_pos", startPosition)
                putInt("repeat_mode", repeatMode)
                putBoolean("shuffle", shuffleModeEnabled)
                putString("shuffle_persist", persistent?.toString())
                putBoolean("ended", ended)
                putFloat("speed", playbackParameters.speed)
                putFloat("pitch", playbackParameters.pitch)
                apply()
            }
        }
    }

    fun restore(callback: (MediaItemsWithStartPosition?, CircularShuffleOrder.Persistent) -> Unit) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "decoding playlist...")
        }
        restoreScope.launch {
            val seed = try {
                CircularShuffleOrder.Persistent.deserialize(prefs.getString("shuffle_persist", null))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                eraseShuffleOrder()
                throw e
            }
            try {
                // The media id moved from raw to base64, so anything written by an older build
                // would decode into nonsense rather than fail loudly. Queue state is only a cache,
                // so a format change just discards it.
                if (prefs.getInt("last_played_format", 0) != LAST_PLAYED_FORMAT) {
                    prefs.edit().putInt("last_played_format", LAST_PLAYED_FORMAT).apply()
                    runCallback(callback, seed) { null }
                    return@launch
                }
                val lastPlayedLst = prefs.getStringSet("last_played_lst", null)
                val lastPlayedGrp = prefs.getString("last_played_grp", null)
                val lastPlayedIdx = prefs.getInt("last_played_idx", 0)
                val lastPlayedPos = prefs.getLong("last_played_pos", 0)
                if (lastPlayedGrp == null || lastPlayedLst == null) {
                    runCallback(callback, seed) { null }
                    return@launch
                }
                val repeatMode = prefs.getInt("repeat_mode", Player.REPEAT_MODE_OFF)
                val shuffleModeEnabled = prefs.getBoolean("shuffle", false)
                val ended = prefs.getBoolean("ended", false)
                val playbackParameters = PlaybackParameters(
                    prefs.getFloat("speed", 1f),
                    prefs.getFloat("pitch", 1f)
                )
                val data = MediaItemsWithStartPosition(
                    PrefsListUtils.parse(lastPlayedLst, lastPlayedGrp)
                        .map {
                            val b = SafeDelimitedStringDecat(":", it)
                            val mediaId = b.readStringSafe()
                            val uri = b.readUri()
                            val mimeType = b.readStringSafe()
                            val title = b.readStringSafe()
                            val artist = b.readStringSafe()
                            val album = b.readStringSafe()
                            val albumArtist = b.readStringSafe()
                            val imgUri = b.readUri()
                            val trackNumber = b.readInt()
                            val discNumber = b.readInt()
                            val recordingYear = b.readInt()
                            val releaseYear = b.readInt()
                            val isBrowsable = b.readBool()
                            val isPlayable = b.readBool()
                            val addDate = b.readLong()
                            val writer = b.readStringSafe()
                            val compilation = b.readStringSafe()
                            val composer = b.readStringSafe()
                            val genre = b.readStringSafe()
                            val recordingDay = b.readInt()
                            val recordingMonth = b.readInt()
                            val artistId = b.readLong()
                            val albumId = b.readLong()
                            val genreId = b.readLong()
                            val author = b.readStringSafe()
                            val cdTrackNumber = b.readInt()
                            val duration = b.readLong()
                            val path = b.readStringSafe()
                            val modifiedDate = b.readLong()
                            MediaItem.Builder()
                                .setUri(uri)
                                .setMediaId(mediaId!!)
                                .setMimeType(mimeType)
                                .setMediaMetadata(
                                    MediaMetadata
                                        .Builder()
                                        .setTitle(title)
                                        .setArtist(artist)
                                        .setWriter(writer)
                                        .setComposer(composer)
                                        .setGenre(genre)
                                        .setCompilation(compilation)
                                        .setRecordingDay(recordingDay)
                                        .setRecordingMonth(recordingMonth)
                                        .setAlbumTitle(album)
                                        .setAlbumArtist(albumArtist)
                                        .setArtworkUri(imgUri)
                                        .setTrackNumber(trackNumber)
                                        .setDiscNumber(discNumber)
                                        .setRecordingYear(recordingYear)
                                        .setReleaseYear(releaseYear)
                                        .setIsBrowsable(isBrowsable)
                                        .setIsPlayable(isPlayable)
                                        .setExtras(Bundle().apply {
                                            if (addDate != null) {
                                                putLong("AddDate", addDate)
                                            }
                                            if (artistId != null) {
                                                putLong("ArtistId", artistId)
                                            }
                                            if (albumId != null) {
                                                putLong("AlbumId", albumId)
                                            }
                                            if (genreId != null) {
                                                putLong("GenreId", genreId)
                                            }
                                            if (cdTrackNumber != null) {
                                                putInt("CdTrackNumber", cdTrackNumber)
                                            }
                                            putString("Author", author)
                                            if (duration != null) {
                                                putLong("Duration", duration)
                                            }
                                            putString("Path", path)
                                            if (modifiedDate != null) {
                                                putLong("ModifiedDate", modifiedDate)
                                            }
                                        })
                                        .build()
                                )
                                .build()
                        },
                    lastPlayedIdx,
                    lastPlayedPos
                )
                runCallback(callback, seed) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "restoring playlist (${data.mediaItems.size} items, repeat $repeatMode, " +
                                "shuffle $shuffleModeEnabled, ended $ended)...")
                    }
                    controller.isEnded = ended
                    controller.repeatMode = repeatMode
                    controller.shuffleModeEnabled = shuffleModeEnabled
                    controller.playbackParameters = playbackParameters
                    data
                }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, Log.getStackTraceString(e))
                runCallback(callback, seed) { null }
                return@launch
            }
        }
    }

    private fun runCallback(
        callback: (MediaItemsWithStartPosition?, CircularShuffleOrder.Persistent) -> Unit,
        seed: CircularShuffleOrder.Persistent,
        parameter: () -> MediaItemsWithStartPosition?,
    ) {
        restoreScope.launch(Dispatchers.Main) { callback(parameter(), seed) }
    }
}

private class SafeDelimitedStringConcat(private val delimiter: String) {
    private val b = StringBuilder()
    private var hadFirst = false

    private fun append(s: String?) {
        if (s?.contains(delimiter, false) == true) {
            throw IllegalArgumentException("argument must not contain delimiter")
        }
        if (hadFirst) {
            b.append(delimiter)
        } else {
            hadFirst = true
        }
        s?.let { b.append(it) }
    }

    override fun toString(): String {
        return b.toString()
    }

    fun writeStringUnsafe(s: CharSequence?) = append(s?.toString())
    fun writeBase64(b: ByteArray?) = append(b?.let { Base64.encodeToString(it, Base64.DEFAULT) })
    fun writeStringSafe(s: CharSequence?) =
        writeBase64(s?.toString()?.toByteArray(StandardCharsets.UTF_8))

    fun writeInt(i: Int?) = append(i?.toString())
    fun writeLong(i: Long?) = append(i?.toString())
    fun writeBool(b: Boolean?) = append(b?.toString())
    fun writeUri(u: Uri?) = writeStringSafe(u?.toString())
}

private class SafeDelimitedStringDecat(delimiter: String, str: String) {
    private val items = str.split(delimiter)
    private var pos = 0

    private fun read(): String? {
        if (pos == items.size) return null
        return items[pos++].ifEmpty { null }
    }

    fun readStringUnsafe(): String? = read()
    fun readBase64(): ByteArray? = read()?.let { Base64.decode(it, Base64.DEFAULT) }
    fun readStringSafe(): String? = readBase64()?.toString(StandardCharsets.UTF_8)
    fun readInt(): Int? = read()?.toInt()
    fun readLong(): Long? = read()?.toLong()
    fun readBool(): Boolean? = read()?.toBooleanStrict()
    fun readUri(): Uri? = readStringSafe()?.toUri()
}

private object PrefsListUtils {
    fun parse(stringSet: Set<String>, groupStr: String): List<String> {
        val groups = groupStr.split(",")
        return groups.map { hashCode ->
            stringSet.first { it.hashCode().toString() == hashCode }
        }
    }

    fun dump(list: List<String>): Pair<Set<String>, String> {
        return Pair(list.toSet(), list.joinToString(",") { it.hashCode().toString() })
    }
}
