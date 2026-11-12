package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LanPlanTest {
    private fun check(text: String): String? {
        val lan = LanPlan.parse(text) ?: return "unparsable"
        return LanPlan.validate(lan)
    }

    @Test fun parsesWithAndWithoutPrefix() {
        assertEquals(LanPlan.Lan("192.168.50.1", 24), LanPlan.parse("192.168.50.1/24"))
        assertEquals(LanPlan.Lan("10.20.0.1", 16), LanPlan.parse(" 10.20.0.1/16 "))
        assertEquals(LanPlan.Lan("192.168.50.1", 24), LanPlan.parse("192.168.50.1"))
        assertNull(LanPlan.parse("192.168.50/24"))
        assertNull(LanPlan.parse("192.168.50.300"))
        assertNull(LanPlan.parse("hello"))
        assertNull(LanPlan.parse("192.168.50.1/x"))
    }

    @Test fun acceptsSensiblePrivateRanges() {
        for (ok in listOf("192.168.50.1/24", "192.168.8.1/24", "10.20.0.1/16", "172.20.5.1/24", "10.200.1.1/24", "192.168.1.1/24")) {
            assertNull(ok, check(ok))
        }
        assertNull(check(LanPlan.DEFAULT))
    }

    @Test fun rejectsPublicRanges() {
        assertNotNull(check("8.8.8.1/24"))
        assertNotNull(check("172.32.0.1/24"))
        assertNotNull(check("11.0.0.1/24"))
    }

    @Test fun rejectsTheWifiDirectLinkRange() {
        assertNotNull(check("192.168.51.1/24"))
        assertNotNull(check("192.168.51.77/24"))
        assertNotNull(check("192.168.0.1/16")) // contains 192.168.49.0/24
    }

    @Test fun rejectsMullvadTunnelSpace() {
        assertNotNull(check("10.64.0.1/24"))
        assertNotNull(check("10.66.1.1/24"))
        assertNotNull(check("10.100.0.1/16"))
        assertNull(check("10.128.0.1/16")) // just outside 10.64.0.0/10
        assertNull(check("10.63.0.1/24"))
    }

    @Test fun rejectsBadSizesAndNetworkOrBroadcastAddresses() {
        assertNotNull(check("192.168.50.1/25"))
        assertNotNull(check("192.168.50.1/8"))
        assertNotNull(check("192.168.50.0/24"))
        assertNotNull(check("192.168.50.255/24"))
        assertNotNull(check("192.168.0.0/16"))
    }

    @Test fun computesNetmaskAndDhcpRange() {
        assertEquals("255.255.255.0", LanPlan.netmask(24))
        assertEquals("255.255.0.0", LanPlan.netmask(16))
        assertEquals("255.255.252.0", LanPlan.netmask(22))
        assertEquals(Pair("192.168.50.100", "192.168.50.249"), LanPlan.dhcpRange(LanPlan.Lan("192.168.50.1", 24)))
        assertEquals(Pair("10.20.0.100", "10.20.0.249"), LanPlan.dhcpRange(LanPlan.Lan("10.20.0.1", 16)))
    }

    @Test fun reservesTheActualPhoneLinkRangeWhenItIsNotTheStandardOne() {
        val odd = LanPlan.Lan("192.168.77.1", 24)
        assertNotNull(LanPlan.validate(LanPlan.Lan("192.168.77.1", 24), odd))
        assertNull(LanPlan.validate(LanPlan.Lan("192.168.49.1", 24), odd)) // fine if the link is elsewhere
    }
}
