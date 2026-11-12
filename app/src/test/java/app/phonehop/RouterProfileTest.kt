package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterProfileTest {
    private fun profile(id: String = "SN1", suffix: String = "hop", stale: Boolean = false) = RouterProfile(
        id = id, model = "GL.iNet GL-AR750S-Ext", mac = "94:83:c4:0a:1b:2c", serial = "SN1",
        linkSsid = suffix, linkPassphrase = "p&ss=w0rd +é", bandIndex = 2,
        clientSsid = "My Net & More", createdAt = 1000L, stale = stale,
        serverHost = "de-fra-wg-005", serverIp = "146.70.1.2", serverKey = "B".repeat(43) + "=", lanCidr = "192.168.50.1/24",
    )

    @Test fun storesAndRestoresAProfileExactly() {
        val store = ProfileStore(MemoryKeyValueStore())
        store.save(profile())
        assertEquals(profile(), store.get("SN1"))
    }

    @Test fun tracksTheActiveProfile() {
        val store = ProfileStore(MemoryKeyValueStore())
        assertNull(store.active())
        store.save(profile("A")); store.save(profile("B").copy(createdAt = 2000L))
        store.setActive("B")
        assertEquals("B", store.active()!!.id)
        assertEquals(listOf("A", "B"), store.all().map { it.id })
        store.delete("B")
        assertNull(store.activeId())
        assertEquals(listOf("A"), store.all().map { it.id })
        store.setActive(null)
        assertNull(store.active())
    }

    @Test fun staleFlagSurvivesAndCanBeCleared() {
        val store = ProfileStore(MemoryKeyValueStore())
        store.save(profile(stale = true))
        assertTrue(store.get("SN1")!!.stale)
        store.save(profile(stale = false))
        assertFalse(store.get("SN1")!!.stale)
    }

    @Test fun damagedEntriesAreIgnored() {
        val kv = MemoryKeyValueStore()
        kv.put("router_profile_x", "garbage")
        kv.put("router_profile_y", "id=y")
        assertTrue(ProfileStore(kv).all().isEmpty())
    }

    @Test fun identityPrefersSerialThenMac() {
        val m = mapOf("MODEL" to "GL.iNet GL-AR750S-Ext", "MAC" to "94:83:C4:0A:1B:2C", "SERIAL" to "SN9")
        assertEquals("SN9", RouterIdentity.fromMarkers(m).id)
        assertEquals("94:83:c4:0a:1b:2c", RouterIdentity.fromMarkers(m - "SERIAL").id)
        assertNull(RouterIdentity.fromMarkers(emptyMap()).id)
    }

    @Test fun generatedPasswordsAreLongAndUnambiguous() {
        val a = PassphraseGenerator.generate(20)
        assertEquals(20, a.length)
        assertTrue(a.none { it in "0O1lI" })
        assertFalse(a == PassphraseGenerator.generate(20))
    }

    @Test fun profilesFromTheOldWifiDirectDesignAreIgnored() {
        val kv = MemoryKeyValueStore()
        kv.put("router_profile_SN1", "id=SN1&suffix=hop&pass=abcdefgh&band=2&created=1&stale=false")
        assertTrue(ProfileStore(kv).all().isEmpty())
    }
}
