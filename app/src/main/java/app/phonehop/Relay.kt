package app.phonehop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Recent round-trip times (milliseconds) as the relay sees them: a packet forwarded to the server until its answer returns. */
class RttLog(private val capacity: Int = 900) {
    private val items = ArrayDeque<Pair<Long, Double>>()
    @Synchronized fun add(ms: Double) { if (items.size >= capacity) items.removeFirst(); items.addLast(System.currentTimeMillis() to ms) }
    @Synchronized fun since(at: Long): List<Double> = items.filter { it.first >= at }.map { it.second }
    @Synchronized fun clear() = items.clear()
}

/** What the relay has done; read by the UI once a second for the graph and status line. */
class RelayStats {
    /** Bytes received from the router (heading to the VPN server). */
    val fromRouter = AtomicLong()
    /** Bytes received from the VPN server (heading to the router). */
    val toRouter = AtomicLong()
    val dropped = AtomicLong()
    /** Tunnel round trips seen by the relay, and the slowest time (microseconds) it took to forward one packet. */
    val rtt = RttLog()
    val maxForwardUs = AtomicLong()
    /** Send failures, and how often the sockets were re-opened to recover from a stall. */
    val sendErrors = AtomicLong()
    val upstreamRebinds = AtomicLong()
    val listenRebinds = AtomicLong()
    @Volatile var routerAddress: InetSocketAddress? = null
    @Volatile var lastFromRouterAt = 0L
    @Volatile var lastFromUpstreamAt = 0L
    @Volatile var listening: String? = null
    @Volatile var upstreamText: String? = null
}

/** Shape checks for WireGuard's four message types so the relay never forwards arbitrary junk. */
object WireGuardPacket {
    fun isValid(buf: ByteArray, len: Int): Boolean {
        if (len < 32) return false
        if (buf[1].toInt() != 0 || buf[2].toInt() != 0 || buf[3].toInt() != 0) return false // reserved bytes
        return when (buf[0].toInt()) {
            1 -> len == 148 // handshake initiation
            2 -> len == 92  // handshake response
            3 -> len == 64  // cookie reply
            4 -> true       // transport data (32 bytes is a keepalive)
            else -> false
        }
    }
}

/**
 * Dumb UDP relay: datagrams from the router are forwarded unchanged to the VPN server, and the
 * server's replies go back to wherever the router last sent from. The traffic is already
 * WireGuard-encrypted end to end, so this never sees plaintext.
 *
 * @param listenAddress local address to listen on, asked again at every (re)bind; null means "not available
 *        yet" (the router link is not up), so the relay waits. The group owner address in production
 * @param allowedSource decides which senders may use the relay; everyone else is dropped
 * @param openUpstream creates a fresh socket for talking to the server (on Android it is bound to the
 *        phone's internet network); called again after [rebindUpstream]
 */
