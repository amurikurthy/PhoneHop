package app.phonehop

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Curve25519 (RFC 7748) so we can make WireGuard keypairs on minSdk 29, where the
 * platform has no X25519 provider. BigInteger is not constant-time; that is acceptable
 * here because keys are generated locally and never exposed to a timing observer.
 */
object X25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)

    fun generatePrivateKey(): ByteArray {
        val k = ByteArray(32)
        SecureRandom().nextBytes(k)
        return clamp(k)
    }

    fun publicKey(privateKey: ByteArray): ByteArray {
        val base = ByteArray(32).also { it[0] = 9 }
        return scalarMult(privateKey, base)
    }

    fun clamp(k: ByteArray): ByteArray {
        val c = k.copyOf()
        c[0] = (c[0].toInt() and 248).toByte()
        c[31] = (c[31].toInt() and 127).toByte()
        c[31] = (c[31].toInt() or 64).toByte()
        return c
    }

    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = clamp(scalar)
        val uMasked = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decode(uMasked).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t ushr 3].toInt() ushr (t and 7)) and 1
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val s = da.add(cb).mod(P)
            val df = da.subtract(cb).mod(P)
            x3 = s.multiply(s).mod(P)
            z3 = x1.multiply(df.multiply(df).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P)
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }

    private fun decode(le: ByteArray): BigInteger = BigInteger(1, le.reversedArray())

    private fun encode(n: BigInteger): ByteArray {
        val be = n.toByteArray() // big-endian, may have a leading sign byte
        val out = ByteArray(32)
        for (i in 0 until 32) {
            val idx = be.size - 1 - i
            out[i] = if (idx >= 0) be[idx] else 0
        }
        return out
    }
}
