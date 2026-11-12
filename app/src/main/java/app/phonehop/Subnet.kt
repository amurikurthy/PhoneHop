package app.phonehop

import java.net.InetAddress

/** IPv4 subnet membership, used to accept relay traffic only from the Wi-Fi Direct group. */
object Subnet {
    fun contains(base: InetAddress, prefixLength: Int, candidate: InetAddress): Boolean {
        val a = base.address
        val b = candidate.address
        if (a.size != 4 || b.size != 4 || prefixLength !in 0..32) return false
        var bits = prefixLength
        for (i in 0 until 4) {
            val take = minOf(8, bits)
            if (take > 0) {
                val mask = (0xFF shl (8 - take)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            }
            bits -= take
        }
        return true
    }
}
