package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPickerTest {
    private fun relay(host: String, city: String, lat: Double, lon: Double, weight: Int = 100, owned: Boolean = false) =
        MullvadApi.Relay(host, "X", city, "1.2.3.4", "key", owned, "p", lat, lon, weight)

    private val sto = relay("se-sto-wg-001", "Stockholm", 59.33, 18.07)
    private val fra = relay("de-fra-wg-001", "Frankfurt", 50.11, 8.68)
    private val nyc = relay("us-nyc-wg-001", "New York", 40.71, -74.0)

    @Test fun distanceBerlinToParisIsAbout880Km() {
        val km = RelayPicker.distanceKm(52.52, 13.405, 48.8566, 2.3522)
        assertTrue("got $km", km in 860.0..900.0)
    }

    @Test fun picksNearestCity() {
        assertEquals("de-fra-wg-001", RelayPicker.nearest(listOf(sto, fra, nyc), 52.52, 13.4)!!.relay.hostname) // Berlin
        assertEquals("us-nyc-wg-001", RelayPicker.nearest(listOf(sto, fra, nyc), 42.36, -71.06)!!.relay.hostname) // Boston
    }

    @Test fun withinACityPrefersHighestWeightThenOwned() {
        val a = relay("de-fra-wg-001", "Frankfurt", 50.11, 8.68, weight = 50)
        val b = relay("de-fra-wg-002", "Frankfurt", 50.11, 8.68, weight = 200)
        val c = relay("de-fra-wg-003", "Frankfurt", 50.11, 8.68, weight = 200, owned = true)
        assertEquals("de-fra-wg-003", RelayPicker.nearest(listOf(a, b, c), 50.0, 8.6)!!.relay.hostname)
    }

    @Test fun nearbyMetroRelaysAreCandidatesToo() {
        // A heavier server ~10 km away (same metro) beats a lighter one that is slightly closer.
        val close = relay("a", "Frankfurt", 50.11, 8.68, weight = 10)
        val heavy = relay("b", "Offenbach", 50.10, 8.76, weight = 300)
        assertEquals("b", RelayPicker.nearest(listOf(close, heavy), 50.11, 8.68)!!.relay.hostname)
    }

    @Test fun emptyListGivesNull() {
        assertNull(RelayPicker.nearest(emptyList(), 0.0, 0.0))
    }
}
