package app.phonehop

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Creates a Wi-Fi Direct group with a custom SSID/passphrase so that a legacy Wi-Fi client
 * (such as the router in repeater mode) can join it. The group owner is always 192.168.49.1.
 * Callers must already hold NEARBY_WIFI_DEVICES (API 33+) or ACCESS_FINE_LOCATION (<33).
 */
@SuppressLint("MissingPermission")
class WifiDirectController(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onGroup: (WifiP2pGroup?) -> Unit,
) {
    enum class Band(val label: String, val value: Int) {
        AUTO("Auto", WifiP2pConfig.GROUP_OWNER_BAND_AUTO),
        GHZ_2_4("2.4 GHz", WifiP2pConfig.GROUP_OWNER_BAND_2GHZ),
        GHZ_5("5 GHz", WifiP2pConfig.GROUP_OWNER_BAND_5GHZ),
    }

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    log("Wi-Fi Direct is ${if (on) "enabled" else "DISABLED (turn Wi-Fi on)"}")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
            }
        }
    }

    fun start() {
        if (channel == null) {
            channel = manager.initialize(context, Looper.getMainLooper()) {
                log("Wi-Fi Direct channel lost")
                channel = null
                onGroup(null)
            }
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
        }
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    /** Builds the full SSID. Android requires "DIRECT-" followed by two characters and a dash. */
    fun fullSsid(suffix: String) = "DIRECT-ph-$suffix"

    private data class Wanted(val ssid: String, val passphrase: String, val band: Band)

    private val handler = Handler(Looper.getMainLooper())
    private var wanted: Wanted? = null
    private var creatingUntil = 0L
    private var retries = 0
    private var recreatePending = false

    /**
     * Makes the real Wi-Fi Direct group match this config. If a group with the same name and
     * password is already up (and was requested on the same band) nothing happens; otherwise the
     * old group is replaced. Returns a reason if the config is not usable yet, else null.
     */
    fun ensureGroup(suffix: String, passphrase: String, band: Band): String? {
        if (suffix.isBlank()) return "Enter a network name"
        if (suffix.length > 20) return "Network name is too long (max 20 characters)"
        if (passphrase.length !in 8..63) return "Password must be 8 to 63 characters"
        val w = Wanted(fullSsid(suffix.trim()), passphrase, band)
        wanted = w
        retries = 0
        val ch = channel ?: return "Wi-Fi Direct is not ready yet"
        manager.requestGroupInfo(ch) { group ->
            val sameBand = lastBand == null || lastBand == w.band
            if (group != null && group.networkName == w.ssid && group.passphrase == w.passphrase && sameBand) {
                onGroup(group) // already what we want
            } else {
                create(ch, w)
            }
        }
        return null
    }

    private var lastBand: Band? = null

    private fun create(ch: WifiP2pManager.Channel, w: Wanted) {
        creatingUntil = System.currentTimeMillis() + 10_000
        lastBand = w.band
        // A leftover group blocks creation, so clear it first.
        manager.requestGroupInfo(ch) { existing ->
            if (existing != null) {
                log("Replacing group ${existing.networkName}")
                manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = doCreate(ch, w.ssid, w.passphrase, w.band)
                    override fun onFailure(reason: Int) {
                        log("removeGroup failed: ${reasonText(reason)}")
                        doCreate(ch, w.ssid, w.passphrase, w.band)
                    }
                })
            } else {
                doCreate(ch, w.ssid, w.passphrase, w.band)
            }
        }
    }

    private fun doCreate(ch: WifiP2pManager.Channel, ssid: String, passphrase: String, band: Band, mayFallBack: Boolean = true) {
        val config = try {
            WifiP2pConfig.Builder()
                .setNetworkName(ssid)
                .setPassphrase(passphrase)
                .enablePersistentMode(false)
                .setGroupOperatingBand(band.value)
                .build()
        } catch (e: IllegalArgumentException) {
            log("Invalid config: ${e.message}")
            return
        }
        log("Creating group $ssid (${band.label})...")
        manager.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Group requested; waiting for it to come up")
                handler.postDelayed({ refresh() }, 1_000)
            }
            override fun onFailure(reason: Int) {
                if (band == Band.GHZ_5 && mayFallBack) {
                    log("5 GHz group failed (${reasonText(reason)}); retrying on 2.4 GHz")
                    doCreate(ch, ssid, passphrase, Band.GHZ_2_4, mayFallBack = false)
                } else {
                    log("Could not create the group: ${reasonText(reason)}")
                    scheduleRecreate()
                }
            }
        })
    }

    /** If the group vanished (Wi-Fi toggled, system cleanup) bring it back, a few times at most. */
    private fun scheduleRecreate() {
        val w = wanted ?: return
        if (recreatePending || retries >= 5) return
        recreatePending = true
        retries++
        handler.postDelayed({
            recreatePending = false
            val ch = channel
            if (wanted == w && ch != null) {
                log("Recreating the group (attempt $retries)")
                create(ch, w)
            }
        }, 3_000L * retries)
    }

    /** Tears the group down for good (used when quitting); [onDone] runs once the system answers. */
    fun removeGroup(onDone: (() -> Unit)? = null) {
        wanted = null // do not bring it back
        val ch = channel
        if (ch == null) { onDone?.invoke(); return }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done() { if (finished.compareAndSet(false, true)) onDone?.invoke() }
        handler.postDelayed({ done() }, 2_000)
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("Group removed"); onGroup(null); done() }
            override fun onFailure(reason: Int) {
                // Failing because there is no group is fine when we are just cleaning up.
                done()
            }
        })
    }

    fun refresh() {
        val ch = channel ?: return
        manager.requestGroupInfo(ch) { group ->
            onGroup(group)
            if (group != null) {
                retries = 0
            } else if (wanted != null && System.currentTimeMillis() > creatingUntil) {
                scheduleRecreate()
            }
        }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "busy (try again in a moment)"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $r"
    }
}
