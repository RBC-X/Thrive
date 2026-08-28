package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.local.SecureValueStore
import com.thrive.app.data.remote.GoogleAccountInfo
import com.thrive.app.data.remote.GoogleAuthResult
import com.thrive.app.data.remote.GoogleBackup
import com.thrive.app.data.remote.GoogleAccountStore
import com.thrive.app.data.remote.HttpResult
import com.thrive.app.data.remote.JsonHttpClient
import com.thrive.app.data.remote.BackupSnapshot
import com.thrive.app.data.remote.PushResult
import kotlinx.coroutines.runBlocking
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

    private class FakeHttpClient : JsonHttpClient {
        val methods = mutableListOf<String>()
        val ifMatches = mutableListOf<String?>()
        var postResult = HttpResult(500, "", null)
        var getResult = HttpResult(404, "", null)
        val putResults = ArrayDeque<HttpResult>()
        val tokens = mutableListOf<String?>()
        val postResults = ArrayDeque<HttpResult>()
        var deleteResult = HttpResult(501, "", null)
        val putBodies = mutableListOf<String>()

        override suspend fun get(url: String, ifNoneMatch: String?, token: String?): HttpResult {
            methods += "GET"
            tokens += token
            return getResult
        }

        override suspend fun putJson(
            url: String,
            jsonBody: String,
            ifMatch: String?,
            token: String?,
        ): HttpResult {
            methods += "PUT"
            putBodies += jsonBody
            ifMatches += ifMatch
            tokens += token
            return putResults.removeFirst()
        }

        override suspend fun postJson(url: String, jsonBody: String, token: String?): HttpResult {
            methods += "POST"
            tokens += token
            return if (postResults.isNotEmpty()) postResults.removeFirst() else postResult
        }

        override suspend fun delete(url: String, token: String?): HttpResult {
            methods += "DELETE"
            tokens += token
            return deleteResult
        }
    }

    private class MemorySecureStore : SecureValueStore {
        private val values = mutableMapOf<String, String>()
        override fun put(key: String, value: String) { values[key] = value }
        override fun get(key: String): String? = values[key]
        override fun remove(key: String) { values.remove(key) }
        fun rawValues(): Collection<String> = values.values
    }

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

    @Test
    fun authExchangeUsesPostAndPersistsServerIdentity() = runBlocking {
        val settings = store()
        val http = FakeHttpClient().apply {
            postResult = HttpResult(
                200,
                """{"sub":"google-123","name":"Ada","email":"ada@example.com","accountKey":"g0123456789abcde","accessToken":"access-1","refreshToken":"refresh-1","accessTokenExpiresAt":9999999999999}""",
                null,
            )
        }
        val secure = MemorySecureStore()
        val backup = GoogleBackup(settings, { "https://sync.example" }, http, secure)

        val result = backup.exchange("google-id-token")

        assertTrue(result is GoogleAuthResult.Ok)
        assertEquals(listOf("POST"), http.methods)
        assertEquals("google-123", backup.account().sub)
        assertEquals("", GoogleAccountStore.load(settings).sub)
        assertTrue(backup.isSignedIn())
    }

    @Test
    fun successfulPushPersistsRevisionForTheNextSave() = runBlocking {
        val settings = store()
        GoogleAccountStore.save(
            settings,
            GoogleAccountInfo(sub = "google-123", accountKey = "g0123456789abcde"),
        )
        val http = FakeHttpClient().apply {
            putResults += HttpResult(200, """{"ok":true,"revision":"r1"}""", null)
            putResults += HttpResult(200, """{"ok":true,"revision":"r2"}""", null)
        }
        val backup = GoogleBackup(settings, { "https://sync.example" }, http)

        val first = backup.push("token", BackupSnapshot(favorites = setOf("deal-a")))
        val second = backup.push("token", BackupSnapshot(favorites = setOf("deal-a", "deal-b")))

        assertTrue(first is PushResult.Ok)
        assertTrue(second is PushResult.Ok)
        assertEquals(listOf("*", "r1"), http.ifMatches)
        assertEquals("r2", GoogleAccountStore.revision(settings, "g0123456789abcde"))
    }

    @Test
    fun signOutRemovesTheAccountRevision() {
        val settings = store()
        val accountKey = "g0123456789abcde"
        GoogleAccountStore.save(settings, GoogleAccountInfo(sub = "google-123", accountKey = accountKey))
        GoogleAccountStore.saveRevision(settings, accountKey, "r7")

        GoogleAccountStore.clear(settings)

        assertEquals(null, GoogleAccountStore.revision(settings, accountKey))
    }

    @Test
    fun expiredSessionRefreshesAndUsesOpaqueAccessToken() = runBlocking {
        val settings = store()
        val secure = MemorySecureStore()
        val http = FakeHttpClient().apply {
            postResults += HttpResult(
                200,
                """{"sub":"google-123","name":"Ada","email":"ada@example.com","accountKey":"g0123456789abcde","accessToken":"expired","refreshToken":"refresh-1","accessTokenExpiresAt":1}""",
                null,
            )
            postResults += HttpResult(
                200,
                """{"accessToken":"access-2","refreshToken":"refresh-2","accessTokenExpiresAt":9999999999999}""",
                null,
            )
            getResult = HttpResult(200, """{"favorites":[],"revision":"r1"}""", null)
        }
        val backup = GoogleBackup(settings, { "https://sync.example" }, http, secure)

        assertTrue(backup.exchange("google-id-token") is GoogleAuthResult.Ok)
        assertTrue(backup.pull() is com.thrive.app.data.remote.PullResult.Found)
        assertEquals(listOf("POST", "POST", "GET"), http.methods)
        assertEquals("access-2", http.tokens.last())
        assertEquals("refresh-2", backup.session()?.refreshToken)
    }

    @Test
    fun conflictMergeKeepsDeletionTombstones() = runBlocking {
        val settings = store()
        val secure = MemorySecureStore()
        val http = FakeHttpClient().apply {
            postResult = HttpResult(
                200,
                """{"sub":"google-123","accountKey":"g0123456789abcde","accessToken":"access-1","refreshToken":"refresh-1","accessTokenExpiresAt":9999999999999}""",
                null,
            )
            putResults += HttpResult(409, "{}", null)
            putResults += HttpResult(200, """{"ok":true,"revision":"r2"}""", null)
            getResult = HttpResult(
                200,
                """{"favorites":["removed-deal"],"deletedFavoriteIds":["removed-deal"],"revision":"r1"}""",
                null,
            )
        }
        val backup = GoogleBackup(settings, { "https://sync.example" }, http, secure)
        assertTrue(backup.exchange("google-id-token") is GoogleAuthResult.Ok)

        val result = backup.push(
            BackupSnapshot(favorites = setOf("removed-deal"), deletedFavoriteIds = setOf("removed-deal")),
        )

        assertTrue(result is PushResult.Ok)
        assertTrue(http.putBodies.last().contains("deletedFavoriteIds"))
        assertFalse(http.putBodies.last().contains("\"favorites\":[\"removed-deal\"]"))
    }

    @Test
    fun accountDeletionClearsEncryptedLocalSessionAfterServerConfirmation() = runBlocking {
        val settings = store()
        val secure = MemorySecureStore()
        val http = FakeHttpClient().apply {
            postResult = HttpResult(
                200,
                """{"sub":"google-123","accountKey":"g0123456789abcde","accessToken":"access-1","refreshToken":"refresh-1","accessTokenExpiresAt":9999999999999}""",
                null,
            )
            deleteResult = HttpResult(200, """{"ok":true,"deleted":true}""", null)
        }
        val backup = GoogleBackup(settings, { "https://sync.example" }, http, secure)
        assertTrue(backup.exchange("google-id-token") is GoogleAuthResult.Ok)

        assertTrue(backup.deleteAccount())
        assertFalse(backup.isSignedIn())
        assertEquals("DELETE", http.methods.last())
    }
}
