package uk.akane.accord.logic.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import kotlinx.parcelize.Parcelize

/** What Android accepted for the currently selected USB output. */
@Parcelize
data class UsbHiFiStatus(
    val deviceName: String,
    val sampleRateHz: Int,
    val channelCount: Int,
    val encoding: Int,
    val bitPerfect: Boolean,
) : Parcelable

/**
 * Uses Android 14's supported USB path instead of opening the DAC through the raw USB APIs.
 *
 * The framework API keeps alarms and other system audio working and lets the device's Audio HAL
 * advertise exactly what it can accept. Bit-perfect is deliberately conditional: Android makes
 * that mixer behaviour optional, so this class never claims it unless the HAL advertised it and
 * [AudioManager.setPreferredMixerAttributes] accepted it.
 */
@OptIn(UnstableApi::class)
class UsbHiFiManager(
    context: Context,
    private val enabled: () -> Boolean,
    private val onChanged: (UsbHiFiStatus?) -> Unit,
) {
    companion object {
        private const val TAG = "UsbHiFiManager"

        fun isUsb(device: AudioDeviceInfo?): Boolean = when (device?.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> true
            else -> false
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mediaAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            scheduleRouteRefresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            scheduleRouteRefresh()
        }
    }

    private var preferredDevice: AudioDeviceInfo? = null
    private var lastFormat: Format? = null

    var status: UsbHiFiStatus? = null
        private set

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    /** Called before Media3 configures its AudioTrack, and again if that track changes route. */
    fun configure(format: Format?) {
        lastFormat = format
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            publish(null)
            return
        }
        configureApi34(format)
    }

    fun refreshRoute() = configure(lastFormat)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            clearApi34()
        }
        publish(null)
    }

    private fun scheduleRouteRefresh() {
        // MediaRouter's selected route trails the AudioManager device callback on some devices.
        handler.removeCallbacks(routeRefresh)
        handler.postDelayed(routeRefresh, 350L)
    }

    private val routeRefresh = Runnable(::refreshRoute)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun configureApi34(format: Format?) {
        val device = MediaRoutes.getSelectedAudioDevice(appContext)
            ?.takeIf(::isUsb)
        if (!enabled() || format == null || device == null) {
            clearApi34()
            publish(null)
            return
        }

        val encoding = format.pcmEncoding.toPlatformPcmEncoding() ?: run {
            clearApi34()
            publish(null)
            return
        }
        val sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE } ?: return
        val channelCount = format.channelCount.takeIf { it > 0 } ?: return

        val supported = runCatching { audioManager.getSupportedMixerAttributes(device) }
            .onFailure { Log.w(TAG, "Unable to query ${device.productName}", it) }
            .getOrElse {
                clearApi34()
                publish(null)
                return
            }
        val candidates = supported.filter { attributes ->
            val candidate = attributes.format
            candidate.sampleRate == sampleRate &&
                candidate.channelCount == channelCount &&
                candidate.encoding == encoding
        }
        val selected = candidates.firstOrNull {
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
        } ?: candidates.firstOrNull()

        if (selected == null) {
            clearApi34()
            publish(null)
            Log.i(TAG, "No exact USB mixer format for ${format.toLogString()} on ${device.productName}")
            return
        }

        val proposed = UsbHiFiStatus(
            deviceName = device.productName?.toString().orEmpty().ifBlank { "USB DAC" },
            sampleRateHz = selected.format.sampleRate,
            channelCount = selected.format.channelCount,
            encoding = selected.format.encoding,
            bitPerfect = selected.mixerBehavior ==
                AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
        )
        // A routing callback is also emitted when Android reopens the preferred stream. Avoid
        // feeding that callback straight back into another identical reopen.
        if (preferredDevice?.id == device.id && status == proposed) return
        if (preferredDevice != null && preferredDevice?.id != device.id) clearApi34()
        val accepted = runCatching {
            audioManager.setPreferredMixerAttributes(mediaAttributes, device, selected)
        }.onFailure {
            Log.w(TAG, "Unable to configure ${device.productName}", it)
        }.getOrDefault(false)
        if (!accepted) {
            publish(null)
            Log.w(TAG, "Android rejected $selected for ${device.productName}")
            return
        }
        preferredDevice = device
        publish(proposed)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun clearApi34() {
        preferredDevice?.let { device ->
            runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, device) }
                .onFailure { Log.w(TAG, "Unable to clear USB mixer attributes", it) }
        }
        preferredDevice = null
    }

    private fun publish(value: UsbHiFiStatus?) {
        if (status == value) return
        status = value
        onChanged(value)
    }

    private fun Int.toPlatformPcmEncoding(): Int? = when (this) {
        C.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
        C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
        C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        else -> null
    }

    private fun Format.toLogString() =
        "${sampleRate}Hz/${channelCount}ch/pcm=$pcmEncoding"
}
