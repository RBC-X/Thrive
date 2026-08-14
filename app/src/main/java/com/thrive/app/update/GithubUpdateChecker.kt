package com.thrive.app.update

import com.thrive.app.BuildConfig
import com.thrive.app.data.remote.UpdateInfo
import com.thrive.app.data.remote.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A GitHub release trimmed to what the updater needs. */
data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val apkUrl: String,
)

/**
 * Checks the canonical Thrive GitHub releases feed for a newer APK. Independent
 * of the self-hosted sync API, so updates work on any network.
 */
object GithubUpdateChecker {

    /** Owner/repo whose releases are the Thrive update channel. */
    const val REPO = "RBC-X/Thrive"

    /** Strips a leading `v` so tag `v1.2.5` compares cleanly against `1.2.5`. */
    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    suspend fun latestRelease(repo: String = REPO): GithubRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/$repo/releases/latest")
                .openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "Thrive-Android-Updater")
                conn.connectTimeout = 8_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parse(body)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Returns an UpdateInfo when a newer stable release with an APK exists. */
    suspend fun checkLatest(repo: String = REPO): UpdateInfo? {
        val release = latestRelease(repo) ?: return null
        if (release.draft || release.prerelease) return null
        val version = versionFromTag(release.tagName)
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo(
            versionName = version,
            apkUrl = release.apkUrl,
            notes = release.body.lines().map { it.trim() }.filter { it.isNotBlank() }.take(5),
        )
    }

    private fun parse(json: String): GithubRelease? {
        val root = JSONObject(json)
        val assets = root.optJSONArray("assets") ?: return null
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name", "").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url", "")
                break
            }
        }
        if (apkUrl.isBlank()) return null
        return GithubRelease(
            tagName = root.optString("tag_name", ""),
            name = root.optString("name", ""),
            body = root.optString("body", ""),
            prerelease = root.optBoolean("prerelease", false),
            draft = root.optBoolean("draft", false),
            apkUrl = apkUrl,
        )
    }
}
