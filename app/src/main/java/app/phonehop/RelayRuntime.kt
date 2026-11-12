package app.phonehop

import android.content.Context
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
    private const val FALLBACK_GO = "192.168.49.1"

    val stats = RelayStats()
    @Volatile var internetText: String = "no internet network"
    @Volatile var logSink: (String) -> Unit = {}

    @Volatile private var relay: Relay? = null
    @Volatile private var network: Network? = null
    @Volatile private var groupInterface: String? = null
    @Volatile private var pendingUpstream: String? = null
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
            log = { logSink(it) },
        )
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                r.rebindUpstream() // the old socket belongs to the previous network
            }
            override fun onLost(n: Network) {
                if (network == n) { network = null; internetText = "no internet network" }
            }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (n == network) internetText = describe(c)
            }
        }
        callback = cb
        cm.registerDefaultNetworkCallback(cb)
        relay = r
        if (pendingUpstream == null) {
            pendingUpstream = app.getSharedPreferences("phonehop", Context.MODE_PRIVATE).getString("relay_ip", null)
        }
        pendingUpstream?.let { runCatching { r.setUpstream(it, PORT) } }
        r.start()
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }
        callback = null
        relay?.stop()
        relay = null
        network = null
    }

    /** Points the relay at a VPN server (an IPv4 address). Remembered if the relay is not running yet. */
    fun setUpstream(ip: String) {
        pendingUpstream = ip
        runCatching { relay?.setUpstream(ip, PORT) }
    }

    /** Called when the Wi-Fi Direct group appears, changes or goes away. */
    fun setGroupInterface(name: String?) {
        if (name == groupInterface) return
        groupInterface = name
        relay?.rebindListen()
    }

    val isRunning get() = relay?.isRunning == true

    /** The phone's address on the Wi-Fi Direct group, read from the group's interface; null while no group is up. */
    fun groupOwnerAddress(): String? = groupAddress()?.first?.hostAddress

    private fun openUpstreamSocket(): DatagramSocket {
        val n = network ?: throw IOException("no internet network")
        return DatagramSocket().also { n.bindSocket(it) }
    }

    /** The group interface's IPv4 address and prefix, or the standard group-owner address as a fallback. */
    private fun groupAddress(): Pair<InetAddress, Int>? {
        val name = groupInterface ?: return null
        val found = runCatching {
            NetworkInterface.getByName(name)?.interfaceAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.let { it.address to it.networkPrefixLength.toInt() }
        }.getOrNull()
        return found ?: (InetAddress.getByName(FALLBACK_GO) to 24)
    }

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
