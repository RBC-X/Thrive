package com.thrive.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thrive.app.ThriveApp
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.remote.UpdateInfo

/**
 * Runs the GitHub release check off the main thread (periodic + one-shot).
 *
 * When a newer release is found it is handed to the in-app [UpdateBus] so the
 * UI can show the update dialog; if the app is not in the foreground the
 * notification still fires as a fallback. A version the user dismissed with
 * "Later" is skipped so the check never nags about the same build.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ThriveApp
        val update = GithubUpdateChecker.checkLatest()
        if (update != null && update.apkUrl.isNotBlank()) {
            val dismissed = app?.settings?.getString(KEY_DISMISSED_VERSION, null)
            if (update.versionName != dismissed) {
                UpdateBus.publish(update)
                UpdateNotifier.notifyAvailable(applicationContext, update)
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_DISMISSED_VERSION = "update_dismissed_version"
    }
}
