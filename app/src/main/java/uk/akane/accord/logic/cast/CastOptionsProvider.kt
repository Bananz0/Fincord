package uk.akane.accord.logic.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * How Accord presents itself to Google Cast.
 *
 * Discovered reflectively by the Cast framework through the `OPTIONS_PROVIDER_CLASS_NAME`
 * meta-data in the manifest - nothing calls this directly.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                // Accord already publishes a MediaSession from its playback service, and that is
                // what the lock screen, the notification and every other client read. Letting the
                // Cast framework publish a second one would put two sessions in front of the same
                // playback, and the system picks between them by recency - so transport controls
                // would land on whichever had most recently changed state.
                .setMediaSessionEnabled(false)
                .setNotificationOptions(null)
                .build()
        )
        // Make a running Fincord receiver survive backgrounding, process death and an ordinary
        // app reopen. These are framework defaults today, but keeping them explicit prevents a
        // future SDK/default change from silently turning a recoverable session into a new one.
        .setResumeSavedSession(true)
        .setEnableReconnectionService(true)
        // Lets the system Output Switcher move playback back from a receiver to this phone. Paired
        // with the MediaTransferReceiver in the manifest; without both, the switcher can send
        // playback out but never bring it home.
        .setRemoteToLocalEnabled(true)
        // A cast that is handed back to the phone should release the receiver rather than leave
        // it parked on Accord with nothing playing.
        .setStopReceiverApplicationWhenEndingSession(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? =
        null

    companion object {
        /** Fincord's registered Cast receiver application. */
        const val RECEIVER_APPLICATION_ID = "6635F618"

        /**
         * Kept for reference: Google's Default Media Receiver, which needs no registration but
         * announces itself as "Default Media Receiver" to every other device on the network.
         * [RECEIVER_APPLICATION_ID] is Accord's own registered receiver ("Fincord") instead.
         *
         * While that application is Unpublished it only loads on Chromecasts whose serials are
         * registered under Cast Receiver Devices in the console - an unregistered speaker simply
         * fails to launch it, which is the usual cause of "it casts to one device but not another".
         */
        @Suppress("unused")
        const val DEFAULT_RECEIVER_APPLICATION_ID =
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    }
}
