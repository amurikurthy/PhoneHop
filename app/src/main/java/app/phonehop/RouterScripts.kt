package app.phonehop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the router needs to be configured. */
data class RouterPlan(
    /** Name of the hidden link network the router hosts for the phone. */
    val phoneSsid: String,
    val phonePassphrase: String,
    val prefer5Ghz: Boolean,
    val wgPrivateKey: String,
    /** Tunnel addresses from Mullvad, e.g. 10.66.1.2/32. */
    val wgAddressV4: String,
    val wgAddressV6: String = "",
    val serverPublicKey: String,
    /** Where the router sends WireGuard packets: the phone's address on the Wi-Fi Direct link. */
    val relayHost: String = Link.PHONE_IP,
    val relayPort: Int = 51820,
    val dns: String = "10.64.0.1",
    /**
     * Base name of the Wi-Fi your own devices join; the router makes two networks from it,
     * "<name>_5G" and "<name>_2.4G". Required: a wipe resets Wi-Fi to the firmware default.
     */
    val clientSsid: String,
    val clientPassphrase: String,
    /** The router's new admin (root) password, set during setup. The phone keeps it, encrypted, to manage the router. */
    val adminPassword: String,
    /** The router's own address and subnet; devices behind it get addresses in this range. */
    val lanAddress: String = "192.168.50.1",
    val lanPrefix: Int = 24,
    /** Two-letter Wi-Fi regulatory country, e.g. US. Without it radios may be limited to a few channels. */
    val country: String = "",
    val nowMillis: Long = System.currentTimeMillis(),
    val rollbackSeconds: Int = 600,
)

/** Builds the shell scripts the app runs on the router over SSH, from the templates in resources/router. */
object RouterScripts {
    /** 32 characters in an SSID, minus room for the longest suffix, "_2.4G". */
    const val MAX_WIFI_BASE_NAME = 27

    fun wifiNames(base: String) = Pair("${base}_5G", "${base}_2.4G")

