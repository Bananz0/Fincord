package org.akanework.gramophone.ui


import android.app.Activity
import android.view.KeyEvent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.autofill.AutofillManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import coil3.dispose
import coil3.load
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.akane.accord.R
import uk.akane.accord.Accord
import org.akanework.gramophone.logic.GramophoneApplication
import uk.akane.accord.logic.enableEdgeToEdgeProperly
import uk.akane.accord.logic.lockPortraitOnPhone
import uk.akane.accord.ui.components.enablePasteInto
import uk.akane.cupertino.utils.AnimationUtils
import org.akanework.gramophone.logic.data.jellyfin.JellyfinClientHolder
import org.akanework.gramophone.logic.data.jellyfin.JellyfinEndpoints
import org.akanework.gramophone.logic.data.jellyfin.JellyfinDiscography
import org.akanework.gramophone.logic.data.jellyfin.JellyfinPlugins
import org.akanework.gramophone.logic.data.jellyfin.JellyfinUserImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.UserDto

/**
 * First-run sign in, in two steps: pick a server, then pick a user.
 *
 * Splitting it that way is what Jellyfin's own clients do, and it earns its keep. Servers announce
 * themselves on the local network, so the usual case needs no typing at all; and by the time a
 * password is asked for, the address has already been proven to answer - which means a failure at
 * that point really is the password, rather than the one error that used to stand in for every
 * possible problem.
 */
class JellyfinLoginActivity : AppCompatActivity() {

    private lateinit var stepServer: View
    private lateinit var stepUser: View

    private lateinit var discoveryEmpty: TextView
    private lateinit var discoveredList: RecyclerView
    private lateinit var discoveryProgress: ProgressBar
    private lateinit var serverUrlField: TextInputEditText
    private lateinit var remoteServerUrlField: TextInputEditText
    private lateinit var localServerStatus: TextView
    private lateinit var remoteServerStatus: TextView
    private lateinit var localServerTest: MaterialButton
    private lateinit var remoteServerTest: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var serverProgress: LinearProgressIndicator
    private lateinit var serverStatus: TextView

    private lateinit var serverNameLabel: TextView
    private lateinit var usersLabel: TextView
    private lateinit var userList: RecyclerView
    private lateinit var userScroll: ScrollView
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var usernameField: TextInputEditText
    private lateinit var passwordField: TextInputEditText
    private lateinit var signInButton: MaterialButton
    private lateinit var quickConnectButton: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var status: TextView

    /** Set once a server has been resolved; every step-two action needs it. */
    private var serverUrl: String? = null
    private var testedLocalServerUrl: String? = null
    private var testedRemoteServerUrl: String? = null
    private var testedLocalNetworkName: String? = null
    /**
     * Set once a test has proven one address and rejected the other, so the next tap on the same
     * button means "yes, go on with the one that works" rather than a repeat of the test.
     */
    private var partialConfirmPending = false
    private var quickConnectJob: Job? = null
    private var showingUserStep = false
    private var stepAnimator: android.animation.ValueAnimator? = null

    private val discoveredAdapter = ServerAdapter { discovered ->
        serverUrlField.setText(discovered.address)
        serverUrlField.setSelection(serverUrlField.text?.length ?: 0)
        localServerStatus.visibility = View.GONE
    }
    private val userAdapter = UserAdapter { user ->
        usernameField.setText(user.name)
        usernameField.setSelection(usernameField.text?.length ?: 0)
        passwordField.requestFocus()
        userScroll.post {
            signInButton.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, signInButton.width, signInButton.height),
                true,
            )
            WindowInsetsControllerCompat(window, passwordField)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeProperly()
        // A stack of text fields with the keyboard up has nothing to gain from a phone's landscape
        // and a great deal of height to lose by it.
        lockPortraitOnPhone()
        setContentView(R.layout.activity_jellyfin_login)
        bindViews()
        applyWindowInsets()

        discoveredList.layoutManager = LinearLayoutManager(this)
        discoveredList.adapter = discoveredAdapter
        userList.layoutManager = LinearLayoutManager(this)
        userList.adapter = userAdapter

