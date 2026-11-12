package app.phonehop

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-updater. Looks at the latest GitHub release of [REPO], downloads its APK, checks the
 * SHA-256 published with the release, and hands it to PackageInstaller. Android itself
 * refuses any APK that is not signed with the same key as the installed app, so a tampered
 * download cannot replace the app even if the hash check were bypassed.
 *
 * All methods block; call them off the main thread.
 */
class Updater(private val context: Context) {

    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String,
    )

    class NoReleaseException : IOException("No releases published yet")

    fun currentVersion(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    /** Returns the latest release, or throws [NoReleaseException]. Does not compare versions. */
    fun fetchLatest(): Release {
        val json = try {
            JSONObject(httpGet("https://api.github.com/repos/$REPO/releases/latest"))
        } catch (e: FileNotFoundException) {
            throw NoReleaseException()
        }
        val tag = json.getString("tag_name")
        val assets = json.getJSONArray("assets")
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { apk = a; break }
        }
        apk ?: throw IOException("Release $tag has no .apk asset")
        val name = apk.getString("name")

        var sha = apk.optString("digest", "").removePrefix("sha256:")
        if (!SHA256_HEX.matches(sha)) {
            // Fall back to a "<apk>.sha256" asset published alongside it.
            sha = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "$name.sha256") {
                    sha = httpGet(a.getString("browser_download_url")).trim().substringBefore(' ').lowercase()
                }
            }
        }
        if (!SHA256_HEX.matches(sha)) throw IOException("Release $tag publishes no SHA-256 for $name; refusing to install")

        return Release(
            tag = tag,
            version = tag.removePrefix("v"),
            notes = json.optString("body", ""),
            apkName = name,
            apkUrl = apk.getString("browser_download_url"),
            size = apk.getLong("size"),
            sha256 = sha.lowercase(),
        )
    }

    fun isNewer(remote: String, local: String = currentVersion()): Boolean = versionIsNewer(remote, local)

    /** Whether the user has allowed this app to install packages ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Streams the APK straight into a PackageInstaller session while hashing it, aborts if the
     * hash differs, then commits. The result arrives later in [InstallResultReceiver].
     */
    fun downloadAndInstall(release: Release, onProgress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.size)
            // On Android 12+ an app that is the installer of record can update without a prompt.
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            var total = 0L
            try {
                if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
                session.openWrite("update.apk", 0, release.size).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            total += n
                            val pct = if (release.size > 0) (total * 100 / release.size).toInt() else 0
                            if (pct / 25 != lastPct / 25) { lastPct = pct; onProgress(pct) }
                        }
                    }
                    session.fsync(out)
                }
            } finally {
                conn.disconnect()
            }
            if (total != release.size) throw IOException("Size mismatch: got $total, expected ${release.size}")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != release.sha256) throw IOException("SHA-256 mismatch; refusing to install")

            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 404) throw FileNotFoundException(url)
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from $url")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** True if [remote] is a strictly higher major.minor.patch than [local]; a leading "v" and any "-suffix" are ignored. */
        fun versionIsNewer(remote: String, local: String): Boolean {
            val r = parse(remote)
            val l = parse(local)
            for (i in 0 until 3) if (r[i] != l[i]) return r[i] > l[i]
            return false
        }

        private fun parse(v: String): IntArray {
            val parts = v.trim().removePrefix("v").substringBefore('-').split('.')
            return IntArray(3) { parts.getOrNull(it)?.toIntOrNull() ?: 0 }
        }

        const val REPO = "amurikurthy/PhoneHop"
        const val ACTION_INSTALL_RESULT = "app.phonehop.INSTALL_RESULT"
        private const val USER_AGENT = "PhoneHop-Updater"
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

        /** Set by the UI to surface install results; called on an arbitrary thread. */
        @Volatile
        var statusListener: ((String) -> Unit)? = null
    }
}

/** Receives the outcome of a PackageInstaller commit. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val report = Updater.statusListener
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android needs the user to confirm (always the case for the first self-update).
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                report?.invoke("Android is asking you to confirm the update")
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> report?.invoke("Update installed")
            else -> report?.invoke("Install failed (status $status): ${message ?: "no details"}")
        }
    }
}
