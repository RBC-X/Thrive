package com.thrive.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thrive.app.data.local.SettingsStore

/** Periodic idle check: posts a short re-engagement nudge after 42h away. */
class ReEngagementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(
            applicationContext.getSharedPreferences("thrive_settings", Context.MODE_PRIVATE)
        )
        if (!settings.getBoolean(ReEngagement.KEY_ENABLED, true)) return Result.success()

        val now = System.currentTimeMillis()
        val lastUsed = settings.getLong(ReEngagement.KEY_LAST_USED, 0L).takeIf { it > 0 }
        val lastReminded = settings.getLong(ReEngagement.KEY_LAST_REMINDED, 0L).takeIf { it > 0 }

        if (!ReEngagement.shouldRemind(lastUsed, lastReminded, now)) return Result.success()

        val phrase = ReEngagement.phraseFor(now)
        ReEngagement.notify(applicationContext, phrase)
        settings.putLong(ReEngagement.KEY_LAST_REMINDED, now)
        return Result.success()
    }
}
