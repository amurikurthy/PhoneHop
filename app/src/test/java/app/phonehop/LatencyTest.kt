package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class LatencyTest {
    @Test fun summarisesPercentilesAndSpikes() {
        val values = (1..100).map { it.toDouble() } + listOf(400.0)
        val s = LatencyStats.summarize(values)!!
        assertEquals(101, s.count)
        assertEquals(51.0, s.p50, 1.0)
        assertEquals(95.0, s.p95, 2.0)
        assertEquals(400.0, s.max, 0.0)
        assertEquals(values.count { it >= 150.0 }, s.spikes)
        assertNull(LatencyStats.summarize(emptyList()))
    }

    @Test fun countsSpikesAtTheThreshold() {
        assertEquals(2, LatencyStats.summarize(listOf(10.0, 150.0, 300.0, 20.0))!!.spikes)
        assertEquals(0, LatencyStats.summarize(listOf(10.0, 149.0))!!.spikes)
    }

    @Test fun formatsMilliseconds() {
        assertEquals("<1 ms", LatencyStats.format(0.4))
        assertEquals("12 ms", LatencyStats.format(12.7))
        assertEquals("1.2 s", LatencyStats.format(1234.0))
    }

    @Test fun ringBufferKeepsOnlyTheNewestSamples() {
        val log = LatencyLog(capacity = 3)
        (1..5).forEach { log.add(LatencySample(it.toLong(), true, it.toDouble(), null)) }
        assertEquals(listOf(3L, 4L, 5L), log.snapshot().map { it.at })
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test fun measuresTcpConnectTimeAgainstALocalServer() {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { while (!server.isClosed) { try { server.accept().close() } catch (_: Exception) { return@thread } } }
        val ms = LatencyProbe.measureConnectMs(InetSocketAddress("127.0.0.1", server.localPort), 1000)
        assertNotNull(ms)
        assertTrue("got $ms", ms!! in 0.0..500.0)
        server.close()
    }

    @Test fun reportsNullWhenNothingAnswers() {
        val closed = ServerSocket(0).also { it.close() }.localPort
        assertNull(LatencyProbe.measureConnectMs(InetSocketAddress("127.0.0.1", closed), 500))
    }

    @Test fun probeRecordsSamplesWithScreenState() {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) { while (!server.isClosed) { try { server.accept().close() } catch (_: Exception) { return@thread } } }
        val log = LatencyLog()
        var screen = true
        val probe = LatencyProbe(
            routerHost = { "127.0.0.1" }, internetNetwork = { null }, internetTarget = { null },
            screenOn = { screen }, log = log, intervalMs = 50, timeoutMs = 500, linkPort = server.localPort,
        )
        probe.start()
        Thread.sleep(250)
        screen = false
        Thread.sleep(250)
        probe.stop()
        server.close()
        val s = log.snapshot()
        assertTrue("samples: ${s.size}", s.size >= 4)
        assertTrue(s.all { it.linkMs != null })
        assertTrue(s.all { it.internetMs == null }) // no internet network given
        assertTrue(s.any { it.screenOn } && s.any { !it.screenOn })
    }
}
