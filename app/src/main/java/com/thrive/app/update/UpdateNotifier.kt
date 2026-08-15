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
import com.thrive.app.MainActivity
import com.thrive.app.data.remote.UpdateInfo

/** Posts the "update available" notification and points it at the downloader. */
object UpdateNotifier {

    const val CHANNEL_ID = "thrive_updates"
    private const val NOTIFICATION_ID = 9001

    const val EXTRA_URL = "extra_apk_url"
    const val EXTRA_VERSION = "extra_version"
    const val EXTRA_SIZE = "extra_apk_size"

    fun ensureChannel(context: Context) {
        // minSdk is 26, so the notification channel API is always available.
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "New Thrive releases" }
        manager.createNotificationChannel(channel)
    }

    /**
     * Body tap opens the app (where the in-app update dialog appears); the
     * "Update now" action button routes to the [DownloadReceiver] broadcast,
     * which is the lint-sanctioned shape: receivers belong on actions, the
     * body opens a destination.
     */
    fun notifyAvailable(context: Context, update: UpdateInfo) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Body tap: open the app with the update details so the in-app dialog
        // shows "Update now" — no surprise background download.
        val openApp = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_URL, update.apkUrl)
            putExtra(EXTRA_VERSION, update.versionName)
            putExtra(EXTRA_SIZE, update.apkSizeBytes)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPending = PendingIntent.getActivity(
            context,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Action button: start the download + install flow directly.
        val download = Intent(context, DownloadReceiver::class.java).apply {
            putExtra(EXTRA_URL, update.apkUrl)
            putExtra(EXTRA_VERSION, update.versionName)
            putExtra(EXTRA_SIZE, update.apkSizeBytes)
        }
        val downloadPending = PendingIntent.getBroadcast(
            context,
            1,
            download,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Thrive update available")
            .setContentText("Version ${update.versionName} — tap to see what's new")
            .setContentIntent(contentPending)
            .addAction(0, "Update now", downloadPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    /**
     * Surfaces a download/install failure with a Retry action. Silently no-ops
     * when notifications are denied — the in-app dialog path still shows errors.
     */
    fun notifyError(
        context: Context,
        title: String,
        detail: String,
        url: String?,
        version: String?,
    ) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (!url.isNullOrBlank() && !version.isNullOrBlank()) {
            val retry = Intent(context, DownloadReceiver::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VERSION, version)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                2,
                retry,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Retry", pending)
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, builder.build()) }
    }
}
