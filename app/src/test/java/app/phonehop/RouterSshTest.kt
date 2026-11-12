package app.phonehop

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class RouterSshTest {
    private val servers = mutableListOf<SshServer>()

    private fun server(port: Int = 0): SshServer {
        val s = SshServer.setUpDefaultServer()
        s.port = port
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser")).apply {
            algorithm = "EC"
            setKeySize(256)
        }
        s.passwordAuthenticator = org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ -> user == "root" && pass == "secret" }
        s.commandFactory = ProcessShellCommandFactory.INSTANCE
        s.start()
        servers += s
        return s
    }

    @After fun tearDown() { servers.forEach { runCatching { it.stop(true) } } }

    @Test fun runsAScriptOverSshAndReturnsOutputAndExitCode() {
        val s = server()
        val ssh = RouterSsh(MemoryKeyValueStore(), port = s.port)
        val r = ssh.run("127.0.0.1", "secret", "echo hello\necho PHONEHOP_X=1\nexit 3\n")
        assertEquals(r.output, 3, r.exitCode)
        assertTrue(r.output, r.output.contains("hello"))
        assertEquals("1", RouterScripts.parseMarkers(r.output)["X"])
    }

    @Test fun carriesALargeScript() {
        val s = server()
        val ssh = RouterSsh(MemoryKeyValueStore(), port = s.port)
        val script = buildString { repeat(2000) { append("echo line$it\n") } }
        val r = ssh.run("127.0.0.1", "secret", script)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("line1999"))
    }

    @Test fun reportsAWrongPassword() {
        val s = server()
        try {
            RouterSsh(MemoryKeyValueStore(), port = s.port).run("127.0.0.1", "wrong", "echo hi")
            fail("expected AuthException")
        } catch (_: RouterSsh.AuthException) {}
    }

    @Test fun trustsTheHostKeyOnFirstUseThenRefusesAChange() {
        val store = MemoryKeyValueStore()
        val first = server()
        val port = first.port
        val ssh = RouterSsh(store, port = port)
        assertEquals(0, ssh.run("127.0.0.1", "secret", "true").exitCode)
        assertTrue(store.keys(RouterSsh.KEY_PREFIX).size == 1)
        // Same router again: fine.
        assertEquals(0, ssh.run("127.0.0.1", "secret", "true").exitCode)

        // A different machine (new host key) now answers at the same address.
        first.stop(true)
        server(port)
        try {
            ssh.run("127.0.0.1", "secret", "true")
            fail("expected HostKeyChangedException")
        } catch (_: RouterSsh.HostKeyChangedException) {}

        // The user chooses to forget the old identity; the new one is trusted.
        ssh.forgetAll()
        assertTrue(store.keys(RouterSsh.KEY_PREFIX).isEmpty())
        assertEquals(0, ssh.run("127.0.0.1", "secret", "true").exitCode)
    }

    @Test fun neverSendsAPasswordToAnUntrustedChangedKey() {
        // The host-key check happens before authentication, so a changed key fails even with a correct password,
        // and nothing is stored for the impostor.
        val store = MemoryKeyValueStore()
        val first = server()
        val port = first.port
        RouterSsh(store, port = port).run("127.0.0.1", "secret", "true")
        val saved = store.keys(RouterSsh.KEY_PREFIX).map { store.get(it) }
        first.stop(true)
        server(port)
        try { RouterSsh(store, port = port).run("127.0.0.1", "secret", "true") } catch (_: RouterSsh.HostKeyChangedException) {}
        assertEquals(saved, store.keys(RouterSsh.KEY_PREFIX).map { store.get(it) })
        assertFalse(saved.isEmpty())
    }
}
