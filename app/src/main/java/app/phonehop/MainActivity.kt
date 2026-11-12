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
    private lateinit var p2p: WifiDirectController
    private lateinit var monitor: TrafficMonitor

    private lateinit var scroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var groupStatus: TextView
    private lateinit var suffixField: EditText
    private lateinit var passField: EditText
    private lateinit var accountField: EditText
    private lateinit var countrySpinner: Spinner
    private lateinit var serverSpinner: Spinner
    private lateinit var manualBox: View
    private lateinit var selectedView: TextView
    private lateinit var updateStatus: TextView
    private lateinit var wifiPanel: TrafficPanel
    private lateinit var cellPanel: TrafficPanel

    private var bandIndex = 2
    private var autoServer = true
    private var imeBottom = 0
    private var relayList: MullvadApi.RelayList? = null
    private var countryRelays: List<MullvadApi.Relay> = emptyList()
    private var selectedRelay: MullvadApi.Relay? = null
    private var pendingServerHost: String? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        p2p = WifiDirectController(this, ::log, ::showGroup)
        monitor = TrafficMonitor { s ->
            wifiPanel.update(s.wifiDown, s.wifiUp)
            cellPanel.update(s.cellDown, s.cellUp)
        }
        setContentView(buildUi())
        log("PhoneHop ${versionName()} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        Updater.statusListener = { msg -> setUpdateStatus(msg) }
        requestPermissionsThenStart()
        // Only on a fresh launch, not on rotation, so we don't re-check repeatedly.
        if (savedInstanceState == null && prefs.getBoolean("auto_update", true)) checkForUpdates(auto = true)
    }

    override fun onStart() {
        super.onStart()
        if (hasP2pPermission()) { p2p.start(); p2p.refresh() }
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
        if (hasP2pPermission()) { p2p.start(); p2p.refresh() }
        // Always on: this is what keeps the Wi-Fi Direct group alive with the screen off.
        KeepAliveService.start(this)
        fetchServers()
    }

    private fun withP2pPermission(action: () -> Unit) {
        if (hasP2pPermission()) { p2p.start(); action(); return }
        pendingAfterPermission = { p2p.start(); action() }
        requestPermissions(arrayOf(p2pPermission()), REQ_P2P)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        permissions.forEachIndexed { i, p ->
            if (grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED) log("Permission denied: ${p.substringAfterLast('.')}")
        }
        if (requestCode == REQ_ALL) { onPermissionsSettled(); return }
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) action?.invoke()
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
        suffixField = ui.field("hop", prefs.getString(PREF_SUFFIX, "hop") ?: "hop")
        ui.watch(suffixField) { prefs.edit().putString(PREF_SUFFIX, it).apply() }
        val savedPass = prefs.getString(PREF_PASS, null)
            ?: "phonehop-${(1000..9999).random()}".also { prefs.edit().putString(PREF_PASS, it).apply() }
        passField = ui.field("8 to 63 characters", savedPass)
        ui.watch(passField) { prefs.edit().putString(PREF_PASS, it).apply() }
        bandIndex = prefs.getInt(PREF_BAND, defaultBandIndex())
        groupStatus = ui.mono("No group.")
        content.addView(
            ui.card(
                "Wi-Fi Direct group",
                "The router joins this as a normal Wi-Fi network. It stays up while PhoneHop runs in the background.",
                ui.labeled("Network name", suffixField, "The SSID will be DIRECT-ph- followed by this name."),
                ui.labeled("Password", passField, "At least 8 characters."),
                ui.labeled(
                    "Band",
                    ui.segmented(WifiDirectController.Band.values().map { it.label }, bandIndex) {
                        bandIndex = it
                        prefs.edit().putInt(PREF_BAND, it).apply()
                    },
                    "5 GHz is fastest. If your phone can't host on 5 GHz it falls back to 2.4 GHz."
                ),
                ui.row(
                    ui.primary("Create group") { withP2pPermission { createGroup() } },
                    ui.danger("Remove group") { p2p.removeGroup() },
                ),
                groupStatus,
            ), ui.cardParams(20)
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
        accountField = ui.field("1234 5678 9012 3456", number = true)
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
                "Your account number is used only for API calls and is never stored or logged.",
                ui.labeled("Account number", accountField, "16 digits, from mullvad.net/account."),
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

    private fun log(msg: String) = runOnUiThread {
        logView.append("${timeFmt.format(Date())}  $msg\n")
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private fun createGroup() {
        val band = WifiDirectController.Band.values()[bandIndex]
        p2p.createGroup(suffixField.text.toString().trim(), passField.text.toString(), band)
    }

    private fun showGroup(group: WifiP2pGroup?) = runOnUiThread {
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
        log("Group up: ${group.networkName}, ${group.clientList.size} client(s)")
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
                selectedRelay = pick.relay
                prefs.edit().putString(PREF_RELAY, pick.relay.hostname).apply()
                log("Auto-selected ${pick.relay.hostname} (${pick.relay.city}, ${pick.relay.country}), about ${pick.km.roundToInt()} km away, from ${here.source}")
                runOnUiThread { showRelay(pick.relay, "Nearest server, ${pick.km.roundToInt()} km away (${here.source})") }
            }
        }
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
                selectedRelay = r
                prefs.edit().putString(PREF_RELAY, r.hostname).apply()
                showRelay(r, "Chosen manually")
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** In manual mode, show the previously chosen server again. */
    private fun restoreManualSelection() {
        val host = prefs.getString(PREF_RELAY, null) ?: run { selectedView.text = "Pick a country and server."; return }
        val relay = relayList?.relays?.firstOrNull { it.hostname == host } ?: return
        pendingServerHost = host
        val countries = relayList?.relays?.map { it.country }?.distinct().orEmpty()
        val idx = countries.indexOf(relay.country)
        if (idx >= 0) countrySpinner.setSelection(idx)
        selectedRelay = relay
        showRelay(relay, "Chosen manually")
    }

    // ---------------------------------------------------------------- Mullvad account

    /** Runs [block] with a fresh access token. The account number never leaves this call. */
    private fun withToken(block: (String) -> Unit) {
        val account = accountField.text.toString().filter { it.isDigit() }
        if (account.length != 16) { log("Enter your 16-digit account number"); return }
        io.execute {
            try {
                block(MullvadApi.login(account))
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
        private const val REQ_P2P = 2
        private const val PREF_SUFFIX = "p2p_suffix"
        private const val PREF_PASS = "p2p_pass"
        private const val PREF_BAND = "p2p_band"
        private const val PREF_AUTO = "server_auto"
        private const val PREF_RELAY = "relay_host"
    }
}
