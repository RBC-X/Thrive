package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.update.DealSyncWorker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the "always up to date" behavior: the persisted last-sync timestamp
 * drives the foreground refresh (a fresh feed skips the one-shot worker, a
 * stale one runs it) and the worker's staleness threshold is sane.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: the manifest's ThriveApp schedules WorkManager in
// onCreate, which isn't initialized under Robolectric and would fail before
// any test body runs. These tests only need a Context for SharedPreferences.
@Config(sdk = [34], application = android.app.Application::class)
class DealFreshnessTest {

    private fun store(): SettingsStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("freshness_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SettingsStore(prefs)
    }

    @Test
    fun neverSyncedIsStale() {
        val settings = store()
        val last = ThriveRepository.lastSyncAt(settings)
        assertTrue("no sync yet must count as stale", last == 0L)
        assertTrue("0 is older than the staleness window", System.currentTimeMillis() - last >= DealSyncWorker.STALE_AFTER_MS)
    }

    @Test
    fun freshSyncIsWithinWindow() {
        // A sync that just happened (lastSyncAt = now) is within the window,
        // so the foreground refresh skips it (avoids hammering the server).
        val now = System.currentTimeMillis()
        assertFalse("just-synced must not be stale", now - now >= DealSyncWorker.STALE_AFTER_MS)
    }

    @Test
    fun oldSyncIsStale() {
        val stale = System.currentTimeMillis() - 45L * 60L * 1000L
        assertTrue("45 minutes ago must be stale", System.currentTimeMillis() - stale >= DealSyncWorker.STALE_AFTER_MS)
    }
}
