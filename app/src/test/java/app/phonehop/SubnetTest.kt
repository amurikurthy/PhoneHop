package app.phonehop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SubnetTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun matchesWithinAGroupSubnet() {
        assertTrue(Subnet.contains(ip("192.168.49.1"), 24, ip("192.168.49.77")))
        assertFalse(Subnet.contains(ip("192.168.49.1"), 24, ip("192.168.50.77")))
        assertFalse(Subnet.contains(ip("192.168.49.1"), 24, ip("10.0.0.5")))
    }

    @Test fun handlesNonByteAlignedPrefixes() {
        assertTrue(Subnet.contains(ip("10.0.0.1"), 20, ip("10.0.15.200")))
        assertFalse(Subnet.contains(ip("10.0.0.1"), 20, ip("10.0.16.1")))
        assertTrue(Subnet.contains(ip("1.2.3.4"), 0, ip("200.1.1.1")))
        assertTrue(Subnet.contains(ip("1.2.3.4"), 32, ip("1.2.3.4")))
        assertFalse(Subnet.contains(ip("1.2.3.4"), 32, ip("1.2.3.5")))
    }

    @Test fun rejectsIpv6AndBadPrefixes() {
        assertFalse(Subnet.contains(ip("192.168.49.1"), 24, ip("::1")))
        assertFalse(Subnet.contains(ip("192.168.49.1"), 33, ip("192.168.49.2")))
    }
}
