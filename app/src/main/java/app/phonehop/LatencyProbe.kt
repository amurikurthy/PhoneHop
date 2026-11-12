package app.phonehop

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** One reading of both paths. A null value means that path could not be measured at that moment. */
/** [loadKbps] is how much traffic the relay was carrying when the sample was taken (kilobits per second). */
/**
 * [internet2Ms] is a second, unrelated server over the same path (if both spike together the phone's cellular path is
 * at fault, if only one does it is that server); [otherKbps] is the phone's other cellular traffic (not the relay's);
 * [signalDbm] is the cellular signal strength when the network reports it.
 */
data class LatencySample(
    val at: Long, val screenOn: Boolean, val linkMs: Double?, val internetMs: Double?, val loadKbps: Double = 0.0,
    val internet2Ms: Double? = null, val otherKbps: Double = 0.0, val signalDbm: Int? = null,
)

/** Thread-safe ring buffer of recent samples. */
class LatencyLog(private val capacity: Int = 900) {
    private val items = ArrayDeque<LatencySample>()

    @Synchronized fun add(s: LatencySample) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(s)
    }

    @Synchronized fun snapshot(): List<LatencySample> = items.toList()
    @Synchronized fun clear() = items.clear()
}

object LatencyStats {
    data class Summary(val count: Int, val p50: Double, val p95: Double, val max: Double, val spikes: Int)

    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).let { Math.round(it).toInt() }
        return sorted[idx.coerceIn(0, sorted.size - 1)]
    }

    fun summarize(values: List<Double>, spikeMs: Double = 150.0): Summary? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return Summary(sorted.size, percentile(sorted, 0.5), percentile(sorted, 0.95), sorted.last(), sorted.count { it >= spikeMs })
    }

    /** "6 ms", "240 ms", "1.2 s". */
    fun format(ms: Double): String = when {
        ms < 1.0 -> "<1 ms"
        ms < 1000.0 -> "${ms.toInt()} ms"
        else -> String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    }
}

/**
 * Measures round-trip time by timing TCP connection set-up, once every [intervalMs]:
 *  - "link": to the router over the router link (its SSH port, which the setup leaves reachable from the phone)
 *  - "internet": to a Mullvad web server, over the same network the relay uses
 * Comparing the two shows which side a latency spike comes from.
 */
class LatencyProbe(
    private val routerHost: () -> String?,
    private val internetNetwork: () -> Network?,
    private val internetTarget: () -> InetSocketAddress?,
    private val screenOn: () -> Boolean,
    private val log: LatencyLog,
    private val onSample: (LatencySample) -> Unit = {},
    private val intervalMs: Long = 2_000,
    private val timeoutMs: Int = 1_500,
    private val linkPort: Int = 22,
    /** Opens the socket used to time the router (on Android: bound to the link network). */
    private val linkSocket: () -> Socket = { Socket() },
    /** Current relayed traffic, so latency can be split into idle and loaded. */
    private val loadKbps: () -> Double = { 0.0 },
    private val internet2Target: () -> InetSocketAddress? = { null },
    private val otherKbps: () -> Double = { 0.0 },
    private val signalDbm: () -> Int? = { null },
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            while (running) {
                val started = System.currentTimeMillis()
                val link = routerHost()?.let { measureConnectMs(InetSocketAddress(it, linkPort), timeoutMs) { linkSocket() } }
                val net = internetNetwork()
                val target = internetTarget()
                val internet = if (net != null && target != null) {
                    measureConnectMs(target, timeoutMs) { net.socketFactory.createSocket() }
                } else null
                val target2 = internet2Target()
                val internet2 = if (net != null && target2 != null) {
                    measureConnectMs(target2, timeoutMs) { net.socketFactory.createSocket() }
                } else null
                val load = loadKbps()
                val sample = LatencySample(System.currentTimeMillis(), screenOn(), link, internet, load, internet2, otherKbps(), signalDbm())
                log.add(sample)
                onSample(sample)
                val wait = intervalMs - (System.currentTimeMillis() - started)
                if (wait > 0) try { Thread.sleep(wait) } catch (e: InterruptedException) { return@Thread }
            }
        }, "latency-probe").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    companion object {
        /** Milliseconds to complete a TCP handshake with [target], or null if it failed or timed out. */
        fun measureConnectMs(target: InetSocketAddress, timeoutMs: Int, open: () -> Socket = { SocketFactory.getDefault().createSocket() }): Double? {
            return try {
                open().use { s ->
                    val t0 = System.nanoTime()
                    s.connect(target, timeoutMs)
                    (System.nanoTime() - t0) / 1_000_000.0
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
