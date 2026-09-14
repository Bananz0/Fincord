package org.akanework.gramophone.ui


import android.os.Bundle
import uk.akane.accord.ui.components.NoToast as Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import org.akanework.gramophone.logic.data.spotify.SpotifyClient
import org.akanework.gramophone.logic.data.spotify.SpotifyCredentialStore

/**
 * Receives Spotify's redirect after the user approves or denies access.
 *
 * A bare activity with no layout: it exists only to catch the `fincord://spotify-callback` intent,
 * redeem the code, and hand the user straight back to where they were. Anything shown here would
 * flash for the fraction of a second the token exchange takes.
 *
 * [ComponentActivity] rather than AppCompatActivity, because the manifest gives this a translucent
 * platform theme and AppCompat refuses to start without an AppCompat theme.
 */
class SpotifyAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        if (data == null) {
            finish()
            return
        }

        val code = SpotifyClient.codeFrom(data).getOrElse { error ->
            toast(getString(R.string.spotify_error_detail, error.message.orEmpty()))
            finish()
            return
        }

        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val store = SpotifyCredentialStore(this@SpotifyAuthActivity)
                    val client = SpotifyClient(store)
                    client.exchangeCode(this@SpotifyAuthActivity, code, System.currentTimeMillis())
                    store.displayName =
                        client.currentUserName(this@SpotifyAuthActivity, System.currentTimeMillis())
                    null
                } catch (e: Exception) {
                    e.message ?: getString(R.string.spotify_error_generic)
                }
            }
            toast(
                if (message == null) getString(R.string.spotify_connected)
                else getString(R.string.spotify_error_detail, message)
            )
            finish()
        }
    }

    private fun toast(text: String) =
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
