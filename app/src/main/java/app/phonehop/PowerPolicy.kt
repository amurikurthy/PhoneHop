package app.phonehop

/**
 * How much the app keeps the phone awake. Low latency costs battery and heat, so it is only paid for while the tunnel is
 * really in use.
 *  - [SEARCHING]: the router link is not joined. Nothing is held: no wake lock, no Wi-Fi lock, no keep-warm packets.
 *  - [IDLE]: the router is connected but only WireGuard keepalives are passing. The CPU wake lock stays (so keepalives are
 *    answered), but the Wi-Fi lock and the keep-warm packets are off and the radios may sleep between keepalives.
 *  - [ACTIVE]: real traffic passed recently. Everything that keeps latency low is on.
 */
enum class PowerMode(val label: String, val description: String) {
    SEARCHING("searching", "Looking for the router. Nothing is kept awake."),
    IDLE("idle", "Router connected, no traffic. Low power."),
    ACTIVE("active", "Router in use. Kept awake for low latency."),
}

object PowerPolicy {
    /** How long after the last real packet the link still counts as in use. */
    const val ACTIVE_WINDOW_MS = 120_000L

    fun decide(linkJoined: Boolean, msSinceData: Long?): PowerMode = when {
        !linkJoined -> PowerMode.SEARCHING
        msSinceData != null && msSinceData < ACTIVE_WINDOW_MS -> PowerMode.ACTIVE
        else -> PowerMode.IDLE
    }

    fun wantsWakeLock(m: PowerMode) = m != PowerMode.SEARCHING
    fun wantsWifiLock(m: PowerMode) = m == PowerMode.ACTIVE
    fun wantsKeepWarm(m: PowerMode) = m == PowerMode.ACTIVE
}

/** A one-line-per-fact record of the background service, kept on disk so the app can show what really happened. */
object BackgroundLog {
    private const val FILE = "phonehop"

    fun started(context: android.content.Context, restartedBySystem: Boolean) {
        val p = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        p.edit().putLong("bg_started", System.currentTimeMillis()).putLong("bg_stopped", 0L)
            .putInt("bg_system_restarts", p.getInt("bg_system_restarts", 0) + if (restartedBySystem) 1 else 0).apply()
    }

    fun stopped(context: android.content.Context, reason: String) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit()
            .putLong("bg_stopped", System.currentTimeMillis()).putString("bg_reason", reason).apply()
    }

    /** Clears the restart counter once the person has seen it (when they open the app). */
    fun acknowledgeRestarts(context: android.content.Context) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putInt("bg_system_restarts", 0).apply()
    }

    fun summary(context: android.content.Context, running: Boolean, mode: PowerMode?, now: Long = System.currentTimeMillis()): String {
        val p = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return format(
            startedAt = p.getLong("bg_started", 0L), stoppedAt = p.getLong("bg_stopped", 0L),
            reason = p.getString("bg_reason", "") ?: "", systemRestarts = p.getInt("bg_system_restarts", 0),
            running = running, mode = mode, now = now, clock = { fmt.format(java.util.Date(it)) },
        )
    }

    fun format(
        startedAt: Long, stoppedAt: Long, reason: String, systemRestarts: Int,
        running: Boolean, mode: PowerMode?, now: Long, clock: (Long) -> String,
    ): String = buildString {
        if (running && startedAt > 0) {
            append("Background service: running since ${clock(startedAt)} (${duration(now - startedAt)}).")
            mode?.let { append("\nNow: ${it.description}") }
        } else if (stoppedAt > 0) {
            append("Background service: stopped at ${clock(stoppedAt)}${if (reason.isNotBlank()) " ($reason)" else ""}. Nothing is running in the background.")
        } else append("Background service: not running.")
        if (systemRestarts > 0) append("\nAndroid restarted it by itself $systemRestarts time${if (systemRestarts == 1) "" else "s"}.")
    }

    fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "under a minute"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