    private val BASE64_KEY = Regex("^[A-Za-z0-9+/]{43}=$")
    private val V4_CIDR = Regex("^\\d{1,3}(\\.\\d{1,3}){3}/\\d{1,2}$")
    private val V6_CIDR = Regex("^[0-9a-fA-F:]+/\\d{1,3}$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Quotes a value for POSIX sh. */
    fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Returns a human-readable problem with the plan, or null if it is fine. */
    fun validate(p: RouterPlan): String? {
        fun bad(s: String) = s.any { it.code < 32 || it.code == 127 }
        if (p.phoneSsid.isBlank() || p.phoneSsid.length > 32 || bad(p.phoneSsid)) return "Wi-Fi Direct network name is not valid"
        if (p.phonePassphrase.length !in 8..63 || bad(p.phonePassphrase)) return "Wi-Fi Direct password must be 8 to 63 characters"
        if (!BASE64_KEY.matches(p.wgPrivateKey)) return "WireGuard private key is not valid; register a key first"
        if (!BASE64_KEY.matches(p.serverPublicKey)) return "Server public key is not valid"
        if (!V4_CIDR.matches(p.wgAddressV4)) return "Tunnel IPv4 address is not valid; register a key first"
        if (p.wgAddressV6.isNotEmpty() && !V6_CIDR.matches(p.wgAddressV6)) return "Tunnel IPv6 address is not valid"
        if (!IPV4.matches(p.relayHost) || !IPV4.matches(p.dns)) return "Relay or DNS address is not valid"
        if (p.relayPort !in 1..65535) return "Relay port is not valid"
        if (p.clientSsid.isBlank() || p.clientSsid.length > MAX_WIFI_BASE_NAME || bad(p.clientSsid)) {
            return "Enter a name for your Wi-Fi (1 to $MAX_WIFI_BASE_NAME characters; _5G or _2.4G is added to it)"
        }
        if (p.clientPassphrase.length !in 8..63 || bad(p.clientPassphrase)) return "Your Wi-Fi password must be 8 to 63 characters"
        if (p.country.isNotEmpty() && !Regex("^[A-Z]{2}$").matches(p.country)) return "Country code must be two capital letters"
        LanPlan.validate(LanPlan.Lan(p.lanAddress, p.lanPrefix), LanPlan.Lan(p.relayHost, 24))?.let { return it }
        if (p.relayHost != Link.PHONE_IP) return "The phone's link address must be ${Link.PHONE_IP}"
        if (p.adminPassword.length !in 8..63 || bad(p.adminPassword)) return "The router admin password must be 8 to 63 characters"
        if (p.rollbackSeconds !in 60..86_400) return "Rollback time is not valid"
        return null
    }

    fun setup(p: RouterPlan): String {
        validate(p)?.let { throw IllegalArgumentException(it) }
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(p.nowMillis))
        val values = mapOf(
            "SSID" to shq(p.phoneSsid),
            "PSK" to shq(p.phonePassphrase),
            "PREFER_5G" to if (p.prefer5Ghz) "1" else "0",
            "WG_PRIVATE_KEY" to shq(p.wgPrivateKey),
            "WG_ADDRESS_V4" to shq(p.wgAddressV4),
            "WG_ADDRESS_V6" to shq(p.wgAddressV6),
            "SERVER_PUBLIC_KEY" to shq(p.serverPublicKey),
            "RELAY_HOST" to shq(p.relayHost),
            "RELAY_PORT" to p.relayPort.toString(),
            "PHONE_ADDRESS" to shq(p.relayHost),
            "DNS" to shq(p.dns),
            "CLIENT_SSID" to shq(p.clientSsid),
            "CLIENT_PSK" to shq(p.clientPassphrase),
            "COUNTRY" to shq(p.country),
            "ADMIN_PW" to shq(p.adminPassword),
            "LAN_IP" to shq(p.lanAddress),
            "LAN_MASK" to shq(LanPlan.netmask(p.lanPrefix)),
            "DHCP_START" to LanPlan.DHCP_START.toString(),
            "DHCP_LIMIT" to LanPlan.DHCP_LIMIT.toString(),
            "NOW" to shq(clock),
            "ROLLBACK_SECONDS" to p.rollbackSeconds.toString(),
        )
        var script = load("setup.sh")
        for ((k, v) in values) script = script.replace("@@$k@@", v)
        // The revert script goes in last, verbatim, so values above can never alter it.
        script = script.replace("@@REVERT_BODY@@", load("revert.sh").trimEnd())
        check(!Regex("@@[A-Z_0-9]+@@").containsMatchIn(script)) { "unfilled placeholder in setup script" }
        return script
    }

    fun revert(): String = load("revert.sh")
    fun identity(): String = load("identity.sh")

    /** Script that points the existing tunnel at another server. */
    fun server(serverPublicKey: String): String {
        require(BASE64_KEY.matches(serverPublicKey)) { "Server public key is not valid" }
        return load("server.sh").replace("@@SERVER_PUBLIC_KEY@@", shq(serverPublicKey))
    }
    fun status(): String = load("status.sh")
    fun confirm(): String = load("confirm.sh")

    /** Lines of the form PHONEHOP_NAME=value in a script's output. */
    fun parseMarkers(output: String): Map<String, String> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("PHONEHOP_") && it.contains('=') }
            .associate { it.substringBefore('=').removePrefix("PHONEHOP_") to it.substringAfter('=') }

    /** Seconds since the tunnel's last handshake, per the router's own clock; null if there has never been one. */
    fun handshakeAgeSeconds(markers: Map<String, String>): Long? {
        val now = markers["NOW"]?.toLongOrNull() ?: return null
        val hs = markers["HANDSHAKE"]?.toLongOrNull() ?: return null
        return if (hs <= 0L) null else maxOf(0L, now - hs)
    }

    private fun load(name: String): String {
        val stream = RouterScripts::class.java.classLoader?.getResourceAsStream("router/$name")
            ?: throw IllegalStateException("missing resource router/$name")
        return stream.bufferedReader().use { it.readText() }
    }
}
