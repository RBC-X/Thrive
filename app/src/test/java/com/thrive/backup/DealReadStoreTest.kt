package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.local.DealReadStore
import com.thrive.app.data.local.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DealReadStoreTest {
    private fun store(): SettingsStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("deal_read_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SettingsStore(prefs)
    }

    @Test
    fun unseenIdsBecomeSeenAndSurviveReload() {
        val settings = store()
        DealReadStore.markSeen(settings, listOf("deal-a", "deal-b", "deal-a"))

        assertEquals(setOf("deal-a", "deal-b"), DealReadStore.seen(settings))
        assertEquals(1, DealReadStore.unseenCount(settings, listOf("deal-a", "deal-c")))
    }

    @Test
    fun malformedStorageFallsBackToEmpty() {
        val settings = store()
        settings.putString("deal_seen_ids", "not-json")

        assertTrue(DealReadStore.seen(settings).isEmpty())
    }

    @Test
    fun storageIsBounded() {
        val settings = store()
        DealReadStore.markSeen(settings, (0..5_100).map { "deal-$it" })

        assertEquals(5_000, DealReadStore.seen(settings).size)
    }
}
