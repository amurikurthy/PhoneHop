package app.phonehop

import org.junit.Assert.assertTrue
import org.junit.Test

class StatusSummaryTest {
    private val base = StatusSummary.In(true, "de-fra-wg-005", "192.168.51.2", "GL.iNet GL-AR750S-Ext", 3, 2_048)

    @Test fun everythingWorking() {
        val s = StatusSummary.build(base)
        assertTrue(s, s.lines().all { it.contains("\u2713") })
        assertTrue(s, s.contains("de-fra-wg-005") && s.contains("192.168.51.2") && s.contains("2.0 KB"))
    }

    @Test fun fresh_install_tells_you_what_to_do_first() {
        val s = StatusSummary.build(StatusSummary.In(false, null, null, null, null, 0))
        assertTrue(s, s.contains("Register a key"))
        assertTrue(s, s.contains("Not set up yet"))
        assertTrue(s, s.contains("not joined"))
        assertTrue(s, s.contains("No traffic"))
    }

    @Test fun staleTrafficIsNotShownAsRelaying() {
        assertTrue(StatusSummary.build(base.copy(routerHeardSecondsAgo = 500)).contains("No traffic"))
    }

    @Test fun keyWithoutServerSaysSo() {
        assertTrue(StatusSummary.build(base.copy(serverName = null)).contains("choosing a server"))
    }
}
