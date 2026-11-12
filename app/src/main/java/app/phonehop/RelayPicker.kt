package app.phonehop

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Chooses the Mullvad relay closest to a point on Earth. */
object RelayPicker {
    data class Pick(val relay: MullvadApi.Relay, val km: Double)

    /** Relays within this many km of the nearest one count as the same metro area. */
    private const val METRO_KM = 25.0

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /**
     * Nearest city wins; within that metro area the relay with the most capacity (Mullvad's
     * weight) is chosen, preferring Mullvad-owned servers, then the lexically first hostname
     * so the choice is stable between runs.
     */
    fun nearest(relays: List<MullvadApi.Relay>, lat: Double, lon: Double): Pick? {
        if (relays.isEmpty()) return null
        val withDistance = relays.map { it to distanceKm(lat, lon, it.latitude, it.longitude) }
        val minKm = withDistance.minOf { it.second }
        val best = withDistance
            .filter { it.second <= minKm + METRO_KM }
            .sortedWith(
                compareByDescending<Pair<MullvadApi.Relay, Double>> { it.first.weight }
                    .thenByDescending { it.first.owned }
                    .thenBy { it.first.hostname }
            ).first()
        return Pick(best.first, best.second)
    }
}
