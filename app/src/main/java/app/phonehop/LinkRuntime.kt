package app.phonehop

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion

/**
 * Makes the phone join the router's link network. A network suggestion lets Android join it automatically
 * whenever it is in range, with one approval, and without making it the phone's internet connection.
 */
object LinkRuntime {
    private var appContext: Context? = null
    private var suggestion: WifiNetworkSuggestion? = null
    private var lastKey: String? = null

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

    /** Returns a reason if the settings are not usable or Android refused, else null. Does nothing if nothing changed. */
    @Synchronized
    fun ensure(ssid: String, passphrase: String): String? {
        val ctx = appContext ?: return "Not ready yet"
        if (ssid.isBlank() || ssid.length > 32) return "Enter a link network name (1 to 32 characters)"
        if (passphrase.length !in 8..63) return "The link password must be 8 to 63 characters"
        val key = "$ssid\u0000$passphrase"
        if (key == lastKey) return null
        val wm = ctx.getSystemService(WifiManager::class.java)
        suggestion?.let { wm.removeNetworkSuggestions(listOf(it)) }
        val s = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .setIsHiddenSsid(true) // the router does not broadcast the link network's name
            .build()
        return when (val status = wm.addNetworkSuggestions(listOf(s))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                suggestion = s
                lastKey = key
                RelayRuntime.event("Link network \"$ssid\" suggested to Android")
                null
            }
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
                "Android will not let PhoneHop suggest Wi-Fi networks. Allow it in the notification or in Settings."
            else -> "Android refused the Wi-Fi suggestion (code $status)"
        }
    }

    /** Stops the phone from joining the link network (used when quitting). */
    @Synchronized
    fun remove() {
        val ctx = appContext ?: return
        suggestion?.let { runCatching { ctx.getSystemService(WifiManager::class.java).removeNetworkSuggestions(listOf(it)) } }
        suggestion = null
        lastKey = null
    }
}
