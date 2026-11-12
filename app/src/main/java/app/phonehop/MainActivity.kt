package app.phonehop

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Wi-Fi Direct group + Mullvad server selection, traffic graphs and the self-updater. */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("phonehop", MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ui by lazy { Ui(this) }
    private val updater by lazy { Updater(this) }
    private val locator by lazy { DeviceLocator(this) }
    private val secrets by lazy {
        SecretStore(PrefsKeyValueStore(getSharedPreferences("phonehop_secrets", MODE_PRIVATE)), KeystoreCipher())
    }
    private lateinit var p2p: WifiDirectController
    private lateinit var monitor: TrafficMonitor

    private lateinit var scroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var groupStatus: TextView
    private lateinit var suffixField: EditText
    private lateinit var passField: EditText
    private lateinit var accountField: EditText
    private lateinit var accountSavedNote: TextView
    private lateinit var forgetAccountButton: View
    private lateinit var countrySpinner: Spinner
    private lateinit var serverSpinner: Spinner
    private lateinit var manualBox: View
    private lateinit var selectedView: TextView
    private lateinit var updateStatus: TextView
    private lateinit var wifiPanel: TrafficPanel
    private lateinit var cellPanel: TrafficPanel
    private lateinit var relayPanel: TrafficPanel
    private val profiles by lazy { ProfileStore(PrefsKeyValueStore(prefs)) }
    private lateinit var wizard: RouterWizard
    private lateinit var lockNote: TextView
    private lateinit var editButton: android.widget.Button
    private lateinit var bandBox: View
    private lateinit var bandBlocker: View
    private var groupUp = false
    private var editingGroup = false
    private lateinit var relayStatus: TextView

    private var bandIndex = 2
    private var lastLoggedGroup: String? = null
    private var autoServer = true
    private var imeBottom = 0
    private var relayList: MullvadApi.RelayList? = null
    private var countryRelays: List<MullvadApi.Relay> = emptyList()
    private var selectedRelay: MullvadApi.Relay? = null
    private var pendingServerHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        p2p = WifiDirectController(this, ::log, ::showGroup)
        monitor = TrafficMonitor(
            relayTotals = { Pair(RelayRuntime.stats.toRouter.get(), RelayRuntime.stats.fromRouter.get()) }
        ) { s ->
            wifiPanel.update(s.wifiDown, s.wifiUp)
            cellPanel.update(s.cellDown, s.cellUp)
            relayPanel.update(s.relayDown, s.relayUp)
            updateRelayStatus()
        }
        RelayRuntime.logSink = ::log
        setContentView(buildUi())
        log("PhoneHop ${versionName()} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        Updater.statusListener = { msg -> setUpdateStatus(msg) }
        requestPermissionsThenStart()
        // Only on a fresh launch, not on rotation, so we don't re-check repeatedly.
        if (savedInstanceState == null && prefs.getBoolean("auto_update", true)) checkForUpdates(auto = true)
    }

    override fun onStart() {
        super.onStart()
        if (hasP2pPermission()) { p2p.start(); p2p.refresh(); applyGroupSoon(500) }
        monitor.start()
    }

    override fun onStop() {
        // Receiver and sampling only; the group and the service live on while the process does.
        p2p.stop()
        monitor.stop()
        super.onStop()
    }

    override fun onDestroy() {
        Updater.statusListener = null
        RelayRuntime.logSink = {}
        super.onDestroy()
    }

    private fun versionName() =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    // ---------------------------------------------------------------- Startup and permissions

    private fun p2pPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    private fun granted(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun hasP2pPermission() = granted(p2pPermission())

    /** Asks for everything the app uses in one go, then starts the keep-alive service and server lookup. */
    private fun requestPermissionsThenStart() {
        val missing = mutableListOf<String>()
        if (!hasP2pPermission()) missing += p2pPermission()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) missing += Manifest.permission.POST_NOTIFICATIONS
        if (!locator.hasPermission()) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (missing.isEmpty()) onPermissionsSettled() else requestPermissions(missing.toTypedArray(), REQ_ALL)
    }

    private fun onPermissionsSettled() {
        if (hasP2pPermission()) { p2p.start(); applyGroupSoon(300) }
        // Always on: this is what keeps the Wi-Fi Direct group alive with the screen off.
        KeepAliveService.start(this)
        fetchServers()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) log("Permission denied: ${p.substringAfterLast('.')}")
        }
        if (requestCode == REQ_ALL) onPermissionsSettled()
    }

    // ---------------------------------------------------------------- Quit

    /** Removes the group, stops the service (clearing its notification) and closes the app completely. */
    private fun quit() {
        log("Quitting: removing Wi-Fi Direct group and stopping background service")
        p2p.removeGroup {
            KeepAliveService.stop(this)
            monitor.stop()
            finishAndRemoveTask()
            // Nothing is left running, so end the process too rather than leaving it cached.
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
        }
    }

    // ---------------------------------------------------------------- Updates

    private fun setUpdateStatus(msg: String) {
        log("Update: $msg")
        runOnUiThread { updateStatus.text = "Version ${updater.currentVersion()}\n$msg" }
    }

    /**
     * auto = true: install silently if newer (Android may still ask the user to confirm).
     * auto = false: ask before installing.
     */
    private fun checkForUpdates(auto: Boolean) {
        io.execute {
            try {
                setUpdateStatus("Checking GitHub...")
                val release = updater.fetchLatest()
                if (!updater.isNewer(release.version)) {
                    setUpdateStatus("Up to date (latest is ${release.tag})")
                    return@execute
                }
                setUpdateStatus("New version ${release.tag} available")
                if (auto) installRelease(release)
                else runOnUiThread {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Update to ${release.tag}?")
                        .setMessage(release.notes.ifBlank { "No release notes." }.take(600))
                        .setPositiveButton("Install") { _, _ -> io.execute { installRelease(release) } }
                        .setNegativeButton("Later", null)
                        .show()
                }
            } catch (e: Updater.NoReleaseException) {
                setUpdateStatus("No releases published yet")
            } catch (e: Exception) {
                setUpdateStatus("Check failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun installRelease(release: Updater.Release) {
        if (!updater.canInstall()) {
            setUpdateStatus("Allow PhoneHop to install apps, then check again")
            runOnUiThread {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
            return
        }
        try {
            setUpdateStatus("Downloading ${release.apkName} (${release.size / 1024} KB)...")
            updater.downloadAndInstall(release) { pct -> setUpdateStatus("Downloaded $pct%") }
            setUpdateStatus("Verified; installing...")
        } catch (e: Exception) {
            setUpdateStatus("Update failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- UI

    private fun defaultBandIndex(): Int {
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        // Index into WifiDirectController.Band: 1 = 2.4 GHz, 2 = 5 GHz. Prefer the fastest the radio has.
        return if (wm != null && wm.is5GHzBandSupported) 2 else 1
    }

    private fun buildUi(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ui.appTitle("PhoneHop", "Wi-Fi Direct relay to Mullvad"))

        // ---- Wi-Fi Direct (values are saved so they survive leaving and returning to the app)
        profiles.active()?.let { p ->
            prefs.edit().putString(PREF_SUFFIX, p.groupSuffix).putString(PREF_PASS, p.groupPassphrase).putInt(PREF_BAND, p.bandIndex).apply()
        }
        suffixField = ui.field("hop", prefs.getString(PREF_SUFFIX, "hop") ?: "hop")
        ui.watch(suffixField) { prefs.edit().putString(PREF_SUFFIX, it).apply(); applyGroupSoon() }
        val savedPass = prefs.getString(PREF_PASS, null)
            ?: PassphraseGenerator.generate(20).also { prefs.edit().putString(PREF_PASS, it).apply() }
        passField = ui.field("8 to 63 characters", savedPass)
        ui.watch(passField) { prefs.edit().putString(PREF_PASS, it).apply(); applyGroupSoon() }
        bandIndex = prefs.getInt(PREF_BAND, defaultBandIndex())
        groupStatus = ui.mono("No group.")
        val bandSegmented = ui.segmented(WifiDirectController.Band.values().map { it.label }, bandIndex) {
            bandIndex = it
            prefs.edit().putInt(PREF_BAND, it).apply()
            applyGroupSoon()
        }
        bandBlocker = View(this).apply { isClickable = true; visibility = View.GONE }
        bandBox = FrameLayout(this).apply {
            addView(bandSegmented)
            addView(bandBlocker, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        lockNote = ui.caption("")
        editButton = ui.secondary("Unlock to edit") { onEditGroupClicked() }
        content.addView(
            ui.card(
                "Wi-Fi Direct group",
                "The router joins this as a normal Wi-Fi network. The group is created automatically and kept in step with these settings.",
                ui.labeled("Network name", suffixField, "The SSID will be DIRECT-ph- followed by this name."),
                ui.labeled("Password", passField, "At least 8 characters."),
                ui.labeled("Band", bandBox, "5 GHz is fastest. If your phone can't host on 5 GHz it falls back to 2.4 GHz."),
                lockNote,
                editButton,
                groupStatus,
            ), ui.cardParams(20)
        )

        // ---- Relay
        relayStatus = ui.mono("Starting...")
        relayPanel = TrafficPanel(this, "Relayed: router and Mullvad")
        content.addView(
            ui.card(
                "Relay",
                "Forwards the router's WireGuard traffic to the selected Mullvad server over the phone's internet connection. It only passes valid WireGuard packets from the Wi-Fi Direct group.",
                relayStatus,
                relayPanel,
            ), ui.cardParams()
        )

        // ---- Live traffic
        wifiPanel = TrafficPanel(this, "Wi-Fi + Wi-Fi Direct")
        cellPanel = TrafficPanel(this, "Cellular")
        content.addView(
            ui.card(
                "Live traffic",
                "Device-wide, updated every second while the app is open. Bright line is download, dim line is upload.",
                wifiPanel,
                cellPanel,
            ), ui.cardParams()
        )

        // ---- Mullvad
        val (accountBox, account) = ui.secretField("1234 5678 9012 3456", number = true)
        accountField = account
        runCatching { secrets.account() }.getOrNull()?.let { account.setText(it) }
        accountSavedNote = ui.caption("")
        forgetAccountButton = ui.danger("Forget saved account number") {
            secrets.clearAccount()
            accountField.setText("")
            updateAccountSavedUi()
            log("Saved account number forgotten")
        }
        countrySpinner = Spinner(this)
        serverSpinner = Spinner(this)
        selectedView = ui.mono("Looking for the nearest server...")
        autoServer = prefs.getBoolean(PREF_AUTO, true)
        manualBox = ui.stack(
            ui.labeled("Country", ui.spinnerBox(countrySpinner)),
            ui.labeled("Server", ui.spinnerBox(serverSpinner)),
        ).apply { visibility = if (autoServer) View.GONE else View.VISIBLE }
        content.addView(
            ui.card(
                "Mullvad",
                "Used only to talk to Mullvad. Once it has worked it is remembered on this phone, encrypted with the Android Keystore, until you forget it. It is never logged.",
                ui.labeled("Account number", accountBox, "16 digits, from mullvad.net/account. Tap the eye to show or hide it."),
                accountSavedNote,
                forgetAccountButton,
                ui.row(
                    ui.secondary("Check account") { withToken { t -> log("Account expires: ${MullvadApi.accountExpiry(t)}") } },
                    ui.secondary("List devices") { listDevices() },
                ),
                ui.labeled(
                    "Server selection",
                    ui.segmented(listOf("Auto (nearest)", "Manual"), if (autoServer) 0 else 1) { i ->
                        autoServer = i == 0
                        prefs.edit().putBoolean(PREF_AUTO, autoServer).apply()
                        manualBox.visibility = if (autoServer) View.GONE else View.VISIBLE
                        if (autoServer) relayList?.let { autoSelect(it) } else restoreManualSelection()
                    },
                    "Auto picks the closest server using your location."
                ),
                manualBox,
                selectedView,
                ui.row(
                    ui.secondary("Refresh servers") { fetchServers() },
                    ui.primary("Register key") { registerKey() },
                ),
                ui.row(
                    ui.danger("Remove my device") { removeMyDevice() },
                    ui.secondary("Copy router config") { copyRouterConfig() },
                ),
            ), ui.cardParams()
        )

        // ---- Router setup
        wizard = RouterWizard(
            activity = this, ui = ui, prefs = prefs, sshStore = PrefsKeyValueStore(prefs), profiles = profiles, secrets = secrets,
            log = ::log,
            inputs = {
                RouterWizard.Inputs(
                    groupSuffix = suffixField.text.toString().trim(), groupPass = passField.text.toString(),
                    bandIndex = bandIndex, relay = selectedRelay, groupUp = groupUp,
                )
            },
            registerKey = { registerKey() },
            onProfileChanged = { runOnUiThread { applyProfileLock(); wizard.render() } },
        )
        content.addView(wizard.view, ui.cardParams())
        wizard.render()
        applyProfileLock()

        // ---- Log
        logView = ui.mono("", 11f)
        content.addView(
            ui.card("Log", null, logView, ui.secondary("Clear log") { logView.text = "" }),
            ui.cardParams()
        )

        // ---- Updates
        updateStatus = ui.caption("Version ${updater.currentVersion()}")
        content.addView(
            ui.card(
                "Updates", null,
                updateStatus,
                ui.toggle("Check and install on launch", prefs.getBoolean("auto_update", true)) { on ->
                    prefs.edit().putBoolean("auto_update", on).apply()
                },
                ui.secondary("Check for updates now") { checkForUpdates(auto = false) },
            ), ui.cardParams()
        )

        // ---- Exit
        content.addView(
            ui.card(
                "Exit",
                "Removes the Wi-Fi Direct network, stops the background service and its notification, and closes the app.",
                ui.danger("Quit PhoneHop") { quit() },
            ), ui.cardParams()
        )

        updateAccountSavedUi()

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = FrameLayout(this).apply {
            background = AuroraDrawable()
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // The window draws edge-to-edge: keep content clear of the status bar, nav bar and keyboard.
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                    top = bars.top
                    bottom = maxOf(bars.bottom, imeBottom)
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                content.setPadding(dp(16), top + dp(20), dp(16), bottom + dp(28))
                // The keyboard just opened or closed: make sure the field being typed in is visible.
                scroll.postDelayed({ (currentFocus as? EditText)?.let { ensureVisible(it) } }, 120)
                insets
            }
            // Scroll to a field as soon as it gets focus, even if the keyboard is already open.
            viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus is EditText) scroll.postDelayed({ ensureVisible(newFocus) }, 250)
            }
        }
        return root
    }

    /** Scrolls so [v] sits fully above the keyboard (and below the top edge). */
    private fun ensureVisible(v: View) {
        val r = Rect()
        v.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(v, r)
        val margin = dp(24)
        val visibleTop = scroll.scrollY + margin
        val visibleBottom = scroll.scrollY + scroll.height - imeBottom - margin
        when {
            r.bottom > visibleBottom -> scroll.smoothScrollBy(0, r.bottom - visibleBottom)
            r.top < visibleTop -> scroll.smoothScrollBy(0, r.top - visibleTop)
        }
    }

    private fun dp(v: Int) = ui.dp(v)

    /** While a router is paired it has the Wi-Fi Direct name, password and band saved, so they are locked. */
    private fun applyProfileLock() {
        val p = profiles.active()
        val locked = p != null && !editingGroup
        suffixField.isEnabled = !locked
        passField.isEnabled = !locked
        listOf<View>(suffixField, passField, bandBox).forEach { it.alpha = if (locked) 0.55f else 1f }
        bandBlocker.visibility = if (locked) View.VISIBLE else View.GONE
        if (p != null && locked) {
            if (suffixField.text.toString() != p.groupSuffix) suffixField.setText(p.groupSuffix)
            if (passField.text.toString() != p.groupPassphrase) passField.setText(p.groupPassphrase)
        }
        lockNote.visibility = if (p != null) View.VISIBLE else View.GONE
        lockNote.text = when {
            p == null -> ""
            editingGroup -> "Editing. The router has the old settings saved, so it will lose the link until you run its setup again."
            p.stale -> "Locked to ${p.title} (${p.shortId}), but the router has older settings: run the router setup again."
            else -> "Locked to ${p.title} (${p.shortId}). The router has these settings saved; changing them would cut its connection."
        }
        editButton.visibility = if (p != null) View.VISIBLE else View.GONE
        editButton.text = if (editingGroup) "Save and lock" else "Unlock to edit"
    }

    private fun onEditGroupClicked() {
        val p = profiles.active() ?: return
        if (!editingGroup) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Change the Wi-Fi Direct network?")
                .setMessage("${p.title} has the current name and password saved. If you change them, it will lose its link to this phone until you run the router setup again.")
                .setNegativeButton("Keep locked", null)
                .setPositiveButton("Unlock") { _, _ -> editingGroup = true; applyProfileLock() }
                .show()
        } else {
            val changed = suffixField.text.toString().trim() != p.groupSuffix || passField.text.toString() != p.groupPassphrase || bandIndex != p.bandIndex
            if (changed) {
                profiles.save(
                    p.copy(
                        groupSuffix = suffixField.text.toString().trim(), groupPassphrase = passField.text.toString(),
                        bandIndex = bandIndex, stale = true,
                    )
                )
                log("Wi-Fi Direct settings changed; the router needs updating")
            }
            editingGroup = false
            applyProfileLock()
            wizard.render()
            applyGroupSoon(200)
        }
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append("${timeFmt.format(Date())}  $msg\n")
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private val uiHandler = Handler(Looper.getMainLooper())
    private val applyGroup = Runnable { applyGroupNow() }

    /** Waits for typing to pause, then makes the group match the fields. */
    private fun applyGroupSoon(delayMs: Long = 1_500) {
        uiHandler.removeCallbacks(applyGroup)
        uiHandler.postDelayed(applyGroup, delayMs)
    }

    private fun applyGroupNow() {
        if (!hasP2pPermission()) { groupStatus.text = "Allow nearby devices access to create the network."; return }
        p2p.start()
        val band = WifiDirectController.Band.values()[bandIndex]
        val problem = p2p.ensureGroup(suffixField.text.toString().trim(), passField.text.toString(), band)
        if (problem != null) groupStatus.text = problem
        else if (groupStatus.text.isNullOrBlank() || groupStatus.text == "No group.") groupStatus.text = "Starting the network..."
    }

    private fun showGroup(group: WifiP2pGroup?) = runOnUiThread {
        RelayRuntime.setGroupInterface(group?.`interface`)
        val up = group != null
        if (up != groupUp) { groupUp = up; if (::wizard.isInitialized) wizard.render() }
        if (group == null) { groupStatus.text = "No group."; return@runOnUiThread }
        val clients = group.clientList.joinToString("\n") { "    ${it.deviceName.ifEmpty { "?" }} ${it.deviceAddress}" }
        groupStatus.text = buildString {
            appendLine("SSID:        ${group.networkName}")
            appendLine("Passphrase:  ${group.passphrase}")
            appendLine("Owner:       ${if (group.isGroupOwner) "this phone (expect 192.168.49.1)" else group.owner?.deviceAddress}")
            appendLine("Interface:   ${group.`interface`}")
            append("Clients:     ${group.clientList.size}")
            if (clients.isNotEmpty()) append("\n$clients")
        }
        if (group.networkName != lastLoggedGroup) {
            lastLoggedGroup = group.networkName
            log("Group up: ${group.networkName}")
        }
    }

    // ---------------------------------------------------------------- Mullvad servers

    private fun fetchServers() = io.execute {
        try {
            log("Fetching relay list...")
            val list = MullvadApi.fetchRelays()
            relayList = list
            val countries = list.relays.map { it.country }.distinct()
            log("Got ${list.relays.size} active WireGuard relays in ${countries.size} countries; ports ${list.portRanges.joinToString { "${it.first}-${it.last}" }}")
            runOnUiThread {
                bindServerPickers(countries)
                if (autoServer) autoSelect(list) else restoreManualSelection()
            }
        } catch (e: Exception) {
            log("Fetch failed: ${describe(e)}")
            runOnUiThread { selectedView.text = "Could not load the server list." }
        }
    }

    /** Picks the closest relay using the device location, or the connection's IP location as a fallback. */
    private fun autoSelect(list: MullvadApi.RelayList) {
        // A paired router's tunnel is tied to one server; auto-selection must never move it.
        profiles.active()?.let { p ->
            list.relays.firstOrNull { it.hostname == p.serverHost }?.let { pinned ->
                useRelay(pinned)
                showRelay(pinned, "Pinned to ${p.title}. Choose Manual to switch the router to another server.")
                return
            }
        }
        selectedView.text = "Finding the nearest server..."
        locator.locate { fix ->
            io.execute {
                val here = fix ?: runCatching { MullvadApi.ipLocation() }.getOrNull()
                    ?.let { DeviceLocator.Fix(it.first, it.second, "IP address; allow location for a better match") }
                if (here == null) {
                    log("Could not work out where you are; switch to Manual to choose a server")
                    runOnUiThread { selectedView.text = "Location unavailable." }
                    return@execute
                }
                val pick = RelayPicker.nearest(list.relays, here.lat, here.lon)
                if (pick == null) { log("No relays available"); return@execute }
                useRelay(pick.relay)
                log("Auto-selected ${pick.relay.hostname} (${pick.relay.city}, ${pick.relay.country}), about ${pick.km.roundToInt()} km away, from ${here.source}")
                runOnUiThread { showRelay(pick.relay, "Nearest server, ${pick.km.roundToInt()} km away (${here.source})") }
            }
        }
    }

    /** Makes [r] the current server: remembered for the config export and used by the relay. */
    private fun useRelay(r: MullvadApi.Relay) {
        selectedRelay = r
        prefs.edit().putString(PREF_RELAY, r.hostname).putString("relay_ip", r.ipv4).apply()
        RelayRuntime.setUpstream(r.ipv4)
    }

    private fun updateRelayStatus() {
        val st = RelayRuntime.stats
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "never" else "${(now - t) / 1000}s ago"
        val router = st.routerAddress
        relayStatus.text = buildString {
            appendLine("Listening:  ${st.listening ?: "waiting for the Wi-Fi Direct group"}")
            appendLine("Server:     ${st.upstreamText ?: "none selected"} (last reply ${ago(st.lastFromUpstreamAt)})")
            appendLine("Internet:   ${RelayRuntime.internetText}")
            appendLine("Router:     ${router?.let { "${it.address.hostAddress}:${it.port} (last packet ${ago(st.lastFromRouterAt)})" } ?: "not seen yet"}")
            append("Relayed:    \u2191 ${TrafficMath.formatBytes(st.fromRouter.get())}  \u2193 ${TrafficMath.formatBytes(st.toRouter.get())}   dropped ${st.dropped.get()}")
        }
    }

    /** A manual choice; with a paired router this also has to be pushed to the router. */
    private fun chooseManually(r: MullvadApi.Relay) {
        val p = profiles.active()
        if (p == null || p.serverHost == r.hostname) {
            useRelay(r)
            showRelay(r, if (p == null) "Chosen manually" else "Pinned to ${p.title}")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Switch the router to ${r.hostname}?")
            .setMessage("The router's tunnel will reconnect to ${r.city}, ${r.country}. Open connections will drop.")
            .setNegativeButton("Cancel") { _, _ -> restoreManualSelection() }
            .setPositiveButton("Switch") { _, _ ->
                useRelay(r)
                showRelay(r, "Switching ${p.title}...")
                wizard.applyServer(r) { msg -> log(msg); showRelay(r, msg) }
            }
            .setOnCancelListener { restoreManualSelection() }
            .show()
    }

    private fun showRelay(r: MullvadApi.Relay, note: String) {
        selectedView.text = "$note\n${r.city}, ${r.country}\n${r.hostname}  ${r.ipv4}:51820\n${r.publicKey}\n${if (r.owned) "Mullvad-owned" else "rented"} (${r.provider})"
    }

    private fun bindServerPickers(countries: List<String>) {
        countrySpinner.adapter = ui.adapter(countries)
        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val country = countries[pos]
                countryRelays = relayList?.relays.orEmpty().filter { it.country == country }
                serverSpinner.adapter = ui.adapter(countryRelays.map { "${it.city} - ${it.hostname}" })
                pendingServerHost?.let { host ->
                    val idx = countryRelays.indexOfFirst { it.hostname == host }
                    if (idx >= 0) serverSpinner.setSelection(idx)
                    pendingServerHost = null
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (autoServer) return // spinners are hidden; don't override the automatic choice
                val r = countryRelays.getOrNull(pos) ?: return
                chooseManually(r)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** In manual mode, show the previously chosen server again. */
    private fun restoreManualSelection() {
        val host = profiles.active()?.serverHost?.takeIf { it.isNotBlank() }
            ?: prefs.getString(PREF_RELAY, null) ?: run { selectedView.text = "Pick a country and server."; return }
        val relay = relayList?.relays?.firstOrNull { it.hostname == host } ?: return
        pendingServerHost = host
        val countries = relayList?.relays?.map { it.country }?.distinct().orEmpty()
        val idx = countries.indexOf(relay.country)
        if (idx >= 0) countrySpinner.setSelection(idx)
        useRelay(relay)
        showRelay(relay, "Chosen manually")
    }

    // ---------------------------------------------------------------- Mullvad account

    private fun updateAccountSavedUi() {
        val saved = runCatching { secrets.account() }.getOrNull() != null
        accountSavedNote.text = if (saved) "Saved on this phone (encrypted)." else "Not saved."
        forgetAccountButton.visibility = if (saved) View.VISIBLE else View.GONE
    }

    /** Runs [block] with a fresh access token. The account number never leaves this call. */
    private fun withToken(block: (String) -> Unit) {
        val account = accountField.text.toString().filter { it.isDigit() }
        if (account.length != 16) { log("Enter your 16-digit account number"); return }
        io.execute {
            try {
                val token = MullvadApi.login(account)
                // It worked, so it is not a typo: remember it (encrypted) for next time.
                if (runCatching { secrets.account() }.getOrNull() != account) {
                    runCatching { secrets.saveAccount(account) }
                        .onFailure { log("Could not save the account number: ${it.message}") }
                    runOnUiThread { updateAccountSavedUi() }
                }
                block(token)
            } catch (e: Exception) {
                log("Failed: ${describe(e)}")
            }
        }
    }

    private fun registerKey() = withToken { token ->
        val existingId = prefs.getString("device_id", null)
        if (existingId != null) { log("Already registered as device $existingId; remove it first"); return@withToken }
        val priv = X25519.generatePrivateKey()
        val pubB64 = Base64.encodeToString(X25519.publicKey(priv), Base64.NO_WRAP)
        val device = MullvadApi.addDevice(token, pubB64)
        prefs.edit()
            .putString("priv", Base64.encodeToString(priv, Base64.NO_WRAP))
            .putString("pub", pubB64)
            .putString("device_id", device.id)
            .putString("ipv4", device.ipv4)
            .putString("ipv6", device.ipv6)
            .apply()
        log("Registered device '${device.name}' (${device.id})\n  tunnel IPs: ${device.ipv4}, ${device.ipv6}")
    }

    private fun listDevices() = withToken { token ->
        val devices = MullvadApi.listDevices(token)
        log("${devices.size}/5 devices on this account:")
        val mine = prefs.getString("device_id", null)
        devices.forEach { log("  ${if (it.id == mine) "*" else " "} ${it.name}  ${it.ipv4}") }
    }

    private fun removeMyDevice() = withToken { token ->
        val id = prefs.getString("device_id", null)
        if (id == null) { log("No device registered by this app"); return@withToken }
        MullvadApi.removeDevice(token, id)
        prefs.edit().remove("priv").remove("pub").remove("device_id").remove("ipv4").remove("ipv6").apply()
        log("Removed device $id")
    }

    private fun copyRouterConfig() {
        val priv = prefs.getString("priv", null)
        val ipv4 = prefs.getString("ipv4", null)
        val ipv6 = prefs.getString("ipv6", null)
        val relay = selectedRelay
        if (priv == null || ipv4 == null) { log("Register a key first"); return }
        if (relay == null) { log("No server selected yet"); return }
        val cfg = """
            [Interface]
            PrivateKey = $priv
            Address = $ipv4, $ipv6
            DNS = 10.64.0.1

            [Peer]
            PublicKey = ${relay.publicKey}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 192.168.49.1:51820
            PersistentKeepalive = 25
        """.trimIndent()
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("WireGuard config", cfg))
        log("Router config copied to clipboard (private key inside; paste it only into the router).\n" +
            "  Phone relay would forward 192.168.49.1:51820 -> ${relay.ipv4}:51820")
    }

    private fun describe(e: Exception) = when (e) {
        is MullvadApi.ApiException -> when (e.code) {
            "INVALID_ACCOUNT" -> "account number not recognised"
            "MAX_DEVICES_REACHED" -> "account already has 5 devices; remove one first"
            "KEY_ALREADY_IN_USE" -> "that key is already registered"
            else -> e.message ?: "API error"
        }
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        private const val REQ_ALL = 1
        private const val PREF_SUFFIX = "p2p_suffix"
        private const val PREF_PASS = "p2p_pass"
        private const val PREF_BAND = "p2p_band"
        private const val PREF_AUTO = "server_auto"
        private const val PREF_RELAY = "relay_host"
    }
}
