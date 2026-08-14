package com.thrive.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.thrive.app.data.remote.UpdateInfo

/** Posts the "update available" notification and points it at the downloader. */
object UpdateNotifier {

    const val CHANNEL_ID = "thrive_updates"
    private const val NOTIFICATION_ID = 9001

    const val EXTRA_URL = "extra_apk_url"
    const val EXTRA_VERSION = "extra_version"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "New Thrive releases" }
            manager.createNotificationChannel(channel)
        }
    }

    fun notifyAvailable(context: Context, update: UpdateInfo) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val tap = Intent(context, DownloadReceiver::class.java).apply {
            putExtra(EXTRA_URL, update.apkUrl)
            putExtra(EXTRA_VERSION, update.versionName)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Thrive update available")
            .setContentText("Version ${update.versionName} — tap to download and install")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}
