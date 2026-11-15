package app.phonehop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service that keeps the relay alive with the screen off. Holds a partial wake lock and a
 * low-latency Wi-Fi lock for as long as it runs.
 */
class KeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PhoneHop running", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("PhoneHop is running")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        // The user quit the app: if Android restarts this service afterwards (it is sticky), do not come back to life.
        if (getSharedPreferences("phonehop", Context.MODE_PRIVATE).getBoolean(PREF_USER_QUIT, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        BackgroundLog.started(this, restartedBySystem = intent == null)

        // The locks follow how much the tunnel is really used: none while searching for the router, the CPU lock while it is
        // connected, and the Wi-Fi lock too only while traffic is flowing.
        RelayRuntime.powerListener = { mode -> applyPower(mode) }
        RelayRuntime.start(applicationContext)
        applyPower(RelayRuntime.mode)
        LinkRuntime.start(applicationContext)
        return START_STICKY
    }

    /** Holds or drops the CPU wake lock and the low-latency Wi-Fi lock to match [mode]. */
    @Suppress("DEPRECATION")
    @Synchronized
    private fun applyPower(mode: PowerMode) {
        if (!running) return
        if (PowerPolicy.wantsWakeLock(mode)) {
            val lock = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneHop:keepalive").apply { setReferenceCounted(false) }.also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } else wakeLock?.takeIf { it.isHeld }?.release()

        if (PowerPolicy.wantsWifiLock(mode)) {
            val lock = wifiLock ?: (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PhoneHop:wifi").apply { setReferenceCounted(false) }.also { wifiLock = it }
            if (!lock.isHeld) lock.acquire()
        } else wifiLock?.takeIf { it.isHeld }?.release()
    }

    override fun onDestroy() {
        running = false
        RelayRuntime.powerListener = null
        BackgroundLog.stopped(
            this,
            if (getSharedPreferences("phonehop", Context.MODE_PRIVATE).getBoolean(PREF_USER_QUIT, false)) "you quit the app"
            else "Android or the system stopped it"
        )
        RelayRuntime.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        /** Set by Quit so a sticky restart after the process is gone does not revive the service; cleared when the app opens. */
        const val PREF_USER_QUIT = "user_quit"

        /** True while the service is running; Quit waits for this to clear before ending the process. */
        @Volatile var running = false
            private set

        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            context.startForegroundService(Intent(context, KeepAliveService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, KeepAliveService::class.java))
    }
}

