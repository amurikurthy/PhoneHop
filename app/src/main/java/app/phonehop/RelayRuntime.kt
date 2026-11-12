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
 * Owns the single [Relay] while the keep-alive service runs: listens on the router link
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
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cellular: Network? = null
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
            prepareListenSocket = { s -> runCatching { linkNetwork()?.bindSocket(s) } },
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
                if (n == network && cellular == null) internetText = describe(c)
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        // The relay's egress must be cellular. Android can make the link Wi-Fi (which has no internet) the default
        // network, and traffic sent there goes nowhere, so ask for the cellular network explicitly.
        val cellCb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                cellular = n
                event("Cellular available; relay traffic uses it")
                r.rebindUpstream()
                resolveInternetTarget(n)
            }
            override fun onLost(n: Network) {
                if (cellular == n) { cellular = null; event("Cellular lost"); r.rebindUpstream() }
            }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (n == cellular) {
                    internetText = describe(c)
                    cellSignal = c.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                }
            }
        }
        cellularCallback = cellCb
        cm.requestNetwork(
            android.net.NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            cellCb
        )
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
        if (app.getSharedPreferences("phonehop", Context.MODE_PRIVATE).getBoolean("keep_warm", true)) setKeepWarm(true)
        registerPowerEvents(app)
        val pm = app.getSystemService(PowerManager::class.java)
        probe = LatencyProbe(
            routerHost = { if (linkAddress() != null) Link.ROUTER_IP else null },
            internetNetwork = { upstreamNetwork() },
            internetTarget = { internetTarget },
            screenOn = { pm.isInteractive },
            log = latency,
            linkSocket = { linkNetwork()?.socketFactory?.createSocket() ?: java.net.Socket() },
            loadKbps = ::currentLoadKbps,
            internet2Target = { InetSocketAddress("1.1.1.1", 443) },
            otherKbps = { lastOtherKbps },
            signalDbm = { cellSignal },
        ).also { it.start() }
        event("Relay started")
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        cellularCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        cellularCallback = null
        cellular = null
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
        val n = upstreamNetwork() ?: throw IOException("no internet network")
        return DatagramSocket().also { n.bindSocket(it) }
    }

    // ---------------------------------------------------------------- keep the link awake (test)

    @Volatile private var keepWarm = false
    private var warmThread: Thread? = null

    /**
     * Keeps the link awake: a one-byte packet to the router every 150 ms (so the phone's Wi-Fi never power-saves) and a
     * one-byte packet to the VPN server about every 450 ms (so the cellular radio stays awake). Costs battery and about
     * 150 MB of mobile data a month; for finding out whether idle radios are the latency problem.
     */
    @Synchronized
    fun setKeepWarm(on: Boolean) {
        keepWarm = on
        event("Keep link warm: ${if (on) "on" else "off"}")
        if (!on || warmThread != null) return
        warmThread = Thread({
            var socket: DatagramSocket? = null
            val target = InetAddress.getByName(Link.ROUTER_IP)
            var tick = 0
            while (keepWarm) {
                // Every 3rd tick (about 450 ms) also keep the cellular radio awake: with the screen off it sleeps within
                // a second or two, and each packet then waits about half a second for it to wake.
                if (tick++ % 3 == 0) relay?.poke()
                try {
                    if (socket == null) {
                        val n = linkNetwork()
                        if (n != null) socket = DatagramSocket().also { n.bindSocket(it) }
                    }
                    socket?.send(java.net.DatagramPacket(ByteArray(1), 1, target, 9))
                } catch (e: Exception) {
                    runCatching { socket?.close() }; socket = null
                }
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
            runCatching { socket?.close() }
            synchronized(this) { warmThread = null }
        }, "link-warm").apply { isDaemon = true; start() }
    }

    private var loadBytes = 0L
    private var loadAt = 0L
    private var mobileBytes = 0L
    @Volatile private var lastOtherKbps = 0.0
    @Volatile private var cellSignal: Int? = null

    /** Relayed kilobits per second since the previous call (both directions). */
    @Synchronized
    private fun currentLoadKbps(): Double {
        val now = System.currentTimeMillis()
        val bytes = stats.fromRouter.get() + stats.toRouter.get()
        val kbps = if (loadAt == 0L || now <= loadAt) 0.0 else (bytes - loadBytes) * 8.0 / (now - loadAt)
        // Everything else on the phone's cellular connection: its total minus what the relay carried (plus ~5% for headers).
        val mobile = android.net.TrafficStats.getMobileRxBytes().coerceAtLeast(0) + android.net.TrafficStats.getMobileTxBytes().coerceAtLeast(0)
        lastOtherKbps = if (loadAt == 0L || now <= loadAt || mobileBytes == 0L) 0.0
            else maxOf(0.0, (mobile - mobileBytes) * 8.0 / (now - loadAt) - kbps * 1.05)
        mobileBytes = mobile
        loadBytes = bytes; loadAt = now
        return kbps
    }

    /** The Wi-Fi network that carries the router link, so sockets to the router are routed over it. */
    fun linkNetwork(): Network? {
        LinkRuntime.network?.let { return it }
        val cm = connectivity ?: return null
        val base = runCatching { InetAddress.getByName(Link.SUBNET) }.getOrNull() ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getLinkProperties(n)?.linkAddresses?.any { it.address is Inet4Address && Subnet.contains(base, Link.PREFIX, it.address) } == true
        }
    }

    /** The network for everything that must reach the internet: cellular, else the default unless it is the link. */
    fun upstreamNetwork(): Network? = cellular ?: network?.takeIf { !isLinkNetwork(it) }

    /** Called when the link network has been joined: the listener must bind to it. */
    fun linkJoined() { relay?.rebindListen() }

    private fun isLinkNetwork(n: Network): Boolean {
        val base = runCatching { InetAddress.getByName(Link.SUBNET) }.getOrNull() ?: return false
        return connectivity?.getLinkProperties(n)?.linkAddresses
            ?.any { it.address is Inet4Address && Subnet.contains(base, Link.PREFIX, it.address) } == true
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
