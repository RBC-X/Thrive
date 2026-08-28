package com.thrive.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thrive.app.ThriveApp
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.remote.SyncStatus
import java.util.concurrent.TimeUnit

/**
 * Refreshes the deal feed in the background so the app is always showing the
 * newest deals — without needing an app update. Runs on a 30-minute periodic
 * schedule (battery-friendly; the server answers 304 when nothing changed, so
 * a quiet sync costs almost nothing) and as a one-shot whenever the app
 * returns to the foreground and the feed is older than [STALE_AFTER_MS].
 *
 * Unlike the update checker (which looks for a NEW APK), this worker syncs the
 * actual deal/pantry/budget payload from the configured sync server. That means
 * new coupons, live Kroger prices, and the daily rotation reach the phone on
 * their own — no user action, no app update required.
 */
class DealSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ThriveApp ?: return Result.failure()
        val repo = ThriveRepository(app, app.settings)
        // Release users never see technical server controls. Discover the
        // operator's currently published HTTPS endpoint automatically.
        if (repo.syncBaseUrl.isBlank()) {
            val discovered = GithubUpdateChecker.discoverSyncServer()
            if (discovered.isNullOrBlank()) return if (runAttemptCount < 4) Result.retry() else Result.success()
            app.settings.putString(ThriveRepository.SYNC_URL_KEY, discovered)
        }
        repo.syncNow(force = false)
        return when (repo.syncState.value.status) {
            SyncStatus.OK -> Result.success()
            SyncStatus.ERROR -> if (runAttemptCount < 4) Result.retry() else Result.failure()
            SyncStatus.OFFLINE -> if (runAttemptCount < 4) Result.retry() else Result.success()
            SyncStatus.SYNCING -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK = "thrive-deal-sync-periodic"
        private const val RESUME_WORK = "thrive-deal-sync-resume"

        /** Deals older than this on app-open trigger an immediate refresh. */
        const val STALE_AFTER_MS = 30L * 60L * 1000L

        /** Registers the 30-minute recurring deal sync. */
        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<DealSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        /**
         * Called on foreground: enqueue a one-shot refresh unless a recent one
         * already ran. Uses the persisted last-sync timestamp so opening the
         * app twice in a minute doesn't hammer the server.
         */
        fun refreshOnResume(context: Context) {
            val app = context.applicationContext as? ThriveApp ?: return
            val lastSync = ThriveRepository.lastSyncAt(app.settings)
            if (System.currentTimeMillis() - lastSync < STALE_AFTER_MS) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                RESUME_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DealSyncWorker>()
                    .setConstraints(networkConstraints())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
