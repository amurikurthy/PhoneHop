package app.phonehop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
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

    val stats = RelayStats()
    @Volatile var internetText: String = "no internet network"
    @Volatile var logSink: (String) -> Unit = {}

    @Volatile private var relay: Relay? = null
    @Volatile private var network: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var cellular: Network? = null
    @Volatile private var pendingUpstream: String? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null
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
            }
            override fun onLost(n: Network) {
                if (cellular == n) { cellular = null; event("Cellular lost"); r.rebindUpstream() }
            }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (n == cellular) internetText = describe(c)
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
            override fun onAvailable(n: Network) { event("Wi-Fi network available"); rebindListenIfLinkChanged(r) }
            override fun onLinkPropertiesChanged(n: Network, lp: android.net.LinkProperties) { rebindListenIfLinkChanged(r) }
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
        startKeepWarm()
        registerPowerEvents(app)
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
        powerReceiver?.let { r -> runCatching { appContext?.unregisterReceiver(r) } }
        powerReceiver = null
        keepWarm = false
        relay?.stop()
        relay = null
        network = null
    }

    // ---------------------------------------------------------------- events

    /** Logs a notable moment (link joined, network change, relay recovery) to the app's log. */
    fun event(msg: String) = logSink(msg)

    /** Warms both radios when the screen turns on or off or the phone is unlocked (Android re-tunes them then). */
    private fun registerPowerEvents(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = warmBurst()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        powerReceiver = receiver
    }

    /** Points the relay at a VPN server (an IPv4 address). Remembered if the relay is not running yet. */
    fun setUpstream(ip: String) {
        pendingUpstream = ip
        runCatching { relay?.setUpstream(ip, PORT) }
    }

    val isRunning get() = relay?.isRunning == true

    private var listenNetwork: Network? = null
    private var listenAddress: String? = null

    /**
     * Re-opens the listener only when the router link really changed (a different network or address).
     * Any other Wi-Fi event, such as a different Wi-Fi network or a routine property update, used to re-open it
     * too, which dropped every packet that arrived in the gap.
     */
    @Synchronized
    private fun rebindListenIfLinkChanged(r: Relay) {
        val net = linkNetwork()
        val addr = linkAddress()?.hostAddress
        if (net == listenNetwork && addr == listenAddress) return
        listenNetwork = net; listenAddress = addr
        r.rebindListen()
    }

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

    // ---------------------------------------------------------------- keep the radios awake

    @Volatile private var keepWarm = false
    private var warmThread: Thread? = null

    /**
     * Keeps both radios out of their low-power states, where every packet waits for the radio to wake: a one-byte packet
     * to the router every 150 ms (Wi-Fi) and one to the VPN server about every 450 ms (cellular). A packet is skipped
     * whenever relayed traffic has just kept that radio busy anyway, so the cost is only paid while the link is quiet.
     */
    @Synchronized
    private fun startKeepWarm() {
        keepWarm = true
        if (warmThread != null) return
        warmThread = Thread({
            var socket: DatagramSocket? = null
            val target = InetAddress.getByName(Link.ROUTER_IP)
            var tick = 0
            while (keepWarm) {
                val sinceTraffic = System.currentTimeMillis() - maxOf(stats.lastFromRouterAt, stats.lastFromUpstreamAt)
                if (tick++ % 3 == 0 && sinceTraffic > 300) relay?.poke()
                if (sinceTraffic > 100) {
                    try {
                        if (socket == null) {
                            val n = linkNetwork()
                            if (n != null) socket = DatagramSocket().also { n.bindSocket(it) }
                        }
                        socket?.send(java.net.DatagramPacket(ByteArray(1), 1, target, 9))
                    } catch (e: Exception) {
                        runCatching { socket?.close() }; socket = null
                    }
                }
                try { Thread.sleep(150) } catch (e: InterruptedException) { break }
            }
            runCatching { socket?.close() }
            synchronized(this) { warmThread = null }
        }, "link-warm").apply { isDaemon = true; start() }
    }

    /**
     * A short rush of tiny packets to the router and to the VPN server. Android re-tunes the Wi-Fi and cellular radios
     * when the screen turns on or off, and a packet caught in that moment can wait several hundred milliseconds; this wakes
     * both radios first.
     */
    private fun warmBurst() {
        if (!keepWarm) return
        Thread({
            var socket: DatagramSocket? = null
            try {
                linkNetwork()?.let { n -> socket = DatagramSocket().also { n.bindSocket(it) } }
                val target = InetAddress.getByName(Link.ROUTER_IP)
                repeat(10) {
                    relay?.poke()
                    socket?.send(java.net.DatagramPacket(ByteArray(1), 1, target, 9))
                    Thread.sleep(30)
                }
            } catch (e: Exception) {
                // best effort
            } finally {
                runCatching { socket?.close() }
            }
        }, "warm-burst").apply { isDaemon = true; start() }
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
