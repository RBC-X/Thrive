package com.thrive.app.update

import com.thrive.app.BuildConfig
import com.thrive.app.data.remote.UpdateInfo
import com.thrive.app.data.remote.isNewerVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    val apkSizeBytes: Long,
)

/**
 * Checks the canonical Thrive GitHub releases feed for a newer APK. Independent
 * of the self-hosted sync API, so updates work on any network.
 *
 * Hardening (v1.2.9): the APK is chosen by the canonical release artifact name
 * (`Thrive-<version>-release.apk` or `Thrive-release.apk`) — never just "any
 * .apk". Tags must be clean semver. The response body is size-capped, and the
 * final download host must be an approved GitHub host.
 */
object GithubUpdateChecker {

    /** Owner/repo whose releases are the Thrive update channel. */
    const val REPO = "RBC-X/Thrive"

    /** Max bytes of the GitHub API response we will read. */
    private const val MAX_BODY_BYTES = 1_048_576 // 1 MB

    /** Asset name under which tools/tunnel.sh publishes the live sync URL. */
    const val SYNC_URL_ASSET = "thrive-sync-url.txt"

    private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")

    /** Approved hosts for release metadata and APK downloads (incl. redirects). */
    private val approvedHosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com")

    /** Strips a leading `v` so tag `v1.2.5` compares cleanly against `1.2.5`. */
    fun versionFromTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun isCleanSemver(version: String): Boolean = SEMVER.matches(version)

    fun hostApproved(host: String): Boolean = host.lowercase() in approvedHosts ||
        host.lowercase().endsWith(".github.com")

    /**
     * Normalizes a sync-server URL discovered from the release asset: only
     * https:// is accepted (backup codes are sent to it), trailing slashes are
     * stripped. Returns null for anything else (http, garbage, empty).
     */
    fun sanitizeSyncUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://")) return null
        if (trimmed.length > 512) return null
        return trimmed.removeSuffix("/")
    }

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
                if (!hostApproved(conn.url.host)) return@runCatching null
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.use { it.readBytesBounded(MAX_BODY_BYTES) }
                    ?: return@runCatching null
                parse(body.toString(Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Returns an UpdateInfo when a newer stable release with a canonical APK exists. */
    suspend fun checkLatest(repo: String = REPO): UpdateInfo? {
        val release = latestRelease(repo) ?: return null
        if (release.draft || release.prerelease) return null
        val version = versionFromTag(release.tagName)
        if (!isCleanSemver(version)) return null
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo(
            versionName = version,
            apkUrl = release.apkUrl,
            notes = cleanNotes(release.body),
            apkSizeBytes = release.apkSizeBytes,
        )
    }

    /**
     * Discovers the operator's current public backup server by reading the
     * `thrive-sync-url.txt` asset attached to the latest GitHub release
     * (published by tools/tunnel.sh). Returns a validated https:// URL, or
     * null when no release advertises one. This is how ordinary users connect
     * without typing IPs: Settings shows a one-tap "Connect" card.
     */
    suspend fun discoverSyncServer(repo: String = REPO): String? = withContext(Dispatchers.IO) {
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
                if (!hostApproved(conn.url.host)) return@runCatching null
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.use { it.readBytesBounded(MAX_BODY_BYTES) }
                    ?: return@runCatching null
                val root = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
                    ?: return@runCatching null
                if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) {
                    return@runCatching null
                }
                val assets = root.optJSONArray("assets") ?: return@runCatching null
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name", "") == SYNC_URL_ASSET) {
                        url = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                        break
                    }
                }
                val target = url ?: return@runCatching null
                val host = runCatching { URL(target).host }.getOrNull() ?: return@runCatching null
                if (!hostApproved(host)) return@runCatching null
                val fetch = URL(target).openConnection() as HttpURLConnection
                try {
                    fetch.requestMethod = "GET"
                    fetch.connectTimeout = 8_000
                    fetch.readTimeout = 15_000
                    fetch.instanceFollowRedirects = true
                    if (fetch.responseCode !in 200..299) return@runCatching null
                    val content = fetch.inputStream.use { it.readBytesBounded(2_048) }
                        ?: return@runCatching null
                    val value = sanitizeSyncUrl(content.toString(Charsets.UTF_8))
                        ?: return@runCatching null
                    value
                } finally {
                    fetch.disconnect()
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Turns a GitHub release body into plain bullet lines for the dialog. */
    private fun cleanNotes(body: String): List<String> {
        return body.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") } // drop markdown headers
            .map { it.removePrefix("- ").removePrefix("* ").trim() } // strip bullet markers
            .map { it.replace("**", "").trim() } // strip bold markers
            .filter { it.isNotBlank() }
            .take(5)
    }

    /**
     * Picks the APK by canonical name for [tagVersion] (e.g. `1.2.9` →
     * `Thrive-1.2.9-release.apk`, falling back to `Thrive-release.apk`).
     * Anything else (debug/test/random names) is rejected.
     */
    internal fun pickApkAsset(assets: JSONArray, tagVersion: String): Pair<String, Long>? {
        val canonical = "Thrive-$tagVersion-release.apk"
        val generic = "Thrive-release.apk"
        var fallbackUrl: String? = null
        var fallbackSize = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            if (name != canonical && name != generic) continue
            if (name == canonical) {
                return asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
                    ?.let { it to asset.optLong("size", 0L) }
            }
            fallbackUrl = asset.optString("browser_download_url", "").takeIf { it.isNotBlank() }
            fallbackSize = asset.optLong("size", 0L)
        }
        return fallbackUrl?.let { it to fallbackSize }
    }

    private fun parse(json: String): GithubRelease? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tagName = root.optString("tag_name", "")
        val version = versionFromTag(tagName)
        if (!isCleanSemver(version)) return null
        val assets = root.optJSONArray("assets") ?: return null
        val picked = pickApkAsset(assets, version) ?: return null
        val apkUrl = picked.first
        val apkSize = picked.second
        // Validate the download URL is an approved host now (redirects are
        // validated again at download time by DownloadReceiver).
        val host = runCatching { URL(apkUrl).host }.getOrNull() ?: return null
        if (!hostApproved(host)) return null
        return GithubRelease(
            tagName = tagName,
            name = root.optString("name", ""),
            body = root.optString("body", ""),
            prerelease = root.optBoolean("prerelease", false),
            draft = root.optBoolean("draft", false),
            apkUrl = apkUrl,
            apkSizeBytes = apkSize,
        )
    }
}

/** Reads up to [max] bytes; returns null when the stream exceeds the cap. */
internal fun java.io.InputStream.readBytesBounded(max: Int): ByteArray? {
    val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
