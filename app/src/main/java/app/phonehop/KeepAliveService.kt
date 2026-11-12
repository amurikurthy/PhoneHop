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
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "PhoneHop running", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("PhoneHop is running")
            .setContentText("Keeping the link to your router alive")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_monochrome), "Stop",
                    PendingIntent.getService(
                        this, 1,
                        Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneHop:keepalive")
                .apply { setReferenceCounted(false); acquire() }
        }
        applyWifiLock()
        RelayRuntime.start(applicationContext)
        LinkRuntime.start(applicationContext)
        return START_STICKY
    }

    /** Low-latency Wi-Fi lock: keeps the Wi-Fi radio out of power-save while the relay runs. */
    @Suppress("DEPRECATION")
    private fun applyWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PhoneHop:wifi").apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        RelayRuntime.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "app.phonehop.STOP"
        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) =
            context.startForegroundService(Intent(context, KeepAliveService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, KeepAliveService::class.java))
    }
}

