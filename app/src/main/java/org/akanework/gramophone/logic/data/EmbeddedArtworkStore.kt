package org.akanework.gramophone.logic.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * The cover a track carries in its own tags, kept on disk under the media id.
 *
 * Jellyfin serves the same picture over HTTP, and that URL is what the library builds - but it is
 * a request, and a track can start playing before it answers. The file's own copy needs no server,
 * so once it has been seen it is written here and every later play of that track resolves its
 * cover from local storage instead.
 *
 * Written by the playback service and read by the player, which is the whole reason this is a
 * shared object rather than a private helper. `MediaMetadata.artworkData` only exists on the
 * ExoPlayer side: media3 does not carry artwork bytes across the MediaSession boundary, because a
 * cover would exceed what a Binder transaction can hold. The UI therefore cannot capture this for
 * itself, no matter how it asks.
 */
object EmbeddedArtworkStore {

    /** Where [mediaId]'s embedded cover lives, whether or not it has been written yet. */
    fun fileFor(context: Context, mediaId: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "$DIRECTORY/$name")
    }

    /**
     * Writes [bytes] for [mediaId], returning the file.
     *
     * Written to a sibling and renamed so a reader can never see a half-written cover: the rename
     * is atomic, the write is not. Identical content is left alone rather than rewritten.
     */
    fun store(context: Context, mediaId: String, bytes: ByteArray): File {
        val target = fileFor(context, mediaId)
        target.parentFile?.mkdirs()
        val unchanged = target.isFile && target.length() == bytes.size.toLong() &&
            runCatching { target.readBytes().contentEquals(bytes) }.getOrDefault(false)
        if (unchanged) return target
        val pending = File(target.parentFile, "${target.name}.pending")
        pending.writeBytes(bytes)
        if (!pending.renameTo(target)) {
            target.writeBytes(bytes)
            pending.delete()
        }
        return target
    }

    private const val DIRECTORY = "embedded_artwork"
}
