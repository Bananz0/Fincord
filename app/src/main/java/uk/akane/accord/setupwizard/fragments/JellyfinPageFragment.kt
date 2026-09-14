package uk.akane.accord.setupwizard.fragments

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import org.akanework.gramophone.logic.data.jellyfin.JellyfinCredentialStore
import org.akanework.gramophone.ui.JellyfinLoginActivity
import uk.akane.accord.R
import uk.akane.cupertino.utils.AnimationUtils

/**
 * The setup wizard's second page.
 *
 * Upstream asks for media permissions here, because upstream reads a local library. This app's
 * library is on a Jellyfin server, so a fresh install needs a server and an account before it has
 * anything to show - asking for storage access first would be asking for the wrong thing.
 *
 * Sign-in itself is [JellyfinLoginActivity], which handles discovery, endpoint testing, the user
 * picker and Quick Connect. It also starts the first forced sync before returning successfully.
 */
class JellyfinPageFragment : Fragment() {

    private lateinit var connectButton: MaterialButton
    private lateinit var serverSubtitle: TextView
    private lateinit var accountSubtitle: TextView

    private var allowColor = 0
    private var allowedColor = 0

    private val signIn = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Authentication owns starting the sync; this page only reflects the durable session.
        refreshState(animate = true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_setup_wizard_jellyfin_page, container, false)

        allowColor = requireContext().getColor(R.color.accentColor)
        allowedColor = requireContext().getColor(R.color.accentColorFainted)

        connectButton = rootView.findViewById(R.id.server_connect_btn)
        serverSubtitle = rootView.findViewById(R.id.server_subtitle)
        accountSubtitle = rootView.findViewById(R.id.account_subtitle)

        connectButton.setOnClickListener {
            signIn.launch(Intent(requireContext(), JellyfinLoginActivity::class.java))
        }

        refreshState(animate = false)
        return rootView
    }

    override fun onResume() {
        super.onResume()
        // Covers the case where sign-in happened somewhere other than the launcher above.
        refreshState(animate = false)
    }

    private fun refreshState(animate: Boolean) {
        val context = context ?: return
        val signedIn = JellyfinCredentialStore.hasStoredSession(context)
        val wasChecked = connectButton.isChecked

        connectButton.isChecked = signedIn
        connectButton.setText(
            if (signedIn) R.string.setup_jellyfin_connected else R.string.setup_jellyfin_connect
        )
        serverSubtitle.setText(
            if (signedIn) R.string.setup_jellyfin_server_connected
            else R.string.setup_jellyfin_server_desc
        )
        accountSubtitle.setText(
            if (signedIn) R.string.setup_jellyfin_account_connected
            else R.string.setup_jellyfin_account_desc
        )

        if (signedIn) {
            (parentFragment as? SetupWizardFragment)?.releaseContinueButton()
            if (animate && !wasChecked) {
                AnimationUtils.createValAnimator(allowColor, allowedColor, isArgb = true) {
                    connectButton.backgroundTintList = ColorStateList.valueOf(it)
                }
            }
        }
    }
}
