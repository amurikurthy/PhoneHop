package app.phonehop

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text report for working out why something is slow or broken. It deliberately leaves out secrets: the Mullvad
 * account number, the router's and link's passwords, and WireGuard keys are never written to it.
 */
object DiagnosticsReport {
    data class In(
        val version: String,
        val device: String,
        val android: String,
        val nowMillis: Long,
        val settings: List<Pair<String, String>>,
        val state: List<Pair<String, String>>,
        val samples: List<LatencySample>,
        val tunnelRtt: List<Double>,
        /** Time and text of each recent event (screen, Doze, network, relay recovery...). */
        val events: List<Pair<Long, String>>,
        val log: String,
    )

    fun build(i: In): String {
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fun t(ms: Long) = clock.format(Date(ms))
        return buildString {
            appendLine("PhoneHop diagnostics")
            appendLine("Created: ${full.format(Date(i.nowMillis))}")
            appendLine("Version: ${i.version}")
            appendLine("Device:  ${i.device}")
            appendLine("Android: ${i.android}")
            appendLine()
            appendLine("== Settings ==")
            i.settings.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("== State ==")
            i.state.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
            appendLine("== Latency summary (10 min) ==")
            fun summary(label: String, values: List<Double>) {
                val s = LatencyStats.summarize(values)
                appendLine(if (s == null) "$label: no data" else
                    "$label: n=${s.count} p50=${LatencyStats.format(s.p50)} p95=${LatencyStats.format(s.p95)} max=${LatencyStats.format(s.max)} spikes>=150ms=${s.spikes}")
            }
            summary("Link, screen on", i.samples.filter { it.screenOn }.mapNotNull { it.linkMs })
            summary("Link, screen off", i.samples.filter { !it.screenOn }.mapNotNull { it.linkMs })
            summary("Internet, screen on", i.samples.filter { it.screenOn }.mapNotNull { it.internetMs })
            summary("Internet, screen off", i.samples.filter { !it.screenOn }.mapNotNull { it.internetMs })
            summary("Tunnel via relay", i.tunnelRtt)
            appendLine()
            appendLine("== Tunnel round trips seen by the relay (ms, newest last) ==")
            appendLine(if (i.tunnelRtt.isEmpty()) "none" else i.tunnelRtt.joinToString(" ") { "%.0f".format(Locale.US, it) })
            appendLine()
            appendLine("== Samples: time,screen,link_ms,internet_ms ==")
            i.samples.forEach {
                appendLine("${t(it.at)},${if (it.screenOn) "on" else "off"},${it.linkMs?.let { v -> "%.0f".format(Locale.US, v) } ?: ""},${it.internetMs?.let { v -> "%.0f".format(Locale.US, v) } ?: ""}")
            }
            appendLine()
            appendLine("== Events ==")
            i.events.forEach { (at, msg) -> appendLine("${t(at)}  $msg") }
            appendLine()
            appendLine("== Log ==")
            appendLine(i.log.trimEnd())
        }
    }
}
