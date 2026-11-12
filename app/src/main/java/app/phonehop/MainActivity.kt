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
import android.os.PowerManager
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

/** Router link + Mullvad server selection, traffic graphs and the self-updater. */
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
    private lateinit var monitor: TrafficMonitor

    private lateinit var scroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var linkStatus: TextView
    private lateinit var statusView: TextView
    private lateinit var ssidField: EditText
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
    private lateinit var latencyPanel: TrafficPanel
    private lateinit var latencySummary: TextView
    private lateinit var batteryNote: TextView
    private lateinit var batteryButton: View
    private var lastLatencyAt = 0L
    private val profiles by lazy { ProfileStore(PrefsKeyValueStore(prefs)) }
    private lateinit var wizard: RouterWizard
    private var lastLinkUp = false
    private lateinit var relayStatus: TextView

    private var autoServer = true
    private var imeBottom = 0
    private var relayList: MullvadApi.RelayList? = null
    private var countryRelays: List<MullvadApi.Relay> = emptyList()
    private var selectedRelay: MullvadApi.Relay? = null
    private var pendingServerHost: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        monitor = TrafficMonitor(
            relayTotals = { Pair(RelayRuntime.stats.toRouter.get(), RelayRuntime.stats.fromRouter.get()) }
        ) { s ->
            wifiPanel.update(s.wifiDown, s.wifiUp)
            cellPanel.update(s.cellDown, s.cellUp)
            relayPanel.update(s.relayDown, s.relayUp)
            updateRelayStatus()
            updateLatency()
            updateLinkStatus()
            updateStatusCard()
        }
        RelayRuntime.logSink = ::log
        // Settings left over from the old Wi-Fi Direct design.
        prefs.edit().remove("p2p_suffix").remove("p2p_pass").remove("p2p_band").apply()
        setContentView(buildUi())
        log("PhoneHop ${versionName()} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        Updater.statusListener = { msg -> setUpdateStatus(msg) }
        // Replay what happened while the screen was away (screen off, Doze, network changes...).
        RelayRuntime.recentEvents().takeLast(60).forEach { logAt(it.at, it.msg) }
        requestPermissionsThenStart()
        // Only on a fresh launch, not on rotation, so we don't re-check repeatedly.
        if (savedInstanceState == null && prefs.getBoolean("auto_update", true)) checkForUpdates(auto = true)
    }

    override fun onStart() {
        super.onStart()
        RelayRuntime.event("App in foreground | ${RelayRuntime.snapshotText()}")
        LinkRuntime.start(applicationContext)
        applyLinkSoon(500)
        updateBatteryUi()
        monitor.start()
    }

    override fun onStop() {
        // Only the screen's own sampling stops; the group, relay and probes belong to the service.
        RelayRuntime.event("App in background | ${RelayRuntime.snapshotText()}")
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

    private fun granted(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Asks for everything the app uses in one go, then starts the keep-alive service and server lookup. */
    private fun requestPermissionsThenStart() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) missing += Manifest.permission.POST_NOTIFICATIONS
        if (!locator.hasPermission()) missing += Manifest.permission.ACCESS_COARSE_LOCATION
        if (missing.isEmpty()) onPermissionsSettled() else requestPermissions(missing.toTypedArray(), REQ_ALL)
    }

    private fun onPermissionsSettled() {
        LinkRuntime.start(applicationContext)
        applyLinkSoon(300)
        // Always on: this keeps the link to the router and the relay alive with the screen off.
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

    /** Leaves the link network, stops the service (clearing its notification) and closes the app completely. */
    private fun quit() {
        log("Quitting: leaving the link network and stopping the background service")
        LinkRuntime.remove()
        KeepAliveService.stop(this)
        monitor.stop()
        finishAndRemoveTask()
        // Nothing is left running, so end the process too rather than leaving it cached.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
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

    private fun buildUi(): View {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Sections are collected as they are built, then added in the order a person would use them.
        val placed = mutableListOf<Pair<View, ViewGroup.LayoutParams?>>()
        fun place(v: View, p: ViewGroup.LayoutParams? = null) { placed += Pair(v, p) }
        place(ui.appTitle("PhoneHop", "Router relay to Mullvad"))
        statusView = ui.mono("Starting...", 12f)
        place(ui.card("Status", null, statusView), ui.cardParams(20))

        // ---- Link to the router (values are saved so they survive leaving and returning to the app)
        profiles.active()?.let { p ->
            prefs.edit().putString(PREF_SSID, p.linkSsid).putString(PREF_PASS, p.linkPassphrase).apply()
        }
        // Generated once and not editable: the router has these saved, and nothing else ever needs to join.
        val savedSsid = prefs.getString(PREF_SSID, null) ?: "PhoneHop-link".also { prefs.edit().putString(PREF_SSID, it).apply() }
        val savedPass = prefs.getString(PREF_PASS, null)
            ?: PassphraseGenerator.generate(20).also { prefs.edit().putString(PREF_PASS, it).apply() }
        ssidField = ui.field("", savedSsid).apply { isEnabled = false; alpha = 0.55f }
        passField = ui.field("", savedPass).apply { isEnabled = false; alpha = 0.55f }
        linkStatus = ui.mono("Starting...")
        place(
            ui.card(
                "Link to router",
                "The router hosts a small hidden Wi-Fi network just for this phone. The phone joins it automatically, like a car's Wi-Fi, and keeps using cellular for its own internet. Nothing here needs configuring.",
                ui.labeled("Link network name", ssidField, "Generated for you and not editable."),
                ui.labeled("Link password", passField, "Random and not editable. Copy it if you ever need it."),
                ui.secondary("Copy password") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link password", passField.text.toString()))
                    log("Link password copied to the clipboard")
                },
                linkStatus,
            ), ui.cardParams(20)
        )

        // ---- Relay
        relayStatus = ui.mono("Starting...")
        relayPanel = TrafficPanel(this, "Relayed: router and Mullvad")
        place(
            ui.collapsible(
                "Relay",
                "Forwards the router's WireGuard traffic to the selected Mullvad server over the phone's internet connection. It only passes valid WireGuard packets from the router link.",
                relayStatus,
                relayPanel,
            ), ui.cardParams()
        )

        // ---- Link quality
        latencyPanel = TrafficPanel(
            this, "Latency", floor = 20f, format = LatencyStats::format, downLabel = "link", upLabel = "internet",
        )
        latencySummary = ui.mono("Measuring...", 11f)
        batteryNote = ui.caption("")
        batteryButton = ui.secondary("Allow background use") { requestBatteryExemption() }
        val lockSegmented = ui.segmented(WifiLockMode.values().map { it.label }, WifiLockMode.fromPrefs(prefs).ordinal) {
            prefs.edit().putInt(WifiLockMode.PREF, it).apply()
            KeepAliveService.applyWifiLockChoice(this)
        }
        place(
            ui.collapsible(
                "Link quality",
                "Round-trip time to the router over the link network (link) and to Mullvad over the phone's internet (internet), measured every 2 seconds, even with the screen off.",
                latencyPanel,
                latencySummary,
                ui.labeled("Wi-Fi lock", lockSegmented, "Keeps the phone's Wi-Fi radio from power-saving. Try each and watch the graph; Low latency only works with the screen on."),
                ui.toggle("Keep link and cellular warm", prefs.getBoolean("keep_warm", true)) { on ->
                    prefs.edit().putBoolean("keep_warm", on).apply()
                    RelayRuntime.setKeepWarm(on)
                },
                ui.caption("Keeps both radios awake with tiny packets: to the router every 150 ms and to the Mullvad server about every half second. Uses more battery and roughly 150 MB of mobile data a month. Turn it on while pinging from a laptop to see whether the delay swings disappear."),
                batteryNote,
                batteryButton,
                ui.secondary("Clear stats") { RelayRuntime.latency.clear(); RelayRuntime.stats.rtt.clear(); RelayRuntime.stats.maxForwardUs.set(0); latencySummary.text = "Measuring..." },
            ), ui.cardParams()
        )

        // ---- Live traffic
        wifiPanel = TrafficPanel(this, "Wi-Fi (router link)")
        cellPanel = TrafficPanel(this, "Cellular")
        place(
            ui.collapsible(
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
        place(
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
                ui.danger("Remove this phone's key") { removeMyDevice() },
            ), ui.cardParams()
        )

        // ---- Router setup
        wizard = RouterWizard(
            activity = this, ui = ui, prefs = prefs, sshStore = PrefsKeyValueStore(prefs), profiles = profiles, secrets = secrets,
            log = ::log,
            inputs = {
                RouterWizard.Inputs(
                    linkSsid = ssidField.text.toString().trim(), linkPass = passField.text.toString(),
                    relay = selectedRelay, linkUp = RelayRuntime.linkAddress() != null,
                )
            },
            registerKey = { registerKey() },
            onProfileChanged = { runOnUiThread { applyProfileLock(); wizard.render() } },
        )
        place(wizard.view, ui.cardParams())

        // ---- Log
        logView = ui.mono("", 11f)
        place(
            ui.collapsible("Log", "Share diagnostics sends a detailed report (latency samples, relay counters, screen and network events) to any app you choose. It never contains your account number, passwords or keys.", logView, ui.secondary("Share diagnostics") { shareDiagnostics() }, ui.secondary("Clear log") { logView.text = "" }),
            ui.cardParams()
        )

        // ---- Updates
        updateStatus = ui.caption("Version ${updater.currentVersion()}")
        place(
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
        place(
            ui.card(
                "Exit",
                "Leaves the link network, stops the background service and its notification, and closes the app.",
                ui.danger("Quit PhoneHop") { quit() },
            ), ui.cardParams()
        )

        updateAccountSavedUi()

        // The page itself takes initial focus, so no text view (the log, a field) grabs it and scrolls the page.
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        content.revealOnFocusHint = false
        // Order of use: title, status, Mullvad (account, server, key), router setup, the link, the technical cards (folded), updates, exit.
        // Built order: 0 title, 1 status, 2 link, 3 relay, 4 quality, 5 traffic, 6 mullvad, 7 router setup, 8 log, 9 updates, 10 exit.
        check(placed.size == 11) { "unexpected number of sections: ${placed.size}" }
        listOf(0, 1, 6, 7, 2, 3, 4, 5, 8, 9, 10).forEachIndexed { position, i ->
            val (view, params) = placed[i]
            // The first card under the title gets a little more room.
            if (position == 1) content.addView(view, ui.cardParams(20))
            else if (params != null) content.addView(view, params) else content.addView(view)
        }
        applyProfileLock()
        wizard.render()

        scroll = StableScrollView(this).apply {
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
        content.post { content.requestFocus() }
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

    /** If a router profile exists, show exactly what the router has saved. */
    private fun applyProfileLock() {
        val p = profiles.active() ?: return
        if (ssidField.text.toString() != p.linkSsid) ssidField.setText(p.linkSsid)
        if (passField.text.toString() != p.linkPassphrase) passField.setText(p.linkPassphrase)
    }

    private fun log(msg: String) = logAt(System.currentTimeMillis(), msg)

    private fun logAt(at: Long, msg: String) = runOnUiThread {
        logView.append("${timeFmt.format(Date(at))}  $msg\n")
        // Keep the log from growing without bound.
        val t = logView.text
        if (t.length > 40_000) logView.text = t.subSequence(t.length - 30_000, t.length)
    }

    // ---------------------------------------------------------------- Link to the router

    private val uiHandler = Handler(Looper.getMainLooper())
    private val applyLink = Runnable { applyLinkNow() }
    private var linkProblem: String? = null

    /** Waits for typing to pause, then makes Android's Wi-Fi suggestion match the fields. */
    private fun applyLinkSoon(delayMs: Long = 1_500) {
        uiHandler.removeCallbacks(applyLink)
        uiHandler.postDelayed(applyLink, delayMs)
    }

    private fun applyLinkNow() {
        linkProblem = LinkRuntime.ensure(ssidField.text.toString().trim(), passField.text.toString())
        updateLinkStatus()
    }

    private fun updateLinkStatus() {
        val addr = RelayRuntime.linkAddress()
        val up = addr != null
        if (up != lastLinkUp) { lastLinkUp = up; if (::wizard.isInitialized) wizard.render() }
        linkStatus.text = linkProblem ?: buildString {
            appendLine("Network:  ${ssidField.text}")
            appendLine("Phone:    ${addr?.hostAddress?.let { "joined as $it" } ?: "not joined yet"}")
            if (up) append("Router:   ${Link.ROUTER_IP}")
            else append("The phone joins once the router is set up. If Android asks about a Wi-Fi suggestion, tap Allow.")
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
            appendLine("Listening:  ${st.listening ?: "waiting for the phone to join the link network"}")
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

    /** Builds the detailed report, keeps a copy in the app's files, and opens the share sheet. */
    private fun shareDiagnostics() {
        val st = RelayRuntime.stats
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "never" else "${(now - t) / 1000}s ago"
        val pm = getSystemService(PowerManager::class.java)
        val report = DiagnosticsReport.build(
            DiagnosticsReport.In(
                version = versionName(),
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                nowMillis = now,
                settings = listOf(
                    "Server" to (selectedRelay?.let { "${it.hostname} ${it.ipv4}" } ?: "none"),
                    "Server selection" to if (prefs.getBoolean(PREF_AUTO, true)) "auto" else "manual",
                    "Wi-Fi lock" to WifiLockMode.fromPrefs(prefs).label,
                    "Keep warm" to prefs.getBoolean("keep_warm", true).toString(),
                    "Link network" to (ssidField.text.toString()),
                    "Router paired" to (profiles.active()?.title ?: "no"),
                ),
                state = listOf(
                    "Link address" to (RelayRuntime.linkAddress()?.hostAddress ?: "not joined"),
                    "Relay listening" to (st.listening ?: "no"),
                    "Router seen at" to (st.routerAddress?.let { "${it.address.hostAddress}:${it.port}" } ?: "never"),
                    "Router last packet" to ago(st.lastFromRouterAt),
                    "Server last reply" to ago(st.lastFromUpstreamAt),
                    "Relayed up/down" to "${st.fromRouter.get()} / ${st.toRouter.get()} bytes",
                    "Dropped / send errors" to "${st.dropped.get()} / ${st.sendErrors.get()}",
                    "Socket re-opens (upstream/listener)" to "${st.upstreamRebinds.get()} / ${st.listenRebinds.get()}",
                    "Relay forwarding, slowest" to "${st.maxForwardUs.get() / 1000.0} ms",
                    "Internet network" to RelayRuntime.internetText,
                    "Screen interactive" to pm.isInteractive.toString(),
                    "Doze" to pm.isDeviceIdleMode.toString(),
                    "Battery saver" to pm.isPowerSaveMode.toString(),
                    "Battery unrestricted" to pm.isIgnoringBatteryOptimizations(packageName).toString(),
                ),
                samples = RelayRuntime.latency.snapshot(),
                tunnelRtt = st.rtt.since(0),
                events = RelayRuntime.recentEvents().map { it.at to it.msg },
                log = logView.text.toString(),
            )
        )
        runCatching { java.io.File(filesDir, "diagnostics-${now / 1000}.txt").writeText(report) }
        log("Diagnostics report built (${report.length / 1024} KB)")
        startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "PhoneHop diagnostics")
                    .putExtra(android.content.Intent.EXTRA_TEXT, report),
                "Share diagnostics"
            )
        )
    }

    private fun updateStatusCard() {
        val st = RelayRuntime.stats
        val heard = if (st.lastFromRouterAt == 0L) null else (System.currentTimeMillis() - st.lastFromRouterAt) / 1000
        statusView.text = StatusSummary.build(
            StatusSummary.In(
                hasKey = prefs.getString("priv", null) != null,
                serverName = selectedRelay?.hostname,
                linkAddress = RelayRuntime.linkAddress()?.hostAddress,
                routerTitle = profiles.active()?.title,
                routerHeardSecondsAgo = heard,
                relayedBytes = st.fromRouter.get() + st.toRouter.get(),
            )
        )
    }

    private fun updateLatency() {
        val all = RelayRuntime.latency.snapshot()
        val last = all.lastOrNull() ?: return
        if (last.at != lastLatencyAt) {
            lastLatencyAt = last.at
            latencyPanel.update(last.linkMs ?: 0.0, last.internetMs ?: 0.0)
        }
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        val recent = all.filter { it.at >= cutoff }
        fun row(label: String, values: List<Double>): String {
            val m = LatencyStats.summarize(values) ?: return "%-17s %s".format(Locale.US, label, "no data")
            return "%-17s %6s %6s %7s %4d".format(
                Locale.US, label, LatencyStats.format(m.p50), LatencyStats.format(m.p95), LatencyStats.format(m.max), m.spikes
            )
        }
        latencySummary.text = buildString {
            appendLine("10 min             p50    p95    max  n>150")
            appendLine(row("Link, screen on", recent.filter { it.screenOn }.mapNotNull { it.linkMs }))
            appendLine(row("Link, screen off", recent.filter { !it.screenOn }.mapNotNull { it.linkMs }))
            appendLine(row("Internet, on", recent.filter { it.screenOn }.mapNotNull { it.internetMs }))
            appendLine(row("Internet, off", recent.filter { !it.screenOn }.mapNotNull { it.internetMs }))
            appendLine(row("Internet, idle", recent.filter { it.loadKbps < IDLE_KBPS }.mapNotNull { it.internetMs }))
            appendLine(row("Internet, loaded", recent.filter { it.loadKbps >= IDLE_KBPS }.mapNotNull { it.internetMs }))
            appendLine(row("Internet, 1.1.1.1", recent.mapNotNull { it.internet2Ms }))
            appendLine(row("Tunnel via relay", RelayRuntime.stats.rtt.since(cutoff)))
            append("Relay forwarding: slowest ${"%.1f".format(Locale.US, RelayRuntime.stats.maxForwardUs.get() / 1000.0)} ms")
            append("\n(spikes are readings of 150 ms or more)")
        }
    }

    private fun updateBatteryUi() {
        val ok = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryNote.text = if (ok) "Battery: unrestricted, so Android will not throttle the relay in the background."
        else "Battery: restricted. Android may delay the relay's network access when the screen is off."
        batteryButton.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private fun requestBatteryExemption() {
        startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
    }

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
        /** Below this much relayed traffic the link counts as idle. */
        private const val IDLE_KBPS = 200.0
        private const val PREF_SSID = "link_ssid"
        private const val PREF_PASS = "link_pass"
        private const val PREF_AUTO = "server_auto"
        private const val PREF_RELAY = "relay_host"
    }
}