class Relay(
    private val listenAddress: () -> InetAddress?,
    private val listenPort: Int,
    private val allowedSource: (InetAddress) -> Boolean,
    private val openUpstream: () -> DatagramSocket,
    val stats: RelayStats = RelayStats(),
    private val log: (String) -> Unit = {},
    /** Self-heal: this much one-way traffic with no answer for this long means the socket went stale. */
    private val healBytes: Long = 16_000,
    private val healSilenceMs: Long = 15_000,
    private val healCooldownMs: Long = 30_000,
    private val watchdogTickMs: Long = 2_000,
    /** Runs at the start of each relay thread (on Android: raises its scheduling priority). */
    private val threadSetup: () -> Unit = {},
    /** Called on the new listening socket before it is bound (on Android: ties it to the link network). */
    private val prepareListenSocket: (DatagramSocket) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    @Volatile private var upstream: InetSocketAddress? = null
    @Volatile private var listenSocket: DatagramSocket? = null
    @Volatile private var upstreamSocket: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()
    private val sentSinceReply = AtomicLong()
    private val receivedSinceRouterPacket = AtomicLong()
    @Volatile private var lastUpstreamHeal = 0L
    @Volatile private var awaitingSince = 0L // nanoTime of the oldest packet sent to the server with no answer yet
    @Volatile private var lastListenHeal = 0L
    @Volatile private var lastReplyNs = System.nanoTime() - 10_000_000_000L

    /** Actual bound port once listening, else -1. */
    val localPort: Int get() = listenSocket?.localPort ?: -1
    val upstreamLocalPort: Int get() = upstreamSocket?.localPort ?: -1

    fun setUpstream(host: String, port: Int) {
        upstream = InetSocketAddress(InetAddress.getByName(host), port)
        stats.upstreamText = "$host:$port"
        log("Relay upstream set to $host:$port")
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        threads += Thread({ threadSetup(); listenLoop() }, "relay-listen").apply { isDaemon = true; start() }
        threads += Thread({ threadSetup(); upstreamLoop() }, "relay-upstream").apply { isDaemon = true; start() }
        threads += Thread(::watchdogLoop, "relay-watchdog").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        listenSocket?.close()
        upstreamSocket?.close()
        threads.forEach { it.interrupt() }
        threads.clear()
        stats.listening = null
    }

    /** Call when the router link is (re)created so the listener binds to the new interface. */
    fun rebindListen() { listenSocket?.close() }

    /** Call when the phone's internet network changes; the old socket is no longer valid. */
    fun rebindUpstream() { upstreamSocket?.close() }

    val isRunning get() = running.get()

    /**
     * Sends one byte to the VPN server on the upstream socket. The server ignores it, but it keeps the phone's
     * cellular radio out of its low-power state, where every packet waits for the radio to wake.
     */
    fun poke() {
        val up = upstreamSocket
        val target = upstream
        if (up == null || target == null) return
        try { up.send(DatagramPacket(ByteArray(1), 1, target)) } catch (e: Exception) { stats.sendErrors.incrementAndGet() }
    }

    // ---------------------------------------------------------------- router -> server

    private fun listenLoop() {
        val buf = ByteArray(2048)
        while (running.get()) {
            val addr = listenAddress()
            if (addr == null) { stats.listening = null; sleep(1000); continue }
            val sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    prepareListenSocket(this)
                    bind(InetSocketAddress(addr, listenPort))
                    soTimeout = 1000
                }
            } catch (e: Exception) {
                // Typically the router link is not up yet; keep trying.
                stats.listening = null
                sleep(1500)
                continue
            }
            listenSocket = sock
            stats.listening = "${sock.localAddress.hostAddress}:${sock.localPort}"
            log("Relay listening on ${stats.listening}")
            try {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                    onFromRouter(p)
                }
            } catch (e: SocketException) {
                // closed by stop()
            } finally {
                sock.close()
                listenSocket = null
                stats.listening = null
            }
        }
    }

    private fun onFromRouter(p: DatagramPacket) {
        val startedNs = System.nanoTime()
        val src = InetSocketAddress(p.address, p.port)
        if (!allowedSource(p.address) || !WireGuardPacket.isValid(p.data, p.length)) {
            stats.dropped.incrementAndGet()
            return
        }
        val target = upstream
        val up = upstreamSocket
        if (target == null || up == null) { stats.dropped.incrementAndGet(); return }
        if (stats.routerAddress != src) {
            stats.routerAddress = src
            log("Router seen at ${src.address.hostAddress}:${src.port}")
        }
        stats.lastFromRouterAt = System.currentTimeMillis()
        stats.fromRouter.addAndGet(p.length.toLong())
        receivedSinceRouterPacket.set(0)
        sentSinceReply.addAndGet(p.length.toLong())
        try {
            up.send(DatagramPacket(p.data, p.length, target))
            // Only time a round trip that starts from quiet; under streaming, replies belong to older packets.
            if (awaitingSince == 0L && startedNs - lastReplyNs > 1_000_000_000L) awaitingSince = startedNs
            val tookUs = (System.nanoTime() - startedNs) / 1_000
            stats.maxForwardUs.accumulateAndGet(tookUs) { a, b -> maxOf(a, b) }
        } catch (e: Exception) {
            stats.dropped.incrementAndGet()
            stats.sendErrors.incrementAndGet()
        }
    }

    // ---------------------------------------------------------------- server -> router

    private fun upstreamLoop() {
        val buf = ByteArray(2048)
        while (running.get()) {
            val sock = try {
                openUpstream().apply { soTimeout = 1000 }
            } catch (e: Exception) {
                sleep(1500) // no internet network yet
                continue
            }
            upstreamSocket = sock
            try {
                while (running.get()) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                    onFromUpstream(p)
                }
            } catch (e: SocketException) {
                // closed by rebindUpstream() or stop(); loop opens a fresh one
            } finally {
                sock.close()
                if (upstreamSocket === sock) upstreamSocket = null
            }
        }
    }

    private fun onFromUpstream(p: DatagramPacket) {
        val expected = upstream
        if (expected == null || p.address != expected.address || p.port != expected.port) {
            stats.dropped.incrementAndGet() // not from our server
            return
        }
        if (!WireGuardPacket.isValid(p.data, p.length)) { stats.dropped.incrementAndGet(); return }
        val router = stats.routerAddress
        val out = listenSocket
        if (router == null || out == null) { stats.dropped.incrementAndGet(); return }
        stats.lastFromUpstreamAt = System.currentTimeMillis()
        stats.toRouter.addAndGet(p.length.toLong())
        lastReplyNs = System.nanoTime()
        val since = awaitingSince
        if (since != 0L) { awaitingSince = 0L; stats.rtt.add((System.nanoTime() - since) / 1_000_000.0) }
        sentSinceReply.set(0)
        receivedSinceRouterPacket.addAndGet(p.length.toLong())
        try {
            out.send(DatagramPacket(p.data, p.length, router))
        } catch (e: Exception) {
            stats.dropped.incrementAndGet()
            stats.sendErrors.incrementAndGet()
        }
    }

    /**
     * Notices a stalled socket and re-opens it. A quiet tunnel (only WireGuard keepalives) never trips this;
     * it needs real one-way traffic with no answer, which is what a socket left stale by a radio or
     * network change looks like.
     */
    private fun watchdogLoop() {
        while (running.get()) {
            sleep(watchdogTickMs)
            val now = System.currentTimeMillis()
            if (sentSinceReply.get() > healBytes && now - stats.lastFromUpstreamAt > healSilenceMs && now - lastUpstreamHeal > healCooldownMs) {
                lastUpstreamHeal = now
                sentSinceReply.set(0)
                stats.upstreamRebinds.incrementAndGet()
                log("Relay: the server stopped answering; re-opening the upstream socket")
                rebindUpstream()
            }
            if (receivedSinceRouterPacket.get() > healBytes && now - stats.lastFromRouterAt > healSilenceMs && now - lastListenHeal > healCooldownMs) {
                lastListenHeal = now
                receivedSinceRouterPacket.set(0)
                stats.listenRebinds.incrementAndGet()
                log("Relay: the router stopped sending; re-opening the listener")
                rebindListen()
            }
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { /* stopping */ }
    }
}
