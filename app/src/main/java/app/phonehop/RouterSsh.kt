package app.phonehop

import android.net.Network
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import javax.net.SocketFactory

/** Runs a shell script on the router over SSH, trusting each router's host key on first use. */
class RouterSsh(
    private val store: KeyValueStore,
    /** Network to bind the connection to (needed for a LAN that Android does not treat as the default). */
    private val network: Network? = null,
    private val port: Int = 22,
    private val log: (String) -> Unit = {},
) {
    class Result(val exitCode: Int, val output: String)

    class HostKeyChangedException(host: String) :
        Exception("The router at $host presented a different SSH identity than before. If you replaced or reset it, use \"Forget router identity\".")

    class AuthException : Exception("The router rejected the password")

    /**
     * Feeds [script] to `sh -s` on the router and returns everything it printed.
     * Passwords are only ever sent over the encrypted connection after the host key check.
     */
    fun run(host: String, password: String, script: String, user: String = "root", timeoutMs: Long = 60_000): Result {
        val jsch = JSch()
        jsch.hostKeyRepository = TofuRepository(store, log)
        val session = jsch.getSession(user, host, port)
        session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // Algorithms Dropbear offers and Android's crypto provider supports.
        session.setConfig("kex", "ecdh-sha2-nistp256,ecdh-sha2-nistp384,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1")
        session.setConfig("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512,ssh-rsa")
        session.setConfig("cipher.s2c", "aes128-ctr,aes256-ctr")
        session.setConfig("cipher.c2s", "aes128-ctr,aes256-ctr")
        session.setConfig("mac.s2c", "hmac-sha2-256,hmac-sha1")
        session.setConfig("mac.c2s", "hmac-sha2-256,hmac-sha1")
        session.setSocketFactory(BoundSocketFactory(network))
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String = password
            override fun promptPassword(m: String?) = true
            override fun promptPassphrase(m: String?) = false
            override fun promptYesNo(m: String?) = false
            override fun showMessage(m: String?) {}
        }
        try {
            session.connect(10_000)
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            when {
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> throw AuthException()
                m.contains("HostKey", true) -> throw HostKeyChangedException(host)
                else -> throw e
            }
        }
        try {
            val channel = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            channel.setCommand("sh -s")
            channel.setInputStream(ByteArrayInputStream(script.toByteArray()))
            val out = ByteArrayOutputStream()
            channel.setOutputStream(out)
            channel.setErrStream(out)
            channel.connect(10_000)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            val closed = channel.isClosed
            val exit = if (closed) channel.exitStatus else -1
            channel.disconnect()
            return Result(exit, out.toString("UTF-8").also { if (!closed) log("Router command timed out") })
        } finally {
            session.disconnect()
        }
    }

    /** Forgets every saved router identity. */
    fun forgetAll() { store.keys(KEY_PREFIX).forEach { store.remove(it) } }

    companion object { const val KEY_PREFIX = "ssh_hostkey_" }

    /** Remembers the SHA-256 of each router's host key the first time and refuses any change afterwards. */
    private class TofuRepository(private val store: KeyValueStore, private val log: (String) -> Unit) : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fp = Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
            val id = KEY_PREFIX + host
            val saved = store.get(id)
            return when {
                saved == null -> {
                    store.put(id, fp)
                    log("Trusting router $host on first use (SSH key SHA256:$fp)")
                    HostKeyRepository.OK
                }
                saved == fp -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID(): String = "phonehop"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Opens connections on [network] when given, else normally. */
    private class BoundSocketFactory(private val network: Network?) : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            val s = (network?.socketFactory ?: SocketFactory.getDefault()).createSocket()
            s.connect(InetSocketAddress(host, port), 8_000)
            return s
        }
        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }
}
