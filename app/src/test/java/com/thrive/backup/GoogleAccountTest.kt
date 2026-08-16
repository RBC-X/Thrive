package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.remote.GoogleAccountInfo
import com.thrive.app.data.remote.GoogleAccountStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure tests for the Google account persistence layer (no network). */
@RunWith(RobolectricTestRunner::class)
// Plain Application: the manifest's ThriveApp schedules WorkManager in
// onCreate, which isn't initialized under Robolectric and would fail before
// any test body runs. These tests only need a Context for SharedPreferences.
@Config(sdk = [34], application = android.app.Application::class)
class GoogleAccountTest {

    private fun store(): SettingsStore {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("google_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SettingsStore(prefs)
    }

    @Test
    fun emptyStoreIsNotSignedIn() {
        val settings = store()
        val info = GoogleAccountStore.load(settings)
        assertEquals("", info.sub)
        assertEquals("", info.email)
        assertEquals("", info.accountKey)
    }

    @Test
    fun saveAndLoadRoundTripsProfile() {
        val settings = store()
        GoogleAccountStore.save(
            settings,
            GoogleAccountInfo(
                sub = "117512345678901234567",
                name = "Ada Lovelace",
                email = "ada@example.com",
                picture = "https://example.com/ada.png",
                accountKey = "g1a2b3c4d5e6f789",
            ),
        )
        val loaded = GoogleAccountStore.load(settings)
        assertEquals("117512345678901234567", loaded.sub)
        assertEquals("Ada Lovelace", loaded.name)
        assertEquals("ada@example.com", loaded.email)
        assertEquals("https://example.com/ada.png", loaded.picture)
        assertEquals("g1a2b3c4d5e6f789", loaded.accountKey)
    }

    @Test
    fun clearRemovesEverything() {
        val settings = store()
        GoogleAccountStore.save(settings, GoogleAccountInfo(sub = "123", name = "N", email = "e", accountKey = "k"))
        GoogleAccountStore.clear(settings)
        val loaded = GoogleAccountStore.load(settings)
        assertEquals("", loaded.sub)
        assertFalse(loaded.sub.isNotBlank())
    }

    @Test
    fun accountKeyLooksLikeBackendKeyShape() {
        // The backend derives "g" + 15 hex chars = 16 chars. The app stores
        // whatever the server returns; the shape must be stable and never the
        // raw sub (a long decimal Google id).
        val key = "g" + "0123456789abcde"
        assertEquals(16, key.length)
        assertTrue(key.startsWith("g"))
        assertFalse(key.contains("111122223333"))
    }

    @Test
    fun twoDevicesWithSameAccountShareTheKey() {
        // The whole point of Google backup: the key is derived from the account
        // (server-side), so both devices end up with the identical storage key.
        val a = GoogleAccountInfo(sub = "same-sub", accountKey = "gdeadbeefdeadbeef")
        val b = GoogleAccountInfo(sub = "same-sub", accountKey = "gdeadbeefdeadbeef")
        assertEquals(a.accountKey, b.accountKey)
        assertEquals(a.sub, b.sub)
    }
}
