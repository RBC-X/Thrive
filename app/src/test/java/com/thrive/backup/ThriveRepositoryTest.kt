package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.data.SyncFetcher
import com.thrive.app.data.ThriveRepository
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.Coupon
import com.thrive.app.data.remote.HttpResult
import com.thrive.app.data.remote.SyncPayload
import com.thrive.app.data.remote.SyncStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the persisted-ETag / cold-start behavior of [ThriveRepository]: a
 * persisted ETag must never be sent before its matching payload is restored,
 * a 304 with no usable payload must trigger one unconditional refetch, corrupt
 * or incompatible caches must fall back safely, and empty server sections must
 * keep the last-good live data (never silently fall back to bundled).
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: the manifest's ThriveApp schedules WorkManager in
// onCreate, which isn't initialized under Robolectric and would fail before
// any test body runs. The repository only needs a Context.
@Config(sdk = [34], application = android.app.Application::class)
class ThriveRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var settings: SettingsStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun coupon(id: String) = Coupon(
        id = id,
        store = "Kroger",
        title = "Live item $id",
        description = "",
        category = "Grocery",
        priceBefore = 5.0,
        priceAfter = 3.0,
        endsInDays = 5,
        url = "https://www.kroger.com/p/$id",
        urlVerified = true,
        estimated = false,
    )

    private fun payload(vararg ids: String) = SyncPayload(coupons = ids.map(::coupon))

    private fun body(p: SyncPayload): String = json.encodeToString(SyncPayload.serializer(), p)

    private val cacheFile: File
        get() = File(context.filesDir, "sync_payload.json")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("repo_test", Context.MODE_PRIVATE)
        settings = SettingsStore(prefs)
        prefs.edit().clear().commit()
        cacheFile.delete()
    }

    private fun repo(fetcher: SyncFetcher): ThriveRepository =
        ThriveRepository(context, settings, fetcher)

    /** Deterministic fake: each call pops the next canned response. */
    private class FakeFetcher : SyncFetcher {
        val calls = mutableListOf<Pair<String, String?>>()
        val responses = ArrayDeque<HttpResult>()
        var sleepMs = 0L

        override suspend fun get(url: String, ifNoneMatch: String?): HttpResult {
            delay(sleepMs)
            calls.add(url to ifNoneMatch)
            return responses.removeFirst()
        }
    }

    private fun fake(vararg rs: HttpResult) = FakeFetcher().apply { rs.forEach { responses.add(it) } }

    private fun ok(body: String, etag: String) = HttpResult(200, body, etag)

    private fun notModified() = HttpResult(304, "", null)

    // ---- offline startup ----

    @Test
    fun `offline startup with no server keeps bundled feed and honest origin`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "")
        val repo = repo(fake())
        repo.syncNow()
        assertEquals(SyncStatus.OFFLINE, repo.syncState.value.status)
        assertEquals("bundled", repo.syncState.value.feedOrigin)
        assertTrue(repo.coupons.isNotEmpty()) // bundled fallback
    }

    // ---- first sync, restart with valid cache + 304 ----

    @Test
    fun `first sync persists payload and etag, restart with valid cache plus 304 keeps live feed`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        val f1 = fake(ok(body(payload("c1", "c2")), "w1"))
        val r1 = repo(f1)
        r1.syncNow()
        assertEquals(listOf("c1", "c2"), r1.coupons.map { it.id })
        assertEquals("live", r1.syncState.value.feedOrigin)
        assertTrue(cacheFile.exists())

        // Simulate a process restart: fresh repository over the same data.
        val f2 = fake(notModified())
        val r2 = repo(f2)
        r2.awaitHydration()
        assertEquals(listOf("c1", "c2"), r2.coupons.map { it.id }) // hydrated from disk

        r2.syncNow()
        // 304 with a usable payload: exactly one request, no refetch, still live.
        assertEquals(1, f2.calls.size)
        assertEquals("w1", f2.calls.first().second) // If-None-Match carried
        assertEquals(SyncStatus.OK, r2.syncState.value.status)
        assertEquals("live", r2.syncState.value.feedOrigin)
        assertEquals(listOf("c1", "c2"), r2.coupons.map { it.id })
    }

    // ---- missing cache + 304 retries once without If-None-Match ----

    @Test
    fun `cold start with persisted etag but missing cache retries once without if-none-match`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        settings.putString("sync_etag", "w1") // persisted ETag, but no payload on disk
        val f = fake(notModified(), ok(body(payload("c9")), "w2"))
        val repo = repo(f)
        repo.syncNow()
        assertEquals(2, f.calls.size)
        assertEquals("w1", f.calls[0].second)          // first attempt carried the ETag
        assertNull(f.calls[1].second)                  // retry sent no If-None-Match
        assertEquals(listOf("c9"), repo.coupons.map { it.id })
        assertEquals("live", repo.syncState.value.feedOrigin)
        assertEquals(SyncStatus.OK, repo.syncState.value.status)
    }

    // ---- double 304 with no payload falls back honestly ----

    @Test
    fun `server keeps answering 304 with no cached payload falls back to bundled and reports error`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        settings.putString("sync_etag", "w1")
        val f = fake(notModified(), notModified())
        val repo = repo(f)
        repo.syncNow()
        assertEquals(SyncStatus.ERROR, repo.syncState.value.status)
        assertEquals("bundled", repo.syncState.value.feedOrigin) // never labeled live
        assertEquals(2, f.calls.size)
    }

    // ---- corrupt cache ----

    @Test
    fun `corrupt cache is deleted, etag cleared, next sync fetches unconditionally`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        cacheFile.writeText("{ not valid json !!! ")
        settings.putString("sync_etag", "w1")
        val f = fake(ok(body(payload("c3")), "w3"))
        val repo = repo(f)
        repo.awaitHydration()
        assertTrue(!cacheFile.exists())       // corrupt file removed
        assertNull(settings.getString("sync_etag", null)) // etag dropped with it
        repo.syncNow()
        assertNull(f.calls.first().second)    // unconditional fetch
        assertEquals(listOf("c3"), repo.coupons.map { it.id })
        assertEquals("live", repo.syncState.value.feedOrigin)
    }

    // ---- changed etag ----

    @Test
    fun `changed etag from server replaces the stored etag`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        val f = fake(ok(body(payload("c1")), "w1"), ok(body(payload("c1", "c2")), "w2"))
        val repo = repo(f)
        repo.syncNow()
        assertEquals("w1", settings.getString("sync_etag", null))
        repo.syncNow()
        assertEquals("w2", settings.getString("sync_etag", null))
        assertEquals(listOf("c1", "c2"), repo.coupons.map { it.id })
    }

    // ---- empty server sections keep last-good live data ----

    @Test
    fun `empty server coupons keep last good live data and stay labeled live`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        val f = fake(ok(body(payload("c1")), "w1"), ok(body(payload()), "w2"))
        val repo = repo(f)
        repo.syncNow()
        assertEquals(listOf("c1"), repo.coupons.map { it.id })
        repo.syncNow()
        // Server sent zero coupons: keep the last-good live feed, never fall to bundled.
        assertEquals(listOf("c1"), repo.coupons.map { it.id })
        assertNotEquals("bundled", repo.syncState.value.feedOrigin)
    }

    // ---- forced refresh ----

    @Test
    fun `forced refresh never sends if-none-match even when an etag is stored`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        val f = fake(ok(body(payload("c1")), "w1"), ok(body(payload("c1", "c7")), "w1"))
        val repo = repo(f)
        repo.syncNow()
        assertEquals("w1", settings.getString("sync_etag", null)) // etag stored
        repo.syncNow(force = true)
        assertEquals(2, f.calls.size)
        assertNull(f.calls[1].second) // forced: no If-None-Match despite stored etag
        assertEquals(listOf("c1", "c7"), repo.coupons.map { it.id })
    }

    // ---- concurrency ----

    @Test
    fun `concurrent syncs are serialized and both complete`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        val f = FakeFetcher().apply {
            sleepMs = 50
            responses.add(ok(body(payload("c1")), "w1"))
            responses.add(ok(body(payload("c1", "c2")), "w2"))
        }
        val repo = repo(f)
        val j1 = launch { repo.syncNow() }
        val j2 = launch { repo.syncNow() }
        j1.join(); j2.join()
        assertEquals(2, f.calls.size)
        assertEquals(SyncStatus.OK, repo.syncState.value.status)
        assertEquals(listOf("c1", "c2"), repo.coupons.map { it.id }) // no lost update
    }

    // ---- schema-incompatible cache ----

    @Test
    fun `schema-incompatible cache is dropped and refresh is unconditional`() = runBlocking {
        settings.putString(ThriveRepository.SYNC_URL_KEY, "https://sync.example.com")
        // Hand-write a cache with a version this build doesn't understand.
        val legacy = """{"version": 1, "etag": "wOld", "payload": ${body(payload("stale"))}}"""
        cacheFile.writeText(legacy)
        settings.putString("sync_etag", "wOld")
        val f = fake(ok(body(payload("c4")), "w4"))
        val repo = repo(f)
        repo.awaitHydration()
        assertNull(settings.getString("sync_etag", null))
        repo.syncNow()
        assertNull(f.calls.first().second)
        assertEquals(listOf("c4"), repo.coupons.map { it.id })
    }
}
