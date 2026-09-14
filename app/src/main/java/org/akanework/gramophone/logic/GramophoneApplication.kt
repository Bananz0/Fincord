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

package org.akanework.gramophone.logic


import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.media.ThumbnailUtils
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import android.os.StrictMode.VmPolicy
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatDelegate
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.map.Mapper
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import coil3.request.NullRequestDataException
import coil3.request.allowHardware
import coil3.size.pxOrElse
import coil3.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.akane.accord.Accord
import uk.akane.accord.BuildConfig
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.akanework.gramophone.logic.data.lidarr.LidarrCredentialStore
import org.akanework.gramophone.logic.data.lidarr.LidarrServerSync
import org.akanework.gramophone.ui.BugHandlerActivity
import uk.akane.accord.logic.cast.FincordCast
import uk.akane.accord.ui.components.LidarrSetupPrompt
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * GramophoneApplication
 *
 * @author AkaneTan, nift4
 */
class GramophoneApplication : Accord(), Thread.UncaughtExceptionHandler {

    lateinit var prefs: SharedPreferences
        private set

    private val serviceIntegrationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lidarrPluginSync: Job? = null

    /**
     * Pulls Lidarr from the authenticated Jellyfin plugin and discovers request defaults.
     *
     * Application-owned so sign-in, onboarding and Settings all join one operation instead of
     * making the user open the Lidarr screen to trigger it. A screen can disappear while this
     * continues without cancelling setup halfway through.
     */
    @Synchronized
    fun syncLidarrFromPlugin(): Job {
        lidarrPluginSync?.takeIf { it.isActive }?.let { return it }
        return serviceIntegrationScope.launch {
            val adopted = LidarrServerSync.sync(this@GramophoneApplication)
            val store = LidarrCredentialStore(this@GramophoneApplication)
            if ((adopted || store.isSyncedThroughPlugin) &&
                !store.serverUrl.isNullOrBlank() && !store.apiKey.isNullOrBlank()
            ) {
                LidarrSetupPrompt.autoConfigure(this@GramophoneApplication)
                    .onFailure { Log.w(TAG, "Plugin Lidarr defaults could not be synced", it) }
            }
        }.also { lidarrPluginSync = it }
    }

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Cheap: only records the application context. The credential store and SDK behind it are
        // built lazily, off the main thread.
        JellyfinClientHolder.init(this)

        // Refresh plugin-managed Lidarr on every authenticated process start. This is deliberately
        // independent of any settings screen: configuration is server state, not a click action.
        if (JellyfinCredentialStore.hasStoredSession(this)) syncLidarrFromPlugin()

        // Started here rather than from the player, because the route category the output picker
        // builds has to know whether Cast came up before it is first read. Returns false and does
        // nothing on a device without Play Services.
        FincordCast.initialize(this)

