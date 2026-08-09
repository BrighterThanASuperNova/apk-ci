package com.example.PLACEHOLDER.update

// CANONICAL SOURCE - lives in BrighterThanASuperNova/apk-ci/shared/android/.
// Vendored into each app; CI fails the build if a copy drifts (line 1, the
// package declaration, is the only line allowed to differ).
//
// Deliberately dependency-free: HttpURLConnection + org.json + MessageDigest.
// These apps have zero third-party dependencies and that is worth keeping -
// nothing to R8-keep, fast resolution, no transitive surface for an agent to
// drag in. OkHttp would be ~800 KB for two GETs a day.
//
// Call from MainActivity:
//     override fun onResume() {
//         super.onResume()
//         AppUpdater.checkOnResume(this, BuildConfig.UPDATE_MANIFEST_URL)
//     }
// and from a "Check for updates" menu item:
//     AppUpdater.checkNow(this, BuildConfig.UPDATE_MANIFEST_URL)

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val PREFS = "app_updater"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_MISMATCHES = "consecutive_sha_mismatches"

    private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000  // 6 hours
    private const val CONNECT_TIMEOUT_MS = 15_000              // defaults are infinite;
    private const val READ_TIMEOUT_MS = 60_000                 // a dead network would wedge the thread
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val APK_CACHE_NAME = "update.apk"

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AppUpdater").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var inFlight = false

    /** Throttled, silent-on-failure. Safe to call from every onResume. */
    fun checkOnResume(activity: Activity, manifestUrl: String) =
        check(activity, manifestUrl, userInitiated = false)

    /** User tapped "Check for updates": ignores the throttle and surfaces errors. */
    fun checkNow(activity: Activity, manifestUrl: String) =
        check(activity, manifestUrl, userInitiated = true)

    private fun check(activity: Activity, manifestUrl: String, userInitiated: Boolean) {
        // Debug builds ship an empty URL, so local builds never nag.
        if (manifestUrl.isBlank()) return
        if (inFlight) return

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!userInitiated && now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return

        inFlight = true
        val ref = WeakReference(activity)
        val appContext = activity.applicationContext

        io.execute {
            // Defensive: a previous run may have died mid-install.
            runCatching { File(appContext.cacheDir, APK_CACHE_NAME).delete() }

            val result = runCatching {
                val body = httpGetText(cacheBust(manifestUrl))
                UpdateManifest.parse(body)
            }
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

            main.post {
                val a = ref.get() ?: run { inFlight = false; return@post }
                if (a.isFinishing || a.isDestroyed) { inFlight = false; return@post }
                inFlight = false

                result.onFailure { e ->
                    // An updater that nags on every subway ride is worse than none.
                    Log.i(TAG, "update check failed: ${e.message}")
                    if (userInitiated) toastLike(a, "Couldn't check for updates.\n${e.message}")
                }
                result.onSuccess { m ->
                    when {
                        m.applicationId != a.packageName -> {
                            // Wrong stream wired up: a stable app must never be
                            // offered a .preview APK, or vice versa.
                            Log.w(TAG, "feed is for ${m.applicationId}, this app is ${a.packageName}")
                            if (userInitiated) toastLike(a, "Update feed is misconfigured for this build.")
                        }
                        m.versionCode <= currentVersionCode(a) -> {
                            if (userInitiated) toastLike(a, "You're on the latest build (${m.versionName}).")
                        }
                        else -> promptUpdate(a, m)
                    }
                }
            }
        }
    }

    private fun promptUpdate(activity: Activity, m: UpdateManifest) {
        val notes = m.releaseNotes.lineSequence().firstOrNull()?.take(160).orEmpty()
        MaterialAlertDialogBuilder(activity)
            .setTitle("Update available")
            .setMessage(buildString {
                append("Install ").append(m.describe()).append("?")
                if (notes.isNotBlank()) append("\n\n").append(notes)
                if (m.sizeBytes > 0) append("\n\n").append(m.sizeBytes / 1024 / 1024).append(" MB")
            })
            .setNegativeButton("Later", null)
            .setPositiveButton("Update") { _, _ -> beginInstall(activity, m) }
            .show()
    }

    private fun beginInstall(activity: Activity, m: UpdateManifest) {
        // Re-check every time: Android 12+ permission hibernation can auto-revoke
        // this on an app you haven't opened in a while.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("One-time permission needed")
                .setMessage(
                    "Android needs permission for this app to install its own updates.\n\n" +
                        "You'll only be asked once."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    // Only ever navigate to Settings on an explicit tap.
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")
                        )
                    )
                }
                .show()
            return
        }
        download(activity, m)
    }

    private fun download(activity: Activity, m: UpdateManifest) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        val label = TextView(activity).apply {
            text = "Downloading ${m.versionName}..."
            gravity = Gravity.START
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
            addView(label)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Updating")
            .setView(layout)
            .setCancelable(false)
            .show()

        val ref = WeakReference(activity)
        val appContext = activity.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        io.execute {
            val target = File(appContext.cacheDir, APK_CACHE_NAME)
            val outcome = runCatching {
                val actualSha = downloadTo(m.apkUrl, target, m.sizeBytes) { pct ->
                    main.post { if (ref.get()?.isDestroyed == false) bar.progress = pct }
                }
                if (!actualSha.equals(m.sha256, ignoreCase = true)) {
                    target.delete()
                    val n = prefs.getInt(KEY_MISMATCHES, 0) + 1
                    prefs.edit().putInt(KEY_MISMATCHES, n).apply()
                    throw IllegalStateException(
                        if (n >= 3) "Downloaded file keeps failing verification ($n times). " +
                            "The published build may be corrupt."
                        else "Checksum mismatch"
                    )
                }
                prefs.edit().putInt(KEY_MISMATCHES, 0).apply()
                installSession(appContext, target)
            }

            main.post {
                runCatching { dialog.dismiss() }
                val a = ref.get() ?: return@post
                if (a.isFinishing || a.isDestroyed) return@post
                outcome.onFailure { e ->
                    Log.w(TAG, "update failed", e)
                    val n = prefs.getInt(KEY_MISMATCHES, 0)
                    // Silent for a one-off flake; surfaced once it is clearly a real problem.
                    if (n >= 3 || e !is IllegalStateException) {
                        toastLike(a, "Update failed.\n${e.message}")
                    }
                }
                // On success the system install dialog takes over from here.
            }
        }
    }

    /**
     * PackageInstaller session rather than FileProvider + ACTION_VIEW: it reports
     * *why* an install failed, needs no <provider> (two of these apps have none),
     * keeps the APK in cacheDir, and ACTION_INSTALL_PACKAGE is deprecated since 29.
     */
    private fun installSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            // Must be MUTABLE on 31+: the system fills in EXTRA_STATUS and friends.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    // ---- plumbing -----------------------------------------------------------

    private fun cacheBust(url: String): String =
        if (url.contains('?')) "$url&t=${System.currentTimeMillis()}"
        else "$url?t=${System.currentTimeMillis()}"

    private fun httpGetText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} for $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Streams to [target] and returns the hex SHA-256 of what was actually written. */
    private fun downloadTo(url: String, target: File, expectedBytes: Long, onProgress: (Int) -> Unit): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true   // github.com -> objects.githubusercontent.com, https->https
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode} downloading APK")
            }
            val total = if (expectedBytes > 0) expectedBytes else conn.contentLengthLong
            if (total > MAX_APK_BYTES) throw IllegalStateException("refusing $total byte download")

            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        if (written > MAX_APK_BYTES) throw IllegalStateException("download exceeded size cap")
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    /** A dialog rather than a Toast: FacebookGuard has no POST_NOTIFICATIONS and this needs no permission. */
    private fun toastLike(activity: Activity, message: String) {
        MaterialAlertDialogBuilder(activity)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
