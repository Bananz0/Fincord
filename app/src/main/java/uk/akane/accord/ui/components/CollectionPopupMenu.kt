package uk.akane.accord.ui.components

import android.content.res.Resources
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDownloadManager
import uk.akane.accord.R
import uk.akane.accord.ui.MainActivity
import uk.akane.accord.ui.components.player.PlayerMenuActions
import uk.akane.cupertino.popup.PopupHelper
import uk.akane.accord.ui.fragments.browse.AddToPlaylistFragment
import uk.akane.accord.ui.fragments.browse.AlbumDetailFragment
import uk.akane.accord.ui.fragments.browse.ArtistDetailFragment
import kotlin.random.Random

/**
 * The three-dot menu on a screen that *is* a collection - an album, a playlist, an artist, a station.
 *
 * Those screens were falling through to the general screen menu, which offers to refresh the library
 * and open settings: true of every screen and useful on none of these. What a listener wants here is
 * to do something with the thing they are looking at.
 */
object CollectionPopupMenu {

    enum class Action {
        PLAY, SHUFFLE, PLAY_NEXT, ADD_TO_QUEUE, ADD_TO_PLAYLIST,
        DOWNLOAD, REMOVE_DOWNLOAD, GO_TO_ARTIST, GO_TO_ALBUM,
        RENAME, CHANGE_PICTURE, DELETE, DELETE_FROM_SERVER,
        SAVE_AS_PLAYLIST, FIND_MORE_RELEASES,
    }

    /** The action an entry carries, for screens that handle some of them themselves. */
    fun actionOf(entry: PopupHelper.PopupEntry): Action? =
        (entry as? PopupHelper.MenuEntry)?.payload as? Action

