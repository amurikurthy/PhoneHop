package app.phonehop

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot device location without Google Play services. Approximate (network) location is
 * plenty for choosing a VPN city, so only ACCESS_COARSE_LOCATION is required.
 */
class DeviceLocator(private val context: Context) {
    data class Fix(val lat: Double, val lon: Double, val source: String)

    fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFine() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Calls [onResult] exactly once, on an arbitrary thread; null means no location was available. */
    @SuppressLint("MissingPermission")
    fun locate(timeoutMs: Long = 8_000, onResult: (Fix?) -> Unit) {
        if (!hasPermission()) { onResult(null); return }
        val lm = context.getSystemService(LocationManager::class.java)
        try {
            // A fix from the last few hours is fine for picking a city.
            val cached = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { it != LocationManager.GPS_PROVIDER || hasFine() }
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < 6 * 3_600_000L }
                .maxByOrNull { it.time }
            if (cached != null) { onResult(Fix(cached.latitude, cached.longitude, "recent device location")); return }

            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                hasFine() && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> { onResult(null); return }
            }
            val done = AtomicBoolean(false)
            fun finish(loc: Location?) {
                if (done.compareAndSet(false, true)) onResult(loc?.let { Fix(it.latitude, it.longitude, "device location") })
            }
            Handler(Looper.getMainLooper()).postDelayed({ finish(null) }, timeoutMs)
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { finish(it) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location)
                    @Deprecated("Required on old API levels")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) = finish(null)
                }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            onResult(null)
        }
    }
}
