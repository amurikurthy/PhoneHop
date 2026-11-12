package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class X25519Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // RFC 7748 section 5.2, first test vector
    @Test
    fun rfc7748ScalarMult() {
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519.scalarMult(k, u).toHex()
        )
    }

    // RFC 7748 section 6.1, Alice's keypair
    @Test
    fun rfc7748PublicKey() {
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            X25519.publicKey(priv).toHex()
        )
    }

    // RFC 7748 section 6.1, shared secret
    @Test
    fun rfc7748SharedSecret() {
        val alicePriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            X25519.scalarMult(alicePriv, bobPub).toHex()
        )
    }

    @Test
    fun generatedKeysAreClampedAndDistinct() {
        val a = X25519.generatePrivateKey()
        val b = X25519.generatePrivateKey()
        assertEquals(0, a[0].toInt() and 7)
        assertEquals(64, a[31].toInt() and 192)
        assertFalse(a.contentEquals(b))
    }
}
