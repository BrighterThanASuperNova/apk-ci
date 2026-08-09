package com.example.PLACEHOLDER.update

// CANONICAL SOURCE - lives in BrighterThanASuperNova/apk-ci/shared/android/.
// Vendored into each app; CI fails the build if a copy drifts (line 1, the
// package declaration, is the only line allowed to differ).

import org.json.JSONObject

/**
 * One entry from the drops feed, e.g.
 * https://github.com/BrighterThanASuperNova/apk-drops/releases/download/appblocker-preview/appblocker-preview.json
 */
data class UpdateManifest(
    val schema: Int,
    val app: String,
    val channel: String,
    val applicationId: String,
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val minSdk: Int,
    val branch: String,
    val commit: String,
    val publishedAt: String,
    val releaseNotes: String
) {
    /** e.g. "preview - opencode/dark-mode - a3f19c2", so you always know what you are holding. */
    fun describe(): String =
        if (channel == "stable") "$versionName ($channel)"
        else "$versionName - $branch - $commit"

    companion object {
        const val SUPPORTED_SCHEMA = 1

        fun parse(json: String): UpdateManifest {
            val o = JSONObject(json)
            val schema = o.optInt("schema", 0)
            require(schema == SUPPORTED_SCHEMA) {
                "unsupported manifest schema $schema (this build understands $SUPPORTED_SCHEMA)"
            }
            return UpdateManifest(
                schema = schema,
                app = o.getString("app"),
                channel = o.getString("channel"),
                applicationId = o.getString("applicationId"),
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = o.getString("apkUrl"),
                sizeBytes = o.optLong("sizeBytes", -1L),
                sha256 = o.getString("sha256").lowercase(),
                minSdk = o.optInt("minSdk", 0),
                branch = o.optString("branch", ""),
                commit = o.optString("commit", ""),
                publishedAt = o.optString("publishedAt", ""),
                releaseNotes = o.optString("releaseNotes", "")
            )
        }
    }
}
