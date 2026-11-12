package app.phonehop

/** The at-a-glance status card: what works, and what to do next. */
object StatusSummary {
    data class In(
        val hasKey: Boolean,
        val serverName: String?,
        val linkAddress: String?,
        val routerTitle: String?,
        val routerHeardSecondsAgo: Long?,
        val relayedBytes: Long,
    )

    fun build(i: In): String {
        val mullvad = when {
            !i.hasKey -> "\u2717 Register a key in the Mullvad card"
            i.serverName == null -> "\u2713 Key registered; choosing a server..."
            else -> "\u2713 Key registered, server ${i.serverName}"
        }
        val link = if (i.linkAddress != null) "\u2713 Phone joined the link (${i.linkAddress})" else "\u2022 Phone has not joined the link yet"
        val router = if (i.routerTitle != null) "\u2713 ${i.routerTitle}" else "\u2022 Not set up yet: use Router setup"
        val tunnel = when {
            i.routerHeardSecondsAgo != null && i.routerHeardSecondsAgo < 120 ->
                "\u2713 Relaying (router heard ${i.routerHeardSecondsAgo}s ago, ${TrafficMath.formatBytes(i.relayedBytes)} so far)"
            else -> "\u2022 No traffic from the router yet"
        }
        return "Mullvad  $mullvad\nLink     $link\nRouter   $router\nTunnel   $tunnel"
    }
}
