package com.thrive.app.update

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Registers the recurring GitHub update check. */
object UpdateScheduler {

    private const val PERIODIC_WORK = "thrive-github-update-check"
    private const val IMMEDIATE_WORK = "thrive-github-update-immediate"

    /** Schedules the 15-minute recurring check plus an immediate check at launch. */
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )

        val immediate = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            immediate,
        )
    }

    /** Manual trigger (Settings "Check for updates"). */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().build(),
        )
    }
}
