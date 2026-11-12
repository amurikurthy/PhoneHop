package app.phonehop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM helpers. The output is base64(iv + ciphertext + tag). */
object AesGcm {
    private const val IV_BYTES = 12

    fun encrypt(key: SecretKey, plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // the provider picks a fresh random IV
        val ct = cipher.doFinal(plain)
        return Base64.getEncoder().encodeToString(cipher.iv + ct)
    }

    /** Returns null if the data is damaged, was tampered with, or was made with another key. */
    fun decrypt(key: SecretKey, encoded: String): ByteArray? = try {
        val raw = Base64.getDecoder().decode(encoded)
        if (raw.size <= IV_BYTES) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
            cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
        }
    } catch (e: Exception) {
        null
    }
}

interface SecretCipher {
    fun encrypt(plain: ByteArray): String
    fun decrypt(encoded: String): ByteArray?
}

/** Keeps the key inside the Android Keystore, so the encrypted value is useless if the file is copied off the phone. */
class KeystoreCipher(private val alias: String = "phonehop_secrets") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray) = AesGcm.encrypt(key(), plain)
    override fun decrypt(encoded: String) = runCatching { AesGcm.decrypt(key(), encoded) }.getOrNull()
}

/** Remembers the Mullvad account number, encrypted, until the user asks to forget it. */
class SecretStore(private val kv: KeyValueStore, private val cipher: SecretCipher) {
    fun account(): String? =
        kv.get(ACCOUNT)
            ?.let { runCatching { cipher.decrypt(it) }.getOrNull() }
            ?.toString(Charsets.UTF_8)
            ?.takeIf { it.length == 16 && it.all(Char::isDigit) }

    fun saveAccount(number: String) {
        require(number.length == 16 && number.all(Char::isDigit)) { "account number must be 16 digits" }
        kv.put(ACCOUNT, cipher.encrypt(number.toByteArray(Charsets.UTF_8)))
    }

    fun clearAccount() = kv.remove(ACCOUNT)

    /** Other named secrets (e.g. a router's admin password), stored the same way. */
    /** The phone's WireGuard private key, kept encrypted like the other secrets. */
    fun wgPrivateKey(): String? = get(WG_PRIVATE_KEY)

    fun put(name: String, value: String) { kv.put(NAMED + name, cipher.encrypt(value.toByteArray(Charsets.UTF_8))) }

    fun get(name: String): String? =
        kv.get(NAMED + name)?.let { runCatching { cipher.decrypt(it) }.getOrNull() }?.toString(Charsets.UTF_8)

    fun clear(name: String) = kv.remove(NAMED + name)

    companion object {
        const val WG_PRIVATE_KEY = "wg_private_key"
        private const val ACCOUNT = "mullvad_account"
        private const val NAMED = "secret_"
    }
}
