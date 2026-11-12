package app.phonehop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Handler
import android.os.Looper

/**
 * Joins the router's link network the way wireless Android Auto joins a car: with a network request, not a saved
 * network. The connection is private to this app and local-only, so Android never makes it the phone's internet
 * connection (no black-holing of other apps, no "no internet" prompt). We get the [Network] and bind sockets to it.
 */
object LinkRuntime {
    private var appContext: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastKey: String? = null
    @Volatile var network: Network? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var retryDelayMs = RETRY_FIRST_MS

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        ensureFromSavedSettings(context.applicationContext)
    }

    fun ensureFromSavedSettings(context: Context) {
        val p = context.getSharedPreferences("phonehop", Context.MODE_PRIVATE)
        val ssid = p.getString("link_ssid", null) ?: return
        val pass = p.getString("link_pass", null) ?: return
        ensure(ssid.trim(), pass)
    }

    /** Returns a reason if the settings are not usable, else null. Does nothing if the request is already active. */
    @Synchronized
    fun ensure(ssid: String, passphrase: String): String? {
        val ctx = appContext ?: return "Not ready yet"
        if (ssid.isBlank() || ssid.length > 32) return "Enter a link network name (1 to 32 characters)"
        if (passphrase.length !in 8..63) return "The link password must be 8 to 63 characters"
        val key = "$ssid\u0000$passphrase"
        if (key == lastKey && callback != null) return null
        release()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .setIsHiddenSsid(true) // the router does not broadcast the link network's name
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                retryDelayMs = RETRY_FIRST_MS
                RelayRuntime.event("Joined the link network \"$ssid\"")
                RelayRuntime.linkJoined()
            }
            override fun onLost(n: Network) {
                if (network == n) { network = null; RelayRuntime.event("Lost the link network") }
            }
            override fun onUnavailable() {
                // Android gave up on this request (router still starting, out of range, or a scan that missed). A request
                // never retries itself, so ask again after a pause, waiting longer each time.
                val wait = retryDelayMs
                retryDelayMs = minOf(wait * 2, RETRY_MAX_MS)
                RelayRuntime.event("Android could not join the link network; trying again in ${wait / 1000} s")
                handler.postDelayed({
                    synchronized(this@LinkRuntime) {
                        if (callback === this) { release(); ensure(ssid, passphrase) }
                    }
                }, wait)
            }
        }
        callback = cb
        lastKey = key
        return try {
            cm.requestNetwork(request, cb)
            RelayRuntime.event("Asking Android to join the link network \"$ssid\"")
            null
        } catch (e: SecurityException) {
            callback = null
            "Android refused the Wi-Fi request: ${e.message}"
        }
    }

    private const val RETRY_FIRST_MS = 5_000L
    private const val RETRY_MAX_MS = 30_000L

    /** Stops the link request (used when quitting). The phone leaves the link network. */
    @Synchronized
    fun remove() { handler.removeCallbacksAndMessages(null); release() }

    private fun release() {
        val ctx = appContext
        callback?.let { cb -> runCatching { ctx?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        callback = null
        network = null
        lastKey = null
    }
}
