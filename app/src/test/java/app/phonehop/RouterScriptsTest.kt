package app.phonehop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RouterScriptsTest {
    private val key = "A".repeat(43) + "="
    private val serverKey = "B".repeat(43) + "="

    private fun plan(
        ssid: String = "DIRECT-ph-hop", pass: String = "phonehop-4821", prefer5: Boolean = true,
        clientSsid: String = "PhoneHop", clientPass: String = "client-pass-1", country: String = "US",
        lan: String = "192.168.50.1", lanPrefix: Int = 24,
    ) = RouterPlan(
        phoneSsid = ssid, phonePassphrase = pass, prefer5Ghz = prefer5,
        wgPrivateKey = key, wgAddressV4 = "10.66.1.2/32", wgAddressV6 = "fc00:bbbb:bbbb:bb01::2:102/128",
        serverPublicKey = serverKey, clientSsid = clientSsid, clientPassphrase = clientPass, adminPassword = "admin-pass-1", country = country,
        lanAddress = lan, lanPrefix = lanPrefix,
        nowMillis = 1_800_000_000_000L,
    )

    // ------------------------------------------------------------ pure logic

    @Test fun quotesShellValues() {
        assertEquals("'plain'", RouterScripts.shq("plain"))
        assertEquals("'it'\\''s'", RouterScripts.shq("it's"))
        assertEquals("'\$(reboot)'", RouterScripts.shq("\$(reboot)"))
    }

    @Test fun fillsEveryPlaceholder() {
        val s = RouterScripts.setup(plan())
        assertFalse(s.contains("@@"))
        assertTrue(s.contains("SSID='DIRECT-ph-hop'"))
        assertTrue(s.contains("PREFER_5G=1"))
        assertTrue(s.contains("NOW='2027-01-15 08:00:00'"))
        assertTrue(s.contains("PHONEHOP_REVERTED")) // the revert script is embedded
    }

    @Test fun hostileValuesCannotBreakOutOfQuotes() {
        val s = RouterScripts.setup(plan(pass = "x'; reboot; echo '12345678"))
        // The password only ever appears inside a correctly escaped single-quoted string.
        assertTrue(s.contains("PSK='x'\\''; reboot; echo '\\''12345678'"))
    }

    @Test fun rejectsBadInput() {
        assertNotNull(RouterScripts.validate(plan(ssid = "")))
        assertNotNull(RouterScripts.validate(plan(pass = "short")))
        assertNotNull(RouterScripts.validate(plan(ssid = "bad\nname")))
        assertNotNull(RouterScripts.validate(plan().copy(wgPrivateKey = "nope")))
        assertNotNull(RouterScripts.validate(plan().copy(wgAddressV4 = "10.0.0.2")))
        assertNotNull(RouterScripts.validate(plan().copy(relayHost = "phone.local")))
        assertNotNull(RouterScripts.validate(plan(clientSsid = "")))
        assertNotNull(RouterScripts.validate(plan(clientSsid = "x".repeat(28)))) // no room for _2.4G
        assertNull(RouterScripts.validate(plan(clientSsid = "x".repeat(27))))
        assertNotNull(RouterScripts.validate(plan(clientPass = "short")))
        assertNotNull(RouterScripts.validate(plan(country = "usa")))
        assertNotNull(RouterScripts.validate(plan(lan = "192.168.51.1")))   // the phone link range
        assertNotNull(RouterScripts.validate(plan(lan = "10.66.0.1")))      // Mullvad's tunnel range
        assertNotNull(RouterScripts.validate(plan(lan = "8.8.8.1")))        // not private
        assertNull(RouterScripts.validate(plan(lan = "172.20.5.1", lanPrefix = 16)))
        assertNull(RouterScripts.validate(plan()))
        try { RouterScripts.setup(plan(pass = "short")); fail("expected rejection") } catch (_: IllegalArgumentException) {}
    }

    @Test fun parsesMarkersAndHandshakeAge() {
        val m = RouterScripts.parseMarkers("noise\nPHONEHOP_PENDING=1\nPHONEHOP_NOW=1000\nPHONEHOP_HANDSHAKE=940\nPHONEHOP_DEFAULT_ROUTE=default dev mullvad\n")
        assertEquals("1", m["PENDING"])
        assertEquals("default dev mullvad", m["DEFAULT_ROUTE"])
        assertEquals(60L, RouterScripts.handshakeAgeSeconds(m))
        assertNull(RouterScripts.handshakeAgeSeconds(mapOf("NOW" to "1000", "HANDSHAKE" to "0")))
        assertNull(RouterScripts.handshakeAgeSeconds(emptyMap()))
    }

    // ------------------------------------------------------------ simulated router

    private val fakeUci = """
        #!/usr/bin/env python3
        import sys, os
        S = os.environ['FAKE_UCI_STATE']
        db = {}
        if os.path.exists(S):
            for line in open(S):
                k, t, v = line.rstrip('\n').split('\t', 2)
                db[k] = v.split('\x1f') if t == 'L' else v
        def save():
            with open(S, 'w') as f:
                for k, v in db.items():
                    f.write(k + '\t' + ('L' if isinstance(v, list) else 'S') + '\t' + ('\x1f'.join(v) if isinstance(v, list) else v) + '\n')
        a = sys.argv[1:]
        if a and a[0] == '-q': a = a[1:]
        c, r = a[0], a[1:]
        if c == 'set':
            k, v = r[0].split('=', 1); db[k] = v; save()
        elif c == 'add_list':
            k, v = r[0].split('=', 1); cur = db.get(k) or []
            if not isinstance(cur, list): cur = [cur]
            cur.append(v); db[k] = cur; save()
        elif c == 'del_list':
            k, v = r[0].split('=', 1); cur = db.get(k)
            if isinstance(cur, list) and v in cur: cur.remove(v); db[k] = cur; save()
        elif c == 'delete':
            k = r[0]; hit = [x for x in db if x == k or x.startswith(k + '.')]
            if not hit: sys.exit(1)
            for x in hit: del db[x]
            save()
        elif c == 'get':
            if r[0] not in db: sys.exit(1)
            v = db[r[0]]; print(' '.join(v) if isinstance(v, list) else v)
        elif c == 'show':
            pkg = r[0] if r else None
            for k, v in db.items():
                if pkg and not k.startswith(pkg + '.'): continue
                if k.count('.') == 1: print(k + '=' + v)
                elif isinstance(v, list): print(k + '=' + ' '.join("'" + x + "'" for x in v))
                else: print(k + "='" + v + "'")
        elif c == 'commit':
            pass
        else:
            sys.exit(2)
    """.trimIndent()

    /** What the firmware would generate for this hardware (after a wipe). */
    private val pristine = """
        wireless.radio0	S	wifi-device
        wireless.radio0.band	S	2g
        wireless.radio0.disabled	S	1
        wireless.default_radio0	S	wifi-iface
        wireless.default_radio0.device	S	radio0
        wireless.default_radio0.mode	S	ap
        wireless.default_radio0.ssid	S	GL-DEFAULT
        wireless.default_radio0.key	S	goodlife
        wireless.radio1	S	wifi-device
        wireless.radio1.band	S	5g
        wireless.radio1.disabled	S	1
        wireless.default_radio1	S	wifi-iface
        wireless.default_radio1.device	S	radio1
        wireless.default_radio1.mode	S	ap
        wireless.default_radio1.ssid	S	GL-DEFAULT
        network.lan	S	interface
        network.lan.ipaddr	S	192.168.1.1
        network.lan.netmask	S	255.255.255.0
        network.wan	S	interface
        network.wan6	S	interface
        firewall.@zone[0]	S	zone
        firewall.@zone[0].name	S	lan
        firewall.@zone[1]	S	zone
        firewall.@zone[1].name	S	wan
        firewall.@zone[1].network	L	wan
        firewall.@forwarding[0]	S	forwarding
        firewall.@forwarding[0].src	S	lan
        firewall.@forwarding[0].dest	S	wan
        dhcp.@dnsmasq[0]	S	dnsmasq
        dhcp.@dnsmasq[0].server	L	1.1.1.1
        dropbear.@dropbear[0]	S	dropbear
        dropbear.@dropbear[0].Interface	S	lan
    """.trimIndent()

    /** What the user had before: pristine plus stale leftovers that a wipe must remove. */
    private val seed = pristine + """
        wireless.old_repeater	S	wifi-iface
        wireless.old_repeater.mode	S	sta
        wireless.old_repeater.ssid	S	HotelWifi
        network.old_vpn	S	interface
        network.old_vpn.proto	S	wireguard
        firewall.custom_rule	S	rule
        firewall.custom_rule.target	S	ACCEPT
    """.trimIndent()

    private class Router(val dir: File) {
        val state = File(dir, "uci.state")
        val pristineFile = File(dir, "pristine.state")
        val cfg = File(dir, "config")
        val etc = File(dir, "etc").also { it.mkdirs() }
        val rom = File(dir, "rom")
        val env = mutableMapOf(
            "FAKE_UCI_STATE" to state.path, "PH_CFG" to cfg.path, "PH_INITD" to File(dir, "initd").path,
            "PH_BK" to File(dir, "backup").path, "PH_REVERT" to File(dir, "revert.sh").path,
            "PH_PENDING" to File(dir, "pending").path, "PH_ETC" to File(dir, "etc").path, "PH_ROM" to rom.path,
            "PH_CONFIG_GENERATE" to File(dir, "bin/config_generate").path,
            "PATH" to File(dir, "bin").path + ":" + System.getenv("PATH"),
        )
        fun get(key: String): List<String>? =
            state.readLines().map { it.split('\t', limit = 3) }.firstOrNull { it[0] == key }
                ?.let { if (it[1] == "L") it[2].split('\u001f') else listOf(it[2]) }
        fun has(prefix: String) = state.readLines().any { it.startsWith(prefix) }
        fun cfgFiles() = cfg.list()!!.sorted()
    }

    private fun newRouter(): Router? {
        val python = runCatching { ProcessBuilder("python3", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 needed for the simulated router", python)
        val dir = Files.createTempDirectory("fake-router").toFile()
        val r = Router(dir)
        File(dir, "bin").mkdirs(); File(dir, "initd").mkdirs(); r.cfg.mkdirs(); r.rom.mkdirs()
        File(dir, "bin/uci").apply { writeText(fakeUci + "\n"); setExecutable(true) }
        fun shim(name: String, body: String) = File(dir, "bin/$name").apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }
        for (name in listOf("date", "modprobe")) shim(name, "echo \"$name \$*\" >> '${dir}/calls.log'")
        shim("wg", "exit 0")
        shim("passwd", "cat >> '${dir}/passwd.log'")
        for (name in listOf("ifup", "ifdown")) shim(name, "echo \"$name \$*\" >> '${dir}/calls.log'")
        // `wifi config` regenerates the wireless file for this hardware.
        shim("wifi", "echo \"wifi \$*\" >> '${dir}/calls.log'\n[ \"\$1\" = config ] && echo 'GENERATED wireless' > \"\$PH_CFG/wireless\"\nexit 0")
        // config_generate rebuilds network/system and the uci database as the firmware would.
        shim("config_generate", "echo 'GENERATED network' > \"\$PH_CFG/network\"\necho 'GENERATED system' > \"\$PH_CFG/system\"\ncp '${r.pristineFile}' \"\$FAKE_UCI_STATE\"")
        // Short sleeps return at once; the long rollback timer is parked so it cannot fire during the test.
        shim("sleep", "[ \"\$1\" -ge 100 ] && exec /bin/sleep 20\nexit 0")
        for (name in listOf("dropbear", "network", "firewall", "dnsmasq")) {
            File(dir, "initd/$name").apply { writeText("#!/bin/sh\necho \"$name \$*\" >> '${dir}/calls.log'\n"); setExecutable(true) }
        }
        // The user's current configuration, including stuff a wipe must remove or reset.
        for (f in listOf("network", "wireless", "firewall", "dhcp", "dropbear")) File(r.cfg, f).writeText("ORIGINAL $f\n")
        File(r.cfg, "glconfig").writeText("USER glconfig\n")
        File(r.cfg, "custom_pkg").writeText("USER custom\n")
        // The firmware's factory defaults.
        for (f in listOf("network", "firewall", "dhcp", "dropbear", "glconfig", "system")) File(r.rom, f).writeText("ROM $f\n")
        r.pristineFile.writeText(pristine + "\n")
        r.state.writeText(seed + "\n")
        return r
    }

    private fun run(r: Router, script: String): Pair<Int, String> {
        val f = File(r.dir, "script.sh").apply { writeText(script) }
        val pb = ProcessBuilder("sh", f.path).redirectErrorStream(true)
        pb.environment().putAll(r.env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        return Pair(p.waitFor(), out)
    }

    @Test fun setupWipesAndRebuildsTheRouterForThisPurpose() {
        val r = newRouter() ?: return
        val (code, out) = run(r, RouterScripts.setup(plan(clientSsid = "My Network", clientPass = "client-pass-1")))
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("PHONEHOP_WIPED"))
        assertTrue(out, out.contains("PHONEHOP_APPLIED"))
        assertTrue(out, out.contains("PHONEHOP_RADIO=radio1 (5g)"))

        // --- everything that was there before is gone
        assertFalse(r.has("wireless.old_repeater"))
        assertFalse(r.has("network.old_vpn"))
        assertFalse(r.has("firewall.custom_rule"))
        assertFalse(r.has("network.wan6"))
        assertFalse(r.has("wireless.default_radio0"))          // firmware default networks (and their default password)
        assertFalse(r.state.readText().contains("goodlife"))
        // files: those with a factory default are reset, others deleted, hardware parts regenerated
        assertEquals("ROM glconfig\n", File(r.cfg, "glconfig").readText())
        assertFalse(File(r.cfg, "custom_pkg").exists())
        assertEquals("GENERATED wireless\n", File(r.cfg, "wireless").readText())
        assertEquals("GENERATED system\n", File(r.cfg, "system").readText())

        // --- the radios work: enabled, country set
        assertEquals(listOf("0"), r.get("wireless.radio0.disabled"))
        assertEquals(listOf("0"), r.get("wireless.radio1.disabled"))
        assertEquals(listOf("US"), r.get("wireless.radio1.country"))
        // --- the router hosts a hidden link network for the phone on the 5 GHz radio
        assertFalse(r.has("wireless.phonehop_sta"))
        assertEquals(listOf("ap"), r.get("wireless.phonehop_link.mode"))
        assertEquals(listOf("radio1"), r.get("wireless.phonehop_link.device"))
        assertEquals(listOf("DIRECT-ph-hop"), r.get("wireless.phonehop_link.ssid"))
        assertEquals(listOf("phonehop-4821"), r.get("wireless.phonehop_link.key"))
        assertEquals(listOf("1"), r.get("wireless.phonehop_link.hidden"))
        // --- your own Wi-Fi: one network per radio with your name and password
        assertEquals(listOf("My Network_2.4G"), r.get("wireless.phonehop_ap_radio0.ssid"))
        assertEquals(listOf("My Network_5G"), r.get("wireless.phonehop_ap_radio1.ssid"))
        assertEquals(listOf("client-pass-1"), r.get("wireless.phonehop_ap_radio0.key"))
        assertEquals(listOf("client-pass-1"), r.get("wireless.phonehop_ap_radio1.key"))
        assertTrue(out, out.contains("PHONEHOP_WIFI_radio1=My Network_5G"))
        assertEquals(listOf("psk2"), r.get("wireless.phonehop_ap_radio1.encryption"))
        // --- the router's own address range, as chosen
        assertEquals(listOf("192.168.50.1"), r.get("network.lan.ipaddr"))
        assertEquals(listOf("255.255.255.0"), r.get("network.lan.netmask"))
        assertEquals(listOf("100"), r.get("dhcp.lan.start"))
        assertEquals(listOf("150"), r.get("dhcp.lan.limit"))
        assertTrue(out, out.contains("PHONEHOP_LAN=192.168.50.1/255.255.255.0"))
        // --- 5 GHz uses a fixed non-DFS channel; 2.4 GHz stays automatic
        assertEquals(listOf("36"), r.get("wireless.radio1.channel"))
        assertNull(r.get("wireless.radio0.channel"))
        // --- the link zone rejects input, so DHCP must be explicitly allowed or the phone can never get an address
        assertEquals(listOf("phonelink"), r.get("firewall.phonehop_dhcp.src"))
        assertEquals(listOf("67"), r.get("firewall.phonehop_dhcp.dest_port"))
        assertEquals(listOf("ACCEPT"), r.get("firewall.phonehop_dhcp.target"))
        assertEquals(listOf("1"), r.get("firewall.@defaults[0].flow_offloading"))
        assertEquals(listOf("1"), r.get("firewall.@defaults[0].flow_offloading_hw"))
        assertEquals(listOf("/etc/phonehop-sqm.sh"), r.get("firewall.phonehop_sqm.path"))
        assertTrue(java.io.File(r.etc, "phonehop-sqm.sh").readText().contains("fq_codel"))
        assertTrue(java.io.File(r.etc, "phonehop-sqm.conf").readText().contains("DOWN="))
        // --- the link: a /30 with one lease and (for now) no gateway or DNS
        assertEquals(listOf("192.168.51.1"), r.get("network.phonelink.ipaddr"))
        assertEquals(listOf("1"), r.get("dhcp.phonelink.limit"))
        assertEquals(listOf("3", "6"), r.get("dhcp.phonelink.dhcp_option")) // experiment: no gateway, no DNS
        // --- tunnel points at the phone
        assertEquals(listOf("wireguard"), r.get("network.mullvad.proto"))
        assertEquals(listOf("10.66.1.2/32", "fc00:bbbb:bbbb:bb01::2:102/128"), r.get("network.mullvad.addresses"))
        assertEquals(listOf("192.168.51.2"), r.get("network.mullvad_peer.endpoint_host"))
        assertEquals(listOf("51820"), r.get("network.mullvad_peer.endpoint_port"))
        assertEquals(listOf("0.0.0.0/0", "::/0"), r.get("network.mullvad_peer.allowed_ips"))
        assertEquals(listOf(serverKey), r.get("network.mullvad_peer.public_key"))
        // --- kill switch: LAN can only forward to the VPN zone; LAN->WAN is gone
        assertFalse(r.has("firewall.@forwarding[0]"))
        assertEquals(listOf("lan"), r.get("firewall.phonehop_lan_vpn.src"))
        assertEquals(listOf("vpn"), r.get("firewall.phonehop_lan_vpn.dest"))
        assertEquals(listOf("wan"), r.get("firewall.@zone[1].network"))
        assertEquals(listOf("192.168.51.2"), r.get("firewall.phonehop_ssh.src_ip"))
        // --- DNS only through the tunnel; SSH not pinned to the LAN
        assertEquals(listOf("10.64.0.1"), r.get("dhcp.@dnsmasq[0].server"))
        assertEquals(listOf("1"), r.get("dhcp.@dnsmasq[0].noresolv"))
        assertNull(r.get("dropbear.@dropbear[0].Interface"))
        // --- the admin password the user chose is set on the router
        assertEquals("admin-pass-1\nadmin-pass-1\n", File(r.dir, "passwd.log").readText())
        assertTrue(out, out.contains("PHONEHOP_ADMIN_PASSWORD_SET=1"))
        // --- safety net
        assertTrue(File(r.dir, "pending").exists())
        assertTrue(File(r.dir, "revert.sh").readText().contains("PHONEHOP_REVERTED"))
        val listing = ProcessBuilder("tar", "tzf", File(r.dir, "backup/config.tgz").path).start().inputStream.bufferedReader().readText()
        assertTrue(listing, listing.contains("custom_pkg") && listing.contains("glconfig"))
    }

    @Test fun anyFailedSafetyCheckLeavesTheRouterUntouched() {
        // No factory defaults: the script must refuse BEFORE erasing or even backing up anything.
        val r = newRouter() ?: return
        r.env["PH_ROM"] = File(r.dir, "does-not-exist").path
        val (code, out) = run(r, RouterScripts.setup(plan()))
        assertEquals(1, code)
        assertTrue(out, out.contains("PHONEHOP_ERROR") && out.contains("factory defaults"))
        assertFalse(out.contains("PHONEHOP_WIPING"))
        assertEquals("USER custom\n", File(r.cfg, "custom_pkg").readText())
        assertEquals("ORIGINAL network\n", File(r.cfg, "network").readText())
        assertFalse(File(r.dir, "backup").exists())
        assertTrue(r.has("wireless.old_repeater"))
    }

    @Test fun refusesWhenWireGuardToolsAreMissing() {
        val r = newRouter() ?: return
        File(r.dir, "bin/wg").delete()
        // Hide any real wg on the host too.
        r.env["PATH"] = File(r.dir, "bin").path + ":/usr/bin:/bin"
        val hostHasWg = ProcessBuilder("sh", "-c", "PATH='${r.env["PATH"]}' command -v wg").start().waitFor() == 0
        assumeTrue("host has a real wg; cannot simulate its absence", !hostHasWg)
        val (code, out) = run(r, RouterScripts.setup(plan()))
        assertEquals(1, code)
        assertTrue(out, out.contains("WireGuard"))
        assertEquals("USER custom\n", File(r.cfg, "custom_pkg").readText())
    }

    @Test fun setupFallsBackToTheOtherBandWhenThereIsNoFiveGhzRadio() {
        val r = newRouter() ?: return
        r.pristineFile.writeText(r.pristineFile.readLines().filter { !it.startsWith("wireless.radio1") && !it.startsWith("wireless.default_radio1") }.joinToString("\n") + "\n")
        val (code, out) = run(r, RouterScripts.setup(plan(prefer5 = true)))
        assertEquals(out, 0, code)
        assertEquals(listOf("radio0"), r.get("wireless.phonehop_link.device"))
    }

    @Test fun setupIsIdempotent() {
        val r = newRouter() ?: return
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        val firstBackup = File(r.dir, "backup/config.tgz").readBytes()
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        assertEquals(listOf("0.0.0.0/0", "::/0"), r.get("network.mullvad_peer.allowed_ips"))
        assertEquals(listOf("wan"), r.get("firewall.@zone[1].network"))
        assertEquals(listOf("10.64.0.1"), r.get("dhcp.@dnsmasq[0].server"))
        // The backup is of the TRUE original and is never overwritten by a later run.
        assertTrue(firstBackup.contentEquals(File(r.dir, "backup/config.tgz").readBytes()))
    }

    @Test fun setupFailsCleanlyWithoutUci() {
        val r = newRouter() ?: return
        File(r.dir, "bin/uci").delete()
        val (code, out) = run(r, RouterScripts.setup(plan()))
        assertEquals(1, code)
        assertTrue(out, out.contains("PHONEHOP_ERROR"))
    }

    @Test fun revertRestoresTheEntireOriginalConfiguration() {
        val r = newRouter() ?: return
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        assertFalse(File(r.cfg, "custom_pkg").exists())
        val (code, out) = run(r, RouterScripts.revert())
        assertEquals(out, 0, code)
        assertTrue(out.contains("PHONEHOP_REVERTED"))
        assertEquals(listOf("custom_pkg", "dhcp", "dropbear", "firewall", "glconfig", "network", "wireless"), r.cfgFiles())
        assertEquals("USER custom\n", File(r.cfg, "custom_pkg").readText())
        assertEquals("USER glconfig\n", File(r.cfg, "glconfig").readText())
        assertEquals("ORIGINAL network\n", File(r.cfg, "network").readText())
        assertEquals("ORIGINAL wireless\n", File(r.cfg, "wireless").readText())
        assertFalse(File(r.dir, "pending").exists())
    }

    @Test fun confirmCancelsTheRollback() {
        val r = newRouter() ?: return
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        assertTrue(File(r.dir, "pending").exists())
        val (code, out) = run(r, RouterScripts.confirm())
        assertEquals(0, code)
        assertTrue(out.contains("PHONEHOP_CONFIRMED"))
        assertFalse(File(r.dir, "pending").exists())
    }

    @Test fun statusReportsPendingState() {
        val r = newRouter() ?: return
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        val markers = RouterScripts.parseMarkers(run(r, RouterScripts.status()).second)
        assertEquals("1", markers["PENDING"])
        assertNotNull(markers["NOW"])
    }

    @Test fun identityReportsModelAndHardwareIds() {
        val r = newRouter() ?: return
        val sys = File(r.dir, "sys/class/net/br-lan").apply { mkdirs() }
        File(sys, "address").writeText("94:83:c4:0a:1b:2c\n")
        val proc = File(r.dir, "proc/gl-hw-info").apply { mkdirs() }
        File(proc, "device_sn").writeText("SN12345678\n")
        File(r.dir, "bin/ubus").apply {
            writeText("#!/bin/sh\necho '{ \"model\": \"GL.iNet GL-AR750S-Ext\", \"board_name\": \"glinet,gl-ar750s\", \"release\": { \"description\": \"OpenWrt 22.03.4\" } }'\n")
            setExecutable(true)
        }
        r.env["PH_SYS"] = File(r.dir, "sys").path
        r.env["PH_PROC"] = File(r.dir, "proc").path
        val (code, out) = run(r, RouterScripts.identity())
        assertEquals(out, 0, code)
        val m = RouterScripts.parseMarkers(out)
        assertEquals("GL.iNet GL-AR750S-Ext", m["MODEL"])
        assertEquals("glinet,gl-ar750s", m["BOARD"])
        assertEquals("94:83:c4:0a:1b:2c", m["MAC"])
        assertEquals("SN12345678", m["SERIAL"])
    }

    @Test fun switchingServerChangesOnlyThePeerKey() {
        val r = newRouter() ?: return
        assertEquals(0, run(r, RouterScripts.setup(plan())).first)
        val newKey = "C".repeat(43) + "="
        val (code, out) = run(r, RouterScripts.server(newKey))
        assertEquals(out, 0, code)
        assertTrue(out.contains("PHONEHOP_SERVER_UPDATED"))
        assertEquals(listOf(newKey), r.get("network.mullvad_peer.public_key"))
        assertEquals(listOf("192.168.51.2"), r.get("network.mullvad_peer.endpoint_host")) // untouched
    }

    @Test fun switchingServerRefusesWhenNothingIsSetUp() {
        val r = newRouter() ?: return
        val (code, out) = run(r, RouterScripts.server("C".repeat(43) + "="))
        assertEquals(1, code)
        assertTrue(out, out.contains("not set up"))
    }

    @Test fun rejectsAWeakOrMissingAdminPassword() {
        assertNotNull(RouterScripts.validate(plan().copy(adminPassword = "short")))
        assertNotNull(RouterScripts.validate(plan().copy(adminPassword = "")))
        assertNull(RouterScripts.validate(plan()))
    }

    @Test fun rejectsABadServerKey() {
        try { RouterScripts.server("nope"); fail("expected rejection") } catch (_: IllegalArgumentException) {}
    }

    @Test fun clientModeHostsAHiddenLinkNetworkForThePhone() {
        val r = newRouter() ?: return
        val (code, out) = run(r, RouterScripts.setup(plan()))
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("PHONEHOP_APPLIED"))
        // no client-mode (join the phone) config; instead a hidden AP just for the phone
        assertFalse(r.has("wireless.phonehop_sta"))
        assertEquals(listOf("ap"), r.get("wireless.phonehop_link.mode"))
        assertEquals(listOf("radio1"), r.get("wireless.phonehop_link.device"))
        assertEquals(listOf("DIRECT-ph-hop"), r.get("wireless.phonehop_link.ssid"))
        assertEquals(listOf("phonehop-4821"), r.get("wireless.phonehop_link.key"))
        assertEquals(listOf("1"), r.get("wireless.phonehop_link.hidden"))
        assertEquals(listOf("1"), r.get("wireless.phonehop_link.dtim_period"))
        // a /30 with exactly one lease, and no gateway or DNS handed out
        assertEquals(listOf("192.168.51.1"), r.get("network.phonelink.ipaddr"))
        assertEquals(listOf("255.255.255.252"), r.get("network.phonelink.netmask"))
        assertEquals(listOf("1"), r.get("dhcp.phonelink.limit"))
        assertEquals(listOf("3", "6"), r.get("dhcp.phonelink.dhcp_option")) // experiment: no gateway, no DNS
        // the tunnel's endpoint is the phone's fixed address; only the phone may SSH in over the link
        assertEquals(listOf("192.168.51.2"), r.get("network.mullvad_peer.endpoint_host"))
        assertEquals(listOf("phonelink"), r.get("firewall.phonehop_ssh.src"))
        assertEquals(listOf("192.168.51.2"), r.get("firewall.phonehop_ssh.src_ip"))
        assertEquals(listOf("phonelink"), r.get("firewall.phonehop_linkzone.network"))
        // the kill switch is unchanged
        assertFalse(r.has("firewall.@forwarding[0]"))
        assertEquals(listOf("vpn"), r.get("firewall.phonehop_lan_vpn.dest"))
    }

    @Test fun clientModeRequiresTheFixedPhoneAddress() {
        assertNotNull(RouterScripts.validate(plan().copy(relayHost = "192.168.49.1")))
        assertNull(RouterScripts.validate(plan()))
    }

}
