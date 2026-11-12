package app.phonehop

import android.content.SharedPreferences

/** Minimal string store so logic can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(prefix: String = ""): List<String>
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(prefix: String): List<String> = prefs.all.keys.filter { it.startsWith(prefix) }
}
