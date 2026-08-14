package com.thrive.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Runs the GitHub release check off the main thread (periodic + one-shot). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = GithubUpdateChecker.checkLatest()
        if (update != null && update.apkUrl.isNotBlank()) {
            UpdateNotifier.notifyAvailable(applicationContext, update)
        }
        return Result.success()
    }
}
