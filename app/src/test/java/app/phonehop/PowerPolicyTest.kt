package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {
    @Test fun noRouterMeansSearchingAndNothingIsHeld() {
        for (since in listOf(null, 0L, 5_000L, 10_000_000L)) {
            val m = PowerPolicy.decide(linkJoined = false, msSinceData = since)
            assertEquals(PowerMode.SEARCHING, m)
            assertFalse(PowerPolicy.wantsWakeLock(m)); assertFalse(PowerPolicy.wantsWifiLock(m)); assertFalse(PowerPolicy.wantsKeepWarm(m))
        }
    }

    @Test fun connectedWithNoTrafficIsIdleAndOnlyHoldsTheCpuLock() {
        for (since in listOf(null, PowerPolicy.ACTIVE_WINDOW_MS, PowerPolicy.ACTIVE_WINDOW_MS + 1, 3_600_000L)) {
            val m = PowerPolicy.decide(true, since)
            assertEquals(PowerMode.IDLE, m)
            assertTrue(PowerPolicy.wantsWakeLock(m))
            assertFalse(PowerPolicy.wantsWifiLock(m)); assertFalse(PowerPolicy.wantsKeepWarm(m))
        }
    }

    @Test fun recentTrafficIsActiveAndKeepsEverythingOn() {
        for (since in listOf(0L, 1_000L, PowerPolicy.ACTIVE_WINDOW_MS - 1)) {
            val m = PowerPolicy.decide(true, since)
            assertEquals(PowerMode.ACTIVE, m)
            assertTrue(PowerPolicy.wantsWakeLock(m) && PowerPolicy.wantsWifiLock(m) && PowerPolicy.wantsKeepWarm(m))
        }
    }

    @Test fun losingTheLinkAlwaysWinsOverRecentTraffic() {
        assertEquals(PowerMode.SEARCHING, PowerPolicy.decide(false, 10L))
    }

    // ---- the background record shown in the app
    private val clock = { t: Long -> "t$t" }

    @Test fun runningShowsSinceAndWhatItIsDoing() {
        val s = BackgroundLog.format(1_000, 0, "", 0, true, PowerMode.IDLE, 1_000 + 3 * 3_600_000L + 12 * 60_000L, clock)
        assertTrue(s, s.contains("running since t1000") && s.contains("3 h 12 min") && s.contains("Low power"))
    }

    @Test fun afterQuitItSaysNothingIsRunning() {
        val s = BackgroundLog.format(1_000, 9_000, "you quit the app", 0, false, null, 20_000, clock)
        assertTrue(s, s.contains("stopped at t9000") && s.contains("you quit the app") && s.contains("Nothing is running in the background"))
        assertFalse(s, s.contains("restarted"))
    }

    @Test fun systemRestartsAreCountedAndNeverHidden() {
        assertTrue(BackgroundLog.format(1, 0, "", 1, true, PowerMode.ACTIVE, 2, clock).contains("restarted it by itself 1 time."))
        assertTrue(BackgroundLog.format(1, 5, "x", 3, false, null, 6, clock).contains("3 times"))
    }

    @Test fun neverStartedAndDurations() {
        assertEquals("Background service: not running.", BackgroundLog.format(0, 0, "", 0, false, null, 1, clock))
        assertEquals("under a minute", BackgroundLog.duration(30_000))
        assertEquals("45 min", BackgroundLog.duration(45 * 60_000L))
        assertEquals("2 h 0 min", BackgroundLog.duration(2 * 3_600_000L))
        assertEquals("under a minute", BackgroundLog.duration(-5))
    }
}
