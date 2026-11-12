package app.phonehop

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * Guided router setup: Prepare, Your Wi-Fi, Router, Review, Apply. It finds and identifies the
 * router, erases its configuration and rebuilds it for this phone, then watches the phone join the
 * router's link network and the tunnel come up before confirming the change. After that it
 * keeps the router profile and manages the router (status, server switch, revert).
 */
class RouterWizard(
    private val activity: Activity,
    private val ui: Ui,
    private val prefs: SharedPreferences,
    private val sshStore: KeyValueStore,
    private val profiles: ProfileStore,
    private val secrets: SecretStore,
    private val log: (String) -> Unit,
    private val inputs: () -> Inputs,
    private val registerKey: () -> Unit,
    private val onProfileChanged: () -> Unit,
) {
    data class Inputs(
        val linkSsid: String, val linkPass: String,
        val relay: MullvadApi.Relay?, val linkUp: Boolean,
    )

    private enum class Step(val label: String) {
        PREPARE("Prepare"), WIFI("Your network"), ROUTER("Router"), REVIEW("Review"), APPLY("Apply")
    }

    /** Add this to the screen; it redraws itself as the steps change. */
    val view = FrameLayout(activity)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var step = Step.PREPARE
    private var updating = false          // re-running setup for an already paired router
    private var wifiName = prefs.getString(PREF_WIFI_NAME, null) ?: "PhoneHop"
    private var wifiPass = prefs.getString(PREF_WIFI_PASS, null)
        ?: PassphraseGenerator.generate(16).also { prefs.edit().putString(PREF_WIFI_PASS, it).apply() }
    private var lanText = prefs.getString(PREF_LAN, null) ?: LanPlan.DEFAULT
    private var routerHost = prefs.getString(PREF_ROUTER_HOST, "") ?: ""
    private var adminPassword = ""        // the router's CURRENT admin password, kept in memory only
    private var newAdminPassword = PassphraseGenerator.generate(16)  // what the setup will set it to
    private var identity: RouterIdentity? = null
    private var probeText = ""
    private var problem = ""
    private var busy = false
    private var applying = false
    private val doneLines = mutableListOf<String>()
    private var currentLine = ""
    private var finished = false
    private var pairedStatus = ""

    fun render() = main.post { draw() }

    private fun draw() {
        view.removeAllViews()
        val paired = profiles.active()
        val card = when {
            paired != null && !updating && !applying -> pairedCard(paired)
            else -> when (step) {
                Step.PREPARE -> prepareCard()
                Step.WIFI -> wifiCard()
                Step.ROUTER -> routerCard()
                Step.REVIEW -> reviewCard()
                Step.APPLY -> applyCard()
            }
        }
        view.addView(card)
    }

    private fun subtitle(extra: String? = null): String {
        val n = Step.values().indexOf(step) + 1
        val steps = Step.values().joinToString("  ") { if (it == step) "[${it.label}]" else it.label }
        return "Step $n of ${Step.values().size}: $steps" + (extra?.let { "\n$it" } ?: "")
    }

    /** The phone's fixed address on the router's link network. */
    private fun phoneLink() = Link.PHONE_IP

    private fun problemView() = ui.caption(problem).apply {
        setTextColor(Glass.RED)
        visibility = if (problem.isBlank()) View.GONE else View.VISIBLE
    }

    private fun go(to: Step) { problem = ""; step = to; draw() }

    // ---------------------------------------------------------------- 1. Prepare

    private fun prepareCard(): View {
        val i = inputs()
        val hasKey = prefs.getString("priv", null) != null && prefs.getString("ipv4", null) != null
        fun line(ok: Boolean?, text: String) = ui.mono(
            (when (ok) { true -> "✓ "; false -> "✗ "; null -> "• " }) + text, 12f
        )
        val ready = hasKey && i.relay != null
        val kids = mutableListOf<View>(
            line(hasKey, if (hasKey) "Mullvad key registered" else "Mullvad key not registered yet"),
            line(i.relay != null, i.relay?.let { "VPN server: ${it.hostname} (${it.city})" } ?: "No VPN server chosen yet"),
            line(if (i.linkUp) true else null, if (i.linkUp) "Phone is on the link network (${phoneLink()})" else "Phone joins the link network after the router is set up"),
        )
        if (!hasKey) {
            kids += ui.caption("Enter your Mullvad account number in the Mullvad card above, then register this phone.")
            kids += ui.secondary("Register key now") { registerKey(); main.postDelayed({ draw() }, 4_000) }
        }
        kids += ui.row(
            ui.secondary("Check again") { draw() },
            ui.primary("Next") { if (ready) go(Step.WIFI) else { problem = "Finish the items marked with a cross first."; draw() } }
                .apply { if (!ready) alpha = 0.45f },
        )
        kids += problemView()
        return ui.card(
            "Router setup", subtitle("Gets a router ready to send everything through Mullvad via this phone."), *kids.toTypedArray()
        )
    }

    // ---------------------------------------------------------------- 2. Your Wi-Fi

    private fun wifiCard(): View {
        val name = ui.field("PhoneHop", wifiName)
        val pass = ui.field("8 to 63 characters", wifiPass)
        val lan = ui.field(LanPlan.DEFAULT, lanText)
        val newAdmin = ui.field("8 to 63 characters", newAdminPassword)
        ui.watch(newAdmin) { newAdminPassword = it }
        val preview = ui.mono("", 12f)
        fun refreshPreview() {
            val (a, b) = RouterScripts.wifiNames(name.text.toString().trim())
            val parsed = LanPlan.parse(lan.text.toString())
            val range = parsed?.takeIf { LanPlan.validate(it, LanPlan.Lan(phoneLink(), 24)) == null }
                ?.let { LanPlan.dhcpRange(it) }?.let { "Devices get ${it.first} to ${it.second}" } ?: "Enter a valid range"
            preview.text = "5 GHz:    $a\n2.4 GHz:  $b\nSame password on both.\n$range"
        }
        refreshPreview()
        ui.watch(name) { wifiName = it.trim(); prefs.edit().putString(PREF_WIFI_NAME, wifiName).apply(); refreshPreview() }
        ui.watch(pass) { wifiPass = it; prefs.edit().putString(PREF_WIFI_PASS, it).apply() }
        ui.watch(lan) { lanText = it.trim(); prefs.edit().putString(PREF_LAN, lanText).apply(); refreshPreview() }
        return ui.card(
            "Router setup", subtitle("This is the network your own devices will join. The router's old Wi-Fi and addressing are erased."),
            ui.labeled("Wi-Fi name", name, "Up to ${RouterScripts.MAX_WIFI_BASE_NAME} characters. _5G and _2.4G are added so each band is its own network."),
            ui.labeled("Wi-Fi password", pass, "A strong one is suggested. Change it if you like."),
            ui.labeled("New router admin password", newAdmin, "Set on the router, replacing the old one. A strong one is suggested; the phone keeps it, encrypted, to manage the router."),
            ui.labeled("Router IP address and range", lan, "The router's own address, e.g. 192.168.50.1/24. Avoid ${phoneLink().substringBeforeLast('.')}.x (the router link) and 10.64 to 10.127 (Mullvad)."),
            preview,
            ui.secondary("Suggest a new password") {
                wifiPass = PassphraseGenerator.generate(16)
                prefs.edit().putString(PREF_WIFI_PASS, wifiPass).apply()
                draw()
            },
            problemView(),
            ui.row(
                ui.secondary("Back") { go(Step.PREPARE) },
                ui.primary("Next") {
                    val parsed = LanPlan.parse(lanText)
                    val err = if (parsed == null) "Enter the router's address like 192.168.50.1/24." else RouterScripts.validate(
                        RouterPlan(
                            phoneSsid = "DIRECT-ph-x", phonePassphrase = "12345678", prefer5Ghz = true,
                            wgPrivateKey = "A".repeat(43) + "=", wgAddressV4 = "10.0.0.2/32", serverPublicKey = "A".repeat(43) + "=",
                            clientSsid = wifiName, clientPassphrase = wifiPass, adminPassword = newAdminPassword,
                            lanAddress = parsed.address, lanPrefix = parsed.prefix, relayHost = phoneLink(),
                        )
                    )
                    if (err != null) { problem = err; draw() } else go(Step.ROUTER)
                },
            ),
        )
    }

    // ---------------------------------------------------------------- 3. Router

    private fun routerCard(): View {
        val address = ui.field("192.168.8.1", routerHost)
        ui.watch(address) { routerHost = it.trim(); prefs.edit().putString(PREF_ROUTER_HOST, routerHost).apply() }
        val (passBox, pass) = ui.secretField("Router admin password", number = false)
        pass.setText(adminPassword)
        ui.watch(pass) { adminPassword = it }
        val status = ui.mono(probeText.ifBlank { "Not looked for yet." }, 12f)
        return ui.card(
            "Router setup",
            subtitle("Join the router's Wi-Fi, or plug into it with Ethernet, then let the app find it."),
            ui.labeled("Router address", address, "Found automatically; type it if the search finds nothing."),
            ui.secondary(if (busy) "Looking..." else "Find router") { if (!busy) findRouter() },
            status,
            ui.labeled("Router admin password", passBox, "The one you use to sign in to the router's admin page. Remembered encrypted after setup works."),
            ui.row(
                ui.secondary("Forget saved router identity") { RouterSsh(sshStore).forgetAll(); probeText = "Saved router identities cleared."; draw() },
            ),
            problemView(),
            ui.row(
                ui.secondary("Back") { go(Step.WIFI) },
                ui.primary(if (busy) "Checking..." else "Next") { if (!busy) checkRouter() },
            ),
        )
    }

    private fun findRouter() {
        busy = true; probeText = "Looking for your router..."; draw()
        worker.execute {
            val lines = mutableListOf<String>()
            var best: RouterDiscovery.Candidate? = null
            for (c in RouterDiscovery.candidates(activity)) {
                val p = RouterFingerprint.probe(c.host, open = RouterDiscovery.opener(c.network))
                val state = if (p.sshOpen) "SSH open" else "no SSH"
                lines += "${c.host}: ${p.kind.label} ($state), from ${c.source}"
                if (best == null && (p.kind != RouterFingerprint.Kind.UNKNOWN || p.sshOpen)) best = c
            }
            main.post {
                busy = false
                if (best != null) { routerHost = best.host; prefs.edit().putString(PREF_ROUTER_HOST, routerHost).apply() }
                probeText = if (lines.isEmpty()) "Nothing found. Join the router's Wi-Fi or plug in with Ethernet, then try again, or type its address."
                else lines.joinToString("\n")
                draw()
            }
        }
    }

    private fun checkRouter() {
        if (routerHost.isBlank()) { problem = "Enter or find the router's address."; draw(); return }
        if (adminPassword.isBlank()) { problem = "Enter the router's admin password."; draw(); return }
        busy = true; problem = ""; draw()
        val host = routerHost
        val pw = adminPassword
        worker.execute {
            try {
                val res = ssh(host).run(host, pw, RouterScripts.identity(), timeoutMs = 20_000)
                val id = RouterIdentity.fromMarkers(RouterScripts.parseMarkers(res.output))
                main.post {
                    busy = false
                    if (id.model.isBlank() && id.mac.isBlank()) {
                        problem = "Connected, but this does not look like an OpenWrt router (no model information)."
                    } else {
                        identity = id
                        go(Step.REVIEW); return@post
                    }
                    draw()
                }
            } catch (e: Exception) {
                main.post { busy = false; problem = friendly(e); draw() }
            }
        }
    }

    // ---------------------------------------------------------------- 4. Review

    private fun reviewCard(): View {
        val i = inputs()
        val id = identity
        val (w5, w24) = RouterScripts.wifiNames(wifiName)
        val summary = ui.mono(
            "Router:      ${id?.model?.ifBlank { "unknown model" }} ${id?.id?.let { "($it)" } ?: ""}\n" +
                "Address:     $routerHost\n" +
                "Your Wi-Fi:  $w5\n             $w24\n" +
                "Router IP:   $lanText\n" +
                "Admin pass:  new one (saved encrypted here)\n" +
                "Link network: ${i.linkSsid}\n" +
                "VPN server:  ${i.relay?.hostname ?: "none"}", 12f
        )
        val confirmText = ui.field("WIPE", "")
        val go = ui.danger("Wipe and set up router") { }.apply { alpha = 0.4f }
        ui.watch(confirmText) { go.alpha = if (it.trim() == "WIPE") 1f else 0.4f }
        go.setOnClickListener {
            if (confirmText.text.toString().trim() != "WIPE") { problem = "Type WIPE (capital letters) to confirm."; draw() }
            else startApply()
        }
        return ui.card(
            "Router setup", subtitle(),
            summary,
            ui.caption(
                "This ERASES the router's whole configuration: Wi-Fi networks and passwords, port forwards, VPN and DHCP settings, " +
                    "and the settings of installed packages. The router's LAN address returns to its default. " +
                    "The router keeps a backup of its old settings and puts them back by itself if the tunnel does not come up. " +
                    "Installed packages and the admin password are kept. If a safety check fails, nothing is changed."
            ),
            ui.labeled("Type WIPE to continue", confirmText),
            problemView(),
            ui.secondary("Preview the commands") { previewScript() },
            go,
            ui.secondary("Back") { go(Step.ROUTER) },
        )
    }

    private fun buildPlan(): Pair<RouterPlan?, String?> {
        val i = inputs()
        val relay = i.relay ?: return Pair(null, "Choose a VPN server first.")
        val priv = prefs.getString("priv", null)
        val v4 = prefs.getString("ipv4", null)
        if (priv == null || v4 == null) return Pair(null, "Register a Mullvad key first.")
        val lan = LanPlan.parse(lanText) ?: return Pair(null, "Enter the router's address like 192.168.50.1/24.")
        val country = activity.resources.configuration.locales[0].country.uppercase().takeIf { it.length == 2 } ?: ""
        val plan = RouterPlan(
            phoneSsid = i.linkSsid, phonePassphrase = i.linkPass, prefer5Ghz = true,
            wgPrivateKey = priv, wgAddressV4 = v4, wgAddressV6 = prefs.getString("ipv6", "").orEmpty(),
            serverPublicKey = relay.publicKey, clientSsid = wifiName, clientPassphrase = wifiPass, adminPassword = newAdminPassword, country = country,
            lanAddress = lan.address, lanPrefix = lan.prefix, relayHost = phoneLink(),
        )
        return Pair(plan, RouterScripts.validate(plan))
    }

    private fun previewScript() {
        val (plan, err) = buildPlan()
        if (plan == null || err != null) { problem = err ?: "Cannot build the setup."; draw(); return }
        val text = RouterScripts.setup(plan)
            .replace(plan.wgPrivateKey, "<private key hidden>")
            .replace(plan.phonePassphrase, "<phone link password hidden>")
            .replace(plan.clientPassphrase, "<your Wi-Fi password hidden>")
        val tv = ui.mono(text, 10f)
        val scroll = android.widget.ScrollView(activity).apply { addView(tv); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        AlertDialog.Builder(activity).setTitle("Commands that will run on the router").setView(scroll)
            .setPositiveButton("Close", null).show()
    }

    // ---------------------------------------------------------------- 5. Apply

    private fun applyCard(): View {
        val lines = ui.mono(
            (doneLines.map { "✓ $it" } + listOfNotNull(currentLine.takeIf { it.isNotBlank() && !finished }?.let { "… $it" }))
                .joinToString("\n"), 12f
        )
        val kids = mutableListOf<View>(lines, problemView())
        if (finished && problem.isBlank()) {
            val (w5, w24) = RouterScripts.wifiNames(wifiName)
            kids += ui.caption("All done. Join $w5 or $w24 on your devices.")
            kids += ui.primary("Finish") { applying = false; updating = false; step = Step.PREPARE; draw() }
        } else if (!applying) {
            kids += ui.row(
                ui.secondary("Back") { applying = false; go(Step.REVIEW) },
                ui.danger("Revert router") { revertRouter() },
            )
        }
        return ui.card("Router setup", subtitle(), *kids.toTypedArray())
    }

    private fun say(text: String) = main.post { currentLine = text; if (step == Step.APPLY) draw() }
    private fun done(text: String) = main.post { doneLines += text; currentLine = ""; if (step == Step.APPLY) draw() }

    private fun startApply() {
        val (plan, err) = buildPlan()
        if (plan == null || err != null) { problem = err ?: "Cannot build the setup."; draw(); return }
        val host = routerHost
        val pw = adminPassword
        val id = identity
        val relay = inputs().relay!!
        applying = true; finished = false; problem = ""; doneLines.clear(); currentLine = "Connecting to the router..."
        step = Step.APPLY; draw()
        log("Router setup started on $host")
        worker.execute {
            try {
                say("Backing up the current configuration, erasing it and rebuilding for this phone...")
                val res = ssh(host).run(host, pw, RouterScripts.setup(plan), timeoutMs = 90_000)
                val m = RouterScripts.parseMarkers(res.output)
                val errors = res.output.lines().filter { it.startsWith("PHONEHOP_ERROR:") }.map { it.removePrefix("PHONEHOP_ERROR:").trim() }
                if (res.exitCode != 0 || !res.output.contains("PHONEHOP_APPLIED")) {
                    val wiped = res.output.contains("PHONEHOP_WIPING")
                    fail(
                        (errors.firstOrNull() ?: "The router reported a problem (exit ${res.exitCode}).") +
                            if (wiped) " The router may be half-reset: use Revert router." else " Nothing was changed."
                    )
                    return@execute
                }
                done("Backed up, erased and rebuilt the router")
                done("Your Wi-Fi: ${RouterScripts.wifiNames(wifiName).first} and ${RouterScripts.wifiNames(wifiName).second}")
                watchTunnel(plan, id, relay, plan.adminPassword)
            } catch (e: Exception) {
                fail(friendly(e))
            }
        }
    }

    /** Waits for the phone to join the router's link network and the tunnel to answer, then confirms the change. */
    private fun watchTunnel(plan: RouterPlan, id: RouterIdentity?, relay: MullvadApi.Relay, pw: String) {
        val deadline = System.currentTimeMillis() + 9 * 60_000L
        say("The router is restarting its network. The phone will join ${plan.phoneSsid} automatically...")
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(6_000)
            val addr = (if (RelayRuntime.linkAddress() != null) Link.ROUTER_IP else null)
            if (addr == null) { say("Waiting for the phone to join ${plan.phoneSsid} (it can take a minute). If Android asks about a Wi-Fi suggestion, tap Allow."); continue }
            try {
                val res = ssh(addr).run(addr, pw, RouterScripts.status(), timeoutMs = 20_000)
                val age = RouterScripts.handshakeAgeSeconds(RouterScripts.parseMarkers(res.output))
                if (age != null && age < 180) {
                    ssh(addr).run(addr, pw, RouterScripts.confirm(), timeoutMs = 20_000)
                    done("Phone joined the router's link network (router at $addr)")
                    done("Tunnel to Mullvad is up (handshake ${age}s ago)")
                    savePairing(id, relay, pw)
                    main.post { finished = true; draw() }
                    log("Router setup confirmed")
                    return
                }
                say("Phone is on the link network; waiting for the tunnel to Mullvad...")
            } catch (e: Exception) {
                say("Phone is on the link network, but the router's admin login is not reachable yet (${friendly(e)})")
            }
        }
        fail("The tunnel did not come up in time. The router will put its old settings back by itself within a few minutes.")
    }

    private fun fail(message: String) = main.post {
        problem = message; applying = false; finished = true; currentLine = ""; draw()
        log("Router setup failed: $message")
    }

    private fun savePairing(id: RouterIdentity?, relay: MullvadApi.Relay, pw: String) {
        val i = inputs()
        val pid = id?.id ?: "router-${System.currentTimeMillis() / 1000}"
        profiles.save(
            RouterProfile(
                id = pid, model = id?.model.orEmpty(), mac = id?.mac.orEmpty(), serial = id?.serial.orEmpty(),
                linkSsid = i.linkSsid, linkPassphrase = i.linkPass, bandIndex = 2,
                clientSsid = wifiName, createdAt = System.currentTimeMillis(),
                serverHost = relay.hostname, serverIp = relay.ipv4, serverKey = relay.publicKey, lanCidr = lanText,
            )
        )
        profiles.setActive(pid)
        runCatching { secrets.put("router_admin_$pid", pw) }
        adminPassword = ""
        main.post { onProfileChanged() }
    }

    // ---------------------------------------------------------------- Paired router

    private fun pairedCard(p: RouterProfile): View {
        val (w5, w24) = RouterScripts.wifiNames(p.clientSsid)
        val info = ui.mono(
            "Router:     ${p.title}\nID:         ${p.shortId}\n" +
                "Wi-Fi:      $w5\n            $w24\nRouter IP:  ${p.lanCidr.ifBlank { "unknown" }}\nLink network: ${p.linkSsid}\n" +
                "VPN server: ${p.serverHost}${if (p.stale) "\n\nThe link settings changed after setup; run setup again to update the router." else ""}",
            12f
        )
        val status = ui.mono(pairedStatus.ifBlank { "Tap Refresh status to check the tunnel." }, 12f)
        return ui.card(
            "Router", "Paired and managed by this phone.",
            info, status,
            ui.row(
                ui.secondary("Refresh status") { refreshStatus(p) },
                ui.danger("Forget router") { forget(p) },
            ),
            ui.secondary("Change Wi-Fi or re-run setup") { updating = true; identity = null; step = Step.WIFI; draw() },
            ui.danger("Revert router to original") { revertRouter() },
        )
    }

    private fun managementHost(): String? = (if (RelayRuntime.linkAddress() != null) Link.ROUTER_IP else null)

    private fun storedPassword(p: RouterProfile) = runCatching { secrets.get("router_admin_${p.id}") }.getOrNull()

    private fun refreshStatus(p: RouterProfile) {
        val host = managementHost()
        val pw = storedPassword(p)
        if (host == null) { pairedStatus = "The phone has not joined the router's link network right now."; draw(); return }
        if (pw == null) { pairedStatus = "The admin password is not saved. Re-run setup to save it."; draw(); return }
        pairedStatus = "Checking $host..."; draw()
        worker.execute {
            val text = try {
                val res = ssh(host).run(host, pw, RouterScripts.status(), timeoutMs = 20_000)
                val m = RouterScripts.parseMarkers(res.output)
                val age = RouterScripts.handshakeAgeSeconds(m)
                "Router reachable at $host.\n" + (age?.let { "Tunnel handshake $it s ago." } ?: "No tunnel handshake yet.") +
                    (if (m["PENDING"] == "1") "\nAutomatic rollback is still armed." else "")
            } catch (e: Exception) { "Could not check: ${friendly(e)}" }
            main.post { pairedStatus = text; draw() }
        }
    }

    /** Called when the user picks a different VPN server while a router is paired. */
    fun applyServer(relay: MullvadApi.Relay, done: (String) -> Unit) {
        val p = profiles.active() ?: return done("No router is paired.")
        val host = managementHost()
        val pw = storedPassword(p)
        if (host == null) return done("The phone has not joined the router's link network right now, so the router was not changed.")
        if (pw == null) return done("The router's admin password is not saved, so it was not changed.")
        worker.execute {
            val msg = try {
                val res = ssh(host).run(host, pw, RouterScripts.server(relay.publicKey), timeoutMs = 30_000)
                if (res.output.contains("PHONEHOP_SERVER_UPDATED")) {
                    profiles.save(p.copy(serverHost = relay.hostname, serverIp = relay.ipv4, serverKey = relay.publicKey))
                    "Router now uses ${relay.hostname}."
                } else "The router did not accept the change: ${res.output.lines().lastOrNull { it.isNotBlank() }}"
            } catch (e: Exception) { "Could not update the router: ${friendly(e)}" }
            main.post { done(msg); onProfileChanged() }
        }
    }

    private fun revertRouter() {
        val p = profiles.active()
        val host = managementHost() ?: routerHost.takeIf { it.isNotBlank() }
        val pw = p?.let { storedPassword(it) } ?: adminPassword.takeIf { it.isNotBlank() }
        if (host == null || pw == null) {
            problem = "To revert, the app needs the router's address and admin password (use the Router step)."; draw(); return
        }
        AlertDialog.Builder(activity).setTitle("Revert the router?")
            .setMessage("The router's configuration goes back to exactly what it was before PhoneHop erased it. The phone will no longer join the link network.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Revert") { _, _ ->
                worker.execute {
                    val msg = try {
                        val res = ssh(host).run(host, pw, RouterScripts.revert(), timeoutMs = 30_000)
                        if (res.output.contains("PHONEHOP_REVERTED")) {
                            p?.let { profiles.delete(it.id); runCatching { secrets.clear("router_admin_${it.id}") } }
                            "Router restored to its original configuration."
                        } else "Revert failed: ${res.output.lines().lastOrNull { it.isNotBlank() }}"
                    } catch (e: Exception) { "Revert failed: ${friendly(e)}" }
                    main.post { pairedStatus = msg; problem = ""; applying = false; finished = false; updating = false; step = Step.PREPARE; log(msg); draw(); onProfileChanged() }
                }
            }.show()
    }

    private fun forget(p: RouterProfile) {
        AlertDialog.Builder(activity).setTitle("Forget this router?")
            .setMessage("The router keeps its current settings. The phone forgets it, unlocks the link settings and deletes the saved admin password.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Forget") { _, _ ->
                profiles.delete(p.id)
                runCatching { secrets.clear("router_admin_${p.id}") }
                pairedStatus = ""; step = Step.PREPARE
                draw(); onProfileChanged()
            }.show()
    }

    // ---------------------------------------------------------------- helpers

    private fun ssh(host: String) = RouterSsh(sshStore, RouterDiscovery.networkFor(activity, host), log = log)

    private fun friendly(e: Exception): String = when (e) {
        is RouterSsh.AuthException -> "The router rejected that admin password."
        is RouterSsh.HostKeyChangedException -> e.message ?: "The router's SSH identity changed."
        is java.net.ConnectException, is java.net.SocketTimeoutException -> "Could not reach the router (is its SSH server on?)."
        else -> e.message?.take(140) ?: e.javaClass.simpleName
    }

    private companion object {
        const val PREF_WIFI_NAME = "client_ssid"
        const val PREF_WIFI_PASS = "client_pass"
        const val PREF_ROUTER_HOST = "router_address"
        const val PREF_LAN = "lan_cidr"
    }
}
