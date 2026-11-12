package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficMathTest {
    @Test fun convertsBytesToBitsPerSecond() {
        assertEquals(8_000_000.0, TrafficMath.bitsPerSecond(0, 1_000_000, 1.0), 0.001)
        assertEquals(4_000_000.0, TrafficMath.bitsPerSecond(0, 1_000_000, 2.0), 0.001)
    }

    @Test fun counterResetOrBadIntervalClampsToZero() {
        assertEquals(0.0, TrafficMath.bitsPerSecond(5_000, 1_000, 1.0), 0.0)
        assertEquals(0.0, TrafficMath.bitsPerSecond(0, 1_000, 0.0), 0.0)
    }

    @Test fun formatsRates() {
        assertEquals("0 bps", TrafficMath.formatRate(0.0))
        assertEquals("512 Kbps", TrafficMath.formatRate(512_000.0))
        assertEquals("12.4 Mbps", TrafficMath.formatRate(12_400_000.0))
        assertEquals("1.50 Gbps", TrafficMath.formatRate(1_500_000_000.0))
    }
}
