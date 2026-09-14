package uk.akane.accord

import android.app.Application
import android.content.ContentUris
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.ContentMetadata
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.NullRequestDataException
import coil3.size.pxOrElse
import coil3.toCoilUri
import coil3.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import org.lsposed.hiddenapibypass.LSPass
import org.akanework.gramophone.logic.data.library.BlacklistStore
import org.akanework.gramophone.logic.data.library.FilteredLibraryReader
import org.akanework.gramophone.logic.data.library.JellyfinLibraryReader
import org.akanework.gramophone.logic.data.library.LibraryReader
import uk.akane.accord.logic.hasScopedStorageWithMediaTypes
import uk.akane.libphonograph.Constants
import uk.akane.libphonograph.reader.FlowReader
import uk.akane.libphonograph.utils.MiscUtils
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Upstream Accord's application class.
 *
 * Left open, and with its cover fetchers and Coil logger split out, so this fork's
 * [org.akanework.gramophone.logic.GramophoneApplication] can extend it: the Accord screens reach
 * for `application as Accord` to get [reader], while everything this fork adds on top - Jellyfin,
 * the crash handler, the network artwork cache - keeps living in the subclass.
 */
open class Accord : Application(), SingletonImageLoader.Factory {

    /**
     * What the screens read the library from. Upstream exposes libPhonograph's [FlowReader] here
     * directly; this app serves the Jellyfin library alongside the local one, so the screens get
     * the [LibraryReader] interface instead and the composition is decided in [onCreate].
     */
    /**
     * Where a library sync runs.
     *
     * Owned by the application, not by whichever screen asked. A full Jellyfin sync is minutes
     * of sequential network work, and on an activity-bound scope the activity is kept alive for
     * all of it - leaving the app mid-sync leaked the whole screen and every bitmap on it.
     */
    private val libraryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var libraryRefresh: Job? = null

    /** Starts a sync, or returns the one already running rather than starting a second. */
    fun refreshLibrary(force: Boolean = false): Job {
        libraryRefresh?.takeIf { it.isActive }?.let { return it }
        return libraryScope.launch {
            if (force) jellyfinReader.refresh(force = true) else reader.refresh()
        }.also { libraryRefresh = it }
    }

    lateinit var reader: LibraryReader
        private set

    /** The Jellyfin half, exposed for the screens that show sync progress or sign-in state. */
    lateinit var jellyfinReader: JellyfinLibraryReader
        private set

    /** Shared with the blacklist settings page, which writes what [reader] then filters by. */
    lateinit var blacklist: BlacklistStore
        private set

    /**
     * The library before the blacklist is applied.
     *
     * Only the blacklist page should want this: listing from [reader] would hide the very entries
     * somebody needs to see in order to unblock them.
     */
    lateinit var unfilteredReader: LibraryReader
        private set

    init {
        LSPass.setHiddenApiExemptions("")
        if (BuildConfig.DEBUG)
            System.setProperty("kotlinx.coroutines.debug", "on")
    }

    companion object {
        // not actually defined in API, but CTS tested
        // https://cs.android.com/android/platform/superproject/main/+/main:packages/providers/MediaProvider/src/com/android/providers/media/LocalUriMatcher.java;drc=ddf0d00b2b84b205a2ab3581df8184e756462e8d;l=182
        private const val MEDIA_ALBUM_ART = "albumart"
    }

    override fun onCreate() {
        super.onCreate()
        // The Jellyfin server is the library. There is no local half to merge in: reading the
        // device would mean asking for storage access this app has no use for.
        jellyfinReader = JellyfinLibraryReader(this)
        blacklist = BlacklistStore(this)
        unfilteredReader = jellyfinReader
        // Wrapped so every screen reading `reader` gets the blacklist applied for free.
        reader = FilteredLibraryReader(unfilteredReader, blacklist)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .diskCache(null)
            .components { addLocalCoverFetchers() }
            .applyCoilLogging()
            .build()
    }

