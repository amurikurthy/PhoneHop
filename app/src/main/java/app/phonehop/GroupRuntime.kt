package app.phonehop

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Owns the Wi-Fi Direct group for the life of the process, like [RelayRuntime] owns the relay.
 * It used to belong to the screen: when Android dropped the activity, the group's connection to the
 * system could be released with it and the relay lost track of the group's interface. Now the
 * foreground service keeps both alive, and the screen only watches.
 */
object GroupRuntime {
    @Volatile var lastGroup: WifiP2pGroup? = null
        private set

    private var controller: WifiDirectController? = null
    private var listener: ((WifiP2pGroup?) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    /** Idempotent. Does nothing until the nearby-devices permission has been granted. */
    @Synchronized
    fun start(context: Context) {
        if (controller != null || !hasPermission(context)) return
        val app = context.applicationContext
        controller = WifiDirectController(app, { RelayRuntime.event(it) }, ::onGroup).also { it.start() }
        ensureFromSavedSettings(app)
    }

    /** Creates the group from the saved name, password and band, so it comes up even if the screen is never opened. */
    fun ensureFromSavedSettings(context: Context) {
        val p = context.getSharedPreferences("phonehop", Context.MODE_PRIVATE)
        val suffix = p.getString("p2p_suffix", null) ?: return
        val pass = p.getString("p2p_pass", null) ?: return
        val band = WifiDirectController.Band.values().getOrElse(p.getInt("p2p_band", 2)) { WifiDirectController.Band.GHZ_5 }
        ensure(suffix.trim(), pass, band)
    }

    /** Returns a reason if the settings are not usable yet, else null. */
    @Synchronized
    fun ensure(suffix: String, passphrase: String, band: WifiDirectController.Band): String? =
        controller?.ensureGroup(suffix, passphrase, band) ?: "Wi-Fi Direct is not ready yet"

    fun removeGroup(onDone: (() -> Unit)? = null) {
        val c = controller
        if (c == null) onDone?.invoke() else c.removeGroup(onDone)
    }

    fun refresh() { controller?.refresh() }

    @Synchronized
    fun stop() {
        controller?.stop()
        controller = null
    }

    /** The screen's view of the group; called on the main thread, and immediately with the current state. */
    fun setListener(l: ((WifiP2pGroup?) -> Unit)?) {
        listener = l
        l?.let { main.post { it(lastGroup) } }
    }

    private fun onGroup(group: WifiP2pGroup?) {
        lastGroup = group
        RelayRuntime.setGroupInterface(group?.`interface`)
        main.post { listener?.invoke(group) }
    }
}
