package app.phonehop

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/** What the router reported about itself (see router/identity.sh). */
data class RouterIdentity(val model: String, val board: String, val firmware: String, val mac: String, val serial: String) {
    /** Serial number if the firmware exposes one, else the LAN MAC address. */
    val id: String? get() = serial.takeIf { it.isNotBlank() } ?: mac.lowercase().takeIf { it.isNotBlank() }

    companion object {
        fun fromMarkers(m: Map<String, String>) = RouterIdentity(
            model = m["MODEL"].orEmpty().trim(), board = m["BOARD"].orEmpty().trim(),
            firmware = m["FIRMWARE"].orEmpty().trim(), mac = m["MAC"].orEmpty().trim(), serial = m["SERIAL"].orEmpty().trim(),
        )
    }
}

/**
 * A router that has been set up for this phone, with the router link network it was given.
 * While a profile is active the group settings are locked, because the router has them saved.
 */
data class RouterProfile(
    val id: String,
    val model: String,
    val mac: String,
    val serial: String,
    val linkSsid: String,
    val linkPassphrase: String,
    val bandIndex: Int,
    val clientSsid: String,
    val createdAt: Long,
    /** The link network settings were changed after setup, so the router needs updating. */
    val stale: Boolean = false,
    /** The VPN server the router's tunnel is pointed at. Auto-selection never changes it. */
    val serverHost: String = "",
    val serverIp: String = "",
    val serverKey: String = "",
    /** The router's own address and prefix, e.g. 192.168.50.1/24. */
    val lanCidr: String = "",
) {
    val title: String get() = model.ifBlank { "Router" }
    val shortId: String get() = if (serial.isNotBlank()) serial else mac
}

class ProfileStore(private val kv: KeyValueStore) {
    fun all(): List<RouterProfile> = kv.keys(PREFIX).mapNotNull { decode(kv.get(it)) }.sortedBy { it.createdAt }
    fun get(id: String): RouterProfile? = decode(kv.get(PREFIX + id))
    fun save(p: RouterProfile) { kv.put(PREFIX + p.id, encode(p)) }

    fun delete(id: String) {
        kv.remove(PREFIX + id)
        if (activeId() == id) kv.remove(ACTIVE)
    }

    fun activeId(): String? = kv.get(ACTIVE)
    fun active(): RouterProfile? = activeId()?.let { get(it) }

    fun setActive(id: String?) {
        if (id == null) kv.remove(ACTIVE) else kv.put(ACTIVE, id)
    }

    private fun encode(p: RouterProfile): String = listOf(
        "id" to p.id, "model" to p.model, "mac" to p.mac, "serial" to p.serial,
        "suffix" to p.linkSsid, "pass" to p.linkPassphrase, "band" to p.bandIndex.toString(),
        "client" to p.clientSsid, "created" to p.createdAt.toString(), "stale" to p.stale.toString(), "lm" to "client",
        "shost" to p.serverHost, "sip" to p.serverIp, "skey" to p.serverKey, "lan" to p.lanCidr,
    ).joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    private fun decode(text: String?): RouterProfile? {
        if (text == null) return null
        val m = text.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        if (m["lm"] != "client") return null // set up the old router link way: it has to be set up again
        return try {
            RouterProfile(
                id = m.getValue("id"), model = m["model"].orEmpty(), mac = m["mac"].orEmpty(), serial = m["serial"].orEmpty(),
                linkSsid = m.getValue("suffix"), linkPassphrase = m.getValue("pass"),
                bandIndex = m["band"]?.toIntOrNull() ?: 2, clientSsid = m["client"].orEmpty(),
                createdAt = m["created"]?.toLongOrNull() ?: 0L, stale = m["stale"] == "true",
                serverHost = m["shost"].orEmpty(), serverIp = m["sip"].orEmpty(), serverKey = m["skey"].orEmpty(), lanCidr = m["lan"].orEmpty(),
            )
        } catch (e: NoSuchElementException) {
            null
        }
    }

    private companion object {
        const val PREFIX = "router_profile_"
        const val ACTIVE = "router_profile_active_id"
    }
}

/** Random passwords that are easy to read out and type: no 0/O, 1/l/I. */
object PassphraseGenerator {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzACDEFGHJKLMNPQRTUVWXYZ23456789"

    fun generate(length: Int = 20, random: SecureRandom = SecureRandom()): String {
        require(length in 8..63)
        return buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    }
}
