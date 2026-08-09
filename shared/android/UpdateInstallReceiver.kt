package com.example.PLACEHOLDER.update

// CANONICAL SOURCE - lives in BrighterThanASuperNova/apk-ci/shared/android/.
// Vendored into each app; CI fails the build if a copy drifts (line 1, the
// package declaration, is the only line allowed to differ).
//
// Declare in AndroidManifest.xml inside <application>:
//     <receiver android:name=".update.UpdateInstallReceiver" android:exported="false" />
// No intent-filter: AppUpdater targets it with an explicit Intent.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Receives PackageInstaller session status. The whole reason for using the
 * Session API over ACTION_VIEW is that this reports *why* an install failed -
 * in a pipeline where the human is the last gate, that is worth having.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system needs to show its install confirmation. Without
                // this branch nothing installs and there is no error either.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Log.w(TAG, "pending user action with no EXTRA_INTENT")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                cleanUp(context)
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "install failed: ${describe(status)} ($status) $msg")
                Toast.makeText(context, "Update failed: ${describe(status)}", Toast.LENGTH_LONG).show()
                cleanUp(context)
            }
        }
    }

    private fun cleanUp(context: Context) {
        runCatching { File(context.cacheDir, "update.apk").delete() }
    }

    private fun describe(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked by the device"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "signature mismatch or conflicting package"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible with this device"
        PackageInstaller.STATUS_FAILURE_INVALID -> "the APK is malformed"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "not enough storage"
        else -> "unknown error"
    }

    private companion object {
        const val TAG = "AppUpdater"
    }
}
