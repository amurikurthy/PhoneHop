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

    fun createGroup(suffix: String, passphrase: String, band: Band) {
        val ch = channel ?: run { log("Not initialised"); return }
        if (suffix.isBlank()) { log("Name suffix is empty"); return }
        if (passphrase.length !in 8..63) { log("Passphrase must be 8-63 characters"); return }
        val ssid = fullSsid(suffix)
        // A leftover group (from a previous run) blocks creation, so clear it first.
        manager.requestGroupInfo(ch) { existing ->
            if (existing != null) {
                log("Removing existing group ${existing.networkName} first")
                manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = doCreate(ch, ssid, passphrase, band)
                    override fun onFailure(reason: Int) {
                        log("removeGroup failed: ${reasonText(reason)}")
                        doCreate(ch, ssid, passphrase, band)
                    }
                })
            } else {
                doCreate(ch, ssid, passphrase, band)
            }
        }
    }

    private fun doCreate(ch: WifiP2pManager.Channel, ssid: String, passphrase: String, band: Band) {
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
                log("createGroup accepted; waiting for group info")
                refresh()
            }
            override fun onFailure(reason: Int) = log("createGroup failed: ${reasonText(reason)}")
        })
    }

    fun removeGroup() {
        val ch = channel ?: return
        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { log("Group removed"); onGroup(null) }
            override fun onFailure(reason: Int) = log("removeGroup failed: ${reasonText(reason)}")
        })
    }

    fun refresh() {
        val ch = channel ?: return
        manager.requestGroupInfo(ch) { group -> onGroup(group) }
    }

    private fun reasonText(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.BUSY -> "busy (try again in a moment)"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "code $r"
    }
}
