package app.phonehop

import java.net.Socket

/** Works out what kind of router is at an address, from its SSH banner and web page. */
object RouterFingerprint {
    enum class Kind(val label: String) {
        GLINET("GL.iNet"),
        OPENWRT("OpenWrt"),
        UNKNOWN("Unknown device"),
    }

    class Probe(val host: String, val sshBanner: String?, val httpHead: String?, val httpsOpen: Boolean) {
        val sshOpen get() = sshBanner != null
        val kind get() = classify(sshBanner, httpHead)
    }

    class Ports(val ssh: Int = 22, val http: Int = 80, val https: Int = 443)

    fun classify(sshBanner: String?, http: String?): Kind {
        val web = (http ?: "").lowercase()
        if (listOf("gl.inet", "gl-inet", "glinet", "gl_inet").any { web.contains(it) }) return Kind.GLINET
        if (listOf("luci", "openwrt", "uhttpd").any { web.contains(it) }) return Kind.OPENWRT
        // OpenWrt (and GL.iNet, which is built on it) ship Dropbear as their SSH server.
        if (sshBanner?.lowercase()?.contains("dropbear") == true) return Kind.OPENWRT
        return Kind.UNKNOWN
    }

    /**
     * Looks at the SSH banner and the web front page. Uses raw sockets, so no HTTP library is
     * involved and Android's cleartext-traffic policy does not apply.
     * @param open returns a connected socket with sensible timeouts, or throws
     */
    fun probe(host: String, ports: Ports = Ports(), open: (String, Int) -> Socket): Probe {
        val banner = runCatching {
            open(host, ports.ssh).use { s -> s.getInputStream().bufferedReader().readLine()?.take(200) }
        }.getOrNull()?.takeIf { it.startsWith("SSH-") }

        val http = runCatching {
            open(host, ports.http).use { s ->
                s.getOutputStream().apply {
                    write("GET / HTTP/1.0\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val buf = CharArray(6000)
                val n = s.getInputStream().bufferedReader().read(buf)
                if (n > 0) String(buf, 0, n) else null
            }
        }.getOrNull()

        val httpsOpen = runCatching { open(host, ports.https).use { true } }.getOrDefault(false)
        return Probe(host, banner, http, httpsOpen)
    }
}
