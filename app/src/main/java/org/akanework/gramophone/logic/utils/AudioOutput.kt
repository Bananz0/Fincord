package org.akanework.gramophone.logic.utils

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import uk.akane.accord.R

/**
 * What the music is coming out of, for the line under the player's controls.
 *
 * Upstream draws a fixed AirPlay glyph there whatever is connected, so a pair of earbuds and the
 * phone's own speaker look identical. This reports the actual output and names it.
 *
 * Android has no public "which device is this stream routed to" call, so the answer is inferred from
 * what is connected, in the order the system itself prefers: a Bluetooth headset wins over a wired
 * one, a wired one wins over the speaker. That matches the routing in every case a music app meets;
 * it would only be wrong for an app that has explicitly overridden routing, which this one never
 * does.
 */
object AudioOutput {

    data class Device(
        /** What to show under the icon: "Galaxy Buds3 Pro". Null for the phone's own speaker. */
        val name: String?,
        @param:DrawableRes val icon: Int,
        /** False for the built-in speaker, where naming the device adds nothing. */
        val isExternal: Boolean,
    )

    /** The output the next note will come out of. */
    fun current(context: Context): Device {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // Highest priority first. getDevices() returns them in no meaningful order, so the choice
        // has to be made here rather than by taking the first entry.
        val device = PRIORITY.firstNotNullOfOrNull { type ->
            outputs.firstOrNull { it.type == type }
        } ?: return Device(null, R.drawable.ic_airplay_radio, isExternal = false)

        val name = device.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return Device(name, iconFor(device, name), isExternal = device.type !in BUILT_IN)
    }

    /**
     * Calls [onChanged] whenever something is plugged in or unplugged. Returns a handle to pass to
     * [unregister] - the callback outlives the view otherwise and keeps it alive.
     */
    fun register(context: Context, onChanged: () -> Unit): AudioDeviceCallback {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = onChanged()
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return callback
    }

    fun unregister(context: Context, callback: AudioDeviceCallback) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    /**
     * Picks the glyph.
     *
     * Type first, name second. The type is what Android is sure about - a car head unit and a wired
     * headset are never in doubt - while earbuds and over-ear headphones are both reported as
     * BLUETOOTH_A2DP with nothing to tell them apart, so for those the product name is the only
     * signal there is.
     *
     * Bluetooth device class is deliberately not consulted. It is self-reported and routinely wrong:
     * the speaker paired to the phone this was built against announces itself as a wearable headset.
     *
     * To use a real product image for a particular device - a manufacturer's own artwork, which this
     * repo does not ship - add it to [NAMED_DEVICES] rather than anywhere else.
     */
    @DrawableRes
    private fun iconFor(device: AudioDeviceInfo, name: String?): Int {
        val lowered = name?.lowercase().orEmpty()
        NAMED_DEVICES.firstOrNull { (needles, _) -> needles.any { lowered.contains(it) } }
            ?.let { return it.second }

        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> R.drawable.ic_headphones

            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_DOCK -> R.drawable.ic_output_speaker

            // Nothing distinguishes headphones from a speaker here, and the name did not either.
            // Headphones is the likelier answer for something paired to a phone for music.
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> R.drawable.ic_headphones

            else -> R.drawable.ic_airplay_radio
        }
    }

    /**
     * Product names worth recognising, most specific first.
     *
     * Bluetooth hands over a product name and little else, so this is where a device becomes a
     * particular kind of thing rather than "some audio device". Matching is a substring of the
     * lowercased name, so "Glen's Buds3 Pro" matches "buds".
     */
    private val NAMED_DEVICES: List<Pair<List<String>, Int>> = listOf(
        // Cars announce themselves by head-unit or manufacturer name far more often than by type.
        listOf(
            "car", "auto", "sync", "uconnect", "mylink", "carplay", "kenwood", "pioneer", "alpine",
        ) to R.drawable.ic_output_car,
        // Earbuds. Covers the families that actually turn up: Samsung, Apple, Google, Sony, Huawei,
        // Anker, Jabra, Nothing, Beats.
        listOf(
            "buds", "airpods", "earbud", "earphone", "pods", "freebuds", "liberty", "elite",
            "wf-", "nothing ear", "ear (", "studio buds", "earfun", "jaybird",
        ) to R.drawable.ic_output_earbuds,
        // Over-ear and on-ear.
        listOf(
            "headphone", "headset", "wh-", "quietcomfort", "momentum", "airpods max", "hd ",
            "beats studio", "solo", "bose", "sennheiser",
        ) to R.drawable.ic_headphones,
        listOf(
            "speaker", "soundbar", "boombox", "flip", "charge", "pulse", "sonos", "homepod",
            "w-king", "megaboom", "wonderboom",
        ) to R.drawable.ic_output_speaker,
    )

    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private val BUILT_IN = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    )
}