        connectButton.setOnClickListener {
            connectTo(
                serverUrlField.text?.toString()?.trim().orEmpty(),
                remoteServerUrlField.text?.toString()?.trim().orEmpty(),
                allowPartial = partialConfirmPending,
            )
        }
        localServerTest.setOnClickListener { testSingleAddress(local = true) }
        remoteServerTest.setOnClickListener { testSingleAddress(local = false) }
        // Editing either address invalidates whatever the last test concluded, including a pending
        // "continue with one of them" - the button must not still be offering that for text the
        // user has since changed.
        serverUrlField.doAfterTextChanged { resetTestState(localServerStatus) }
        remoteServerUrlField.doAfterTextChanged { resetTestState(remoteServerStatus) }
        signInButton.setOnClickListener { signInWithPassword() }
        quickConnectButton.setOnClickListener { startQuickConnect() }
        findViewById<TextView>(R.id.change_server).setOnClickListener { showServerStep() }

        serverUrlField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                remoteServerUrlField.requestFocus()
                true
            } else false
        }
        remoteServerUrlField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                connectButton.performClick()
                true
            } else false
        }
        usernameField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                passwordField.requestFocus()
                true
            } else false
        }
        passwordField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                signInButton.performClick()
                true
            } else false
        }
        passwordField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) userScroll.post {
                signInButton.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, signInButton.width, signInButton.height),
                    true,
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    showingUserStep -> showServerStep()
                    else -> finish()
                }
            }
        })

        val restoredServer = savedInstanceState?.getString(STATE_SERVER_URL)
        val credentials = JellyfinClientHolder.credentials
        serverUrlField.setText(
            savedInstanceState?.getString(STATE_LOCAL_SERVER_URL)
                ?: credentials.localServerUrl.orEmpty()
        )
        remoteServerUrlField.setText(
            savedInstanceState?.getString(STATE_REMOTE_SERVER_URL)
                ?: credentials.remoteServerUrl.orEmpty()
        )

        startDiscovery()
        if (savedInstanceState?.getBoolean(STATE_USER_STEP) == true && !restoredServer.isNullOrBlank()) {
            connectTo(
                serverUrlField.text?.toString().orEmpty(),
                remoteServerUrlField.text?.toString().orEmpty(),
            )
        }
    }

    private fun bindViews() {
        stepServer = findViewById(R.id.step_server)
        stepUser = findViewById(R.id.step_user)
        userScroll = stepUser as ScrollView
        discoveryEmpty = findViewById(R.id.discovery_empty)
        discoveredList = findViewById(R.id.discovered_servers)
        discoveryProgress = findViewById(R.id.discovery_progress)
        serverUrlField = findViewById(R.id.server_url)
        findViewById<TextInputLayout>(R.id.server_url_layout).enablePasteInto(serverUrlField)
        remoteServerUrlField = findViewById(R.id.remote_server_url)
        findViewById<TextInputLayout>(R.id.remote_server_url_layout)
            .enablePasteInto(remoteServerUrlField)
        localServerStatus = findViewById(R.id.local_server_status)
        remoteServerStatus = findViewById(R.id.remote_server_status)
        localServerTest = findViewById(R.id.local_server_test)
        remoteServerTest = findViewById(R.id.remote_server_test)
        JellyfinEndpoints.currentWifiName(this)?.let { networkName ->
            findViewById<TextInputLayout>(R.id.server_url_layout).hint =
                getString(R.string.jellyfin_local_server_url_on_network, networkName)
        }
        connectButton = findViewById(R.id.connect)
        serverProgress = findViewById(R.id.server_progress)
        serverStatus = findViewById(R.id.server_status)
        serverNameLabel = findViewById(R.id.server_name)
        usersLabel = findViewById(R.id.users_label)
        userList = findViewById(R.id.users)
        usernameLayout = findViewById(R.id.username_layout)
        usernameField = findViewById(R.id.username)
        passwordField = findViewById(R.id.password)
        signInButton = findViewById(R.id.sign_in)
        quickConnectButton = findViewById(R.id.quick_connect)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
    }

    /**
     * Listens for servers announcing themselves on the local network.
     *
     * Jellyfin answers a UDP broadcast on port 7359, which is how its own clients find a server
     * without being told where it is. Results arrive one at a time, so the list fills in as they
     * reply rather than after a fixed wait.
     */
    private fun startDiscovery() {
        JellyfinClientHolder.discovery()
            .discoverLocalServers()
            .onEach { found ->
                discoveredAdapter.add(found.name.orEmpty(), found.address.orEmpty())
                // Immich-style setup: the server announcing on the current Wi-Fi is the best
                // default LAN endpoint. Keep anything the user already typed.
                if (serverUrlField.text.isNullOrBlank() && !found.address.isNullOrBlank()) {
                    serverUrlField.setText(found.address)
                    testedLocalNetworkName = JellyfinEndpoints.currentWifiName(this)
                }
                discoveredList.visibility = View.VISIBLE
                discoveryEmpty.visibility = View.GONE
                discoveryProgress.visibility = View.GONE
            }
            .catch { e ->
                // A network that blocks broadcast traffic is ordinary, not an error worth showing;
                // the address field is still there.
                Log.d(TAG, "Local discovery unavailable", e)
                discoveryProgress.visibility = View.GONE
            }
            .launchIn(lifecycleScope)

        // Discovery has no completion signal beyond its timeout, so stop the spinner on our own
        // rather than leaving it turning forever on a network with no Jellyfin on it.
        lifecycleScope.launch {
            delay(DISCOVERY_SPINNER_MS)
            discoveryProgress.visibility = View.GONE
            if (discoveredAdapter.itemCount == 0) discoveryEmpty.visibility = View.VISIBLE
        }
    }

    /**
     * Tests one address on its own, without the other one having any say in the result.
     *
     * The two addresses are for different situations, so at any given moment one of them is quite
     * likely not to answer: the LAN address cannot be reached from outside the house, and a
     * reverse proxy can be down while the server itself is fine. Being able to prove them one at a
     * time is what makes signing in from either side of that possible.
     */
    private fun testSingleAddress(local: Boolean) {
        val field = if (local) serverUrlField else remoteServerUrlField
        val statusView = if (local) localServerStatus else remoteServerStatus
        val button = if (local) localServerTest else remoteServerTest
        val input = field.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) {
            statusView.visibility = View.VISIBLE
            statusView.setText(R.string.jellyfin_error_no_server)
            return
        }
        hideKeyboard()
        clearPartialConfirm()
        serverStatus.visibility = View.GONE
        button.isEnabled = false
        showEndpointTesting(statusView, input)
        lifecycleScope.launch {
            val tested = withContext(Dispatchers.IO) { JellyfinEndpoints.testInput(input) }
            showEndpointResult(statusView, input, tested)
            button.isEnabled = true
        }
    }

    /** A changed address makes its old result, and any offer based on it, meaningless. */
    private fun resetTestState(statusView: TextView) {
        statusView.visibility = View.GONE
        clearPartialConfirm()
    }

    private fun clearPartialConfirm() {
        if (!partialConfirmPending) return
        partialConfirmPending = false
        connectButton.setText(R.string.jellyfin_test_and_connect)
        serverStatus.visibility = View.GONE
    }

    /**
     * Proves the configured addresses, then moves to the user step.
     *
     * One address answering is enough. The other is kept exactly as typed and tried again on every
     * later connection, which is the whole point of having two: away from home the LAN address
     * cannot answer, and it is still the right address to use once the phone is back on that
     * network. Continuing on one address takes a second tap, so a typo is not quietly stored as a
     * second endpoint.
     */
    private fun connectTo(localInput: String, remoteInput: String, allowPartial: Boolean = false) {
        if (localInput.isEmpty() && remoteInput.isEmpty()) {
            showServerError(getString(R.string.jellyfin_error_no_server))
            return
        }
        partialConfirmPending = false
        connectButton.setText(R.string.jellyfin_test_and_connect)
        hideKeyboard()
        setServerBusy(true)
        serverStatus.visibility = View.VISIBLE
        serverStatus.setText(R.string.jellyfin_finding_server)
        showEndpointTesting(localServerStatus, localInput)
        showEndpointTesting(remoteServerStatus, remoteInput)

        lifecycleScope.launch {
            val localRequest = async(Dispatchers.IO) {
                localInput.takeIf(String::isNotBlank)?.let { JellyfinEndpoints.testInput(it) }
            }
            val remoteRequest = async(Dispatchers.IO) {
                remoteInput.takeIf(String::isNotBlank)?.let { JellyfinEndpoints.testInput(it) }
            }
            val local = localRequest.await()
            val remote = remoteRequest.await()
            showEndpointResult(localServerStatus, localInput, local)
            showEndpointResult(remoteServerStatus, remoteInput, remote)

            if (local == null && remote == null) {
                setServerBusy(false)
                showServerError(getString(R.string.jellyfin_error_unreachable))
                return@launch
            }
            if (local?.serverId != null && remote?.serverId != null &&
                local.serverId != remote.serverId
            ) {
                setServerBusy(false)
                showServerError(getString(R.string.jellyfin_endpoint_mismatch))
                return@launch
            }

            val localFailed = localInput.isNotBlank() && local == null
            val remoteFailed = remoteInput.isNotBlank() && remote == null
            if ((localFailed || remoteFailed) && !allowPartial) {
                val workingName = getString(
                    if (local != null) R.string.jellyfin_endpoint_local_name
                    else R.string.jellyfin_endpoint_remote_name
                )
                val failedStatus = if (localFailed) localServerStatus else remoteServerStatus
                failedStatus.text = getString(R.string.jellyfin_endpoint_failed) + " " +
                    getString(R.string.jellyfin_endpoint_kept)
                setServerBusy(false)
                showServerError(getString(R.string.jellyfin_endpoint_partial, workingName))
                connectButton.text = getString(R.string.jellyfin_continue_anyway, workingName)
                partialConfirmPending = true
                return@launch
            }

            // A kept-but-unproven address is stored as typed; JellyfinEndpoints resolves it the
            // first time it does answer.
            testedLocalServerUrl = local?.url
                ?: localInput.takeIf(String::isNotBlank)?.let(JellyfinEndpoints::normalizeInput)
            testedRemoteServerUrl = remote?.url
                ?: remoteInput.takeIf(String::isNotBlank)?.let(JellyfinEndpoints::normalizeInput)
            // Only a LAN address that actually answered says anything about the network this phone
            // is on; a null here keeps plain reachability in charge of choosing later.
            testedLocalNetworkName = if (local == null) null
                else JellyfinEndpoints.currentWifiName(this@JellyfinLoginActivity)
            val resolved = local?.url ?: remote?.url ?: return@launch
            serverUrl = resolved
            val api = JellyfinClientHolder.createUnauthenticatedApi(resolved)
            val users = withContext(Dispatchers.IO) { publicUsers(api) }
            val quickConnectAvailable = withContext(Dispatchers.IO) { quickConnectEnabled(api) }
            setServerBusy(false)
            serverStatus.visibility = View.GONE

            serverNameLabel.text = local?.serverName ?: remote?.serverName
                ?: resolved.toUri().host ?: resolved
            userAdapter.submit(users, resolved)
            // With no public users the server is hiding them, so a name has to be typed. With some,
            // the field is still there for hidden accounts but starts out of the way.
            usersLabel.visibility = if (users.isEmpty()) View.GONE else View.VISIBLE
            quickConnectButton.visibility =
                if (quickConnectAvailable) View.VISIBLE else View.GONE
            showUserStep(animate = true)
        }
    }

    private fun showServerStep() {
        quickConnectJob?.cancel()
        clearPartialConfirm()
        hideKeyboard(clearFocus = true)
        if (!showingUserStep) return
        showingUserStep = false
        animateStep(stepUser, stepServer)
        status.visibility = View.GONE
    }

    private fun showUserStep(animate: Boolean) {
        if (showingUserStep) return
        showingUserStep = true
        userScroll.requestFocus()
        if (animate) animateStep(stepServer, stepUser) else {
            stepServer.visibility = View.GONE
            stepUser.visibility = View.VISIBLE
        }
    }

    /** Uses the same Cupertino scale swap utility as the rest of Accord's content changes. */
    private fun animateStep(outgoing: View, incoming: View) {
        stepAnimator?.cancel()
        incoming.visibility = View.VISIBLE
        stepAnimator = AnimationUtils.createScaleSwapAnimator(
            outView = outgoing,
            inView = incoming,
            doOnEnd = {
                outgoing.visibility = View.GONE
                outgoing.alpha = 1f
                outgoing.scaleX = 1f
                outgoing.scaleY = 1f
                incoming.alpha = 1f
                incoming.scaleX = 1f
                incoming.scaleY = 1f
                stepAnimator = null
            },
        )
    }

    /** The accounts the server chooses to advertise. Empty is valid - it just means type a name. */
    private suspend fun publicUsers(api: ApiClient): List<UserDto> = try {
        api.userApi.getPublicUsers().content
    } catch (e: Exception) {
        Log.d(TAG, "Server does not advertise its users", e)
        emptyList()
    }

    private suspend fun quickConnectEnabled(api: ApiClient): Boolean = try {
        api.authenticationApi.getQuickConnectEnabled().content
    } catch (e: Exception) {
        Log.d(TAG, "Quick Connect unavailable", e)
        false
    }

    private fun signInWithPassword() {
        val server = serverUrl ?: return
        val username = usernameField.text?.toString()?.trim().orEmpty()
        val password = passwordField.text?.toString().orEmpty()
        if (username.isEmpty()) {
            showError(getString(R.string.jellyfin_error_no_username))
            usernameField.requestFocus()
            WindowInsetsControllerCompat(window, usernameField)
                .show(WindowInsetsCompat.Type.ime())
            return
        }
        hideKeyboard()
        setBusy(true)
        status.visibility = View.VISIBLE
        status.setText(R.string.jellyfin_signing_in)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runAuthentication(server) {
                    it.authenticationApi.authenticateUserByName(username, password).content
                }
            }
            finishAuthentication(result)
        }
    }

    /**
     * Quick Connect: the server shows a code, the user approves it from a session they are already
     * signed in to, and no password is ever typed here.
     *
     * The code is polled rather than pushed - Jellyfin offers no callback - so this loops until the
     * server reports it authorised or the dialog is dismissed.
     */
    private fun startQuickConnect() {
        val server = serverUrl ?: return
        val api = JellyfinClientHolder.createUnauthenticatedApi(server)
        quickConnectJob?.cancel()
        quickConnectJob = lifecycleScope.launch {
            val initiated = withContext(Dispatchers.IO) {
                try {
                    api.authenticationApi.initiateQuickConnect().content
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start Quick Connect", e)
                    null
                }
            }
            val secret = initiated?.secret
            val code = initiated?.code
            if (secret.isNullOrBlank() || code.isNullOrBlank()) {
                showError(getString(R.string.jellyfin_quick_connect_failed))
                return@launch
            }

            val dialog = BottomSheetDialog(
                this@JellyfinLoginActivity,
                R.style.Theme_Accord_OutputPicker,
            ).apply {
                val content = buildCodeView(code)
                setContentView(content)
                setCancelable(true)
                setCanceledOnTouchOutside(true)
                setOnCancelListener { quickConnectJob?.cancel() }
                content.findViewById<View>(R.id.quick_connect_cancel).setOnClickListener {
                    quickConnectJob?.cancel()
                    dismiss()
                }
                show()
            }

            try {
                while (true) {
                    delay(QUICK_CONNECT_POLL_MS)
                    val state = withContext(Dispatchers.IO) {
                        try {
                            api.authenticationApi.getQuickConnectState(secret).content
                        } catch (e: Exception) {
                            // The code expires server-side; treat that as still waiting and let the
                            // user cancel rather than failing under them.
                            Log.d(TAG, "Quick Connect not ready", e)
                            null
                        }
                    }
                    if (state?.authenticated == true) break
                }
                dialog.dismiss()
                setBusy(true)
                status.visibility = View.VISIBLE
                status.setText(R.string.jellyfin_signing_in)
                val result = withContext(Dispatchers.IO) {
                    runAuthentication(server) {
                        it.authenticationApi.authenticateWithQuickConnect(QuickConnectDto(secret)).content
                    }
                }
                finishAuthentication(result)
            } finally {
                dialog.dismiss()
            }
        }
    }

    /**
     * Lays the code out as one large boxed character each.
     *
     * The code has to be read off this screen and typed into another device, so it is set as
     * separated cells rather than a run of digits inside a sentence - the same reason a verification
     * code is never presented as prose.
     */
    private fun buildCodeView(code: String): View {
        val view = layoutInflater.inflate(R.layout.dialog_quick_connect, null)
        val boxes = view.findViewById<android.widget.LinearLayout>(R.id.code_boxes)
        code.forEachIndexed { index, character ->
            val cell = layoutInflater.inflate(R.layout.item_quick_connect_digit, boxes, false)
                    as TextView
            cell.text = character.toString()
            if (index > 0) {
                (cell.layoutParams as ViewGroup.MarginLayoutParams).marginStart =
                    resources.getDimensionPixelSize(R.dimen.quick_connect_digit_gap)
            }
            boxes.addView(cell)
        }
        return view
    }

    /**
     * Keeps the form clear of the system bars and, more importantly, of the keyboard.
     *
     * `adjustResize` alone did not do it: the window is laid out edge to edge, so the IME arrives
     * as an inset rather than as a smaller window, and the password field - the last thing above
     * the fold - ended up underneath the keyboard with no way to scroll to it. Padding the root by
     * whichever of the navigation bar or the keyboard is taller gives the ScrollViews a real
     * bottom to scroll against, so the focused field comes into view by itself.
     */
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.login_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                top = bars.top,
                bottom = maxOf(bars.bottom, ime.bottom),
            )
            if (ime.bottom > 0 && ::passwordField.isInitialized && passwordField.hasFocus()) {
                userScroll.post {
                    signInButton.requestRectangleOnScreen(
                        android.graphics.Rect(0, 0, signInButton.width, signInButton.height),
                        true,
                    )
                }
            }
            insets
        }
    }

    private fun finishAuthentication(result: LoginResult) = when (result) {
        is LoginResult.Success -> {
            ContextCompat.getSystemService(this, AutofillManager::class.java)?.commit()
            setResult(Activity.RESULT_OK)
            finish()
        }

        is LoginResult.Failure -> {
            setBusy(false)
            showError(result.message)
            passwordField.requestFocus()
            passwordField.selectAll()
        }
    }

    private fun hideKeyboard(clearFocus: Boolean = false) {
        WindowInsetsControllerCompat(window, currentFocus ?: findViewById(R.id.login_root))
            .hide(WindowInsetsCompat.Type.ime())
        if (clearFocus) currentFocus?.clearFocus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SERVER_URL, serverUrl ?: serverUrlField.text?.toString())
        outState.putString(STATE_LOCAL_SERVER_URL, serverUrlField.text?.toString())
        outState.putString(STATE_REMOTE_SERVER_URL, remoteServerUrlField.text?.toString())
        outState.putBoolean(STATE_USER_STEP, showingUserStep)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stepAnimator?.cancel()
        quickConnectJob?.cancel()
        super.onDestroy()
    }

    /**
     * Runs an authentication call and stores whatever session comes back.
     *
     * Password and Quick Connect differ only in the call itself; everything after - checking the
     * token, persisting it, dropping the stale client - is identical, and the error handling is
     * worth having in one place.
     */
    private suspend fun runAuthentication(
        server: String,
        authenticate: suspend (ApiClient) -> AuthenticationResult
    ): LoginResult = try {
        val api = JellyfinClientHolder.createUnauthenticatedApi(server)
        val result = authenticate(api)
        val token = result.accessToken
        val userId = result.user?.id?.toString()
        if (token.isNullOrEmpty() || userId.isNullOrEmpty()) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            JellyfinClientHolder.credentials.saveSession(
                context = this,
                serverUrl = server,
                accessToken = token,
                userId = userId,
                serverName = result.serverId,
                localServerUrl = testedLocalServerUrl,
                remoteServerUrl = testedRemoteServerUrl,
                localNetworkName = testedLocalNetworkName,
            )
            JellyfinClientHolder.invalidate()
            JellyfinPlugins.invalidate()
            JellyfinDiscography.invalidate()
            // The server plugin already knows Lidarr. Start adopting it as soon as authentication
            // exists so the services page can show the finished state without a second tap.
            (application as GramophoneApplication).syncLidarrFromPlugin()
            // Authentication is the moment a first library becomes possible. Start a forced,
            // application-owned sync now; it survives this activity closing and publishes each
            // server page while the user finishes the wizard.
            (application as Accord).refreshLibrary(force = true)
            // The signed-in user's picture is what the navigation bar draws, so it has to be known
            // before the first screen that has one is shown.
            JellyfinUserImage.refresh()
            LoginResult.Success
        }
    } catch (e: TimeoutException) {
        Log.w(TAG, "Timed out reaching $server", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_unreachable))
    } catch (e: InvalidStatusException) {
        Log.w(TAG, "Server rejected sign in with HTTP ${e.status}", e)
        // The address is already known to answer by this point, so an auth status really does mean
        // the credentials; anything else is still worth reporting as itself.
        if (e.status == 401 || e.status == 403) {
            LoginResult.Failure(getString(R.string.jellyfin_error_credentials))
        } else {
            LoginResult.Failure(getString(R.string.jellyfin_error_http_status, e.status))
        }
    } catch (e: SecureConnectionException) {
        Log.w(TAG, "TLS problem talking to $server", e)
        LoginResult.Failure(getString(R.string.jellyfin_error_tls))
    } catch (e: ApiClientException) {
        Log.w(TAG, "Could not sign in to $server", e)
        LoginResult.Failure(
            getString(
                R.string.jellyfin_error_detail,
                e.cause?.message ?: e.message.orEmpty()
            )
        )
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected failure signing in to $server", e)
        LoginResult.Failure(
            getString(R.string.jellyfin_error_detail, e.message.orEmpty())
        )
    }

    private fun setServerBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        localServerTest.isEnabled = !busy
        remoteServerTest.isEnabled = !busy
        serverProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun setBusy(busy: Boolean) {
        signInButton.isEnabled = !busy
        quickConnectButton.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showServerError(message: String) {
        serverStatus.visibility = View.VISIBLE
        serverStatus.text = message
    }

    private fun showEndpointTesting(statusView: TextView, input: String) {
        statusView.visibility = if (input.isBlank()) View.GONE else View.VISIBLE
        if (input.isNotBlank()) statusView.setText(R.string.jellyfin_endpoint_testing)
    }

    private fun showEndpointResult(
        statusView: TextView,
        input: String,
        endpoint: JellyfinEndpoints.TestedEndpoint?,
    ) {
        if (input.isBlank()) {
            statusView.visibility = View.GONE
            return
        }
        statusView.visibility = View.VISIBLE
        statusView.text = if (endpoint == null) {
            getString(R.string.jellyfin_endpoint_failed)
        } else {
            getString(
                R.string.jellyfin_endpoint_connected,
                endpoint.serverName ?: endpoint.url.toUri().host ?: endpoint.url,
            )
        }
    }

    private fun showError(message: String) {
        setBusy(false)
        status.visibility = View.VISIBLE
        status.text = message
    }

    private sealed interface LoginResult {
        data object Success : LoginResult
        data class Failure(val message: String) : LoginResult
    }

    private class ServerAdapter(
        private val onClick: (Server) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.Holder>() {

        data class Server(val name: String, val address: String)

        private val servers = mutableListOf<Server>()

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.name)
            val address: TextView = view.findViewById(R.id.address)
        }

        /** Ignores repeats: a server answers the broadcast more than once. */
        fun add(name: String, address: String) {
            if (address.isBlank() || servers.any { it.address == address }) return
            servers += Server(name.ifBlank { address }, address)
            notifyItemInserted(servers.size - 1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_jellyfin_server, parent, false)
        )

        override fun getItemCount() = servers.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val server = servers[position]
            holder.name.text = server.name
            holder.address.text = server.address
            holder.itemView.setOnClickListener { onClick(server) }
        }
    }

    private class UserAdapter(
        private val onClick: (UserDto) -> Unit
    ) : RecyclerView.Adapter<UserAdapter.Holder>() {

        private val users = mutableListOf<UserDto>()
        private var serverUrl: String = ""

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatar: ImageView = view.findViewById(R.id.avatar)
            val name: TextView = view.findViewById(R.id.name)
            val selected: ImageView = view.findViewById(R.id.selected)
        }

        private var selectedPosition = RecyclerView.NO_POSITION

        fun submit(newUsers: List<UserDto>, server: String) {
            users.clear()
            users.addAll(newUsers)
            serverUrl = server
            selectedPosition = RecyclerView.NO_POSITION
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_jellyfin_user, parent, false)
        )

        override fun getItemCount() = users.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val user = users[position]
            holder.name.text = user.name
            holder.selected.visibility =
                if (position == selectedPosition) View.VISIBLE else View.INVISIBLE
            val tag = user.primaryImageTag
            // Both branches set every property they depend on. Rows are recycled, so anything left
            // over from the previous user - a photo behind a glyph, a glyph stretched edge to edge -
            // shows up as a rendering bug that only appears after scrolling.
            if (tag != null) {
                holder.avatar.setPadding(0, 0, 0, 0)
                holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.avatar.imageTintList = null
                // Circle-cropped by the loader rather than by the view. An ImageView draws its own
                // src, so clipToOutline against a round background does not touch it - the photo
                // keeps its square corners and spills outside the circle.
                holder.avatar.load(
                    "$serverUrl/Users/${user.id}/Images/Primary?tag=$tag"
                ) {
                    transformations(CircleCropTransformation())
                }
            } else {
                val inset = holder.itemView.resources
                    .getDimensionPixelSize(R.dimen.jellyfin_avatar_glyph_inset)
                holder.avatar.dispose()
                holder.avatar.setImageResource(R.drawable.ic_person_small)
                holder.avatar.imageTintList =
                    ColorStateList.valueOf(
                        holder.itemView.context.getColor(R.color.onSurfaceColorInactive)
                    )
                holder.avatar.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.avatar.setPadding(inset, inset, inset, inset)
            }
            holder.itemView.setOnClickListener {
                val old = selectedPosition
                selectedPosition = holder.bindingAdapterPosition
                if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
                if (selectedPosition != RecyclerView.NO_POSITION) notifyItemChanged(selectedPosition)
                onClick(user)
            }
        }
    }

    companion object {
        private const val TAG = "JellyfinLoginActivity"

        /** How long to keep the discovery spinner up before assuming nothing will answer. */
        private const val DISCOVERY_SPINNER_MS = 4_000L

        /** The Kotlin SDK authentication guide recommends updating Quick Connect every 5 seconds. */
        private const val QUICK_CONNECT_POLL_MS = 5_000L
        private const val STATE_SERVER_URL = "server_url"
        private const val STATE_LOCAL_SERVER_URL = "local_server_url"
        private const val STATE_REMOTE_SERVER_URL = "remote_server_url"
        private const val STATE_USER_STEP = "user_step"
    }
}
