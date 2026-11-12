package app.phonehop

/** The router's own address and subnet, chosen by the user, with rules that keep the network working. */
object LanPlan {
    data class Lan(val address: String, val prefix: Int)

    const val DEFAULT = "192.168.50.1/24"

    private fun toLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (part in p) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val n = part.toInt()
            if (n > 255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    private fun toIp(v: Long) = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"

    private fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    /** Accepts "192.168.50.1/24" or just "192.168.50.1" (meaning /24). */
    fun parse(text: String): Lan? {
        val t = text.trim()
        val ip = t.substringBefore('/')
        val prefix = if (t.contains('/')) t.substringAfter('/').toIntOrNull() ?: return null else 24
        if (toLong(ip) == null) return null
        return Lan(ip, prefix)
    }

    private fun overlaps(aIp: Long, aPrefix: Int, bIp: Long, bPrefix: Int): Boolean {
        val m = mask(minOf(aPrefix, bPrefix))
        return (aIp and m) == (bIp and m)
    }

    /** Returns a plain-language problem with the range, or null if it is fine. */
    fun validate(lan: Lan, phoneLink: Lan = Lan(Link.PHONE_IP, 24)): String? {
        val ip = toLong(lan.address) ?: return "That is not a valid IPv4 address."
        if (lan.prefix !in 16..24) return "Use a /16 to /24 network (for example 192.168.50.1/24)."
        val private = overlaps(ip, 32, 0x0A000000L, 8) || overlaps(ip, 32, 0xAC100000L, 12) || overlaps(ip, 32, 0xC0A80000L, 16)
        if (!private) return "Use a private range: 10.x.x.x, 172.16-31.x.x or 192.168.x.x."
        val link = toLong(phoneLink.address)
        if (link != null && overlaps(ip, lan.prefix, link, phoneLink.prefix)) {
            return "${phoneLink.address.substringBeforeLast('.')}.x is used for the phone's link to the router. Pick another range."
        }
        if (overlaps(ip, lan.prefix, 0x0A400000L, 10)) return "10.64.x.x to 10.127.x.x is used by Mullvad's tunnel. Pick another range."
        val net = ip and mask(lan.prefix)
        val broadcast = net or mask(lan.prefix).inv() and 0xFFFFFFFFL
        if (ip == net || ip == broadcast) return "That address is the network or broadcast address; use something like ${toIp(net + 1)}."
        return null
    }

    fun netmask(prefix: Int): String = toIp(mask(prefix))

    /** First and last address handed out by DHCP: .100 to .249 of the router's /24 (or the first /24 of a larger network). */
    fun dhcpRange(lan: Lan): Pair<String, String> {
        val base = (toLong(lan.address) ?: 0L) and mask(maxOf(lan.prefix, 24))
        return Pair(toIp(base + 100), toIp(base + 249))
    }

    const val DHCP_START = 100
    const val DHCP_LIMIT = 150
}