    /**
     * @param withArtist whether "Go to Artist" is worth offering - it is not on the artist's own page.
     * @param withAlbum likewise for "Go to Album", which only means something on a mixed collection.
     */
    fun build(
        resources: Resources,
        withArtist: Boolean = true,
        withAlbum: Boolean = false,
        withPlaylistManagement: Boolean = false,
        withQueueActions: Boolean = true,
        withDownload: Boolean = false,
        withRemoveDownload: Boolean = false,
        withServerDelete: Boolean = false,
        withSaveAsPlaylist: Boolean = false,
        withFindMoreReleases: Boolean = false,
    ): PopupHelper.PopupEntries =
        PopupHelper.PopupMenuBuilder()
            .addMenuEntry(resources, R.drawable.ic_master_play, R.string.play, Action.PLAY)
            .addMenuEntry(resources, R.drawable.ic_shuffle, R.string.shuffle, Action.SHUFFLE)
            .apply {
                if (withQueueActions) {
                    addMenuEntry(
                        resources, R.drawable.ic_master_play, R.string.play_next, Action.PLAY_NEXT
                    )
                    addMenuEntry(
                        resources, R.drawable.ic_bulletin_select, R.string.add_to_queue,
                        Action.ADD_TO_QUEUE
                    )
                }
            }
            .addSpacer()
            .addMenuEntry(
                resources, R.drawable.ic_playlist, R.string.popup_add_to_a_playlist,
                Action.ADD_TO_PLAYLIST
            )
            .apply {
                if (withSaveAsPlaylist) {
                    addMenuEntry(
                        resources, R.drawable.ic_plus, R.string.station_save_as_playlist,
                        Action.SAVE_AS_PLAYLIST
                    )
                }
                if (withDownload) {
                    addMenuEntry(
                        resources, R.drawable.ic_download, R.string.download,
                        Action.DOWNLOAD
                    )
                }
                if (withRemoveDownload) {
                    addMenuEntry(
                        resources, R.drawable.ic_trash, R.string.collection_remove_from_device,
                        Action.REMOVE_DOWNLOAD
                    )
                }
                if (withArtist) {
                    addMenuEntry(
                        resources, R.drawable.ic_person_small, R.string.go_to_artist,
                        Action.GO_TO_ARTIST
                    )
                }
                if (withAlbum) {
                    addMenuEntry(
                        resources, R.drawable.ic_album, R.string.go_to_album, Action.GO_TO_ALBUM
                    )
                }
                // The library can only answer "what do I have"; the downloader answers "what did
                // they make". On an artist's page that is the more interesting of the two, and it
                // was three screens away.
                if (withFindMoreReleases) {
                    addMenuEntry(
                        resources, R.drawable.ic_magnifying_glass,
                        R.string.collection_find_more_releases, Action.FIND_MORE_RELEASES
                    )
                }
                // Only a playlist the user made can be renamed, re-covered or deleted.
                if (withPlaylistManagement) {
                    addSpacer()
                    addMenuEntry(
                        resources, R.drawable.ic_edit, R.string.playlist_rename, Action.RENAME
                    )
                    addMenuEntry(
                        resources, R.drawable.ic_album, R.string.playlist_change_picture,
                        Action.CHANGE_PICTURE
                    )
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.playlist_delete, Action.DELETE
                    )
                }
                if (withServerDelete) {
                    addSpacer()
                    addDestructiveMenuEntry(
                        resources, R.drawable.ic_trash, R.string.collection_delete_from_server,
                        Action.DELETE_FROM_SERVER
                    )
                }
            }
            .build()

    /**
     * @param tracks what the screen is showing, in the order it shows them.
     * @param title the collection's name, for anything opened from here.
     */
    fun handle(
        activity: MainActivity,
        entry: PopupHelper.PopupEntry,
        tracks: List<MediaItem>,
        title: String,
    ) {
        if (tracks.isEmpty()) {
            Toast.makeText(activity, R.string.no_tracks_available, Toast.LENGTH_SHORT).show()
            return
        }
        when ((entry as? PopupHelper.MenuEntry)?.payload as? Action) {
            Action.PLAY -> play(activity, tracks)
            Action.SHUFFLE -> play(activity, tracks.shuffled(Random(title.hashCode())))
            Action.PLAY_NEXT -> {
                val player = activity.getPlayer() ?: return
                if (player.mediaItemCount == 0) {
                    play(activity, tracks)
                } else {
                    tracks.asReversed().forEach { item ->
                        player.addMediaItem(player.currentMediaItemIndex + 1, item)
                    }
                }
                Toast.makeText(activity, R.string.queued_next, Toast.LENGTH_SHORT).show()
            }
            Action.ADD_TO_QUEUE -> {
                val player = activity.getPlayer() ?: return
                if (player.mediaItemCount == 0) play(activity, tracks)
                else player.addMediaItems(tracks)
                Toast.makeText(activity, R.string.queued, Toast.LENGTH_SHORT).show()
            }
            Action.ADD_TO_PLAYLIST -> {
                activity.collapseNowPlaying()
                activity.fragmentSwitcherView.addFragmentToCurrentStack(
                    AddToPlaylistFragment.newInstance(tracks.map { it.mediaId })
                )
            }
            Action.DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.download(activity, tracks)
                }
                Toast.makeText(activity, R.string.download_started, Toast.LENGTH_SHORT).show()
            }
            Action.REMOVE_DOWNLOAD -> {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    JellyfinDownloadManager.remove(activity, tracks)
                }
                Toast.makeText(activity, R.string.download_removed, Toast.LENGTH_SHORT).show()
            }
            Action.GO_TO_ARTIST -> {
                val first = tracks.first()
                val artist = first.mediaMetadata.albumArtist?.toString()
                    ?: first.mediaMetadata.artist?.toString()
                if (artist.isNullOrBlank()) {
                    Toast.makeText(activity, R.string.go_to_artist_missing, Toast.LENGTH_SHORT).show()
                } else {
                    activity.collapseNowPlaying()
                    activity.fragmentSwitcherView.addFragmentToCurrentStack(
                        ArtistDetailFragment.newInstance(artist)
                    )
                }
            }
            Action.GO_TO_ALBUM -> {
                val first = tracks.first()
                val album = first.mediaMetadata.albumTitle?.toString()
                if (album.isNullOrBlank()) {
                    Toast.makeText(activity, R.string.go_to_album_missing, Toast.LENGTH_SHORT).show()
                } else {
                    activity.collapseNowPlaying()
                    val artist = first.mediaMetadata.albumArtist?.toString()
                        ?: first.mediaMetadata.artist?.toString().orEmpty()
                    activity.fragmentSwitcherView.addFragmentToCurrentStack(
                        AlbumDetailFragment.newInstance(album, artist)
                    )
                }
            }
            Action.FIND_MORE_RELEASES -> FindMoreReleases.open(activity, title)
            // Handled by the playlist screen itself, which owns the file being renamed or deleted.
            Action.RENAME, Action.CHANGE_PICTURE, Action.DELETE, Action.DELETE_FROM_SERVER,
            Action.SAVE_AS_PLAYLIST -> Unit
            null -> Unit
        }
    }

    private fun play(activity: MainActivity, tracks: List<MediaItem>) {
        activity.getPlayer()?.apply {
            setMediaItems(tracks, 0, C.TIME_UNSET)
            prepare()
            play()
        }
    }

}
