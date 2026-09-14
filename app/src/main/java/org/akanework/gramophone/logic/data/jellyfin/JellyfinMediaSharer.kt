package org.akanework.gramophone.logic.data.jellyfin

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import uk.akane.accord.BuildConfig
import uk.akane.accord.R
import java.io.File
import java.io.FileOutputStream

/** Materialises a track as a private, read-only file suitable for Android's share sheet. */
object JellyfinMediaSharer {

    data class SharedMedia(val uri: Uri, val mimeType: String, val displayName: String)

    /**
     * Copies the original media bytes into cache before sharing.
     *
     * Jellyfin's playback URL contains the account's API key. Sending that URL would give the
     * recipient access to the server and would also fail as soon as the session changed. A scoped
     * FileProvider URI shares only this file, and Android revokes the grant with the receiving
     * activity.
     */
    suspend fun prepare(context: Context, item: MediaItem): SharedMedia = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        JellyfinEndpoints.selectReachableStoredEndpoint()
        val source = item.localConfiguration?.uri?.let { uri ->
            JellyfinEndpoints.rewriteServerBase(uri, JellyfinClientHolder.credentials.serverUrl)
        }
            ?: error(appContext.getString(R.string.share_song_source_missing))
        val declaredContainer = item.mediaMetadata.extras
            ?.getString(JellyfinLibraryLoader.EXTRA_SOURCE_CONTAINER)
        val format = sharedAudioFormat(declaredContainer, source.lastPathSegment)
        val title = item.mediaMetadata.title?.toString().orEmpty()
            .ifBlank { appContext.getString(R.string.default_track) }
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        val displayName = buildString {
            append(title)
            if (artist.isNotBlank()) append(" — ").append(artist)
        }.safeFileName() + ".${format.extension}"

        val shareDirectory = File(appContext.cacheDir, SHARE_DIRECTORY).apply {
            check(isDirectory || mkdirs()) { "Could not create the media share cache" }
        }
        val destination = File(shareDirectory, displayName)
        val partial = File(shareDirectory, "$displayName.partial")
        partial.delete()

        var responseMime: String? = null
        try {
            FileOutputStream(partial).use { output ->
                when (source.scheme?.lowercase()) {
                    "http", "https" -> {
                        val request = Request.Builder().url(source.toString()).get().build()
                        JellyfinClientHolder.mediaHttpClient().newCall(request).execute().use { response ->
                            check(response.isSuccessful) {
                                "Jellyfin returned HTTP ${response.code} while sharing the track"
                            }
                            // Nullable only in OkHttp 4's type: the sub-responses hanging off a
                            // Response have no body, but the one a call returns always does. There
                            // is nothing to share without it, so say so rather than writing an
                            // empty file the user then has to work out the meaning of.
                            val body = checkNotNull(response.body) {
                                "Jellyfin returned no body while sharing the track"
                            }
                            responseMime = body.contentType()?.toString()?.substringBefore(';')
                            body.byteStream().use { it.copyTo(output, COPY_BUFFER_BYTES) }
                        }
                    }
                    "content" -> {
                        responseMime = appContext.contentResolver.getType(source)
                        appContext.contentResolver.openInputStream(source)?.use { input ->
                            input.copyTo(output, COPY_BUFFER_BYTES)
                        } ?: error("The track could not be opened")
                    }
                    "file", null -> {
                        val file = if (source.scheme == "file") File(source.path.orEmpty())
                        else File(source.toString())
                        file.inputStream().use { it.copyTo(output, COPY_BUFFER_BYTES) }
                    }
                    else -> error("Unsupported media source: ${source.scheme}")
                }
            }
            check(partial.length() > 0L) { "The shared track is empty" }
            if (destination.exists()) check(destination.delete()) {
                "Could not replace the previous shared track"
            }
            check(partial.renameTo(destination)) { "Could not finish preparing the shared track" }
        } catch (failure: Throwable) {
            partial.delete()
            throw failure
        }

        val mimeType = responseMime?.takeIf { it.startsWith("audio/") } ?: format.mimeType
        SharedMedia(
            uri = FileProvider.getUriForFile(
                appContext,
                "${BuildConfig.APPLICATION_ID}.shared-media",
                destination,
            ),
            mimeType = mimeType,
            displayName = displayName,
        )
    }

    fun intent(context: Context, media: SharedMedia): Intent = Intent(Intent.ACTION_SEND).apply {
        type = media.mimeType
        putExtra(Intent.EXTRA_STREAM, media.uri)
        putExtra(Intent.EXTRA_TITLE, media.displayName)
        clipData = ClipData.newUri(context.contentResolver, media.displayName, media.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun String.safeFileName(): String =
        replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim(' ', '.')
            .take(MAX_FILE_NAME_CHARS)
            .ifBlank { "Fincord track" }

    private const val SHARE_DIRECTORY = "shared_media"
    private const val COPY_BUFFER_BYTES = 128 * 1024
    private const val MAX_FILE_NAME_CHARS = 120
}

internal data class SharedAudioFormat(val extension: String, val mimeType: String)

internal fun sharedAudioFormat(container: String?, pathSegment: String?): SharedAudioFormat {
    val value = container
        ?.substringBefore(',')
        ?.trim()
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
        ?: pathSegment?.substringAfterLast('.', "")?.lowercase()
    return when (value) {
        "aac", "adts" -> SharedAudioFormat("aac", "audio/aac")
        "flac" -> SharedAudioFormat("flac", "audio/flac")
        "mp3", "mpeg" -> SharedAudioFormat("mp3", "audio/mpeg")
        "ogg", "oga", "vorbis" -> SharedAudioFormat("ogg", "audio/ogg")
        "opus" -> SharedAudioFormat("opus", "audio/opus")
        "m4a", "mp4", "alac" -> SharedAudioFormat("m4a", "audio/mp4")
        "wav", "wave" -> SharedAudioFormat("wav", "audio/wav")
        "wma" -> SharedAudioFormat("wma", "audio/x-ms-wma")
        "aiff", "aif" -> SharedAudioFormat("aiff", "audio/aiff")
        else -> SharedAudioFormat("audio", "audio/*")
    }
}