    /**
     * Registers the two fetchers that resolve MediaStore-backed cover art: `gramophoneSongCover`
     * for a song's embedded artwork and `gramophoneAlbumCover` for an album's. Both schemes are
     * minted by libPhonograph, so any subclass building its own [ImageLoader] has to add these or
     * local artwork silently stops loading.
     */
    protected fun ComponentRegistry.Builder.addLocalCoverFetchers() {
        run {
                add(Fetcher.Factory { data, options, _ ->
                    if (data !is Uri) return@Factory null
                    if (data.scheme != "gramophoneSongCover") return@Factory null
                    return@Factory Fetcher {
                        val file = File(data.path!!)
                        val uri = ContentUris.appendId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon(), data.authority!!.toLong()
                        ).appendPath(MEDIA_ALBUM_ART).build()
                        val bmp = if (options.size.width.pxOrElse { 0 } > 300
                            && options.size.height.pxOrElse { 0 } > 300) try {
                            ThumbnailUtils.createAudioThumbnail(file, options.size.let {
                                Size(
                                    it.width.pxOrElse { throw IllegalArgumentException("missing required size") },
                                    it.height.pxOrElse { throw IllegalArgumentException("missing required size") })
                            }, null)
                        } catch (e: IOException) {
                            if (e.message != "No embedded album art found" &&
                                e.message != "No thumbnails in Downloads directories" &&
                                e.message != "No thumbnails in top-level directories" &&
                                e.message != "No album art found")
                                throw e
                            null
                        } else null
                        if (bmp != null) {
                            ImageFetchResult(
                                bmp.asImage(), true, DataSource.DISK
                            )
                        } else {
                            if (uri == null) return@Fetcher null
                            val stream = contentResolver.openAssetFileDescriptor(uri, "r")
                            checkNotNull(stream) { "Unable to open '$uri'." }
                            SourceFetchResult(
                                source = ImageSource(
                                    source = stream.createInputStream().source().buffer(),
                                    fileSystem = options.fileSystem,
                                    metadata = ContentMetadata(uri.toCoilUri(), stream),
                                ),
                                mimeType = contentResolver.getType(uri),
                                dataSource = DataSource.DISK,
                            )
                        }
                    }
                })
                add(Fetcher.Factory { data, options, _ ->
                    if (data !is Uri) return@Factory null
                    if (data.scheme != "gramophoneAlbumCover") return@Factory null
                    return@Factory Fetcher {
                        val cover = MiscUtils.findBestCover(File(data.path!!))
                        if (cover == null) {
                            val uri =
                                ContentUris.withAppendedId(Constants.baseAlbumCoverUri, data.authority!!.toLong())
                            val contentResolver = options.context.contentResolver
                            val afd = contentResolver.openAssetFileDescriptor(uri, "r")
                            checkNotNull(afd) { "Unable to open '$uri'." }
                            return@Fetcher SourceFetchResult(
                                source = ImageSource(
                                    source = afd.createInputStream().source().buffer(),
                                    fileSystem = options.fileSystem,
                                    metadata = ContentMetadata(data, afd),
                                ),
                                mimeType = contentResolver.getType(uri),
                                dataSource = DataSource.DISK,
                            )
                        }
                        return@Fetcher SourceFetchResult(
                            ImageSource(cover.toOkioPath(), options.fileSystem, null, null, null),
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(cover.extension),
                            DataSource.DISK
                        )
                    }
                })
        }
    }

    /**
     * Verbose Coil logging on debug builds, quiet on release. Kept out of [newImageLoader] so
     * subclasses that build their own loader get the same behaviour.
     */
    protected fun ImageLoader.Builder.applyCoilLogging(): ImageLoader.Builder =
            run {
                if (!BuildConfig.DEBUG) this else
                    logger(object : Logger {
                        override var minLevel = Logger.Level.Verbose
                        override fun log(
                            tag: String,
                            level: Logger.Level,
                            message: String?,
                            throwable: Throwable?
                        ) {
                            if (level < minLevel) return
                            val priority = level.ordinal + 2 // obviously the best way to do it
                            if (message != null) {
                                Log.println(priority, tag, message)
                            }
                            // Let's keep the log readable and ignore normal events' stack traces.
                            if (throwable != null && throwable !is NullRequestDataException
                                && (throwable !is IOException
                                        || throwable.message != "No album art found")
                            ) {
                                Log.println(priority, tag, Log.getStackTraceString(throwable))
                            }
                        }
                    })
            }
}
