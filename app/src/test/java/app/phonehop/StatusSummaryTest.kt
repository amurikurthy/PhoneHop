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

    // ---- the one-glance headline
    private fun h(i: StatusSummary.In) = StatusSummary.headline(i)

    @Test fun headlineWalksThroughSetupInOrder() {
        assertTrue(h(base.copy(hasKey = false)).title == "Set up needed")
        assertTrue(h(base.copy(serverName = null)).title == "Choosing a server")
        assertTrue(h(base.copy(routerTitle = null)).title == "Set up your router")
        assertTrue(h(base.copy(linkAddress = null)).title == "Waiting for your router")
    }

    @Test fun headlineIsConnectedOnlyWhileTheRouterIsBeingHeard() {
        val ok = h(base)
        assertTrue(ok.title == "Connected" && ok.level == StatusSummary.Level.GOOD && ok.subtitle.contains("de-fra-wg-005"))
        val stale = h(base.copy(routerHeardSecondsAgo = 500))
        assertTrue(stale.title == "Ready" && stale.level == StatusSummary.Level.IDLE)
        assertTrue(h(base.copy(routerHeardSecondsAgo = null)).title == "Ready")
    }

    @Test fun missingPiecesAreWarningsNotErrors() {
        assertTrue(h(base.copy(hasKey = false)).level == StatusSummary.Level.WARN)
        assertTrue(h(base.copy(routerTitle = null)).level == StatusSummary.Level.WARN)
    }
}
