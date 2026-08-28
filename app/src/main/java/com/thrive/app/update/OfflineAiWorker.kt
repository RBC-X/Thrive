package com.thrive.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thrive.app.R
import com.thrive.app.ThriveApp
import java.util.concurrent.TimeUnit

/**
 * Prepares the optional offline model after onboarding. WorkManager survives
 * process death and waits for unmetered internet plus adequate device storage;
 * the long transfer runs as a visible foreground data-sync operation.
 */
class OfflineAiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo())
        val app = applicationContext as? ThriveApp ?: return Result.failure()
        val ready = runCatching { app.onDeviceLlm.ensureDownloaded() }.getOrDefault(false)
        return when {
            ready -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Offline AI setup", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress while Thrive securely prepares its offline assistant."
                    setShowBadge(false)
                },
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Preparing Thrive offline AI")
            .setContentText("Downloading and verifying the private on-device assistant")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val UNIQUE_WORK = "thrive-offline-ai-v1"
        private const val CHANNEL_ID = "thrive_offline_ai"
        private const val NOTIFICATION_ID = 4302
        private const val MAX_RETRIES = 5

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineAiWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
