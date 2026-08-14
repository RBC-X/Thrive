package com.thrive.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the update APK from GitHub and fires the system install prompt. */
class DownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(UpdateNotifier.EXTRA_URL) ?: return
        val version = intent.getStringExtra(UpdateNotifier.EXTRA_VERSION) ?: "update"

        val pending = goAsync()
        Thread {
            try {
                val file = download(context, url, version)
                install(context, file)
            } catch (_: Exception) {
                // Download failed; the next periodic check will re-offer the update.
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun download(context: Context, url: String, version: String): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val file = File(dir, "thrive-$version.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
        return file
    }

    private fun install(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(install) }
    }
}
