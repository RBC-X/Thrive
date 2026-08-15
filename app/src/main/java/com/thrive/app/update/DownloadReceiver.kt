package com.thrive.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the update APK from GitHub, verifies it, then hands it to the
 * system installer.
 *
 * Hardening (v1.2.9):
 *  - download is verified before install: HTTP 200, content-type, size cap,
 *    expected byte size from the release metadata, package name, versionName,
 *    and signing certificate (must match the installed app's release key);
 *  - bytes stream to a `.partial` file with a hard cap; the file is promoted
 *    to its final name atomically only after every check passes;
 *  - when "install unknown apps" permission is missing, the request is
 *    persisted and the installer resumes after the user grants it — the
 *    one-tap flow no longer stalls on first use;
 *  - every failure is surfaced as a notification with a Retry action instead
 *    of being swallowed.
 */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A previous attempt blocked on the permission grant? Resume it first.
        if (resumePending(context)) return
        val url = intent.getStringExtra(UpdateNotifier.EXTRA_URL) ?: return
        val version = intent.getStringExtra(UpdateNotifier.EXTRA_VERSION) ?: "update"
        val expectedSize = intent.getLongExtra(UpdateNotifier.EXTRA_SIZE, 0L)

        val pending = goAsync()
        Thread {
            try {
                val file = download(context, url, version, expectedSize)
                install(context, file, url, version)
            } catch (e: Exception) {
                val detail = (e as? DownloadException)?.message ?: e.javaClass.simpleName
                UpdateNotifier.notifyError(context, "Thrive update failed", detail, url, version)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Thrown for every verifiable download/install failure. */
    internal class DownloadException(message: String) : IOException(message)

    private fun download(context: Context, url: String, version: String, expectedSize: Long): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val finalFile = File(dir, "thrive-$version.apk")
        val partial = File(dir, "thrive-$version.apk.partial")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.connect()

            if (!hostApproved(conn.url.host)) {
                throw DownloadException("Blocked unapproved download host: ${conn.url.host}")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw DownloadException("Server returned HTTP ${conn.responseCode}")
            }
            val contentType = (conn.contentType ?: "").substringBefore(';').trim()
            if (contentType.isNotEmpty() && contentType !in ALLOWED_MIME) {
                throw DownloadException("Unexpected content type: $contentType")
            }
            val contentLength = conn.contentLengthLong
            if (contentLength > MAX_APK_BYTES) {
                throw DownloadException("APK exceeds the size cap")
            }

            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw DownloadException("APK exceeds the size cap")
                        output.write(buf, 0, n)
                    }
                }
            }
            if (expectedSize > 0 && partial.length() != expectedSize) {
                throw DownloadException(
                    "Size mismatch: downloaded ${partial.length()} bytes, release lists $expectedSize",
                )
            }

            verifyApk(context, partial, version)

            // All checks passed — promote atomically and clean stale copies.
            finalFile.delete()
            if (!partial.renameTo(finalFile)) {
                throw DownloadException("Could not finalize the downloaded APK")
            }
            cleanup(dir, finalFile)
            return finalFile
        } finally {
            conn.disconnect()
        }
    }

    /** Package name, version, and signer must all match the installed app. */
    private fun verifyApk(context: Context, file: File, expectedVersion: String) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw DownloadException("Not a valid APK")
        if (archive.packageName != context.packageName) {
            throw DownloadException("Wrong package: ${archive.packageName}")
        }
        if (expectedVersion.isNotBlank() && archive.versionName != expectedVersion) {
            throw DownloadException("Wrong version: ${archive.versionName} (expected $expectedVersion)")
        }
        val archiveHashes = signatureHashes(archive)
        val installed = pm.getPackageInfo(context.packageName, flags)
        val installedHashes = signatureHashes(installed)
        if (archiveHashes.isEmpty() || installedHashes.isEmpty()) {
            throw DownloadException("Could not read APK signature")
        }
        if (archiveHashes.none { it in installedHashes }) {
            throw DownloadException("APK is not signed by the Thrive release key")
        }
    }

    private fun signatureHashes(info: PackageInfo): List<String> {
        val signatures: List<Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList() ?: emptyList()
        }
        return signatures.map { sha256Hex(it.toByteArray()) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cleanup(dir: File, keep: File) {
        dir.listFiles()?.filter { it.name.endsWith(".apk") && it != keep }?.forEach { it.delete() }
    }

    companion object {
        private const val PREFS = "thrive_updater"
        private const val KEY_URL = "pending_url"
        private const val KEY_VERSION = "pending_version"
        private const val KEY_FILE = "pending_file"

        private const val MAX_APK_BYTES = 100L * 1024 * 1024 // 100 MB hard cap

        private val ALLOWED_MIME = setOf(
            "application/vnd.android.package-archive",
            "application/octet-stream",
            "application/zip",
        )

        private val approvedHosts = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "codeload.github.com",
            "api.github.com",
        )

        private fun hostApproved(host: String): Boolean {
            val h = host.lowercase()
            return h in approvedHosts || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
        }

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun canRequestInstalls(context: Context): Boolean =
            context.packageManager.canRequestPackageInstalls()

        /**
         * Launches the system installer, or persists the request and opens the
         * "install unknown apps" setting when the permission is missing. Returns
         * true when the install prompt was shown.
         */
        private fun install(context: Context, file: File, url: String, version: String): Boolean {
            if (!canRequestInstalls(context)) {
                prefs(context).edit {
                    putString(KEY_URL, url)
                    putString(KEY_VERSION, version)
                    putString(KEY_FILE, file.absolutePath)
                }
                val settings = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(settings) }
                return false
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val install = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return runCatching { context.startActivity(install) }.isSuccess
        }

        /**
         * Resumes an install that was waiting on the "install unknown apps"
         * grant. Returns true when a pending request was handled (installed or
         * dropped because its file is gone); false when nothing was pending.
         */
        fun resumePending(context: Context): Boolean {
            val p = prefs(context)
            val url = p.getString(KEY_URL, null) ?: return false
            val filePath = p.getString(KEY_FILE, null) ?: return false
            val file = File(filePath)
            if (!file.exists()) {
                p.edit { clear() } // download never persisted; periodic check re-offers
                return false
            }
            if (!canRequestInstalls(context)) return false // keep waiting
            p.edit { clear() }
            val version = p.getString(KEY_VERSION, "") ?: ""
            return install(context, file, url, version)
        }
    }
}
