package uk.akane.accord.logic.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.parcelize.Parcelize
import org.nift4.gramophone.hificore.AudioTrackHiddenApi

@Parcelize
data class AfFormatInfo(
    val routedDeviceName: String?, val routedDeviceId: Int?,
    val routedDeviceType: Int?, val audioSessionId: Int, val mixPortId: Int?,
    val mixPortName: String?, val mixPortFlags: Int?, val mixPortHwModule: Int?,
    val mixPortFast: Boolean?, val ioHandle: Int?, val sampleRateHz: UInt?,
    val audioFormat: String?, val channelCount: Int?, val channelMask: Int?,
    val grantedFlags: Int?, val policyPortId: Int?, val afTrackFlags: Int?,
    val isBluetoothOffload: Boolean?
) : Parcelable

@Parcelize
data class AudioTrackInfo(
    val encoding: Int, val sampleRateHz: Int, val channelConfig: Int,
    val offload: Boolean
) : Parcelable {
    companion object {
        @OptIn(UnstableApi::class)
        fun fromMedia3AudioTrackConfig(config: AudioTrackConfig) =
            AudioTrackInfo(
                config.encoding, config.sampleRate, config.channelConfig,
                config.offload
            )
    }
}

@OptIn(UnstableApi::class)
class AfFormatTracker(
    private val context: Context, private val playbackHandler: Handler,
    private val handler: Handler
) : AnalyticsListener {
    companion object {
        private const val LOG_EVENTS = true
        private const val TAG = "AfFormatTracker"
    }

    // only access sink or track on PlaybackThread
    private var lastAudioTrack: AudioTrack? = null
    private var lastPeriodUid: Any? = null
    private var audioSink: DefaultAudioSink? = null
    var format: AfFormatInfo? = null
        private set
    var formatChangedCallback: ((AfFormatInfo?, Any?) -> Unit)? = null

    private val routingChangedListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        AudioRouting.OnRoutingChangedListener { router ->
            this@AfFormatTracker.onRoutingChanged(router as AudioTrack)
        } as Any
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        @Suppress("deprecation")
        AudioTrack.OnRoutingChangedListener { router ->
            this@AfFormatTracker.onRoutingChanged(router)
        } as Any
    } else null

    private fun onRoutingChanged(router: AudioTrack) {
        val audioTrack = audioSink?.getAudioTrack() ?: return
        if (router !== audioTrack) return // stale callback
        // reaching here implies router == lastAudioTrack
        buildFormat(audioTrack, lastPeriodUid)
    }

    // Media3 1.9 moved AudioTrack out of DefaultAudioSink and into AudioTrackAudioOutput. Keep the
    // old direct-field path for earlier Media3 versions, then follow the new AudioOutput wrapper.
    // Failure here must only disable diagnostics; it must never take down the playback thread.
    private fun DefaultAudioSink.getAudioTrack(): AudioTrack? {
        return runCatching {
            findField(javaClass, "audioTrack")?.let { field ->
                field.isAccessible = true
                return@runCatching field.get(this) as? AudioTrack
            }

            val outputField = findField(javaClass, "audioOutput") ?: return@runCatching null
            outputField.isAccessible = true
            val output = outputField.get(this) ?: return@runCatching null
            output.javaClass.getMethod("getAudioTrack").invoke(output) as? AudioTrack
        }.onFailure {
            Log.e(TAG, "Media3 did not expose its AudioTrack; HAL diagnostics are unavailable", it)
        }.getOrNull()
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = start
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let { return it }
            type = type.superclass
        }
        return null
    }

    private fun runOnPlaybackHandler(action: () -> Unit) {
        val looper = playbackHandler.looper
        if (!looper.thread.isAlive) {
            action()
            return
        }
        if (Looper.myLooper() == looper) {
            action()
        } else {
            playbackHandler.post(action)
        }
    }

    fun setAudioSink(sink: DefaultAudioSink) {
        this.audioSink = sink
    }

    /**
     * The platform track behind the sink, for [PlaybackClockProbe].
     *
     * A volatile snapshot rather than the reflection path, because the probe has to sample this
     * from the *application* thread - ExoPlayer.getCurrentPosition may only be read there, and a
     * clock comparison is worthless if its two halves come from different moments. The track
     * reference is published here by the playback thread whenever it changes; reading a position
     * off an AudioTrack is a thread-safe native call, and a track released underneath us fails
     * inside the probe's own runCatching rather than here.
     */
    @Volatile
    var probeAudioTrack: AudioTrack? = null
        private set

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioTrackConfig
    ) {
        format = null
        runOnPlaybackHandler {
            val audioTrack = audioSink?.getAudioTrack()
            if (audioTrack == null) {
                Log.w(TAG, "AudioTrack initialized, but its platform track is unavailable")
                return@runOnPlaybackHandler
            }
            if (audioTrack != lastAudioTrack) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioRouting.OnRoutingChangedListener
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("deprecation")
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioTrack.OnRoutingChangedListener
                    )
                }
                lastPeriodUid?.let { formatChangedCallback?.invoke(null, it) }
                this.lastAudioTrack = audioTrack
                this.probeAudioTrack = audioTrack
                this.lastPeriodUid = eventTime.mediaPeriodId?.periodUid
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    audioTrack?.addOnRoutingChangedListener(
                        routingChangedListener as AudioRouting.OnRoutingChangedListener,
                        playbackHandler
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("deprecation")
                    audioTrack?.addOnRoutingChangedListener(
                        routingChangedListener as AudioTrack.OnRoutingChangedListener,
                        playbackHandler
                    )
                }
            }
            buildFormat(audioTrack, eventTime.mediaPeriodId?.periodUid)
        }
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioTrackConfig
    ) {
        runOnPlaybackHandler {
            if (lastAudioTrack?.state == AudioTrack.STATE_UNINITIALIZED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioRouting.OnRoutingChangedListener
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    @Suppress("deprecation")
                    lastAudioTrack?.removeOnRoutingChangedListener(
                        routingChangedListener as AudioTrack.OnRoutingChangedListener
                    )
                }
                lastAudioTrack = null
                probeAudioTrack = null
                formatChangedCallback?.invoke(null, lastPeriodUid)
                lastPeriodUid = null
                format = null
            }
        }
    }

    private fun buildFormat(audioTrack: AudioTrack?, periodUid: Any?) {
        audioTrack?.let {
            if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED) return@let null
            val rd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                audioTrack.routedDevice else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                handler.post {
                    val sd = MediaRoutes.getSelectedAudioDevice(context)
                    if (rd != sd)
                        Log.w(
                            TAG,
                            "routedDevice ${rd?.productName}(${rd?.id}) is not the same as MediaRoute " +
                                    "selected device ${sd?.productName}(${sd?.id})"
                        )
                }
            }
            val deviceProductName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd?.productName.toString() else null
            val deviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd?.type else null
            val deviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                rd?.id else null
            val ioHandle = AudioTrackHiddenApi.getOutput(audioTrack)
            val halSampleRate = AudioTrackHiddenApi.getHalSampleRate(audioTrack)
            val grantedFlags = AudioTrackHiddenApi.getGrantedFlags(audioTrack)
            val mixPort = AudioTrackHiddenApi.getMixPortForThread(ioHandle)
            val primaryHw = AudioTrackHiddenApi.getPrimaryMixPort()?.hwModule
            val latency = try {
                // this call writes to mAfLatency and mLatency fields, hence call dump after this
                AudioTrack::class.java.getMethod("getLatency").invoke(audioTrack) as Int
            } catch (t: Throwable) {
                Log.e(TAG, Log.getStackTraceString(t))
                null
            }
            val dump = AudioTrackHiddenApi.dump(audioTrack)
            val isBluetoothOffload = if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || deviceType == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || deviceType == AudioDeviceInfo.TYPE_BLE_BROADCAST
            ) {
                mixPort?.hwModule?.let { it == primaryHw }
            } else null
            AfFormatInfo(
                deviceProductName,
                deviceId,
                deviceType,
                audioTrack.audioSessionId,
                mixPort?.id,
                mixPort?.name,
                mixPort?.flags,
                mixPort?.hwModule,
                mixPort?.fast,
                ioHandle,
                halSampleRate ?: mixPort?.sampleRate,
                audioFormatToString(
                    AudioTrackHiddenApi.getHalFormat(audioTrack) ?: mixPort?.format
                ),
                AudioTrackHiddenApi.getHalChannelCount(audioTrack),
                mixPort?.channelMask,
                grantedFlags,
                AudioTrackHiddenApi.getPortIdFromDump(dump),
                AudioTrackHiddenApi.findAfTrackFlags(dump, latency, audioTrack, grantedFlags),
                isBluetoothOffload
            )
        }.let {
            if (LOG_EVENTS)
                Log.d(TAG, "audio hal format changed to: $it")
            format = it
            formatChangedCallback?.invoke(it, periodUid)
        }
    }

    private fun audioFormatToString(audioFormat: UInt?): String {
        // Native audio_format_t values. Keep this local: the old AudioFormatDetector also models
        // every decoder/header format in Media3 and is intentionally not part of Accord's active
        // player. AudioFlinger only needs the small set a phone is likely to grant at its output.
        return when (audioFormat) {
            0x1U -> "AUDIO_FORMAT_PCM_16_BIT"
            0x2U -> "AUDIO_FORMAT_PCM_8_BIT"
            0x3U -> "AUDIO_FORMAT_PCM_32_BIT"
            0x4U -> "AUDIO_FORMAT_PCM_8_24_BIT"
            0x5U -> "AUDIO_FORMAT_PCM_FLOAT"
            0x6U -> "AUDIO_FORMAT_PCM_24_BIT_PACKED"
            0x01000000U -> "AUDIO_FORMAT_MP3"
            0x04000000U -> "AUDIO_FORMAT_AAC"
            0x1B000000U -> "AUDIO_FORMAT_FLAC"
            0x1C000000U -> "AUDIO_FORMAT_ALAC"
            0x1F000000U -> "AUDIO_FORMAT_SBC"
            0x20000000U -> "AUDIO_FORMAT_APTX"
            0x21000000U -> "AUDIO_FORMAT_APTX_HD"
            0x23000000U -> "AUDIO_FORMAT_LDAC"
            0x27000000U -> "AUDIO_FORMAT_APTX_ADAPTIVE"
            0x28000000U -> "AUDIO_FORMAT_LHDC"
            0x29000000U -> "AUDIO_FORMAT_LHDC_LL"
            0x2B000000U -> "AUDIO_FORMAT_LC3"
            null -> "AUDIO_FORMAT_UNKNOWN"
            else -> "AUDIO_FORMAT_(0x${audioFormat.toString(16)})"
        }
    }
}
