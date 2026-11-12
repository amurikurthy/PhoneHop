package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class MemoryKeyValueStore : KeyValueStore {
    val map = linkedMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}

class SecretStoreTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class SoftwareCipher(val key: SecretKey) : SecretCipher {
        override fun encrypt(plain: ByteArray) = AesGcm.encrypt(key, plain)
        override fun decrypt(encoded: String) = AesGcm.decrypt(key, encoded)
    }

    @Test fun aesGcmRoundTrips() {
        val key = newKey()
        val enc = AesGcm.encrypt(key, "1234567890123456".toByteArray())
        assertEquals("1234567890123456", AesGcm.decrypt(key, enc)!!.toString(Charsets.UTF_8))
    }

    @Test fun eachEncryptionUsesAFreshIv() {
        val key = newKey()
        assertNotEquals(AesGcm.encrypt(key, "same".toByteArray()), AesGcm.encrypt(key, "same".toByteArray()))
    }

    @Test fun tamperingOrWrongKeyIsRejected() {
        val key = newKey()
        val raw = Base64.getDecoder().decode(AesGcm.encrypt(key, "secret-value".toByteArray()))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(AesGcm.decrypt(key, Base64.getEncoder().encodeToString(raw)))
        assertNull(AesGcm.decrypt(newKey(), AesGcm.encrypt(key, "x".toByteArray())))
        assertNull(AesGcm.decrypt(key, "not base64 !!"))
        assertNull(AesGcm.decrypt(key, ""))
    }

    @Test fun storesEncryptedAndForgetsOnRequest() {
        val kv = MemoryKeyValueStore()
        val store = SecretStore(kv, SoftwareCipher(newKey()))
        assertNull(store.account())
        store.saveAccount("1234567890123456")
        assertEquals("1234567890123456", store.account())
        // What is on disk is not the number.
        assertFalse(kv.map.values.any { it.contains("1234567890123456") })
        store.clearAccount()
        assertNull(store.account())
        assertTrue(kv.map.isEmpty())
    }

    @Test fun rejectsAnythingThatIsNotASixteenDigitNumber() {
        val store = SecretStore(MemoryKeyValueStore(), SoftwareCipher(newKey()))
        for (bad in listOf("", "123", "12345678901234567", "abcd567890123456")) {
            try { store.saveAccount(bad); fail("accepted '$bad'") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun anUnreadableSavedValueActsAsNotSaved() {
        val kv = MemoryKeyValueStore()
        SecretStore(kv, SoftwareCipher(newKey())).saveAccount("1234567890123456")
        // e.g. the key was lost after a restore: decryption fails, and the app just asks again.
        assertNull(SecretStore(kv, SoftwareCipher(newKey())).account())
    }

    @Test fun namedSecretsAreEncryptedAndIndependent() {
        val kv = MemoryKeyValueStore()
        val store = SecretStore(kv, SoftwareCipher(newKey()))
        store.put("router_admin_SN1", "hunter2-hunter2")
        store.put("router_admin_SN2", "other-password")
        assertEquals("hunter2-hunter2", store.get("router_admin_SN1"))
        assertEquals("other-password", store.get("router_admin_SN2"))
        assertFalse(kv.map.values.any { it.contains("hunter2") })
        store.clear("router_admin_SN1")
        assertNull(store.get("router_admin_SN1"))
        assertEquals("other-password", store.get("router_admin_SN2"))
        assertNull(store.get("never-set"))
    }

    @Test fun theWireGuardPrivateKeyIsStoredEncrypted() {
        val kv = MemoryKeyValueStore()
        val store = SecretStore(kv, SoftwareCipher(newKey()))
        assertNull(store.wgPrivateKey())
        val key = "yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk="
        store.put(SecretStore.WG_PRIVATE_KEY, key)
        assertEquals(key, store.wgPrivateKey())
        assertFalse(kv.map.values.any { it.contains(key) })
        store.clear(SecretStore.WG_PRIVATE_KEY)
        assertNull(store.wgPrivateKey())
    }
}
