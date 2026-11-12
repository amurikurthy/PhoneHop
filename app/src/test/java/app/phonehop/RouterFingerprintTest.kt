package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RouterFingerprintTest {
    @Test fun classifiesByWebPage() {
        assertEquals(RouterFingerprint.Kind.GLINET, RouterFingerprint.classify(null, "HTTP/1.1 200 OK\r\n\r\n<title>GL.iNet Admin Panel</title>"))
        assertEquals(RouterFingerprint.Kind.OPENWRT, RouterFingerprint.classify(null, "HTTP/1.1 200\r\nServer: uhttpd\r\n\r\n<a href=\"/cgi-bin/luci\">"))
    }

    @Test fun glinetWinsOverGenericOpenWrtMarkers() {
        val page = "HTTP/1.1 200 OK\r\nServer: uhttpd\r\n\r\nGL.iNet ... luci"
        assertEquals(RouterFingerprint.Kind.GLINET, RouterFingerprint.classify("SSH-2.0-dropbear", page))
    }

    @Test fun fallsBackToTheSshBanner() {
        assertEquals(RouterFingerprint.Kind.OPENWRT, RouterFingerprint.classify("SSH-2.0-dropbear_2022.83", null))
        assertEquals(RouterFingerprint.Kind.UNKNOWN, RouterFingerprint.classify("SSH-2.0-OpenSSH_9.6", null))
        assertEquals(RouterFingerprint.Kind.UNKNOWN, RouterFingerprint.classify(null, null))
    }

    // Probe against throwaway local servers.
    private fun serve(reply: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (e: Exception) { return@thread }
                runCatching { reply(s) }
                runCatching { s.close() }
            }
        }
        return server
    }

    private fun open(host: String, port: Int) = Socket().apply { soTimeout = 1500; connect(java.net.InetSocketAddress(host, port), 1500) }

    @Test fun probesBannerAndWebPage() {
        val ssh = serve { it.getOutputStream().write("SSH-2.0-dropbear_2022.83\r\n".toByteArray()) }
        val web = serve { s ->
            s.getInputStream().bufferedReader().readLine() // request line
            s.getOutputStream().write("HTTP/1.0 200 OK\r\nServer: nginx\r\n\r\n<title>GL.iNet Admin Panel</title>".toByteArray())
        }
        val tls = serve { }
        val p = RouterFingerprint.probe("127.0.0.1", RouterFingerprint.Ports(ssh.localPort, web.localPort, tls.localPort), ::open)
        assertEquals("SSH-2.0-dropbear_2022.83", p.sshBanner)
        assertTrue(p.sshOpen)
        assertTrue(p.httpsOpen)
        assertEquals(RouterFingerprint.Kind.GLINET, p.kind)
        listOf(ssh, web, tls).forEach { it.close() }
    }

    @Test fun reportsClosedPortsAsMissing() {
        val closed = ServerSocket(0).also { it.close() }.localPort
        val p = RouterFingerprint.probe("127.0.0.1", RouterFingerprint.Ports(closed, closed, closed), ::open)
        assertNull(p.sshBanner)
        assertNull(p.httpHead)
        assertFalse(p.httpsOpen)
        assertEquals(RouterFingerprint.Kind.UNKNOWN, p.kind)
    }

    @Test fun ignoresNonSshOnThePort() {
        val fake = serve { it.getOutputStream().write("HELLO\r\n".toByteArray()) }
        val p = RouterFingerprint.probe("127.0.0.1", RouterFingerprint.Ports(fake.localPort, fake.localPort, fake.localPort), ::open)
        assertNull(p.sshBanner)
        fake.close()
    }
}
