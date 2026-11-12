package app.phonehop

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Test harness for the two risky pieces: Wi-Fi Direct group creation and the Mullvad API. */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("phonehop", MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var logView: TextView
    private lateinit var groupStatus: TextView
    private lateinit var suffixField: EditText
    private lateinit var passField: EditText
    private lateinit var bandGroup: RadioGroup
    private lateinit var accountField: EditText
    private lateinit var countrySpinner: Spinner
    private lateinit var serverSpinner: Spinner
    private lateinit var selectedView: TextView
    private lateinit var p2p: WifiDirectController
    private lateinit var updateStatus: TextView
    private val updater by lazy { Updater(this) }

    private var relayList: MullvadApi.RelayList? = null
    private var countryRelays: List<MullvadApi.Relay> = emptyList()
    private var selectedRelay: MullvadApi.Relay? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p2p = WifiDirectController(this, ::log, ::showGroup)
        setContentView(buildUi())
        log("PhoneHop ${BuildConfigInfo.versionName(this)} on Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        Updater.statusListener = { msg -> setUpdateStatus(msg) }
        // Only on a fresh launch, not on rotation, so we don't re-check repeatedly.
        if (savedInstanceState == null && prefs.getBoolean("auto_update", true)) checkForUpdates(auto = true)
    }

    override fun onDestroy() {
        Updater.statusListener = null
        super.onDestroy()
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

    override fun onStart() {
        super.onStart()
        if (hasP2pPermission()) { p2p.start(); p2p.refresh() }
    }

    override fun onStop() {
        // Receiver only; the group itself stays alive as long as the process does.
        p2p.stop()
        super.onStop()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        root.addView(header("Updates"))
        updateStatus = TextView(this).apply {
            textSize = 12f
            text = "Version ${updater.currentVersion()}"
        }
        root.addView(updateStatus)
        root.addView(Switch(this).apply {
            text = "Check and install updates on launch"
            isChecked = prefs.getBoolean("auto_update", true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto_update", on).apply() }
        })
        root.addView(button("Check for updates now") { checkForUpdates(auto = false) })

        root.addView(header("1. Wi-Fi Direct group"))
        root.addView(note("The router will join this as a normal Wi-Fi network. SSID is always DIRECT-ph-<name>."))
        suffixField = EditText(this).apply { hint = "Name (after DIRECT-ph-)"; setText("hop"); setSingleLine() }
        passField = EditText(this).apply {
            hint = "Passphrase (8-63 chars)"
            setText("phonehop-" + (1000..9999).random())
            setSingleLine()
        }
        root.addView(suffixField)
        root.addView(passField)
        bandGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        WifiDirectController.Band.values().forEachIndexed { i, b ->
            bandGroup.addView(RadioButton(this).apply { text = b.label; id = 100 + i })
        }
        bandGroup.check(100 + 1) // default 2.4 GHz: most compatible with a router's STA radio
        root.addView(bandGroup)
        root.addView(row(
            button("Create group") { withP2pPermission { createGroup() } },
            button("Remove group") { p2p.removeGroup() },
        ))
        root.addView(Switch(this).apply {
            text = "Keep alive (foreground service)"
            setOnCheckedChangeListener { _, on ->
                if (on) requestNotificationsThen { KeepAliveService.start(this@MainActivity); log("Keep-alive service started") }
                else { KeepAliveService.stop(this@MainActivity); log("Keep-alive service stopped") }
            }
        })
        groupStatus = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; text = "No group."
            setTextIsSelectable(true); setPadding(0, dp(8), 0, 0)
        }
        root.addView(groupStatus)

        root.addView(header("2. Mullvad"))
        root.addView(note("Account number is used only for the API calls and is never stored or logged."))
        accountField = EditText(this).apply {
            hint = "Mullvad account number"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(accountField)
        root.addView(row(
            button("Fetch servers") { fetchServers() },
            button("Check account") { withToken { t -> log("Account expires: ${MullvadApi.accountExpiry(t)}") } },
        ))
        countrySpinner = Spinner(this)
        serverSpinner = Spinner(this)
        root.addView(countrySpinner)
        root.addView(serverSpinner)
        selectedView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; text = "No server selected." }
        root.addView(selectedView)
        root.addView(row(
            button("Register key") { registerKey() },
            button("List devices") { listDevices() },
        ))
        root.addView(row(
            button("Remove my device") { removeMyDevice() },
            button("Copy router config") { copyRouterConfig() },
        ))

        root.addView(header("Log"))
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true)
        }
        root.addView(logView)
        root.addView(button("Clear log") { logView.text = "" })

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, dp(4))
    }

    private fun note(t: String) = TextView(this).apply { text = t; textSize = 12f }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun log(msg: String) = runOnUiThread {
        logView.append("${timeFmt.format(Date())}  $msg\n")
    }

    // ---------------------------------------------------------------- Wi-Fi Direct

    private fun hasP2pPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun withP2pPermission(action: () -> Unit) {
        if (hasP2pPermission()) { p2p.start(); action(); return }
        pendingAfterPermission = { p2p.start(); action() }
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        requestPermissions(arrayOf(perm), REQ_P2P)
    }

    private fun requestNotificationsThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) { action(); return }
        pendingAfterPermission = action
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (granted) action?.invoke()
        else log("Permission denied (${permissions.firstOrNull()}); that feature won't work")
    }

    private fun createGroup() {
        val band = WifiDirectController.Band.values()[bandGroup.checkedRadioButtonId - 100]
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

    // ---------------------------------------------------------------- Mullvad

    private fun fetchServers() = io.execute {
        try {
            log("Fetching relay list...")
            val list = MullvadApi.fetchRelays()
            relayList = list
            val countries = list.relays.map { it.country }.distinct()
            log("Got ${list.relays.size} active WireGuard relays in ${countries.size} countries; ports ${list.portRanges.joinToString { "${it.first}-${it.last}" }}")
            runOnUiThread { bindServerPickers(countries) }
        } catch (e: Exception) {
            log("Fetch failed: ${describe(e)}")
        }
    }

    private fun bindServerPickers(countries: List<String>) {
        countrySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, countries)
        countrySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val country = countries[pos]
                countryRelays = relayList?.relays.orEmpty().filter { it.country == country }
                serverSpinner.adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                    countryRelays.map { "${it.city} - ${it.hostname}" }
                )
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val r = countryRelays.getOrNull(pos) ?: return
                selectedRelay = r
                prefs.edit().putString("relay_host", r.hostname).apply()
                selectedView.text = "${r.hostname}\n${r.ipv4}:51820\n${r.publicKey}\n${if (r.owned) "owned" else "rented"} (${r.provider})"
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

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
        if (relay == null) { log("Fetch servers and pick one first"); return }
        val gw = relayList?.let { "10.64.0.1" } ?: "10.64.0.1"
        val cfg = """
            [Interface]
            PrivateKey = $priv
            Address = $ipv4, $ipv6
            DNS = $gw

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
        private const val REQ_P2P = 1
        private const val REQ_NOTIF = 2
    }
}

private object BuildConfigInfo {
    fun versionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
}
