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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thrive.app.MainActivity
import com.thrive.app.data.local.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Re-engagement reminders: if the user hasn't opened Thrive in [IDLE_MS] (42h),
 * post a short, honest nudge with a rotating phrase. One reminder per absence
 * cycle (a [COOLDOWN_MS] cooldown), and only when the user has actually used
 * the app before. Fully opt-in via the Settings toggle; respects notification
 * permission.
 */
object ReEngagement {

    const val CHANNEL_ID = "thrive_reminders"
    private const val NOTIFICATION_ID = 9101

    // Settings keys (shared with SettingsStore "thrive_settings").
    const val KEY_LAST_USED = "last_used_at"
    const val KEY_LAST_REMINDED = "last_reminded_at"
    const val KEY_ENABLED = "reminders_enabled"

    const val IDLE_MS = 42L * 60 * 60 * 1000   // 42 hours without opening
    const val COOLDOWN_MS = 40L * 60 * 60 * 1000 // don't nag within an absence

    /** Short, honest nudge phrases — one per notification, rotated by day. */
    val PHRASES = listOf(
        "New deals are in 👀",
        "Your deals missed you",
        "Fresh coupons today",
        "Dinner under $10? We've got ideas",
        "Your shopping list is waiting",
        "Kroger prices updated near you",
        "New savings since you left",
        "Recipe ideas for tonight",
        "Your saved deals are still there",
        "Quick trip? We found some deals",
        "Don't miss today's coupons",
        "Something's on sale this week",
        "Your pantry has ideas waiting",
        "Come see what's new in the app",
        "Deals don't last forever",
        "We saved your spot (and your deals)",
    )

    /**
     * Pure decision logic — testable without Android. A reminder is due when:
     * the app was used before, the idle window passed, and the cooldown since
     * the last reminder has elapsed (or no reminder was ever sent).
     */
    fun shouldRemind(
        lastUsedAt: Long?,
        lastRemindedAt: Long?,
        now: Long,
        idleMs: Long = IDLE_MS,
        cooldownMs: Long = COOLDOWN_MS,
    ): Boolean {
        if (lastUsedAt == null) return false // never opened — don't chase a stranger
        if (now - lastUsedAt < idleMs) return false
        if (lastRemindedAt != null && now - lastRemindedAt < cooldownMs) return false
        return true
    }

    /** Stable daily rotation so consecutive reminders use different phrases. */
    fun phraseFor(now: Long): String {
        val day = (now / TimeUnit.DAYS.toMillis(1)).toInt()
        return PHRASES[Math.floorMod(day, PHRASES.size)]
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Deal reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A short nudge when you haven't opened Thrive in a while" }
            manager.createNotificationChannel(channel)
        }
    }

    /** Posts the reminder; silently skips when notifications are denied. */
    fun notify(context: Context, phrase: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val tap = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Thrive")
            .setContentText(phrase)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private const val PERIODIC_WORK = "thrive-re-engagement"

    /** Recurring idle check (every 6h — the 42h window makes finer cadence pointless). */
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<ReEngagementWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }

    /** Stores that the app was opened — resets the 42h idle window. */
    fun markAppUsed(settings: SettingsStore) {
        settings.putLong(KEY_LAST_USED, System.currentTimeMillis())
    }
}
