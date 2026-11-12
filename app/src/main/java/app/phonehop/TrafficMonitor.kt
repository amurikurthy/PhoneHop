package app.phonehop

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/** Pure helpers so the maths can be unit tested without Android. */
object TrafficMath {
    /** Bits per second from two byte counters; negative deltas (counter reset) clamp to zero. */
    fun bitsPerSecond(prevBytes: Long, curBytes: Long, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else maxOf(0L, curBytes - prevBytes) * 8.0 / seconds

    fun formatRate(bps: Double): String = when {
        bps < 1_000 -> "${bps.toInt()} bps"
        bps < 1_000_000 -> String.format(Locale.US, "%.0f Kbps", bps / 1_000)
        bps < 1_000_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000)
        else -> String.format(Locale.US, "%.2f Gbps", bps / 1_000_000_000)
    }
}

/**
 * Samples device-wide traffic once a second while started. "Cellular" is the mobile radio;
 * "Wi-Fi + Direct" is everything else (total minus mobile), which includes the Wi-Fi Direct
 * group's interface. Per-interface counters are not available to normal apps, so this is
 * device-level; a relay running in this app can report exact figures later.
 */
class TrafficMonitor(private val onSample: (Sample) -> Unit) {
    data class Sample(val wifiDown: Double, val wifiUp: Double, val cellDown: Double, val cellUp: Double)

    private data class Counters(val totalRx: Long, val totalTx: Long, val mobileRx: Long, val mobileTx: Long, val at: Long)

    private val handler = Handler(Looper.getMainLooper())
    private var last: Counters? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sample()
            handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        if (running) return
        running = true
        last = null
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun sample() {
        val cur = Counters(
            TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes(),
            TrafficStats.getMobileRxBytes(), TrafficStats.getMobileTxBytes(),
            SystemClock.elapsedRealtime()
        )
        if (listOf(cur.totalRx, cur.totalTx, cur.mobileRx, cur.mobileTx).any { it == TrafficStats.UNSUPPORTED.toLong() }) return
        val prev = last
        last = cur
        if (prev == null) return
        val s = (cur.at - prev.at) / 1000.0
        onSample(
            Sample(
                wifiDown = TrafficMath.bitsPerSecond(prev.totalRx - prev.mobileRx, cur.totalRx - cur.mobileRx, s),
                wifiUp = TrafficMath.bitsPerSecond(prev.totalTx - prev.mobileTx, cur.totalTx - cur.mobileTx, s),
                cellDown = TrafficMath.bitsPerSecond(prev.mobileRx, cur.mobileRx, s),
                cellUp = TrafficMath.bitsPerSecond(prev.mobileTx, cur.mobileTx, s),
            )
        )
    }
}
