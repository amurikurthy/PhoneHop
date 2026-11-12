package app.phonehop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    private fun sample() = DiagnosticsReport.In(
        version = "0.5.5", device = "Google Pixel 9 Pro XL", android = "Android 17 (API 37)", nowMillis = 1_800_000_000_000L,
        settings = listOf("Server" to "us-chi-wg-301", "Wi-Fi lock" to "High perf"),
        state = listOf("Link address" to "192.168.51.2", "Screen" to "on"),
        samples = listOf(
            LatencySample(1_800_000_000_000L, true, 7.0, 84.0),
            LatencySample(1_800_000_002_000L, false, 78.0, null),
        ),
        tunnelRtt = listOf(45.0, 520.0, 60.0),
        events = listOf(1_800_000_000_000L to "Screen off | relay up 1 KB"),
        log = "line one\nline two\n",
    )

    @Test fun containsEverySection() {
        val r = DiagnosticsReport.build(sample())
        listOf("PhoneHop diagnostics", "== Settings ==", "== State ==", "== Latency summary", "== Tunnel round trips",
            "== Samples", "== Events ==", "== Log ==", "Server: us-chi-wg-301", "Link address: 192.168.51.2",
            "Screen off | relay up 1 KB", "line two").forEach { assertTrue("missing: $it\n$r", r.contains(it)) }
    }

    @Test fun summarisesAndListsSamples() {
        val r = DiagnosticsReport.build(sample())
        assertTrue(r, r.contains("Link, screen on: n=1 p50=7 ms"))
        assertTrue(r, r.contains("Internet, screen off: no data"))
        assertTrue(r, r.contains("Tunnel via relay: n=3"))
        assertTrue(r, r.contains("45 520 60"))
        assertTrue(r, r.contains(",on,7,84"))
        assertTrue(r, r.contains(",off,78,"))
    }

    @Test fun handlesAnEmptyReport() {
        val r = DiagnosticsReport.build(sample().copy(samples = emptyList(), tunnelRtt = emptyList(), events = emptyList(), log = ""))
        assertTrue(r.contains("Tunnel via relay: no data"))
        assertTrue(r.contains("none"))
        assertFalse(r.contains("null"))
    }
}
