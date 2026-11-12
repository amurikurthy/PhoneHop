package app.phonehop

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class RelayTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val allow = AtomicBoolean(true)
    private lateinit var relay: Relay
    private lateinit var server: DatagramSocket
    private lateinit var router: DatagramSocket
    private val toClose = mutableListOf<DatagramSocket>()

    private fun socket(): DatagramSocket =
        DatagramSocket(0, loopback).apply { soTimeout = 1500 }.also { toClose += it }

    /** A well-formed WireGuard transport packet (type 4) of the given length. */
    private fun wgData(len: Int, fill: Int = 7): ByteArray =
        ByteArray(len) { fill.toByte() }.also { it[0] = 4; it[1] = 0; it[2] = 0; it[3] = 0 }

    private fun await(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue("timed out waiting", cond())
    }

    @Before fun setUp() {
        server = socket()
        router = socket()
        relay = Relay(
            listenAddress = { loopback }, listenPort = 0,
            allowedSource = { allow.get() },
            openUpstream = { DatagramSocket(0, loopback) },
        )
        relay.setUpstream("127.0.0.1", server.localPort)
        relay.start()
        await { relay.localPort > 0 && relay.upstreamLocalPort > 0 }
    }

    @After fun tearDown() {
        relay.stop()
        toClose.forEach { it.close() }
    }

    private fun sendToRelay(from: DatagramSocket, data: ByteArray) =
        from.send(DatagramPacket(data, data.size, loopback, relay.localPort))

    private fun receive(from: DatagramSocket): DatagramPacket {
        val p = DatagramPacket(ByteArray(2048), 2048)
        from.receive(p)
        return p
    }

    @Test fun forwardsRouterToServerUnchanged() {
        val pkt = wgData(100)
        sendToRelay(router, pkt)
        val got = receive(server)
        assertArrayEquals(pkt, got.data.copyOf(got.length))
        assertEquals(100L, relay.stats.fromRouter.get())
    }

    @Test fun forwardsServerReplyBackToRouter() {
        sendToRelay(router, wgData(100))
        val seen = receive(server)
        val reply = wgData(64, fill = 9)
        server.send(DatagramPacket(reply, reply.size, seen.address, seen.port))
        val back = receive(router)
        assertArrayEquals(reply, back.data.copyOf(back.length))
        assertEquals(64L, relay.stats.toRouter.get())
        assertEquals(InetSocketAddress(loopback, router.localPort), relay.stats.routerAddress)
    }

    @Test fun dropsPacketsThatAreNotWireGuard() {
        val junk = ByteArray(80).also { it[0] = 9 }
        sendToRelay(router, junk)
        sendToRelay(router, ByteArray(10)) // too short
        val badReserved = wgData(50).also { it[2] = 1 }
        sendToRelay(router, badReserved)
        val handshakeWrongSize = ByteArray(100).also { it[0] = 1 }
        sendToRelay(router, handshakeWrongSize)
        server.soTimeout = 400
        try { receive(server); throw AssertionError("junk was forwarded") } catch (_: SocketTimeoutException) {}
        await { relay.stats.dropped.get() == 4L }
        assertEquals(0L, relay.stats.fromRouter.get())
    }

    @Test fun acceptsRealHandshakeSizes() {
        assertTrue(WireGuardPacket.isValid(ByteArray(148).also { it[0] = 1 }, 148))
        assertTrue(WireGuardPacket.isValid(ByteArray(92).also { it[0] = 2 }, 92))
        assertTrue(WireGuardPacket.isValid(ByteArray(64).also { it[0] = 3 }, 64))
        assertTrue(WireGuardPacket.isValid(wgData(32), 32)) // keepalive
        assertFalse(WireGuardPacket.isValid(wgData(31), 31))
    }

    @Test fun dropsSendersThatAreNotAllowed() {
        allow.set(false)
        sendToRelay(router, wgData(100))
        server.soTimeout = 400
        try { receive(server); throw AssertionError("forwarded from a blocked sender") } catch (_: SocketTimeoutException) {}
        await { relay.stats.dropped.get() == 1L }
        assertNull(relay.stats.routerAddress)
    }

    @Test fun ignoresRepliesFromAnythingButTheServer() {
        sendToRelay(router, wgData(100))
        receive(server)
        val rogue = socket()
        val fake = wgData(80, fill = 5)
        rogue.send(DatagramPacket(fake, fake.size, loopback, relay.upstreamLocalPort))
        router.soTimeout = 400
        try { receive(router); throw AssertionError("rogue reply reached the router") } catch (_: SocketTimeoutException) {}
        assertEquals(0L, relay.stats.toRouter.get())
    }

    @Test fun followsTheRouterWhenItsPortChanges() {
        sendToRelay(router, wgData(100))
        val first = receive(server)
        val router2 = socket()
        sendToRelay(router2, wgData(100))
        receive(server)
        val reply = wgData(48)
        server.send(DatagramPacket(reply, reply.size, first.address, first.port))
        val back = receive(router2)
        assertEquals(48, back.length)
    }

    @Test fun switchingUpstreamRedirectsTraffic() {
        val server2 = socket()
        relay.setUpstream("127.0.0.1", server2.localPort)
        val pkt = wgData(90)
        sendToRelay(router, pkt)
        val got = receive(server2)
        assertArrayEquals(pkt, got.data.copyOf(got.length))
        server.soTimeout = 300
        try { receive(server); throw AssertionError("old server still receives") } catch (_: SocketTimeoutException) {}
    }

    @Test fun survivesRebindingTheUpstreamSocket() {
        sendToRelay(router, wgData(100))
        receive(server)
        val oldPort = relay.upstreamLocalPort
        relay.rebindUpstream()
        await { relay.upstreamLocalPort > 0 && relay.upstreamLocalPort != oldPort }
        sendToRelay(router, wgData(101))
        assertEquals(101, receive(server).length)
    }

    @Test fun neverForwardsWithoutAnUpstream() {
        val bare = Relay({ loopback }, 0, { true }, { DatagramSocket(0, loopback) })
        bare.start()
        await { bare.localPort > 0 }
        val data = wgData(100)
        router.send(DatagramPacket(data, data.size, loopback, bare.localPort))
        await { bare.stats.dropped.get() == 1L }
        assertNotNull(bare.stats)
        bare.stop()
    }

    @Test fun waitsUntilTheListenAddressExists() {
        val available = AtomicBoolean(false)
        val late = Relay({ if (available.get()) loopback else null }, 0, { true }, { DatagramSocket(0, loopback) })
        late.start()
        Thread.sleep(300)
        assertEquals(-1, late.localPort)
        available.set(true)
        await { late.localPort > 0 }
        late.stop()
    }

    @Test fun rebindListenReopensTheListener() {
        val before = relay.localPort
        relay.rebindListen()
        await { relay.localPort > 0 }
        // Port 0 means a new ephemeral port; just prove it still forwards.
        sendToRelay(router, wgData(100))
        assertEquals(100, receive(server).length)
        assertTrue(before > 0)
    }

    @Test fun measuresTheTunnelRoundTripAndForwardingTime() {
        sendToRelay(router, wgData(100))
        val seen = receive(server)
        Thread.sleep(60) // the "server" takes a while to answer
        val reply = wgData(80)
        server.send(DatagramPacket(reply, reply.size, seen.address, seen.port))
        receive(router)
        val rtts = relay.stats.rtt.since(0)
        assertEquals(1, rtts.size)
        assertTrue("rtt ${rtts[0]}", rtts[0] in 50.0..500.0)
        assertTrue(relay.stats.maxForwardUs.get() in 0..200_000)
    }

    // ---- self-heal watchdog (fast timings so the tests are quick)
    private fun healingRelay(): Relay = Relay(
        { loopback }, 0, { true }, { DatagramSocket(0, loopback) },
        healBytes = 500, healSilenceMs = 300, healCooldownMs = 300, watchdogTickMs = 50,
    ).also { it.setUpstream("127.0.0.1", server.localPort); it.start(); await { it.localPort > 0 && it.upstreamLocalPort > 0 } }

    @Test fun aStuckUpstreamSocketIsReopened() {
        val r = healingRelay()
        val before = r.upstreamLocalPort
        repeat(10) { router.send(DatagramPacket(wgData(100), 100, loopback, r.localPort)); Thread.sleep(5) }
        // The server never answers.
        await { r.upstreamLocalPort > 0 && r.upstreamLocalPort != before }
        assertTrue(r.stats.upstreamRebinds.get() >= 1)
        r.stop()
    }

    @Test fun aQuietTunnelIsNeverRebound() {
        val r = healingRelay()
        val before = r.upstreamLocalPort
        repeat(10) { router.send(DatagramPacket(wgData(32), 32, loopback, r.localPort)); Thread.sleep(80) } // keepalives only
        assertEquals(before, r.upstreamLocalPort)
        assertEquals(0L, r.stats.upstreamRebinds.get())
        assertEquals(0L, r.stats.listenRebinds.get())
        r.stop()
    }

    @Test fun aSilentRouterGetsTheListenerReopened() {
        val r = healingRelay()
        router.send(DatagramPacket(wgData(100), 100, loopback, r.localPort)) // router speaks once, so its address is learned
        val seen = receive(server)
        repeat(10) { server.send(DatagramPacket(wgData(100), 100, seen.address, seen.port)); Thread.sleep(5) }
        // ...and then says nothing while the server keeps sending
        await { r.stats.listenRebinds.get() >= 1 }
        r.stop()
    }
}
