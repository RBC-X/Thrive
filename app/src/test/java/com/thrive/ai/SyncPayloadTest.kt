package com.thrive.ai

import com.thrive.app.data.remote.SyncPayload
import com.thrive.app.data.remote.isNewerVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Fixture mirrors the shape served by backend /api/v1/sync. */
    private val fixture = """
        {
          "version": 3,
          "generatedAt": "2026-08-13T00:00:00.000Z",
          "source": ["daily-rotation"],
          "deals": [
            {
              "id": "dl09", "store": "Walmart", "productName": "Boneless Chicken Breast 2 lb",
              "category": "Meat", "price": 6.98, "unitPrice": "${'$'}3.49/lb",
              "savingsPercent": 22, "keywords": ["chicken breast"], "endsInDays": 2,
              "url": "https://walmart.com/p/1", "urlVerified": true, "size": "2 lb",
              "brand": "Great Value", "imageUrl": "https://img.example/1.jpg", "estimated": false
            }
          ],
          "coupons": [],
          "recipes": [],
          "catalog": [],
          "update": {
            "versionName": "1.2.0",
            "apkUrl": "http://host/releases/Thrive-release.apk",
            "notes": ["In-app update helper", "Weekly meal planner"]
          }
        }
    """.trimIndent()

    @Test
    fun `decodes sync payload with size and unit price`() {
        val payload = json.decodeFromString(SyncPayload.serializer(), fixture)
        assertEquals(3, payload.version)
        assertEquals(listOf("daily-rotation"), payload.source)
        assertEquals(1, payload.deals.size)
        val deal = payload.deals.first()
        assertEquals("2 lb", deal.size)
        assertEquals("$3.49/lb", deal.unitPrice)
        assertEquals(6.98, deal.price, 0.001)
        assertTrue(payload.generatedAt.isNotBlank())
        // New honesty fields flow through the sync payload.
        assertEquals("https://walmart.com/p/1", deal.url)
        assertTrue(deal.urlVerified)
        assertEquals("Great Value", deal.brand)
        assertEquals("https://img.example/1.jpg", deal.imageUrl)
        assertFalse(deal.estimated)
    }

    @Test
    fun `tolerates missing optional fields`() {
        val minimal = """{"version":1,"generatedAt":"x","source":[],"deals":[],"coupons":[],"recipes":[],"catalog":[]}"""
        val payload = json.decodeFromString(SyncPayload.serializer(), minimal)
        assertEquals(0, payload.deals.size)
        assertNull(payload.update)
    }

    @Test
    fun `decodes update info from sync payload`() {
        val payload = json.decodeFromString(SyncPayload.serializer(), fixture)
        val update = payload.update
        assertEquals("1.2.0", update?.versionName)
        assertEquals("http://host/releases/Thrive-release.apk", update?.apkUrl)
        assertEquals(listOf("In-app update helper", "Weekly meal planner"), update?.notes)
    }

    @Test
    fun `tolerates update without notes`() {
        val noNotes = """{"version":1,"generatedAt":"x","source":[],"deals":[],"coupons":[],"recipes":[],"catalog":[],"update":{"versionName":"1.2.0","apkUrl":"http://host/a.apk"}}"""
        val payload = json.decodeFromString(SyncPayload.serializer(), noNotes)
        assertEquals(emptyList<String>(), payload.update?.notes)
    }

    @Test
    fun `version comparison flags newer releases only`() {
        assertTrue(isNewerVersion("1.2.0", "1.1.1"))
        assertTrue(isNewerVersion("2.0.0", "1.9.9"))
        assertTrue(isNewerVersion("1.1.2", "1.1.1"))
        assertFalse(isNewerVersion("1.1.1", "1.1.1"))
        assertFalse(isNewerVersion("1.0.9", "1.1.1"))
        assertFalse(isNewerVersion("", "1.1.1"))
        assertFalse(isNewerVersion(null, "1.1.1"))
        assertFalse(isNewerVersion("1.1.1-beta", "1.1.1"))
    }
}
