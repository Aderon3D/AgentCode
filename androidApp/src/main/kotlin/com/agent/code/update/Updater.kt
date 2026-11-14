package com.agent.code.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.agent.code.opencode.OpenCodeClient
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub releases.
 *
 * CI publishes `AgentCode-r<NN>.apk` for every push to main, tagged `r<NN>` from
 * [OpenCodeClient.APP_BUILD]. A cold start looks for a newer tag, downloads the
 * APK into cache and commits a PackageInstaller session — the system shows its
 * one-tap update dialog, then replaces the app.
 *
 * ponytail: launch-only check (one GET per cold start, no WorkManager and no
 * polling); installs are user-confirmed by the platform. The APK must stay
 * signed with the same key as before (CI uses the old debug key from secrets),
 * otherwise Android rejects the update as INSTALL_FAILED_UPDATE_INCOMPATIBLE.
 */
object Updater {

    private const val RELEASES_API =
        "https://api.github.com/repos/Aderon3D/AgentCode/releases?per_page=10"

    /** Download URL + tag of the newest `r<NN>` release newer than this build. */
    private data class Release(val tag: String, val code: Int, val apkUrl: String)

    /**
     * What the banner shows: null = nothing to say, otherwise one line.
     * A silent updater is undebuggable on a device with no adb, so every check
     * leaves a visible trace (including failures) for a few seconds.
     */
    val status = MutableStateFlow<String?>(null)

    // Foregrounding is the cheapest retry trigger: a cold start often fires
    // before the radio is up, so one launch-time attempt is not enough.
    private var lastCheckMs = 0L

    fun checkIfDue(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < 10 * 60_000) return
        lastCheckMs = now
        check(context)
    }

    @Suppress("TooGenericExceptionCaught") // update path is best-effort: any failure lands in the banner
    fun check(context: Context) {
        status.value = "Checking for updates…"
        Thread {
            var lastError: Exception? = null
            for (attempt in 0 until 3) {
                try {
                    val current = OpenCodeClient.APP_BUILD.removePrefix("r").toIntOrNull()
                        ?: error("bad build id ${OpenCodeClient.APP_BUILD}")
                    val release = latestNewer(current)
                    if (release == null) {
                        status.value = "Up to date (${OpenCodeClient.APP_BUILD})"
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (status.value == "Up to date (${OpenCodeClient.APP_BUILD})") status.value = null
                        }, 4000)
                        return@Thread
                    }
                    status.value = "Update ${release.tag} available — downloading…"
                    val apk = File(context.cacheDir, "update-${release.tag}.apk")
                    download(release.apkUrl, apk)
                    status.value = "Confirm the install dialog for ${release.tag}"
                    install(context, apk)
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(1500L * (attempt + 1))
                }
            }
            status.value = "Update check failed (${OpenCodeClient.APP_BUILD}): " +
                "${lastError?.let { it::class.simpleName }}: ${lastError?.message}"
        }.start()
    }

    private fun latestNewer(current: Int): Release? {
        val json = JSONArray(fetch(RELEASES_API))
        var best: Release? = null
        for (i in 0 until json.length()) {
            val rel = json.getJSONObject(i)
            val tag = rel.optString("tag_name")
            val code = Regex("^r(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (code <= current) continue
            if (best != null && code <= best.code) continue
            val assets = rel.optJSONArray("assets") ?: continue
            val apkUrl = (0 until assets.length())
                .map { assets.getJSONObject(it).optString("browser_download_url") }
                .firstOrNull { it.endsWith(".apk") } ?: continue
            best = Release(tag, code, apkUrl)
        }
        return best
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "AgentCode-Updater")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "AgentCode-Updater")
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }
}

/** Terminal callback for the install session; the system owns the confirmation UI. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") // typed getParcelableExtra needs API 33
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "AgentCode updated", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(
                context,
                "Update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
