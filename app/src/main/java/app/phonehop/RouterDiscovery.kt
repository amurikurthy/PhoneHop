package app.phonehop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Finds where the router is. A phone that has joined the router's Wi-Fi (or Ethernet) is told the
 * router's address by Android as its default gateway, so no scanning is needed. Once the router is
 * on the Wi-Fi Direct link, the relay learns its address from its WireGuard packets.
 */
object RouterDiscovery {
    data class Candidate(val host: String, val network: Network?, val source: String)

    fun candidates(context: Context): List<Candidate> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val found = mutableListOf<Candidate>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val kind = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi" else "Ethernet"
            lp.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway as? Inet4Address }.forEach {
                found += Candidate(it.hostAddress ?: return@forEach, n, "the gateway of your $kind connection")
            }
        }
        RelayRuntime.stats.routerAddress?.address?.hostAddress?.let {
            found += Candidate(it, null, "the router link")
        }
        return found.distinctBy { it.host }
    }

    /** The network whose local subnet contains [host], so sockets can be bound to it. Null means use normal routing. */
    fun networkFor(context: Context, host: String): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                cm.getLinkProperties(n)?.routes?.any { !it.isDefaultRoute && it.matches(addr) } == true
        }
    }

    /** Opens plain TCP connections, on [network] when given (a LAN without internet is not Android's default network). */
    fun opener(network: Network?): (String, Int) -> Socket = { host, port ->
        (network?.socketFactory ?: SocketFactory.getDefault()).createSocket().apply {
            soTimeout = 2_500
            connect(InetSocketAddress(host, port), 2_000)
        }
    }
}