        if (BuildConfig.DEBUG) {
            // Use StrictMode to find anti-pattern issues
            // penaltyLog() without penaltyDialog(): One UI's own IdsController.openIdsWindow()
            // calls deleteSharedPreferences() on the main thread during every single activity
            // resume, so the dialog fired constantly with a stack containing no app frames at all.
            // Violations are still detected and logged, they just no longer block the UI.
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectAll().permitDiskReads() // permit disk reads due to media3 setMetadata() TODO extra player thread
                    .penaltyLog().build())
            // Deliberately not detectAll(): it bundles detectCleartextNetwork() and
            // detectUntaggedSockets(), which combined with penaltyDeath() killed the process on
            // every single HTTP request once the library moved to Jellyfin. Plain HTTP to a
            // self-hosted server on the LAN is the normal case here, and OkHttp does not tag its
            // sockets. Everything else worth catching is kept, still fatal.
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .detectLeakedRegistrationObjects()
                    .detectFileUriExposure()
                    .detectContentUriWithoutPermission()
                    .detectImplicitDirectBoot()
                    .detectCredentialProtectedWhileLocked()
                    .detectIncorrectContextUse()
                    .detectUnsafeIntentLaunch()
                    // Logged, not fatal. The Accord UI lets a Surface reach its finalizer without
                    // an explicit release - its blend and backdrop views hand surfaces around - and
                    // penaltyDeath turned that into the app being killed seconds after launch,
                    // over and over. A finalizer-timing warning is not worth losing the process
                    // for; the violations are still reported.
                    .penaltyLog().build())
        }

        // This is a separate thread to avoid disk read on main thread and improve startup time
        Thread {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            // Set application theme when launching.
            when (prefs.getString("theme_mode", "0")) {
                "0" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }

                "1" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }

                "2" -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
            }

            // https://github.com/androidx/media/issues/805
            if (needsMissingOnDestroyCallWorkarounds()) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
            }
        }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            // Artwork now comes over the network, so it has to survive process death or every
            // scroll re-downloads it. Jellyfin serves the original source file for these URLs;
            // the image request's size downsamples the decoded bitmap but does not shrink that
            // cached response. A library containing 3K/5K covers filled the former 256 MiB cache
            // during ordinary Home/queue use, so retain a useful working set on disk. This does
            // not enlarge the decoded-bitmap memory cache.
            .diskCache(
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_artwork"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            )
            .allowHardware(false)
            .components {
                // One artworkUri is stored per track and every surface shares it, so a 100px queue
                // row was downloading the same 1440px cover the full-screen player asks for. A
                // 50-item queue drawn from a couple of albums then fired ~50 concurrent requests
                // for the same two URLs against a cold cache - none of them coalesce, so they all
                // went to the network, saturated the connection, and the player's own cover queued
                // behind them and landed twenty-odd seconds late.
                //
                // Rewriting maxWidth per request is done here rather than at each call site because
                // Coil is the only place that knows the *resolved* target size. Sizes are bucketed:
                // a distinct URL per pixel width would make every cache entry a miss, which is the
                // problem this is meant to solve.
                add(Mapper<android.net.Uri, android.net.Uri> { data, options ->
                    if (!data.path.orEmpty().contains("/Images/")) return@Mapper null
                    val current = data.getQueryParameter("maxWidth") ?: return@Mapper null
                    val target = maxOf(
                        options.size.width.pxOrElse { 0 },
                        options.size.height.pxOrElse { 0 },
                    )
                    if (target <= 0) return@Mapper null
                    val bucket = ARTWORK_SIZE_BUCKETS.firstOrNull { it >= target }
                        ?: ARTWORK_SIZE_BUCKETS.last()
                    Log.d(TAG, "COVERDBG mapper target=$target bucket=$bucket was=$current") // TEMP-COVERDBG
                    if (bucket.toString() == current) return@Mapper null
                    val builder = data.buildUpon().clearQuery()
                    for (name in data.getQueryParameterNames()) {
                        val value = if (name == "maxWidth") bucket.toString()
                        else data.getQueryParameter(name)
                        builder.appendQueryParameter(name, value)
                    }
                    builder.build()
                })
                // Every cover in the app comes from the one Jellyfin host, and OkHttp's dispatcher
                // allows five concurrent requests per host by default - a figure meant for public
                // web servers, not a personal server on the same network. A burst (a collection
                // screen warming its first covers, the queue warming the next few, a list binding
                // its thumbnails) therefore queued behind that limit, and the cover the player was
                // actually waiting for could sit at the back of it.
                //
                // Measured on device: the server answered in 1.4ms and the phone fetched the image
                // in 54-88ms, while Coil took 5261ms to deliver it. All of that was head-of-line
                // blocking rather than the network.
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dispatcher(
                                    Dispatcher().apply {
                                        maxRequestsPerHost = ARTWORK_MAX_REQUESTS_PER_HOST
                                    }
                                )
                                .build()
                        }
                    )
                )
                // The Accord screens ask for local artwork through libPhonograph's
                // gramophoneSongCover/gramophoneAlbumCover schemes, which only the superclass knows
                // how to resolve. Without these, every local cover in the new UI comes up blank.
                addLocalCoverFetchers()
                if (hasScopedStorageV1()) {
                    add(Fetcher.Factory { data, options, _ ->
                        if (data !is Pair<*, *>) return@Factory null
                        val size = data.second
                        if (size !is Size?) return@Factory null
                        val file = data.first as? File ?: return@Factory null
                        return@Factory Fetcher {
                            ImageFetchResult(
                                ThumbnailUtils.createAudioThumbnail(file, options.size.let {
                                    Size(it.width.pxOrElse { size?.width ?: 10000 },
                                        it.height.pxOrElse { size?.height ?: 10000 })
                                }, null).asImage(), true, DataSource.DISK
                            )
                        }
                    })
                }
            }
            .run {
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
                            if (throwable != null && throwable !is NullRequestDataException && (throwable !is IOException || throwable.message != "No album art found")) {
                                Log.println(priority, tag, Log.getStackTraceString(throwable))
                            }
                        }
                    })
            }
            .build()
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        val exceptionMessage = Log.getStackTraceString(e)
        val threadName = t.name
        // Also emit to logcat. Handing the trace to BugHandlerActivity and calling exitProcess()
        // means Android never writes it to the crash buffer, so without this a crash is invisible
        // to adb and can only be read off the device screen.
        Log.e("GramophoneApplication", "Uncaught exception on thread $threadName", e)
        val intent = Intent(this, BugHandlerActivity::class.java)
        intent.putExtra("exception_message", exceptionMessage)
        intent.putExtra("thread", threadName)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        exitProcess(10)
    }

    private companion object {
        const val TAG = "GramophoneApplication"

        /**
         * The only widths artwork is ever requested at, smallest first.
         *
         * Bucketing is the point: the cache is keyed by URL, so asking for each view's exact pixel
         * width would give almost every request its own entry and never hit. Four sizes cover a
         * list row, a mini-player, a tablet row and the full-screen cover, and the last is also the
         * ceiling - nothing draws artwork larger than 1440.
         */
        val ARTWORK_SIZE_BUCKETS = intArrayOf(128, 256, 512, 1440)

        /**
         * Concurrent image requests allowed to the one server everything comes from.
         *
         * OkHttp's default of five is a politeness limit for the public internet. Here the only
         * host is the user's own Jellyfin, usually on the same network, and the cost of the default
         * was the player's cover queueing behind a burst of thumbnails.
         */
        private const val ARTWORK_MAX_REQUESTS_PER_HOST = 16
    }
}
