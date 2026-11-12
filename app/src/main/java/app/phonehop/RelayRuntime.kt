package app.phonehop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.Process
import java.net.InetSocketAddress
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Owns the single [Relay] while the keep-alive service runs: listens on the Wi-Fi Direct
 * group's address, and sends to the VPN server over whatever network the phone currently uses
 * for internet (usually cellular), following it as it changes.
 */
object RelayRuntime {
    const val PORT = 51820

    data class Event(val at: Long, val msg: String)

    val stats = RelayStats()
    val latency = LatencyLog()
    private val events = ArrayDeque<Event>()
    @Volatile var internetText: String = "no internet network"
    @Volatile var logSink: (String) -> Unit = {}

    @Volatile private var relay: Relay? = null
    @Volatile private var network: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var pendingUpstream: String? = null
    private var probe: LatencyProbe? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null
    @Volatile private var internetTarget: InetSocketAddress? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var connectivity: ConnectivityManager? = null

    @Synchronized
    fun start(context: Context) {
        if (relay != null) return
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        connectivity = cm
        val r = Relay(
            listenAddress = { groupAddress()?.first },
            listenPort = PORT,
            allowedSource = { a -> groupAddress()?.let { (base, prefix) -> Subnet.contains(base, prefix, a) } ?: false },
            openUpstream = { openUpstreamSocket() },
            stats = stats,
            log = { event(it) },
            threadSetup = { runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } },
        )
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                event("Internet network available: ${cm.getNetworkCapabilities(n)?.let { describe(it) } ?: "?"}")
                r.rebindUpstream() // the old socket belongs to the previous network
                resolveInternetTarget(n)
            }
            override fun onLost(n: Network) {
                if (network == n) { network = null; internetText = "no internet network"; event("Internet network lost") }
            }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (n == network) internetText = describe(c)
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        val wifiCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { event("Wi-Fi network available"); r.rebindListen() }
            override fun onLinkPropertiesChanged(n: Network, lp: android.net.LinkProperties) { r.rebindListen() }
            override fun onLost(n: Network) { event("Wi-Fi network lost") }
        }
        wifiCallback = wifiCb
        cm.registerNetworkCallback(
            android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCb
        )
        relay = r
        if (pendingUpstream == null) {
            pendingUpstream = app.getSharedPreferences("phonehop", Context.MODE_PRIVATE).getString("relay_ip", null)
        }
        pendingUpstream?.let { runCatching { r.setUpstream(it, PORT) } }
        r.start()
        appContext = app
        registerPowerEvents(app)
        val pm = app.getSystemService(PowerManager::class.java)
        probe = LatencyProbe(
            routerHost = { if (linkAddress() != null) Link.ROUTER_IP else null },
            internetNetwork = { network },
            internetTarget = { internetTarget },
            screenOn = { pm.isInteractive },
            log = latency,
        ).also { it.start() }
        event("Relay started")
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        wifiCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        wifiCallback = null
        callback = null
        probe?.stop(); probe = null
        powerReceiver?.let { r -> runCatching { appContext?.unregisterReceiver(r) } }
        powerReceiver = null
        relay?.stop()
        relay = null
        network = null
    }

    // ---------------------------------------------------------------- events and diagnostics

    /** Logs a notable moment (screen, Doze, network, relay recovery) with the relay's state, so spikes can be lined up with causes. */
    fun event(msg: String) {
        synchronized(events) {
            if (events.size >= 200) events.removeFirst()
            events.addLast(Event(System.currentTimeMillis(), msg))
        }
        logSink(msg)
    }

    fun recentEvents(): List<Event> = synchronized(events) { events.toList() }

    /** One line describing what the relay is doing right now. */
    fun snapshotText(): String {
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t == 0L) "never" else "${(now - t) / 1000}s ago"
        return "relay up ${stats.fromRouter.get() / 1024} KB / down ${stats.toRouter.get() / 1024} KB, " +
            "router ${ago(stats.lastFromRouterAt)}, server ${ago(stats.lastFromUpstreamAt)}, $internetText"
    }

    private fun registerPowerEvents(app: Context) {
        val pm = app.getSystemService(PowerManager::class.java)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val what = when (i.action) {
                    Intent.ACTION_SCREEN_ON -> "Screen on"
                    Intent.ACTION_SCREEN_OFF -> "Screen off"
                    Intent.ACTION_USER_PRESENT -> "Unlocked"
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "Doze ${if (pm.isDeviceIdleMode) "ON" else "off"}"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "Battery saver ${if (pm.isPowerSaveMode) "ON" else "off"}"
                    else -> return
                }
                event("$what | ${snapshotText()}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        powerReceiver = receiver
    }

    /** Looks up a Mullvad web server over the internet network, to measure the internet side of the path. */
    private fun resolveInternetTarget(n: Network) {
        Thread {
            internetTarget = runCatching { InetSocketAddress(n.getByName("api.mullvad.net"), 443) }.getOrNull()
        }.apply { isDaemon = true; start() }
    }

    /** Points the relay at a VPN server (an IPv4 address). Remembered if the relay is not running yet. */
    fun setUpstream(ip: String) {
        pendingUpstream = ip
        runCatching { relay?.setUpstream(ip, PORT) }
    }

    val isRunning get() = relay?.isRunning == true

    /** The phone's own address on the router's link network, or null while it has not joined. */
    fun linkAddress(): InetAddress? = runCatching {
        val base = InetAddress.getByName(Link.SUBNET)
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.interfaceAddresses }.map { it.address }
            .firstOrNull { it is Inet4Address && Subnet.contains(base, Link.PREFIX, it) }
    }.getOrNull()

    private fun openUpstreamSocket(): DatagramSocket {
        val n = network ?: throw IOException("no internet network")
        return DatagramSocket().also { n.bindSocket(it) }
    }

    private fun groupAddress(): Pair<InetAddress, Int>? = linkAddress()?.let { it to Link.PREFIX }

    private fun describe(c: NetworkCapabilities): String {
        val kind = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        val ok = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "online" else "not validated"
        return "$kind ($ok)"
    }
}
