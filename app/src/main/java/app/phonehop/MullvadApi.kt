package app.phonehop

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thin client for the Mullvad endpoints the app needs. All calls block; run off the main thread. */
object MullvadApi {
    private const val BASE = "https://api.mullvad.net"

    class ApiException(val status: Int, val code: String?, message: String) : IOException(message)

    data class Relay(
        val hostname: String,
        val country: String,
        val city: String,
        val ipv4: String,
        val publicKey: String,
        val owned: Boolean,
        val provider: String,
        val latitude: Double,
        val longitude: Double,
        val weight: Int,
    )

    data class RelayList(val relays: List<Relay>, val gatewayV4: String, val portRanges: List<IntRange>)

    data class Device(
        val id: String,
        val name: String,
        val pubkey: String,
        val ipv4: String,
        val ipv6: String,
    )

    fun fetchRelays(): RelayList {
        val root = JSONObject(request("GET", "/public/relays/wireguard/v2"))
        val locations = root.getJSONObject("locations")
        val wg = root.getJSONObject("wireguard")
        val ranges = wg.getJSONArray("port_ranges").let { arr ->
            (0 until arr.length()).map { i ->
                val r = arr.getJSONArray(i)
                r.getInt(0)..r.getInt(1)
            }
        }
        val relays = wg.getJSONArray("relays").let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val r = arr.getJSONObject(i)
                if (!r.optBoolean("active", false)) return@mapNotNull null
                val loc = locations.optJSONObject(r.getString("location")) ?: return@mapNotNull null
                Relay(
                    hostname = r.getString("hostname"),
                    country = loc.getString("country"),
                    city = loc.getString("city"),
                    ipv4 = r.getString("ipv4_addr_in"),
                    publicKey = r.getString("public_key"),
                    owned = r.optBoolean("owned", false),
                    provider = r.optString("provider", ""),
                    latitude = loc.optDouble("latitude", 0.0),
                    longitude = loc.optDouble("longitude", 0.0),
                    weight = r.optInt("weight", 1),
                )
            }
        }.sortedWith(compareBy({ it.country }, { it.city }, { it.hostname }))
        return RelayList(relays, wg.optString("ipv4_gateway", "10.64.0.1"), ranges)
    }

    /** Approximate (latitude, longitude) of this connection's public IP, from Mullvad's own endpoint. */
    fun ipLocation(): Pair<Double, Double> {
        val o = JSONObject(request("GET", "https://am.i.mullvad.net/json"))
        return Pair(o.getDouble("latitude"), o.getDouble("longitude"))
    }

    /** Exchanges an account number for a short-lived access token. */
    fun login(accountNumber: String): String {
        val body = JSONObject().put("account_number", accountNumber).toString()
        return JSONObject(request("POST", "/auth/v1/token", body)).getString("access_token")
    }

    fun accountExpiry(token: String): String =
        JSONObject(request("GET", "/accounts/v1/accounts/me", bearer = token)).optString("expiry", "?")

    fun listDevices(token: String): List<Device> {
        val arr = JSONArray(request("GET", "/accounts/v1/devices", bearer = token))
        return (0 until arr.length()).map { parseDevice(arr.getJSONObject(it)) }
    }

    fun addDevice(token: String, pubkeyBase64: String): Device {
        val body = JSONObject().put("pubkey", pubkeyBase64).put("hijack_dns", false).toString()
        return parseDevice(JSONObject(request("POST", "/accounts/v1/devices", body, token)))
    }

    fun removeDevice(token: String, id: String) {
        request("DELETE", "/accounts/v1/devices/$id", bearer = token)
    }

    private fun parseDevice(o: JSONObject) = Device(
        id = o.getString("id"),
        name = o.optString("name", ""),
        pubkey = o.optString("pubkey", ""),
        ipv4 = o.optString("ipv4_address", ""),
        ipv6 = o.optString("ipv6_address", ""),
    )

    private fun request(method: String, path: String, body: String? = null, bearer: String? = null): String {
        val conn = URL(if (path.startsWith("http")) path else BASE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val code = runCatching { JSONObject(text).optString("code") }.getOrNull()?.ifEmpty { null }
                throw ApiException(status, code, "HTTP $status${code?.let { " $it" } ?: ""}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
